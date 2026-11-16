package br.controleservicos.app.core.model

/** Estados de um serviço. Persistidos pelo [name]; não renomear sem migração. */
enum class StatusServico(val rotulo: String) {
    EM_ANDAMENTO("Em andamento"),
    ENCERRADO_PENDENTE_COMPLEMENTOS("Concluído — pendente de complementos"),
    CONCLUIDO("Concluído"),
    CANCELADO("Cancelado");

    companion object {
        fun de(valor: String): StatusServico = entries.firstOrNull { it.name == valor } ?: CONCLUIDO
    }
}

/** Tipos de evento da trilha de auditoria. Persistidos pelo [name]. */
enum class TipoEvento { INICIO, REDE_ENCONTRADA, FIM, CORRECAO, CANCELAMENTO, EXCLUSAO, RESTAURACAO, COMPLEMENTO, PARTICIPANTES }

/** Função exercida por um funcionário (habitual ou no serviço). Persistida pelo [name]. */
enum class FuncaoEquipe(val rotulo: String, val rotuloCurto: String) {
    ENCANADOR("Encanador", "Encanador"),
    MAQUINISTA("Maquinista", "Maquinista"),
    OPERADOR_BASCULANTE("Operador de basculante", "Op. basculante"),
    OUTRA("Outra", "Outra");

    companion object {
        fun de(valor: String?): FuncaoEquipe? = entries.firstOrNull { it.name == valor }
    }
}

/** Lado do trecho de ramal/ligação de água substituído. Persistido pelo [name]. */
enum class LadoRamal(val rotulo: String) {
    LONGO("Lado longo"),
    CURTO("Lado curto");

    companion object {
        fun de(valor: String?): LadoRamal? = entries.firstOrNull { it.name == valor }
    }
}

/**
 * Base de relógio usada para congelar a duração no fim do serviço.
 * MONOTONICO: mesmo boot, imune a ajuste de hora. RELOGIO_SISTEMA_APOS_REINICIO: o aparelho
 * reiniciou durante a medição (sujeito a ajuste de relógio). RELOGIO_SISTEMA: sem base monotônica.
 */
enum class BaseTempo(val rotulo: String) {
    MONOTONICO("Relógio interno (mesmo boot)"),
    RELOGIO_SISTEMA_APOS_REINICIO("Relógio do sistema após reinício — sujeito a ajuste de hora"),
    RELOGIO_SISTEMA("Relógio do sistema — sujeito a ajuste de hora"),
}

/** Origem de um bairro/logradouro do catálogo local (exibida discretamente quando relevante). */
enum class OrigemEndereco(val rotulo: String) {
    LOCAL_SERVICO("Base local"),
    VIACEP("ViaCEP"),
    MANUAL("Inserido manualmente"),
    IMPORTACAO("Lista importada");

    companion object {
        fun de(valor: String?): OrigemEndereco? = entries.firstOrNull { it.name == valor }
    }
}

/** Contexto operacional fixo desta fase: uso exclusivo em Leme/SP. */
object LocalOperacional {
    const val MUNICIPIO = "Leme"
    const val UF = "SP"
    const val PAIS = "BR"
    const val TIMEZONE = "America/Sao_Paulo"
    val ZONA: java.time.ZoneId = java.time.ZoneId.of(TIMEZONE)
    const val ROTULO = "Leme · SP"
    /** Código IBGE de Leme/SP (validação de respostas de APIs de endereço). */
    const val CODIGO_IBGE = "3526704"
}

/** Situação do marco "rede encontrada" informada no formulário final. */
enum class SituacaoRede(val rotulo: String) {
    MARCADA("Sim (marcada)"),
    NAO_MARCADA("Não marcada"),
    NAO_APLICAVEL("Não aplicável");

    companion object {
        fun de(valor: String?): SituacaoRede? = entries.firstOrNull { it.name == valor }
    }
}

/** Códigos estáveis dos itens de catálogo semeados. Nomes exibidos ficam no banco. */
object CodigosCatalogo {
    const val TIPO_VAZAMENTO_VISIVEL = "VAZAMENTO_VISIVEL"
    const val TIPO_PRP_NAO_VISIVEL = "PRP_NAO_VISIVEL"
    const val TIPO_LIGACAO_AGUA = "LIGACAO_AGUA"
    const val TIPO_LIGACAO_ESGOTO = "LIGACAO_ESGOTO"

    const val COND_ESGOTO_ACIMA_REDE = "ESGOTO_ACIMA_REDE"
    const val COND_REDE_DANIFICADA = "REDE_DANIFICADA_ABERTURA"
    const val COND_MAIS_DE_UMA_LIGACAO = "MAIS_DE_UMA_LIGACAO"
    const val COND_NENHUMA = "NENHUMA"
    const val COND_OUTRAS = "OUTRAS"
}

/** Constantes de regra de negócio centralizadas. */
object RegrasNegocio {
    /** Limiar padrão (configurável) abaixo do qual a amostra é marcada como pequena. */
    const val LIMIAR_AMOSTRA_PEQUENA_PADRAO = 5

    /** Percentis só são exibidos a partir deste n. */
    const val N_MINIMO_PERCENTIS = 10

    /** Divergência entre relógio de parede e relógio monotônico que exige revisão. */
    const val TOLERANCIA_DIVERGENCIA_RELOGIO_MS = 2 * 60 * 1000L

    const val LIMITE_OBSERVACOES = 2000
    const val LIMITE_DESCRICAO_CONDICAO = 300
    const val LIMITE_CAMPO_ENDERECO = 150
    const val TAMANHO_MINIMO_JUSTIFICATIVA = 5

    /** Versão do esquema das exportações CSV/XLSX. Incrementar ao mudar colunas. */
    const val VERSAO_ESQUEMA_EXPORTACAO = 3

    /** Limite técnico padrão (configurável) do comprimento de ramal trocado, para detectar erro de digitação. */
    const val LIMITE_RAMAL_METROS_PADRAO = 100

    const val LIMITE_NOME_FUNCIONARIO = 80
    const val LIMITE_NOTA_PARTICIPANTE = 120
}
