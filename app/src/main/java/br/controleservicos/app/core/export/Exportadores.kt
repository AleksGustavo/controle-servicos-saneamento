package br.controleservicos.app.core.export

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CSV: UTF-8 com BOM (Excel reconhece acentos), separador `;`, quebra de linha CRLF,
 * campos entre aspas quando necessário (aspas duplicadas), ponto decimal invariável.
 */
object ExportadorCsv {
    const val SEPARADOR = ';'
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val PERIGOSOS = setOf('=', '+', '-', '@', '\t', '\r', '\n', '|', '%')

    /**
     * Neutraliza injeção de fórmula (CSV/Excel injection): se o primeiro caractere não-espaço
     * for `=`, `+`, `-`, `@`, tab, CR, `|` ou `%`, prefixa um apóstrofo `'`.
     * Também se o texto começar com tab/CR. Aplica-se somente a células de texto livre;
     * colunas numéricas são emitidas como números e não passam por aqui.
     */
    fun neutralizarFormula(valor: String): String {
        if (valor.isEmpty()) return valor
        val primeiro = valor.firstOrNull { it != ' ' } ?: return valor
        return if (primeiro in PERIGOSOS || valor[0] == '\t' || valor[0] == '\r') "'$valor" else valor
    }

    fun escapar(valor: String): String {
        val precisaAspas = valor.any { it == SEPARADOR || it == '"' || it == '\n' || it == '\r' } ||
            valor.startsWith(' ') || valor.endsWith(' ')
        return if (precisaAspas) "\"" + valor.replace("\"", "\"\"") + "\"" else valor
    }

    fun celula(c: Celula): String = when (c) {
        Celula.Vazia -> ""
        is Celula.Num -> ContratoExportacao.formatarNumero(c.valor)
        is Celula.Txt -> escapar(neutralizarFormula(c.valor))
    }

    fun escrever(tabela: Tabela, saida: OutputStream) {
        saida.write(BOM)
        val w = BufferedWriter(OutputStreamWriter(saida, Charsets.UTF_8))
        w.write(tabela.cabecalho.joinToString(SEPARADOR.toString()) { escapar(it) })
        w.write("\r\n")
        for (linha in tabela.linhas) {
            w.write(linha.joinToString(SEPARADOR.toString(), transform = ::celula))
            w.write("\r\n")
        }
        w.flush()
    }
}

/**
 * Gerador XLSX (Office Open XML / SpreadsheetML) mínimo e válido, sem dependências externas.
 * Textos como `inlineStr` (nunca interpretados como fórmula), números como `n`,
 * cabeçalho em negrito com fundo, primeira linha congelada e autofiltro.
 */
object ExportadorXlsx {

    data class Planilha(val nome: String, val tabela: Tabela, val cabecalhoDestacado: Boolean = true, val filtro: Boolean = true)

