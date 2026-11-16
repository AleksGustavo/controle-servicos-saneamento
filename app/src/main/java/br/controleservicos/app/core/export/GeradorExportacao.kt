package br.controleservicos.app.core.export

import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.RegrasNegocio
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FormatoExportacao(val extensao: String, val mime: String, val rotulo: String) {
    CSV("csv", "text/csv", "CSV"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)"),
    CSV_PARTICIPACOES("csv", "text/csv", "CSV de participações (1 linha por pessoa)"),
    /** ZIP com servicos.csv, ligacoes_trocadas.csv, participantes.csv, indicadores_por_bairro.csv e metadados.csv. */
    CSV_ZIP("zip", "application/zip", "CSV (arquivo .zip com tabelas relacionadas)"),
}

data class PedidoExportacao(
    val formato: FormatoExportacao,
    val linhas: List<LinhaServico>,
    val condicoes: Map<String, List<CondicaoRegistro>>,
    /** Descrição legível dos filtros aplicados (ou "Todos os dados"). */
    val descricaoFiltros: String,
    val versaoApp: String,
    val limiarAmostra: Int,
    val geradoEmMs: Long,
    val zona: ZoneId,
    val participantes: Map<String, List<ParticipanteServico>> = emptyMap(),
    /** Padrão conservador: sem nomes de funcionários (pseudônimos estáveis no lugar). */
    val incluirNomes: Boolean = false,
    val ligacoes: Map<String, List<LigacaoTrocada>> = emptyMap(),
)

object GeradorExportacao {
    private val FMT_NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")

    /** `servicos_saneamento_YYYY-MM-DD_HH-mm[_bairro].ext` — sem dados pessoais. */
    fun nomeArquivo(formato: FormatoExportacao, agoraMs: Long, zona: ZoneId, bairroFiltrado: String?): String {
        val data = FMT_NOME.format(Instant.ofEpochMilli(agoraMs).atZone(zona))
        val sufixo = bairroFiltrado?.let(Texto::paraNomeArquivo)?.takeIf { it.isNotEmpty() }?.let { "_$it" } ?: ""
        val base = if (formato == FormatoExportacao.CSV_PARTICIPACOES) "participacoes_saneamento" else "servicos_saneamento_leme"
        return "${base}_$data$sufixo.${formato.extensao}"
    }

    fun gerar(p: PedidoExportacao, saida: OutputStream) {
        val registros = ContratoExportacao.registros(p.linhas, p.condicoes, p.zona, p.participantes, p.incluirNomes, p.ligacoes)
        when (p.formato) {
            FormatoExportacao.CSV_ZIP -> {
                val zip = java.util.zip.ZipOutputStream(saida)
                fun csv(nome: String, t: Tabela) {
                    zip.putNextEntry(java.util.zip.ZipEntry(nome))
                    val buf = java.io.ByteArrayOutputStream()
                    ExportadorCsv.escrever(t, buf)
                    zip.write(buf.toByteArray())
                    zip.closeEntry()
                }
                csv("servicos.csv", registros)
                csv("ligacoes_trocadas.csv", ContratoExportacao.ligacoes(p.linhas, p.ligacoes, p.zona))
                csv("participantes.csv", ContratoExportacao.participacoes(p.linhas, p.participantes, p.zona, p.incluirNomes))
                csv("indicadores_por_bairro.csv", ContratoExportacao.indicadoresPorBairro(p.linhas, p.ligacoes, p.limiarAmostra))
                csv("metadados.csv", metadados(p))
                zip.finish()
                zip.flush()
            }
            FormatoExportacao.CSV -> ExportadorCsv.escrever(registros, saida)
            FormatoExportacao.CSV_PARTICIPACOES -> ExportadorCsv.escrever(
                ContratoExportacao.participacoes(p.linhas, p.participantes, p.zona, p.incluirNomes), saida,
            )
            FormatoExportacao.XLSX -> ExportadorXlsx.escrever(
                listOf(
                    ExportadorXlsx.Planilha("Serviços", registros),
                    ExportadorXlsx.Planilha("Ligações trocadas", ContratoExportacao.ligacoes(p.linhas, p.ligacoes, p.zona)),
                    ExportadorXlsx.Planilha("Participantes", ContratoExportacao.participacoes(p.linhas, p.participantes, p.zona, p.incluirNomes)),
                    ExportadorXlsx.Planilha("Indicadores por bairro", ContratoExportacao.indicadoresPorBairro(p.linhas, p.ligacoes, p.limiarAmostra)),
                    ExportadorXlsx.Planilha("Resumo_equipes", ContratoExportacao.resumoEquipes(p.linhas, p.participantes, p.limiarAmostra, p.incluirNomes)),
                    ExportadorXlsx.Planilha("Metadados", metadados(p), cabecalhoDestacado = true, filtro = false),
                ),
                saida,
            )
        }
    }

