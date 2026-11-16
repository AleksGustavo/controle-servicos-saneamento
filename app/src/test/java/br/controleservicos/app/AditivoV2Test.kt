package br.controleservicos.app

import br.controleservicos.app.Fixtures.MIN
import br.controleservicos.app.Fixtures.T0
import br.controleservicos.app.Fixtures.linha
import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.EntradaNumerica
import br.controleservicos.app.core.domain.Estatisticas
import br.controleservicos.app.core.domain.EstatisticasEquipe
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.ResolvedorRelogio
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.export.ContratoExportacao
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.export.GeradorExportacao
import br.controleservicos.app.core.export.PedidoExportacao
import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.StatusServico
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId

/** Testes JVM do aditivo Leme/equipes/ramal/cronômetro. Dados SINTÉTICOS. IDs conforme a especificação. */
class AditivoV2Test {
    private val zona = ZoneId.of("America/Sao_Paulo")

    private fun p(servico: String, func: String, nome: String = func.uppercase(), funcao: FuncaoEquipe = FuncaoEquipe.ENCANADOR) =
        ParticipanteServico(servico, func, nome, null, true, nome, funcao, null, null)

    private fun equipe(servico: String, vararg ids: String) = ids.map { p(servico, it) }

    // ---------------- Composição ----------------

    @Test fun `EQP-006 composicao ABCD independe da ordem de selecao`() {
        assertEquals(Composicao.chave(listOf("A", "B", "C", "D")), Composicao.chave(listOf("D", "B", "A", "C")))
        assertEquals(Composicao.assinatura(Composicao.chave(listOf("A", "B", "C", "D"))), Composicao.assinatura(Composicao.chave(listOf("C", "A", "D", "B"))))
        val lista = listOf(linha(id = "s1", duracaoMin = 30), linha(id = "s2", duracaoMin = 50))
        val part = mapOf("s1" to equipe("s1", "A", "B", "C", "D"), "s2" to equipe("s2", "D", "C", "B", "A"))
        val g = EstatisticasEquipe.porComposicao(lista, part, 5)
        assertEquals(1, g.size)
        assertEquals(2, g.single().total.n)
    }

    @Test fun `EQP-007 composicao AECD e diferente de ABCD`() {
        assertNotEquals(Composicao.chave(listOf("A", "B", "C", "D")), Composicao.chave(listOf("A", "E", "C", "D")))
        val lista = listOf(linha(id = "s1"), linha(id = "s2"))
        val part = mapOf("s1" to equipe("s1", "A", "B", "C", "D"), "s2" to equipe("s2", "A", "E", "C", "D"))
        assertEquals(2, EstatisticasEquipe.porComposicao(lista, part, 5).size)
    }

    @Test fun `EQP-008 servico com quatro participantes conta uma vez na composicao`() {
        val lista = listOf(linha(id = "s1", duracaoMin = 40))
        val part = mapOf("s1" to equipe("s1", "A", "B", "C", "D"))
        val g = EstatisticasEquipe.porComposicao(lista, part, 5).single()
        assertEquals(1, g.total.n)
        assertEquals(40 * MIN.toDouble(), g.total.mediaMs!!, 0.0)
    }

    @Test fun `EQP-009 media e mediana impar e par com n e rede ausente`() {
        val impar = listOf(30L, 40L, 50L).mapIndexed { i, d -> linha(id = "i$i", duracaoMin = d, redeMin = if (i == 0) 10 else null) }
        val partI = impar.associate { it.id to equipe(it.id, "A", "B") }
        val gi = EstatisticasEquipe.porComposicao(impar, partI, 5).single()
        assertEquals(3, gi.total.n)
        assertEquals(40 * MIN.toDouble(), gi.total.mediaMs!!, 0.0)
        assertEquals(40 * MIN.toDouble(), gi.total.medianaMs!!, 0.0)
        // nTotal ≠ nRedeEncontrada: só 1 marco válido.
        assertEquals(1, gi.ateRede.n)
        assertEquals(10 * MIN.toDouble(), gi.ateRede.medianaMs!!, 0.0)

        val par = listOf(10L, 20L, 30L, 100L).mapIndexed { i, d -> linha(id = "p$i", duracaoMin = d) }
        val partP = par.associate { it.id to equipe(it.id, "A", "B") }
        val gp = EstatisticasEquipe.porComposicao(par, partP, 5).single()
        assertEquals(4, gp.total.n)
        assertEquals(40 * MIN.toDouble(), gp.total.mediaMs!!, 0.0)
        assertEquals(25 * MIN.toDouble(), gp.total.medianaMs!!, 0.0) // média dos dois centrais
        assertEquals(0, gp.ateRede.n)
        assertNull(gp.ateRede.medianaMs)
    }

