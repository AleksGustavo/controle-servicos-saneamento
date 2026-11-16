package br.controleservicos.app

import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico

/** Dados SINTÉTICOS somente para testes. */
object Fixtures {
    const val T0 = 1_763_000_000_000L
    const val MIN = 60_000L

    fun linha(
        id: String = "s1",
        tipo: String = "PRP_NAO_VISIVEL",
        tipoNome: String = "PRP",
        municipioId: String = "m1",
        municipio: String = "Cidade A",
        bairroId: String = "b1",
        bairro: String = "Centro",
        inicio: Long = T0,
        duracaoMin: Long? = 30,
        redeMin: Long? = null,
        status: StatusServico = StatusServico.CONCLUIDO,
        deletado: Boolean = false,
        revisao: Boolean = false,
        observacoes: String? = null,
        logradouro: String = "Rua Teste",
        equipe: String? = null,
    ): LinhaServico {
        val fim = duracaoMin?.let { inicio + it * MIN }
        val rede = redeMin?.let { inicio + it * MIN }
        return LinhaServico(
            id = id, tipoServicoId = tipo, tipoNome = tipoNome, municipioId = municipioId, municipioNome = municipio, municipioUf = "SP",
            bairroId = bairroId, bairroNome = bairro, logradouro = logradouro, numero = "100", complemento = null, equipeIdentificador = equipe,
            status = status, inicioUtcEpochMs = inicio, inicioZoneId = "America/Sao_Paulo", inicioElapsedRealtimeMs = null, inicioBootCount = null,
            redeEncontradaUtcEpochMs = rede, fimUtcEpochMs = fim, duracaoTotalMs = fim?.minus(inicio), duracaoAteRedeMs = rede?.minus(inicio),
            situacaoRede = if (rede != null) SituacaoRede.MARCADA else SituacaoRede.NAO_MARCADA,
            diametroMm = null, materialRedeId = null, materialNome = null, profundidadeMetros = null, quantidadeLigacoes = null,
            observacoes = observacoes, requerRevisao = revisao, motivoRevisao = null, createdAt = inicio, updatedAt = inicio,
            deletedAt = if (deletado) inicio else null,
        )
    }
}