    fun escrever(planilhas: List<Planilha>, saida: OutputStream) {
        require(planilhas.isNotEmpty())
        val zip = ZipOutputStream(saida)
        fun entrada(nome: String, conteudo: String) {
            zip.putNextEntry(ZipEntry(nome))
            zip.write(conteudo.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entrada("[Content_Types].xml", contentTypes(planilhas.size))
        entrada("_rels/.rels", RELS)
        entrada("docProps/app.xml", APP)
        entrada("xl/workbook.xml", workbook(planilhas))
        entrada("xl/_rels/workbook.xml.rels", workbookRels(planilhas.size))
        entrada("xl/styles.xml", STYLES)
        planilhas.forEachIndexed { i, p ->
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet${i + 1}.xml"))
            val w = zip.bufferedWriter(Charsets.UTF_8)
            escreverPlanilha(p, w)
            w.flush()
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** Referência de coluna estilo Excel: 0 → A, 25 → Z, 26 → AA. */
    fun coluna(indice: Int): String {
        var i = indice
        val sb = StringBuilder()
        do {
            sb.append('A' + (i % 26))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.reverse().toString()
    }

    /** Escapa XML e remove caracteres de controle inválidos em XML 1.0 (mantém \t \n \r e emojis). */
    fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '￾' || c == '￿' -> {}
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(c).append(s[i + 1]); i++
                    }
                }
                Character.isLowSurrogate(c) -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun escreverPlanilha(p: Planilha, w: java.io.Writer) {
        val ncols = p.tabela.cabecalho.size
        val nlin = p.tabela.linhas.size + 1
        val ultima = "${coluna(ncols - 1)}$nlin"
        w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        w.write("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        w.write("""<dimension ref="A1:$ultima"/>""")
        if (p.cabecalhoDestacado) {
            w.write("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/><selection pane="bottomLeft" activeCell="A2" sqref="A2"/></sheetView></sheetViews>""")
        } else {
            w.write("""<sheetViews><sheetView workbookViewId="0"/></sheetViews>""")
        }
        w.write("""<sheetFormatPr defaultRowHeight="15"/>""")
        w.write("<cols>")
        for (c in 0 until ncols) {
            val maxLen = (listOf(p.tabela.cabecalho[c].length) + p.tabela.linhas.take(200).map { l ->
                when (val v = l.getOrNull(c)) { is Celula.Txt -> v.valor.length; is Celula.Num -> 10; else -> 0 }
            }).max()
            val largura = (maxLen + 2).coerceIn(8, 60)
            w.write("""<col min="${c + 1}" max="${c + 1}" width="$largura" customWidth="1"/>""")
        }
        w.write("</cols><sheetData>")
        w.write("""<row r="1">""")
        p.tabela.cabecalho.forEachIndexed { c, h ->
            val estilo = if (p.cabecalhoDestacado) " s=\"1\"" else ""
            w.write("""<c r="${coluna(c)}1" t="inlineStr"$estilo><is><t xml:space="preserve">${xml(h)}</t></is></c>""")
        }
        w.write("</row>")
        p.tabela.linhas.forEachIndexed { li, linha ->
            val r = li + 2
            w.write("""<row r="$r">""")
            linha.forEachIndexed { c, cel ->
                val ref = "${coluna(c)}$r"
                when (cel) {
                    Celula.Vazia -> {}
                    is Celula.Num -> {
                        val d = cel.valor.toDouble()
                        if (d.isFinite()) w.write("""<c r="$ref"><v>${ContratoExportacao.formatarNumero(cel.valor)}</v></c>""")
                    }
                    is Celula.Txt -> w.write("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xml(cel.valor)}</t></is></c>""")
                }
            }
            w.write("</row>")
        }
        w.write("</sheetData>")
        if (p.filtro) w.write("""<autoFilter ref="A1:$ultima"/>""")
        w.write("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""")
        w.write("</worksheet>")
    }

    private fun contentTypes(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        append("""<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>""")
        for (i in 1..n) append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("</Types>")
    }

    private val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private val APP = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Controle de Servicos</Application></Properties>"""

    private fun workbook(planilhas: List<Planilha>) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
        planilhas.forEachIndexed { i, p -> append("""<sheet name="${xml(p.nome.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""") }
        append("</sheets>")
        val comFiltro = planilhas.withIndex().filter { it.value.filtro }
        if (comFiltro.isNotEmpty()) {
            append("<definedNames>")
            for ((i, p) in comFiltro) {
                val ultima = "\$${coluna(p.tabela.cabecalho.size - 1)}\$${p.tabela.linhas.size + 1}"
                append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">'${xml(p.nome.take(31).replace("'", "''"))}'!${'$'}A${'$'}1:$ultima</definedName>""")
            }
            append("</definedNames>")
        }
        append("</workbook>")
    }

    private fun workbookRels(n: Int) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..n) append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        append("""<Relationship Id="rId${n + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        append("</Relationships>")
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/><family val="2"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF1565C0"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
}