    @Test fun `EQP-009 mediana de valores enormes sem overflow`() {
        val grande = Long.MAX_VALUE / 2
        assertEquals(grande.toDouble(), Estatisticas.percentil(listOf(grande, grande), 0.5)!!, 1.0)
    }

    @Test fun `EQP-010 participacao individual inclui o servico para cada participante`() {
        val lista = listOf(linha(id = "s1", duracaoMin = 30), linha(id = "s2", duracaoMin = 60))
        val part = mapOf("s1" to equipe("s1", "A", "B"), "s2" to equipe("s2", "A", "C"))
        val g = EstatisticasEquipe.porFuncionario(lista, part, 5).associateBy { it.chave.substringBefore('#') }
        assertEquals(2, g.getValue("A").total.n)
        assertEquals(1, g.getValue("B").total.n)
        assertEquals(1, g.getValue("C").total.n)
        // Ordem alfabética (sem ranking por média).
        assertEquals(listOf("A", "B", "C"), EstatisticasEquipe.porFuncionario(lista, part, 5).map { it.rotulo })
    }

    @Test fun `equipe separada por tipo de servico e sem equipe fora das estatisticas de equipe`() {
        val lista = listOf(
            linha(id = "s1", tipo = "PRP", tipoNome = "PRP"), linha(id = "s2", tipo = "AGUA", tipoNome = "Ligação"),
            linha(id = "s3"), linha(id = "s4", status = StatusServico.CANCELADO),
        )
        val part = mapOf("s1" to equipe("s1", "A"), "s2" to equipe("s2", "A"), "s4" to equipe("s4", "A"))
        assertEquals(2, EstatisticasEquipe.porComposicao(lista, part, 5).size)
        assertEquals(1, EstatisticasEquipe.semEquipe(lista, part))
    }

    @Test fun `perfil de funcoes 2+1+1 e distinto da composicao nominal`() {
        val a = listOf(
            p("s1", "A", funcao = FuncaoEquipe.ENCANADOR), p("s1", "B", funcao = FuncaoEquipe.ENCANADOR),
            p("s1", "C", funcao = FuncaoEquipe.MAQUINISTA), p("s1", "D", funcao = FuncaoEquipe.OPERADOR_BASCULANTE),
        )
        val b = listOf(
            p("s2", "E", funcao = FuncaoEquipe.ENCANADOR), p("s2", "F", funcao = FuncaoEquipe.ENCANADOR),
            p("s2", "G", funcao = FuncaoEquipe.MAQUINISTA), p("s2", "H", funcao = FuncaoEquipe.OPERADOR_BASCULANTE),
        )
        assertEquals("2 Encanador + 1 Maquinista + 1 Op. basculante", Composicao.perfilFuncoes(a))
        val lista = listOf(linha(id = "s1"), linha(id = "s2"))
        val part = mapOf("s1" to a, "s2" to b)
        assertEquals(1, EstatisticasEquipe.porPerfilFuncoes(lista, part, 5).size)
        assertEquals(2, EstatisticasEquipe.porComposicao(lista, part, 5).size)
    }

    @Test fun `pseudonimo estavel e sem nome`() {
        assertEquals(Composicao.pseudonimo("uuid-1"), Composicao.pseudonimo("uuid-1"))
        assertTrue(Composicao.pseudonimo("uuid-1").matches(Regex("F-[0-9a-f]{6}")))
        assertNull(Composicao.chave(emptyList()))
    }

    // ---------------- Busca ----------------

