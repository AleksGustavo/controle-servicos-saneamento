package br.controleservicos.app

import br.controleservicos.app.Fixtures.linha
import br.controleservicos.app.core.export.ContratoExportacao
import br.controleservicos.app.core.export.ExportadorCsv
import br.controleservicos.app.core.export.ExportadorXlsx
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.export.GeradorExportacao
import br.controleservicos.app.core.export.PedidoExportacao
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.StatusServico
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream

class ExportacaoTest {
    private val zona = ZoneId.of("America/Sao_Paulo")

    private fun pedido(linhas: List<LinhaServico>, formato: FormatoExportacao, cond: Map<String, List<CondicaoRegistro>> = emptyMap()) =
        PedidoExportacao(formato, linhas, cond, "Teste", "1.0.0", 5, Fixtures.T0, zona)

    private fun gerar(p: PedidoExportacao) = ByteArrayOutputStream().also { GeradorExportacao.gerar(p, it) }.toByteArray()

    /** Parser CSV RFC 4180 (separador ;) para conferir o que foi escrito. */
    private fun lerCsv(bytes: ByteArray): List<List<String>> {
        val txt = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val linhas = mutableListOf<List<String>>()
        var campo = StringBuilder(); var linha = mutableListOf<String>(); var aspas = false; var i = 0
        while (i < txt.length) {
            val c = txt[i]
            if (aspas) {
                if (c == '"' && i + 1 < txt.length && txt[i + 1] == '"') { campo.append('"'); i++ }
                else if (c == '"') aspas = false else campo.append(c)
            } else when (c) {
                '"' -> aspas = true
                ';' -> { linha.add(campo.toString()); campo = StringBuilder() }
                '\r' -> {}
                '\n' -> { linha.add(campo.toString()); linhas.add(linha); linha = mutableListOf(); campo = StringBuilder() }
                else -> campo.append(c)
            }
            i++
        }
        return linhas
    }