    fun metadados(p: PedidoExportacao): Tabela {
        val inicio = p.linhas.minOfOrNull { it.inicioUtcEpochMs }
        val fim = p.linhas.maxOfOrNull { it.inicioUtcEpochMs }
        val pares = listOf(
            "aplicativo" to "Controle de Serviços",
            "municipio_operacional" to "${LocalOperacional.MUNICIPIO}/${LocalOperacional.UF}",
            "versao_aplicativo" to p.versaoApp,
            "versao_esquema_exportacao" to RegrasNegocio.VERSAO_ESQUEMA_EXPORTACAO.toString(),
            "exportado_em_local" to (Tempo.localParaExportacao(p.geradoEmMs, p.zona) ?: ""),
            "exportado_em_utc" to (Tempo.utcIso(p.geradoEmMs) ?: ""),
            "fuso_horario" to p.zona.id,
            "filtros" to p.descricaoFiltros,
            "quantidade_registros" to p.linhas.size.toString(),
            "periodo_primeiro_inicio" to (Tempo.localParaExportacao(inicio, p.zona) ?: "sem dados"),
            "periodo_ultimo_inicio" to (Tempo.localParaExportacao(fim, p.zona) ?: "sem dados"),
            "unidades" to "duracoes em segundos (inteiros, truncados) e HH:mm:ss; diametro em mm; profundidade em metros (ponto decimal)",
            "datas" to "*_local no fuso do aparelho (yyyy-MM-dd HH:mm:ss); *_utc_iso em ISO 8601 UTC",
            "criterio_resumo" to "Resumo considera apenas registros nao excluidos, nao cancelados, com fim e duracao validos e sem revisao pendente",
            "limiar_amostra_pequena" to p.limiarAmostra.toString(),
            "campos_vazios" to "celula vazia = nao informado (diferente de zero)",
            "condicoes_observadas" to "itens separados por ' | '; '|' e '\\' dentro de um item sao escapados com '\\'",
            "incluir_nomes_funcionarios" to (if (p.incluirNomes) "SIM" else "NAO (referencias pseudonimas F-xxxxxx, estaveis entre exportacoes)"),
            "participantes" to "uma linha por servico na aba Registros (nunca repetida por pessoa); detalhamento relacional na aba Participantes",
            "chave_composicao" to "SHA-256 (12 hex) dos IDs estaveis dos participantes, ordenados; nao usa nomes nem funcoes",
            "estatisticas_equipe" to "Resumo_equipes conta cada servico uma vez por grupo; e tempo da equipe, nao produtividade individual; sem ranking",
            "ramal_agua_trocado" to "SIM/NAO; lado_ramal_trocado e comprimento_ramal_m vazios quando NAO. Registros anteriores a v2 aparecem como NAO (campo nao existia)",
            "comprimento_ramal_m" to "metros com ate 2 casas decimais (ponto decimal); v3: lado_ramal_trocado/comprimento_ramal_m so quando ha exatamente UMA ligacao; detalhes por imovel na aba/arquivo Ligacoes trocadas",
            "ligacoes_trocadas" to "uma linha por imovel atendido, relacionada por id_servico; quantidade_ligacoes_trocadas e a contagem dessas linhas e nunca equivale a quantidade de servicos",
            "indicadores_por_bairro" to "agrupado por bairro e tipo; n = servicos validos (concluidos/pendentes de complemento, sem revisao, duracao coerente); media/mediana das duracoes individuais (nao media de medias); P75/P90 a partir de n=10",
            "base_tempo" to "MONOTONICO = relogio interno no mesmo boot; RELOGIO_SISTEMA_APOS_REINICIO / RELOGIO_SISTEMA = sujeito a ajuste de hora; vazio = registro anterior a v2 ou nao finalizado",
        )
        return Tabela(listOf("chave", "valor"), pares.map { (k, v) -> listOf(txt(k), txt(v)) })
    }
}
