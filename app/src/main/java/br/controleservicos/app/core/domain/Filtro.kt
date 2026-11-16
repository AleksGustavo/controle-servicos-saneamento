package br.controleservicos.app.core.domain

import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.StatusServico
import java.time.LocalDate
import java.time.ZoneId

enum class Periodo(val rotulo: String) {
    TODOS("Todo o período"),
    HOJE("Hoje"),
    ULTIMOS_7("Últimos 7 dias"),
    ULTIMOS_30("Últimos 30 dias"),
    ESTE_ANO("Este ano"),
}

enum class Ordenacao(val rotulo: String) {
    RECENTES("Mais recentes"),
    ANTIGOS("Mais antigos"),
    MAIOR_DURACAO("Maior duração"),
    MENOR_DURACAO("Menor duração"),
}

/** Filtros compartilhados por Histórico, Estatísticas e Exportação ("Filtros atuais"). */
data class FiltroServicos(
    val municipioId: String? = null,
    val bairroId: String? = null,
    val tipoServicoId: String? = null,
    val periodo: Periodo = Periodo.TODOS,
    val status: StatusServico? = null,
    val equipe: String? = null,
    /** Somente serviços em que este funcionário participou. */
    val funcionarioId: String? = null,
    /** Composição exata de equipe (chave de IDs ordenados, ver Composicao.chave). */
    val chaveComposicao: String? = null,
    /** Vários bairros (vazio = todos). Combina com [bairroId] por AND quando ambos definidos. */
    val bairroIds: Set<String> = emptySet(),
    /** Vários tipos (vazio = todos). */
    val tipoIds: Set<String> = emptySet(),
    val materialId: String? = null,
    /** Intervalo personalizado (inclusivo, no fuso de Leme). Tem prioridade sobre [periodo]. */
    val dataInicial: LocalDate? = null,
    val dataFinal: LocalDate? = null,
    /** Somente concluídos/encerrados (exclui em andamento e cancelados). */
    val somenteFinalizados: Boolean = false,
    val texto: String = "",
    val incluirExcluidos: Boolean = false,
    val ordenacao: Ordenacao = Ordenacao.RECENTES,
) {
    val ativo: Boolean
        get() = municipioId != null || bairroId != null || tipoServicoId != null || periodo != Periodo.TODOS ||
            status != null || !equipe.isNullOrBlank() || funcionarioId != null || chaveComposicao != null || texto.isNotBlank() || incluirExcluidos ||
            bairroIds.isNotEmpty() || tipoIds.isNotEmpty() || materialId != null || dataInicial != null || dataFinal != null || somenteFinalizados

    /** Intervalo [inicio, fim) em epoch ms para o período, no fuso informado. */
    fun intervalo(agoraMs: Long, zona: ZoneId): Pair<Long, Long>? {
        if (dataInicial != null || dataFinal != null) {
            val ini = dataInicial?.atStartOfDay(zona)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
            val fim = dataFinal?.plusDays(1)?.atStartOfDay(zona)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
            return ini to fim
        }
        val hoje = java.time.Instant.ofEpochMilli(agoraMs).atZone(zona).toLocalDate()
        val inicio: LocalDate = when (periodo) {
            Periodo.TODOS -> return null
            Periodo.HOJE -> hoje
            Periodo.ULTIMOS_7 -> hoje.minusDays(6)
            Periodo.ULTIMOS_30 -> hoje.minusDays(29)
            Periodo.ESTE_ANO -> hoje.withDayOfYear(1)
        }
        val fim = hoje.plusDays(1)
        return inicio.atStartOfDay(zona).toInstant().toEpochMilli() to fim.atStartOfDay(zona).toInstant().toEpochMilli()
    }

    fun aplicar(
        lista: List<LinhaServico>,
        agoraMs: Long = System.currentTimeMillis(),
        zona: ZoneId = LocalOperacional.ZONA,
        participantes: Map<String, List<ParticipanteServico>> = emptyMap(),
    ): List<LinhaServico> {
        val faixa = intervalo(agoraMs, zona)
        val busca = Texto.normalizarNome(texto)
        val equipeNorm = equipe?.takeIf { it.isNotBlank() }?.let(Texto::normalizarNome)
        val filtrada = lista.filter { s ->
            (incluirExcluidos || s.deletedAt == null) &&
                (municipioId == null || s.municipioId == municipioId) &&
                (bairroId == null || s.bairroId == bairroId) &&
                (tipoServicoId == null || s.tipoServicoId == tipoServicoId) &&
                (status == null || s.status == status) &&
                (equipeNorm == null || Texto.normalizarNome(s.equipeIdentificador ?: "") == equipeNorm) &&
                (funcionarioId == null || participantes[s.id].orEmpty().any { it.funcionarioId == funcionarioId }) &&
                (chaveComposicao == null || Composicao.chaveDe(participantes[s.id]) == chaveComposicao) &&
                (bairroIds.isEmpty() || s.bairroId in bairroIds) &&
                (tipoIds.isEmpty() || s.tipoServicoId in tipoIds) &&
                (materialId == null || s.materialRedeId == materialId) &&
                (!somenteFinalizados || s.status == StatusServico.CONCLUIDO || s.status == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS) &&
                (faixa == null || (s.inicioUtcEpochMs >= faixa.first && s.inicioUtcEpochMs < faixa.second)) &&
                (busca.isEmpty() || Texto.normalizarNome(
                    (
                        listOfNotNull(s.tipoNome, s.bairroNome, s.municipioNome, s.logradouro, s.numero, s.complemento, s.equipeIdentificador, s.observacoes, s.materialNome) +
                            participantes[s.id].orEmpty().map { it.nomeAtual }
                        ).joinToString(" "),
                ).contains(busca))
        }
        return when (ordenacao) {
            Ordenacao.RECENTES -> filtrada.sortedByDescending { it.inicioUtcEpochMs }
            Ordenacao.ANTIGOS -> filtrada.sortedBy { it.inicioUtcEpochMs }
            Ordenacao.MAIOR_DURACAO -> filtrada.sortedByDescending { it.duracaoTotalMs ?: Long.MIN_VALUE }
            Ordenacao.MENOR_DURACAO -> filtrada.sortedBy { it.duracaoTotalMs ?: Long.MAX_VALUE }
        }
    }
}