    @Test fun `EQP-001 busca parcial sem caixa e sem acentos`() {
        assertTrue(Texto.correspondeBusca("joao", "João da Silva"))
        assertTrue(Texto.correspondeBusca("JOÃO SIL", "João da Silva"))
        assertTrue(Texto.correspondeBusca("ze", "José", "Zé Pequeno"))
        assertFalse(Texto.correspondeBusca("maria", "João da Silva"))
        assertTrue(Texto.correspondeBusca("  ", "qualquer"))
    }

    @Test fun `filtro por participante`() {
        val lista = listOf(linha(id = "s1"), linha(id = "s2"))
        val part = mapOf("s1" to equipe("s1", "A", "B"), "s2" to listOf(p("s2", "C", nome = "Zuleica Ávila")))
        assertEquals(listOf("s1"), FiltroServicos(funcionarioId = "B").aplicar(lista, participantes = part).map { it.id })
        assertEquals(listOf("s2"), FiltroServicos(texto = "zuleica avila").aplicar(lista, participantes = part).map { it.id })
    }

    // ---------------- Ramal ----------------

    private fun cm(t: String, limite: Int = 100) = Numeros.metrosParaCentimetros(t, limite)

    @Test fun `RAM-003 3,5 m vira 350 cm sem virar 35 nem 3`() {
        assertEquals(EntradaNumerica.Valida(350), cm("3,5"))
        assertEquals(EntradaNumerica.Valida(350), cm("3.5"))
        assertEquals(EntradaNumerica.Valida(300), cm("3"))
        assertEquals(EntradaNumerica.Valida(1225), cm(" 12,25 "))
        assertEquals("3,5", Numeros.formatarMetrosBr(350))
        assertEquals("12,25", Numeros.formatarMetrosBr(1225))
        assertEquals("3", Numeros.formatarMetrosBr(300))
        assertEquals(java.math.BigDecimal("3.5"), Numeros.centimetrosEmMetros(350))
    }

    @Test fun `RAM-005 zero negativo letras e excesso rejeitados`() {
        listOf("0", "0,0", "-1", "-3,5", "abc", "3,5m", "1.234", "3,555", "1,2,3", ",5").forEach { t ->
            assertTrue("deveria rejeitar '$t'", cm(t) is EntradaNumerica.Invalida)
        }
        assertEquals(EntradaNumerica.Vazia, cm(""))
        assertTrue(cm("101") is EntradaNumerica.Invalida)
        assertEquals(EntradaNumerica.Valida(10000), cm("100"))
        assertTrue(cm("150", limite = 200) is EntradaNumerica.Valida)
    }

    // ---------------- Relógio ----------------

    @Test fun `TEM-007 hora alterada no mesmo boot nao distorce a duracao monotonica`() {
        val ini = Marcacao(T0, 1_000_000L, 7)
        // 30 min reais se passaram, mas a hora do aparelho foi adiantada 2 h.
        val fimAdiantado = Marcacao(T0 + 30 * MIN + 120 * MIN, 1_000_000L + 30 * MIN, 7)
        val r = ResolvedorRelogio.resolver(ini, fimAdiantado)
        assertEquals(T0 + 30 * MIN, r.instanteMs)
        assertEquals(BaseTempo.MONOTONICO, r.base)
        // Hora atrasada 1 h.
        val fimAtrasado = Marcacao(T0 + 30 * MIN - 60 * MIN, 1_000_000L + 30 * MIN, 7)
        assertEquals(T0 + 30 * MIN, ResolvedorRelogio.resolver(ini, fimAtrasado).instanteMs)
        assertEquals(30 * MIN, ResolvedorRelogio.decorridoParaExibicao(ini, fimAtrasado))
    }

    @Test fun `TEM-008 reinicio detectado usa relogio do sistema com indicador e nunca negativo`() {
        val ini = Marcacao(T0, 9_000_000L, 7)
        val depois = Marcacao(T0 + 45 * MIN, 60_000L, 8)
        assertTrue(ResolvedorRelogio.reiniciouDesde(ini, depois))
        val r = ResolvedorRelogio.resolver(ini, depois)
        assertEquals(T0 + 45 * MIN, r.instanteMs)
        assertEquals(BaseTempo.RELOGIO_SISTEMA_APOS_REINICIO, r.base)
        assertTrue(r.motivo!!.contains("reiniciado"))
        // Sem contador de boot, mas monotônico menor que o do início ⇒ também é reinício.
        assertTrue(ResolvedorRelogio.reiniciouDesde(Marcacao(T0, 9_000_000L, null), Marcacao(T0 + MIN, 10L, null)))
        // Relógio voltou para antes do início após reinício ⇒ duração 0 e revisão, nunca negativa.
        val voltou = ResolvedorRelogio.resolver(ini, Marcacao(T0 - 10 * MIN, 50L, 8))
        assertEquals(T0, voltou.instanteMs)
        assertTrue(voltou.requerRevisao)
    }

