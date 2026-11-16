package br.controleservicos.app.core.domain

import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.ParticipanteServico
import java.security.MessageDigest

/**
 * Composição de equipe = conjunto exato de IDs estáveis dos funcionários presentes num serviço.
 * Não usa nomes (renomear não quebra a série) nem funções (função é atributo da participação).
 */
object Composicao {

    /** Chave canônica: IDs distintos, ordenados, unidos por "|". Null quando não há participantes. */
    fun chave(ids: Collection<String>): String? {
        val ordenados = ids.toSortedSet()
        return if (ordenados.isEmpty()) null else ordenados.joinToString("|")
    }

    /** Assinatura curta e determinística (SHA-256, 12 hex) da chave, para exportação. */
    fun assinatura(chave: String?): String? {
        if (chave == null) return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(chave.toByteArray(Charsets.UTF_8))
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    fun chaveDe(participantes: List<ParticipanteServico>?): String? = chave(participantes.orEmpty().map { it.funcionarioId })

    /**
     * Perfil por funções (métrica complementar, NÃO é composição nominal):
     * ex. "2 Encanador + 1 Maquinista + 1 Op. basculante". Null quando não há participantes.
     */
    fun perfilFuncoes(participantes: List<ParticipanteServico>?): String? {
        if (participantes.isNullOrEmpty()) return null
        return FuncaoEquipe.entries.mapNotNull { f ->
            participantes.count { it.funcaoNoServico == f }.takeIf { it > 0 }?.let { "$it ${f.rotuloCurto}" }
        }.joinToString(" + ")
    }

    /**
     * Composições exatas existentes, para o filtro "Equipe": (chave, rótulo, nº de serviços).
     * Rótulo com nomes atuais ou, sem nomes, com pseudônimos. Ordem alfabética (sem ranking).
     */
    fun opcoes(participantes: Map<String, List<ParticipanteServico>>, comNomes: Boolean): List<Triple<String, String, Int>> =
        participantes.values.filter { it.isNotEmpty() }
            .groupBy { chaveDe(it)!! }
            .map { (chave, servicos) ->
                val membros = servicos.first()
                val rotulo = if (comNomes) EstatisticasEquipe.rotuloMembros(membros)
                else membros.map { pseudonimo(it.funcionarioId) }.sorted().joinToString(", ")
                Triple(chave, rotulo, servicos.size)
            }
            .sortedBy { Texto.normalizarNome(it.second) }

    /** Pseudônimo não diretamente pessoal para exportação sem nomes: "F-" + 6 primeiros hex do id. */
    fun pseudonimo(funcionarioId: String): String = "F-" + (assinatura(funcionarioId) ?: "").take(6)
}

/** Estatística de um grupo de serviços com equipe registrada. */
data class EstatisticaEquipe(
    val chave: String,
    val tipoServicoId: String,
    val tipoNome: String,
    /** Rótulo do grupo: nomes dos integrantes, nome do funcionário ou perfil de funções. */
    val rotulo: String,
    val bairroNome: String?,
    /** Serviços válidos no grupo (cada serviço conta uma vez). */
    val total: Resumo,
    /** Somente serviços com marco de rede válido (nRedeEncontrada). */
    val ateRede: Resumo,
    val primeiroInicioMs: Long?,
    val ultimoInicioMs: Long?,
    val qualidade: QualidadeAmostra,
)

/**
 * Indicadores por equipe, sempre separados por tipo de serviço e sem ranking:
 * a ordenação é alfabética, nunca por média.
 */
object EstatisticasEquipe {

