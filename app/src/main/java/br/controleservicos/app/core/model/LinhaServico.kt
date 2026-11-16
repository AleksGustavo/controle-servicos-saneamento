package br.controleservicos.app.core.model

/**
 * Registro de serviço já com nomes de catálogo resolvidos (resultado de JOIN).
 * Tipo puro, usado por domínio, filtros, estatísticas, telas e exportação.
 */
data class LinhaServico(
    val id: String,
    val tipoServicoId: String,
    val tipoNome: String,
    val municipioId: String,
    val municipioNome: String,
    val municipioUf: String?,
    val bairroId: String,
    val bairroNome: String,
    val logradouro: String,
    val numero: String,
    val complemento: String?,
    val equipeIdentificador: String?,
    val status: StatusServico,
    val inicioUtcEpochMs: Long,
    val inicioZoneId: String?,
    val inicioElapsedRealtimeMs: Long?,
    val inicioBootCount: Int?,
    val redeEncontradaUtcEpochMs: Long?,
    val fimUtcEpochMs: Long?,
    val duracaoTotalMs: Long?,
    val duracaoAteRedeMs: Long?,
    val situacaoRede: SituacaoRede?,
    val diametroMm: Int?,
    val materialRedeId: String?,
    val materialNome: String?,
    val profundidadeMetros: Double?,
    val quantidadeLigacoes: Int?,
    val observacoes: String?,
    val requerRevisao: Boolean,
    val motivoRevisao: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    /** Ligação/ramal de água trocado nesta escavação (false em registros anteriores à v2). */
    val ramalAguaTrocado: Boolean = false,
    /** Somente quando [ramalAguaTrocado]; null caso contrário. */
    val ladoRamalTrocado: LadoRamal? = null,
    /** Comprimento substituído em centímetros inteiros (exato); null se não trocado. */
    val comprimentoRamalCm: Int? = null,
    /** Base de relógio usada ao congelar a duração; null em registros antigos/em andamento. */
    val baseTempo: BaseTempo? = null,
    val logradouroId: String? = null,
    val cep: String? = null,
) {
    val excluido: Boolean get() = deletedAt != null

    val enderecoResumido: String
        get() = buildString {
            append(logradouro)
            append(", ")
            append(numero)
            if (!complemento.isNullOrBlank()) append(" — ").append(complemento)
        }

    /** Faltam complementos ou há revisão pendente. */
    val incompleto: Boolean
        get() = status == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS || requerRevisao

    /** Registro de município diferente do operacional (histórico anterior à fixação em Leme/SP). */
    val foraDoMunicipioOperacional: Boolean
        get() = br.controleservicos.app.core.domain.Texto.normalizarNome(municipioNome) !=
            br.controleservicos.app.core.domain.Texto.normalizarNome(LocalOperacional.MUNICIPIO) ||
            (municipioUf ?: "") != LocalOperacional.UF
}

/** Participação de um funcionário num serviço, com nome atual e fotografia histórica. */
data class ParticipanteServico(
    val servicoId: String,
    val funcionarioId: String,
    /** Nome de exibição atual do cadastro (muda se o funcionário for renomeado). */
    val nomeAtual: String,
    val apelidoOuCodigo: String?,
    val funcionarioAtivo: Boolean,
    /** Nome no momento em que a participação foi gravada. */
    val nomeSnapshot: String,
    val funcaoNoServico: FuncaoEquipe,
    val funcaoDescricao: String?,
    val nota: String?,
) {
    val funcaoTexto: String
        get() = if (funcaoNoServico == FuncaoEquipe.OUTRA && !funcaoDescricao.isNullOrBlank()) "Outra: $funcaoDescricao" else funcaoNoServico.rotulo
}

/** Condição observada associada a um serviço, com nome resolvido. */
data class CondicaoRegistro(
    val servicoId: String,
    val condicaoId: String,
    val codigo: String,
    val nome: String,
    val descricao: String?,
)

/** Ligação/ramal trocado (um imóvel atendido), pronto para telas e documentos. */
data class LigacaoTrocada(
    val id: String,
    val servicoId: String,
    val ordem: Int,
    val numeroTexto: String?,
    val bairroTexto: String?,
    val logradouroTexto: String?,
    val complemento: String?,
    val identificadorLigacao: String?,
    val lado: LadoRamal,
    val comprimentoCm: Int,
    val observacoes: String?,
    val pendenteIdentificacao: Boolean,
    val legado: Boolean,
) {
    /** "nº 12A — Rua X, Bairro Y (lig. 2)" usando o endereço principal quando não há próprio. */
    fun descricaoImovel(ruaPrincipal: String, bairroPrincipal: String): String = buildString {
        append(if (numeroTexto.isNullOrBlank()) "nº não identificado" else "nº $numeroTexto")
        if (!complemento.isNullOrBlank()) append(" ").append(complemento)
        val rua = logradouroTexto?.takeIf { it.isNotBlank() } ?: ruaPrincipal
        val bairro = bairroTexto?.takeIf { it.isNotBlank() } ?: bairroPrincipal
        append(" — ").append(rua).append(", ").append(bairro)
        if (!identificadorLigacao.isNullOrBlank()) append(" (").append(identificadorLigacao).append(")")
    }
}