    @Test fun `TEM-001 TEM-002 tela apagada ou segundo plano - exibicao recalcula do marco`() {
        val ini = Marcacao(T0, 2_000_000L, 3)
        // Nenhum tique durante 17 min (tela apagada): ao voltar, um único cálculo dá o intervalo inteiro.
        val volta = Marcacao(T0 + 17 * MIN + 4_321, 2_000_000L + 17 * MIN + 4_321, 3)
        assertEquals(17 * MIN + 4_321, ResolvedorRelogio.decorridoParaExibicao(ini, volta))
    }

    // ---------------- Exportação ----------------

    private fun comRamal(l: LinhaServico, lado: LadoRamal?, cm: Int?) =
        l.copy(ramalAguaTrocado = lado != null, ladoRamalTrocado = lado, comprimentoRamalCm = cm)

    private fun registros(linhas: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>, nomes: Boolean) =
        ContratoExportacao.registros(linhas, emptyMap(), zona, part, nomes)

    private fun col(nome: String) = ContratoExportacao.COLUNAS.indexOf(nome)

    @Test fun `EXP-001 colunas novas, ramal vazio quando nao e nomes protegidos`() {
        val linhas = listOf(
            comRamal(linha(id = "s1"), LadoRamal.LONGO, 350),
            linha(id = "s2"),
        )
        val part = mapOf(
            "s1" to listOf(
                ParticipanteServico("s1", "f1", "José; \"Zé\"", "apelido", true, "José", FuncaoEquipe.ENCANADOR, null, null),
                ParticipanteServico("s1", "f2", "Ana|Maria", null, true, "Ana", FuncaoEquipe.OUTRA, "Ajudante", null),
            ),
        )
        listOf("participantes", "funcoes_por_participante", "n_participantes", "chave_composicao", "perfil_funcoes",
            "ramal_agua_trocado", "lado_ramal_trocado", "comprimento_ramal_m", "base_tempo").forEach { assertTrue(it, col(it) >= 0) }
        val t = registros(linhas, part, nomes = true)
        assertEquals(2, t.linhas.size) // uma linha por serviço, não por pessoa
        val r1 = t.linhas[0]
        assertEquals(br.controleservicos.app.core.export.Celula.Txt("SIM"), r1[col("ramal_agua_trocado")])
        assertEquals(br.controleservicos.app.core.export.Celula.Txt("LONGO"), r1[col("lado_ramal_trocado")])
        assertEquals(br.controleservicos.app.core.export.Celula.Num(java.math.BigDecimal("3.5")), r1[col("comprimento_ramal_m")])
        assertEquals(br.controleservicos.app.core.export.Celula.Num(2), r1[col("n_participantes")])
        val nomes = (r1[col("participantes")] as br.controleservicos.app.core.export.Celula.Txt).valor
        assertTrue(nomes.contains("Ana\\|Maria")) // '|' interno escapado
        assertTrue(nomes.contains("José; \"Zé\" (apelido)"))
        val r2 = t.linhas[1]
        assertEquals(br.controleservicos.app.core.export.Celula.Txt("NAO"), r2[col("ramal_agua_trocado")])
        assertEquals(br.controleservicos.app.core.export.Celula.Vazia, r2[col("lado_ramal_trocado")])
        assertEquals(br.controleservicos.app.core.export.Celula.Vazia, r2[col("comprimento_ramal_m")])
        assertEquals(br.controleservicos.app.core.export.Celula.Vazia, r2[col("participantes")])

        // CSV: ';' e aspas dos nomes escapados corretamente, BOM presente, valor 3.5.
        val csv = ByteArrayOutputStream().also {
            GeradorExportacao.gerar(PedidoExportacao(FormatoExportacao.CSV, linhas, emptyMap(), "t", "1", 5, T0, zona, part, incluirNomes = true), it)
        }.toString(Charsets.UTF_8)
        assertTrue(csv.contains("\"José; \"\"Zé\"\" (apelido) | Ana\\|Maria\"") || csv.contains("\"Ana\\|Maria | José; \"\"Zé\"\" (apelido)\""))
        assertTrue(csv.contains(";3.5;"))
    }

