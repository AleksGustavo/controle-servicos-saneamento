package br.controleservicos.app.core.share

import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.export.ExportadorXlsx
import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.ParticipanteServico
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tabela simples do relatório. */
data class TabelaRelatorio(val cabecalho: List<String>, val linhas: List<List<String>>)

/** Uma seção do relatório: pares rótulo/valor, parágrafos e/ou uma tabela. */
data class SecaoRelatorio(
    val titulo: String,
    val pares: List<Pair<String, String>> = emptyList(),
    val paragrafos: List<String> = emptyList(),
    val tabela: TabelaRelatorio? = null,
)

/** Conteúdo neutro de um serviço (sem brasão, logotipo ou caráter oficial). */
data class RelatorioServico(
    val titulo: String,
    val subtitulo: String,
    val secoes: List<SecaoRelatorio>,
    val rodape: String,
) {
    /** Todos os textos, para testes de completude. */
    fun textos(): List<String> = listOf(titulo, subtitulo, rodape) + secoes.flatMap { s ->
        listOf(s.titulo) + s.pares.flatMap { listOf(it.first, it.second) } + s.paragrafos +
            (s.tabela?.let { t -> t.cabecalho + t.linhas.flatten() } ?: emptyList())
    }
}

enum class FormatoRelatorio(val extensao: String, val mime: String, val rotulo: String) {
    PDF("pdf", "application/pdf", "PDF (.pdf)"),
    PNG("png", "image/png", "Imagem (.png)"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Documento editável (.docx)"),
}

object MontadorRelatorio {
    const val NAO_INFORMADO = "Não informado"
    const val NAO_REGISTRADO = "Não registrado"
    private val FMT_ARQ = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** `servico_LEME_YYYYMMDD_<id8>.ext` — sem caracteres inválidos e sem dados pessoais. */
    fun nomeArquivo(s: LinhaServico, formato: FormatoRelatorio, pagina: Int? = null): String {
        val data = FMT_ARQ.format(Instant.ofEpochMilli(s.inicioUtcEpochMs).atZone(LocalOperacional.ZONA))
        val id = s.id.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "servico" }
        val pag = pagina?.let { "_p$it" } ?: ""
        return "servico_LEME_${data}_$id$pag.${formato.extensao}"
    }

    fun montar(
        s: LinhaServico,
        condicoes: List<CondicaoRegistro>,
        participantes: List<ParticipanteServico>,
        ligacoes: List<LigacaoTrocada>,
        ocultarNomes: Boolean,
        geradoEmMs: Long,
    ): RelatorioServico {
        val total = Tempo.medirDuracaoTotal(s.inicioUtcEpochMs, s.fimUtcEpochMs)
        val ateRede = Tempo.medirTempoAteRede(s.inicioUtcEpochMs, s.redeEncontradaUtcEpochMs, s.fimUtcEpochMs)
        val secoes = mutableListOf<SecaoRelatorio>()
        secoes += SecaoRelatorio(
            "Serviço",
            listOf(
                "Tipo" to s.tipoNome,
                "Situação" to (s.status.rotulo + if (s.excluido) " (excluído)" else ""),
                "Identificador" to s.id,
            ),
        )
        secoes += SecaoRelatorio(
            "Endereço",
            listOf(
                "Município" to "${s.municipioNome}/${s.municipioUf ?: LocalOperacional.UF}",
                "Bairro" to s.bairroNome,
                "Logradouro" to s.logradouro,
                "Número" to s.numero,
                "Complemento" to (s.complemento ?: NAO_INFORMADO),
                "CEP" to (s.cep ?: NAO_INFORMADO),
            ),
        )
        secoes += SecaoRelatorio(
            "Horários e durações",
            listOf(
                "Início da escavação" to Tempo.dataHora(s.inicioUtcEpochMs),
                "Rede encontrada (horário)" to (s.redeEncontradaUtcEpochMs?.let { Tempo.dataHora(it) } ?: NAO_REGISTRADO),
                "Fim da escavação" to (s.fimUtcEpochMs?.let { Tempo.dataHora(it) } ?: NAO_REGISTRADO),
                "Duração total (início → fim)" to (total?.let { Tempo.formatarDuracao(it) } ?: NAO_REGISTRADO),
                "Tempo até encontrar a rede (início → rede)" to (ateRede?.let { Tempo.formatarDuracao(it) } ?: NAO_REGISTRADO),
            ),
            paragrafos = listOfNotNull(
                "O tempo até encontrar a rede é parte da duração total; não são somados.",
                s.baseTempo?.takeIf { it != BaseTempo.MONOTONICO }?.let { "Duração sujeita a ajuste de relógio: ${it.rotulo}." },
                if (s.requerRevisao) "Horários em revisão: ${s.motivoRevisao ?: "verificar"}." else null,
            ),
        )
        secoes += if (participantes.isEmpty()) {
            SecaoRelatorio("Equipe", paragrafos = listOf("Equipe não registrada."))
        } else {
            SecaoRelatorio(
                "Equipe",
                tabela = TabelaRelatorio(
                    listOf("Participante", "Função no serviço"),
                    participantes.sortedBy { Texto.normalizarNome(it.nomeAtual) }.mapIndexed { i, p ->
                        listOf(if (ocultarNomes) "Participante ${i + 1} (${Composicao.pseudonimo(p.funcionarioId)})" else p.nomeAtual, p.funcaoTexto)
                    },
                ),
                paragrafos = if (ocultarNomes) listOf("Nomes ocultados neste documento.") else emptyList(),
            )
        }
        secoes += SecaoRelatorio(
            "Rede",
            listOf(
                "Rede encontrada?" to (s.situacaoRede?.rotulo ?: NAO_INFORMADO),
                "Material" to (s.materialNome ?: NAO_INFORMADO),
                "Diâmetro" to (s.diametroMm?.let { "$it mm" } ?: NAO_INFORMADO),
                "Profundidade" to (s.profundidadeMetros?.let { "${Numeros.formatarDecimalBr(it)} m" } ?: NAO_INFORMADO),
                "Ligações existentes no local" to (s.quantidadeLigacoes?.toString() ?: NAO_INFORMADO),
            ),
        )
        secoes += if (!s.ramalAguaTrocado || ligacoes.isEmpty()) {
            SecaoRelatorio("Ligações trocadas", paragrafos = listOf("Não houve troca de ligação/ramal."))
        } else {
            SecaoRelatorio(
                "Ligações trocadas (${ligacoes.size})",
                tabela = TabelaRelatorio(
                    listOf("Nº", "Imóvel atendido", "Lado", "Comprimento", "Observações"),
                    ligacoes.sortedBy { it.ordem }.map { l ->
                        listOf(
                            l.ordem.toString(),
                            l.descricaoImovel(s.logradouro, s.bairroNome),
                            l.lado.rotulo,
                            "${Numeros.formatarMetrosBr(l.comprimentoCm)} m",
                            listOfNotNull(l.observacoes, if (l.pendenteIdentificacao) "número pendente de identificação" else null).joinToString("; ").ifEmpty { "—" },
                        )
                    },
                ),
                paragrafos = listOf("Total utilizado: ${Numeros.formatarMetrosBr(ligacoes.sumOf { it.comprimentoCm })} m."),
            )
        }
        secoes += SecaoRelatorio(
            "Condições observadas",
            paragrafos = if (condicoes.isEmpty()) listOf("Nenhuma registrada.") else condicoes.map { c -> "• " + c.nome + (c.descricao?.let { ": $it" } ?: "") },
        )
        if (!s.observacoes.isNullOrBlank()) secoes += SecaoRelatorio("Observações", paragrafos = listOf(s.observacoes))
        return RelatorioServico(
            titulo = "Relatório de serviço — Leme/SP",
            subtitulo = "${s.tipoNome} • ${s.bairroNome} • ${Tempo.data(s.inicioUtcEpochMs)}",
            secoes = secoes,
            rodape = "Gerado em ${Tempo.dataHora(geradoEmMs)} pelo aplicativo Controle de Serviços (registro pessoal, não é documento oficial).",
        )
    }
}