    @Test fun `csv vazio tem apenas cabecalho e BOM`() {
        val b = gerar(pedido(emptyList(), FormatoExportacao.CSV))
        assertArrayEquals(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), b.copyOfRange(0, 3))
        val l = lerCsv(b)
        assertEquals(1, l.size)
        assertEquals(ContratoExportacao.COLUNAS, l[0])
        assertTrue(String(b, Charsets.UTF_8).endsWith("\r\n"))
    }

    @Test fun `csv com acentos aspas ponto e virgula quebras e emoji`() {
        val obs = "Água \"funda\"; São João\nsegunda linha 🚧"
        val s = linha("x1", bairro = "São João", tipoNome = "PRP", observacoes = obs, redeMin = 10)
        val cond = mapOf("x1" to listOf(CondicaoRegistro("x1", "c1", "OUTRAS", "Outras", "raiz | pedra"), CondicaoRegistro("x1", "c2", "E", "Esgoto acima da rede", null)))
        val l = lerCsv(gerar(pedido(listOf(s), FormatoExportacao.CSV, cond)))
        assertEquals(2, l.size)
        val r = ContratoExportacao.COLUNAS.zip(l[1]).toMap()
        assertEquals(ContratoExportacao.COLUNAS.size, l[1].size)
        assertEquals(obs, r["observacoes"])
        assertEquals("São João", r["bairro"])
        assertEquals("PRP", r["tipo_servico"])
        assertEquals("Outras: raiz \\| pedra | Esgoto acima da rede", r["condicoes_observadas"])
        assertEquals("1800", r["duracao_total_segundos"])
        assertEquals("00:30:00", r["duracao_total_hhmmss"])
        assertEquals("600", r["tempo_ate_rede_segundos"])
        assertEquals("1200", r["tempo_apos_rede_segundos"])
    }

    @Test fun `null e diferente de zero`() {
        val s = linha("z", redeMin = null).copy(quantidadeLigacoes = 0, diametroMm = null, profundidadeMetros = 1.25)
        val r = ContratoExportacao.COLUNAS.zip(lerCsv(gerar(pedido(listOf(s), FormatoExportacao.CSV)))[1]).toMap()
        assertEquals("0", r["quantidade_ligacoes"])
        assertEquals("", r["diametro_mm"])
        assertEquals("", r["tempo_ate_rede_segundos"])
        assertEquals("1.25", r["profundidade_m"])
    }

    @Test fun `neutraliza injecao de formula`() {
        listOf("=SUM(A1:A9)", "+1+1", "-2+3", "@cmd", "  =1+1", "\t=1", "\r=1").forEach {
            val n = ExportadorCsv.neutralizarFormula(it)
            assertTrue("não neutralizou: $it", n.startsWith("'"))
        }
        assertEquals("Rua A", ExportadorCsv.neutralizarFormula("Rua A"))
        assertEquals("", ExportadorCsv.neutralizarFormula(""))
        val s = linha("f", observacoes = "=HYPERLINK(\"http://x\")", logradouro = "+55 rua")
        val r = ContratoExportacao.COLUNAS.zip(lerCsv(gerar(pedido(listOf(s), FormatoExportacao.CSV)))[1]).toMap()
        assertEquals("'=HYPERLINK(\"http://x\")", r["observacoes"])
        assertEquals("'+55 rua", r["logradouro"])
    }

    @Test fun `csv filtrado tem a contagem anunciada`() {
        val linhas = (1..7).map { linha("id$it") }
        assertEquals(8, lerCsv(gerar(pedido(linhas, FormatoExportacao.CSV))).size)
    }

    @Test fun `xlsx valido lido pelo Apache POI`() {
        val obs = "Texto com \"aspas\", <tags> & emoji 🚰\nlinha 2"
        val linhas = listOf(
            linha("a", bairro = "São João", observacoes = obs, redeMin = 5),
            linha("b", bairroId = "b2", bairro = "Centro", observacoes = "=SUM(A1:A2)", duracaoMin = 45),
            linha("c", status = StatusServico.CANCELADO, duracaoMin = null),
        )
        val bytes = gerar(pedido(linhas, FormatoExportacao.XLSX))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(listOf("Serviços", "Ligações trocadas", "Participantes", "Indicadores por bairro", "Resumo_equipes", "Metadados"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            val reg = wb.getSheet("Serviços")
            assertEquals(3, reg.lastRowNum) // cabeçalho + 3
            val cab = reg.getRow(0)
            assertEquals(ContratoExportacao.COLUNAS, (0 until cab.lastCellNum).map { cab.getCell(it).stringCellValue })
            assertTrue("cabeçalho em negrito", wb.getFontAt(cab.getCell(0).cellStyle.fontIndex).bold)
            assertNotNull("primeira linha congelada", reg.paneInformation)
            assertTrue(reg.paneInformation.isFreezePane)
            assertNotNull("autofiltro", reg.ctWorksheet.autoFilter)
            val col = ContratoExportacao.COLUNAS
            val a = reg.getRow(1)
            assertEquals(obs, a.getCell(col.indexOf("observacoes")).stringCellValue)
            assertEquals("São João", a.getCell(col.indexOf("bairro")).stringCellValue)
            val dur = a.getCell(col.indexOf("duracao_total_segundos"))
            assertEquals(CellType.NUMERIC, dur.cellType)
            assertEquals(1800.0, dur.numericCellValue, 0.0)
            val b = reg.getRow(2)
            val formula = b.getCell(col.indexOf("observacoes"))
            assertEquals("fórmula fica como texto", CellType.STRING, formula.cellType)
            assertEquals("=SUM(A1:A2)", formula.stringCellValue)
            val c = reg.getRow(3)
            assertNull("vazio != zero", c.getCell(col.indexOf("duracao_total_segundos")))

            val resumo = wb.getSheet("Indicadores por bairro")
            // Cancelado fora dos indicadores: Centro (45 min) e São João (30 min), ordenados por bairro.
            assertEquals(2, resumo.lastRowNum)
            val nCol = ContratoExportacao.COLUNAS_INDICADORES.indexOf("n")
            assertEquals("Centro", resumo.getRow(1).getCell(0).stringCellValue)
            assertEquals(1.0, resumo.getRow(1).getCell(nCol).numericCellValue, 0.0)

            val meta = wb.getSheet("Metadados")
            val chaves = (1..meta.lastRowNum).associate { meta.getRow(it).getCell(0).stringCellValue to meta.getRow(it).getCell(1).stringCellValue }
            assertEquals("3", chaves["quantidade_registros"])
            assertEquals("3", chaves["versao_esquema_exportacao"])
            assertEquals("Teste", chaves["filtros"])
        }
    }

    @Test fun `xlsx vazio continua valido`() {
        val bytes = gerar(pedido(emptyList(), FormatoExportacao.XLSX))
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb -> assertEquals(0, wb.getSheet("Serviços").lastRowNum) }
    }

    @Test fun `xlsx nao e csv renomeado`() {
        val bytes = gerar(pedido(listOf(linha()), FormatoExportacao.XLSX))
        assertEquals('P'.code.toByte(), bytes[0]); assertEquals('K'.code.toByte(), bytes[1])
        val nomes = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { nomes += it.name } }
        assertTrue("[Content_Types].xml" in nomes)
        assertTrue("xl/workbook.xml" in nomes)
    }

    @Test fun `xml remove caracteres de controle invalidos`() {
        assertEquals("ab&amp;&lt;c", ExportadorXlsx.xml("a\u0001b&<c"))
        assertEquals("🚧", ExportadorXlsx.xml("🚧"))
        assertEquals("x", ExportadorXlsx.xml("x\uD800"))
    }

    @Test fun `referencia de colunas excel`() {
        assertEquals("A", ExportadorXlsx.coluna(0)); assertEquals("Z", ExportadorXlsx.coluna(25))
        assertEquals("AA", ExportadorXlsx.coluna(26)); assertEquals("AF", ExportadorXlsx.coluna(31))
    }

    @Test fun `nome de arquivo e mime`() {
        val nome = GeradorExportacao.nomeArquivo(FormatoExportacao.CSV, Fixtures.T0, zona, null)
        assertTrue(nome, Regex("""servicos_saneamento_leme_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}\.csv""").matches(nome))
        assertTrue(GeradorExportacao.nomeArquivo(FormatoExportacao.XLSX, Fixtures.T0, zona, "São João").endsWith("_sao_joao.xlsx"))
        assertEquals("text/csv", FormatoExportacao.CSV.mime)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", FormatoExportacao.XLSX.mime)
    }

    @Test fun `exportacao grande gera rapido e completa`() {
        val linhas = (1..5000).map { linha("id$it", observacoes = "obs $it; \"x\"") }
        val ini = System.nanoTime()
        val csv = gerar(pedido(linhas, FormatoExportacao.CSV))
        val xlsx = gerar(pedido(linhas, FormatoExportacao.XLSX))
        val ms = (System.nanoTime() - ini) / 1_000_000
        assertEquals(5001, lerCsv(csv).size)
        XSSFWorkbook(ByteArrayInputStream(xlsx)).use { assertEquals(5000, it.getSheet("Serviços").lastRowNum) }
        assertFalse("demorou ${ms}ms", ms > 20_000)
    }
}