    private fun montar(chave: String, rotulo: String, bairro: String?, itens: List<LinhaServico>, limiar: Int): EstatisticaEquipe {
        val total = Estatisticas.resumir(itens.mapNotNull(Estatisticas::duracaoValida))
        val f = itens.first()
        return EstatisticaEquipe(
            chave = chave,
            tipoServicoId = f.tipoServicoId,
            tipoNome = f.tipoNome,
            rotulo = rotulo,
            bairroNome = bairro,
            total = total,
            ateRede = Estatisticas.resumir(itens.mapNotNull(Estatisticas::ateRedeValida)),
            primeiroInicioMs = itens.minOfOrNull { it.inicioUtcEpochMs },
            ultimoInicioMs = itens.maxOfOrNull { it.inicioUtcEpochMs },
            qualidade = Estatisticas.qualidade(total.n, limiar),
        )
    }

    /** Nomes atuais dos integrantes, em ordem alfabética, para rotular uma composição. */
    fun rotuloMembros(participantes: List<ParticipanteServico>): String =
        participantes.sortedBy { Texto.normalizarNome(it.nomeAtual) }
            .joinToString(", ") { p -> p.nomeAtual + (p.apelidoOuCodigo?.let { " ($it)" } ?: "") }

    private fun validosComEquipe(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>) =
        lista.filter { Estatisticas.ehValido(it) && !part[it.id].isNullOrEmpty() }

    /** Por composição exata (conjunto de IDs) + tipo. Um serviço com 4 pessoas conta 1 vez. */
    fun porComposicao(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>, limiar: Int): List<EstatisticaEquipe> =
        validosComEquipe(lista, part)
            .groupBy { it.tipoServicoId to Composicao.chaveDe(part[it.id])!! }
            .map { (k, itens) -> montar("${k.first}#${k.second}", rotuloMembros(part.getValue(itens.first().id)), null, itens, limiar) }
            .sortedWith(compareBy({ it.tipoNome }, { it.rotulo }))

    /** Por bairro + tipo + composição exata. */
    fun porBairroEComposicao(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>, limiar: Int): List<EstatisticaEquipe> =
        validosComEquipe(lista, part)
            .groupBy { Triple(it.bairroId, it.tipoServicoId, Composicao.chaveDe(part[it.id])!!) }
            .map { (k, itens) ->
                montar("${k.first}#${k.second}#${k.third}", rotuloMembros(part.getValue(itens.first().id)), itens.first().bairroNome, itens, limiar)
            }
            .sortedWith(compareBy({ it.tipoNome }, { it.bairroNome }, { it.rotulo }))

    /**
     * Participação individual: cada serviço entra na série de CADA participante.
     * É tempo da equipe que executou o serviço, não produtividade individual.
     */
    fun porFuncionario(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>, limiar: Int): List<EstatisticaEquipe> {
        val pares = validosComEquipe(lista, part).flatMap { s -> part.getValue(s.id).map { p -> p to s } }
        return pares.groupBy { (p, s) -> p.funcionarioId to s.tipoServicoId }
            .map { (k, itens) ->
                val p = itens.first().first
                montar("${k.first}#${k.second}", p.nomeAtual + (p.apelidoOuCodigo?.let { " ($it)" } ?: ""), null, itens.map { it.second }.distinctBy { it.id }, limiar)
            }
            .sortedWith(compareBy({ it.tipoNome }, { Texto.normalizarNome(it.rotulo) }))
    }

    /** Por perfil de funções (quantidade por função), métrica complementar à composição nominal. */
    fun porPerfilFuncoes(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>, limiar: Int): List<EstatisticaEquipe> =
        validosComEquipe(lista, part)
            .groupBy { it.tipoServicoId to Composicao.perfilFuncoes(part[it.id])!! }
            .map { (k, itens) -> montar("${k.first}#${k.second}", k.second, null, itens, limiar) }
            .sortedWith(compareBy({ it.tipoNome }, { it.rotulo }))

    /** Serviços válidos sem equipe registrada (ficam só nas estatísticas gerais). */
    fun semEquipe(lista: List<LinhaServico>, part: Map<String, List<ParticipanteServico>>): Int =
        lista.count { Estatisticas.ehValido(it) && part[it.id].isNullOrEmpty() }
}