/**
 * Documento editável DOCX real (WordprocessingML/OOXML), sem dependências: título, seções,
 * pares rótulo/valor e tabelas com bordas. Abre no Word, LibreOffice e Google Docs.
 */
object GeradorDocx {
    private fun x(s: String) = ExportadorXlsx.xml(s)

    private fun run(texto: String, negrito: Boolean = false, cor: String? = null, tamanho: Int? = null) = buildString {
        append("<w:r><w:rPr>")
        if (negrito) append("<w:b/>")
        cor?.let { append("""<w:color w:val="$it"/>""") }
        tamanho?.let { append("""<w:sz w:val="$it"/>""") }
        append("""</w:rPr><w:t xml:space="preserve">${x(texto)}</w:t></w:r>""")
    }

    private fun par(vararg runs: String, estilo: String? = null) =
        "<w:p>" + (estilo?.let { """<w:pPr><w:pStyle w:val="$it"/></w:pPr>""" } ?: "") + runs.joinToString("") + "</w:p>"

    private fun tabela(cab: List<String>, linhas: List<List<String>>): String = buildString {
        append("""<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/><w:tblW w:w="5000" w:type="pct"/>""")
        append("""<w:tblBorders>""")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { append("""<w:$it w:val="single" w:sz="4" w:space="0" w:color="1565C0"/>""") }
        append("</w:tblBorders></w:tblPr>")
        append("<w:tr>")
        cab.forEach { append("""<w:tc><w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="E3F0FD"/></w:tcPr>""" + par(run(it, negrito = true)) + "</w:tc>") }
        append("</w:tr>")
        linhas.forEach { l -> append("<w:tr>"); l.forEach { c -> append("<w:tc>" + par(run(c)) + "</w:tc>") }; append("</w:tr>") }
        append("</w:tbl>")
        append(par())
    }

    fun corpo(r: RelatorioServico): String = buildString {
        append(par(run(r.titulo, negrito = true, cor = "1565C0", tamanho = 36), estilo = "Title"))
        append(par(run(r.subtitulo, cor = "4A5568")))
        r.secoes.forEach { s ->
            append(par(run(s.titulo, negrito = true, cor = "0D47A1", tamanho = 26), estilo = "Heading1"))
            s.pares.forEach { (k, v) -> append(par(run("$k: ", negrito = true), run(v))) }
            s.tabela?.let { append(tabela(it.cabecalho, it.linhas)) }
            s.paragrafos.forEach { append(par(run(it))) }
        }
        append(par(run(r.rodape, cor = "4A5568", tamanho = 16)))
    }

    fun escrever(r: RelatorioServico, saida: OutputStream) {
        val zip = ZipOutputStream(saida)
        fun e(nome: String, c: String) { zip.putNextEntry(ZipEntry(nome)); zip.write(c.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        e(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>""",
        )
        e("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        e("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        e(
            "word/styles.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:lang w:val="pt-BR"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="80"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="0"/></w:pPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/></w:style></w:styles>""",
        )
        e(
            "word/document.xml",
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""" +
                corpo(r) +
                """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="709" w:footer="709" w:gutter="0"/></w:sectPr></w:body></w:document>""",
        )
        zip.finish()
        zip.flush()
    }
}