    @Test fun `EXP-001 sem nomes usa pseudonimos e nao vaza nomes`() {
        val part = mapOf("s1" to listOf(ParticipanteServico("s1", "f1", "Fulano Secreto", "Fu", true, "Fulano Secreto", FuncaoEquipe.MAQUINISTA, null, "nota pessoal")))
        val linhas = listOf(linha(id = "s1"))
        listOf(FormatoExportacao.CSV, FormatoExportacao.CSV_PARTICIPACOES).forEach { f ->
            val out = ByteArrayOutputStream().also {
                GeradorExportacao.gerar(PedidoExportacao(f, linhas, emptyMap(), "t", "1", 5, T0, zona, part, incluirNomes = false), it)
            }.toString(Charsets.UTF_8)
            assertFalse(out.contains("Fulano"))
            assertFalse(out.contains("nota pessoal"))
            assertTrue(out.contains(Composicao.pseudonimo("f1")))
        }
    }

    @Test fun `EXP-002 xlsx tem abas novas, nao duplica servicos e comprimento numerico`() {
        val linhas = listOf(comRamal(linha(id = "s1", duracaoMin = 30), LadoRamal.CURTO, 350), linha(id = "s2", duracaoMin = 50))
        val part = mapOf("s1" to equipe("s1", "A", "B", "C", "D"), "s2" to equipe("s2", "A", "B", "C", "D"))
        val bytes = ByteArrayOutputStream().also {
            GeradorExportacao.gerar(PedidoExportacao(FormatoExportacao.XLSX, linhas, emptyMap(), "t", "1", 5, T0, zona, part, incluirNomes = true), it)
        }.toByteArray()
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(listOf("Serviços", "Ligações trocadas", "Participantes", "Indicadores por bairro", "Resumo_equipes", "Metadados"), (0 until wb.numberOfSheets).map { wb.getSheetName(it) })
            val reg = wb.getSheet("Serviços")
            assertEquals(2, reg.lastRowNum) // cabeçalho + 2 serviços (não 8)
            val c = reg.getRow(1).getCell(col("comprimento_ramal_m"))
            assertEquals(CellType.NUMERIC, c.cellType)
            assertEquals(3.5, c.numericCellValue, 0.0)
            assertNull(reg.getRow(2).getCell(col("comprimento_ramal_m"))) // vazio, não zero
            assertEquals(8, wb.getSheet("Participantes").lastRowNum) // 4 pessoas × 2 serviços
            val eq = wb.getSheet("Resumo_equipes")
            val cab = eq.getRow(0).map { it.stringCellValue }
            val linhaComp = (1..eq.lastRowNum).map { eq.getRow(it) }.first { it.getCell(0).stringCellValue == "composicao_exata" }
            assertEquals(2.0, linhaComp.getCell(cab.indexOf("n")).numericCellValue, 0.0) // 2 serviços, não 8
            assertEquals(2400.0, linhaComp.getCell(cab.indexOf("media_segundos")).numericCellValue, 0.0)
            assertEquals(2400.0, linhaComp.getCell(cab.indexOf("mediana_segundos")).numericCellValue, 0.0)
        }
    }

    @Test fun `metadados declaram Leme e opcao de nomes`() {
        val m = GeradorExportacao.metadados(PedidoExportacao(FormatoExportacao.XLSX, emptyList(), emptyMap(), "t", "1", 5, T0, zona))
        val mapa = m.linhas.associate { (it[0] as br.controleservicos.app.core.export.Celula.Txt).valor to (it[1] as br.controleservicos.app.core.export.Celula.Txt).valor }
        assertEquals("Leme/SP", mapa["municipio_operacional"])
        assertEquals("3", mapa["versao_esquema_exportacao"])
        assertTrue(mapa.getValue("incluir_nomes_funcionarios").startsWith("NAO"))
    }
}
