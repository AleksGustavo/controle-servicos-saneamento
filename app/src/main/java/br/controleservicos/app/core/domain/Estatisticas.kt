package br.controleservicos.app.core.domain

import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.model.StatusServico

/** Medidas descritivas de um conjunto de durações (ms). */
data class Resumo(
    val n: Int,
    val mediaMs: Double?,
    val medianaMs: Double?,
    val minimoMs: Long?,
    val maximoMs: Long?,
    val p25Ms: Double?,
    val p75Ms: Double?,
    val p90Ms: Double?,
) {
    companion object {
        val VAZIO = Resumo(0, null, null, null, null, null, null, null)
    }
}

enum class QualidadeAmostra { SEM_DADOS, AMOSTRA_PEQUENA, SUFICIENTE }

/** Estatísticas de um grupo (ex.: tipo de serviço, ou tipo + bairro). */
data class EstatisticaGrupo(
    val chave: String,
    val rotulo: String,
    val total: Resumo,
    val ateRede: Resumo,
    val primeiroInicioMs: Long?,
    val ultimoInicioMs: Long?,
    val qualidade: QualidadeAmostra,
)

/** Contagem de registros fora das métricas, por motivo. */
data class Exclusoes(
    val cancelados: Int = 0,
    val excluidos: Int = 0,
    val emAndamento: Int = 0,
    val semDuracaoValida: Int = 0,
    val emRevisao: Int = 0,
) {
    val total get() = cancelados + excluidos + emAndamento + semDuracaoValida + emRevisao
}

object Estatisticas {

    /**
     * Regra de validade para métricas: não excluído, status CONCLUIDO ou
     * ENCERRADO_PENDENTE_COMPLEMENTOS (a duração já está congelada), com fim e duração
     * coerentes (>= 0) e sem revisão de relógio pendente.
     */
    fun motivoExclusao(s: LinhaServico): String? = when {
        s.deletedAt != null -> "excluido"
        s.status == StatusServico.CANCELADO -> "cancelado"
        s.status == StatusServico.EM_ANDAMENTO -> "andamento"
        s.requerRevisao -> "revisao"
        s.fimUtcEpochMs == null || duracaoValida(s) == null -> "invalido"
        else -> null
    }

    fun ehValido(s: LinhaServico) = motivoExclusao(s) == null

    /** Duração total recalculada dos timestamps e conferida com o snapshot persistido. */
    fun duracaoValida(s: LinhaServico): Long? {
        val d = Tempo.medirDuracaoTotal(s.inicioUtcEpochMs, s.fimUtcEpochMs) ?: return null
        if (s.duracaoTotalMs != null && s.duracaoTotalMs != d) return null
        return d
    }

    fun ateRedeValida(s: LinhaServico): Long? =
        Tempo.medirTempoAteRede(s.inicioUtcEpochMs, s.redeEncontradaUtcEpochMs, s.fimUtcEpochMs)

    fun contarExclusoes(lista: List<LinhaServico>): Exclusoes {
        var e = Exclusoes()
        for (s in lista) {
            e = when (motivoExclusao(s)) {
                "excluido" -> e.copy(excluidos = e.excluidos + 1)
                "cancelado" -> e.copy(cancelados = e.cancelados + 1)
                "andamento" -> e.copy(emAndamento = e.emAndamento + 1)
                "revisao" -> e.copy(emRevisao = e.emRevisao + 1)
                "invalido" -> e.copy(semDuracaoValida = e.semDuracaoValida + 1)
                else -> e
            }
        }
        return e
    }

    /** Calcula o resumo de uma lista de durações. Sem arredondamento interno. */
    fun resumir(duracoes: List<Long>): Resumo {
        if (duracoes.isEmpty()) return Resumo.VAZIO
        val ord = duracoes.sorted()
        val n = ord.size
        val soma = ord.fold(0.0) { acc, v -> acc + v }
        val mostrarPercentis = n >= RegrasNegocio.N_MINIMO_PERCENTIS
        return Resumo(
            n = n,
            mediaMs = soma / n,
            medianaMs = percentil(ord, 0.5),
            minimoMs = ord.first(),
            maximoMs = ord.last(),
            p25Ms = if (mostrarPercentis) percentil(ord, 0.25) else null,
            p75Ms = if (mostrarPercentis) percentil(ord, 0.75) else null,
            p90Ms = if (mostrarPercentis) percentil(ord, 0.90) else null,
        )
    }

    /**
     * Percentil por interpolação linear entre pontos de ordem (Hyndman & Fan tipo 7,
     * o mesmo de `PERCENTIL.INC` do Excel e do padrão do R/NumPy):
     * h = (n - 1) * p; resultado = x[⌊h⌋] + (h - ⌊h⌋) * (x[⌊h⌋+1] - x[⌊h⌋]).
     * Com p = 0,5 coincide com a mediana usual (média dos dois centrais quando n é par).
     * [ordenados] deve estar em ordem crescente.
     */
    fun percentil(ordenados: List<Long>, p: Double): Double? {
        if (ordenados.isEmpty()) return null
        require(p in 0.0..1.0)
        val h = (ordenados.size - 1) * p
        val lo = kotlin.math.floor(h).toInt()
        val hi = kotlin.math.min(lo + 1, ordenados.size - 1)
        val frac = h - lo
        return ordenados[lo] + frac * (ordenados[hi] - ordenados[lo])
    }

    fun qualidade(n: Int, limiar: Int): QualidadeAmostra = when {
        n == 0 -> QualidadeAmostra.SEM_DADOS
        n < limiar -> QualidadeAmostra.AMOSTRA_PEQUENA
        else -> QualidadeAmostra.SUFICIENTE
    }

    private fun grupo(chave: String, rotulo: String, itens: List<LinhaServico>, limiar: Int): EstatisticaGrupo {
        val validos = itens.filter(::ehValido)
        val totais = validos.mapNotNull(::duracaoValida)
        val rede = validos.mapNotNull(::ateRedeValida)
        val total = resumir(totais)
        return EstatisticaGrupo(
            chave = chave,
            rotulo = rotulo,
            total = total,
            ateRede = resumir(rede),
            primeiroInicioMs = validos.minOfOrNull { it.inicioUtcEpochMs },
            ultimoInicioMs = validos.maxOfOrNull { it.inicioUtcEpochMs },
            qualidade = qualidade(total.n, limiar),
        )
    }

    /** Estatísticas por tipo de serviço (nunca mistura tipos). */
    fun porTipo(lista: List<LinhaServico>, limiar: Int): List<EstatisticaGrupo> =
        lista.filter(::ehValido).groupBy { it.tipoServicoId }
            .map { (tipoId, itens) -> grupo(tipoId, itens.first().tipoNome, itens, limiar) }
            .sortedBy { it.rotulo }

    /** Estatísticas por tipo + município + bairro. Bairros homônimos em municípios distintos ficam separados. */
    fun porTipoEBairro(lista: List<LinhaServico>, limiar: Int): List<EstatisticaGrupo> =
        lista.filter(::ehValido).groupBy { Triple(it.tipoServicoId, it.municipioId, it.bairroId) }
            .map { (k, itens) ->
                val f = itens.first()
                grupo("${k.first}|${k.second}|${k.third}", "${f.tipoNome} • ${f.bairroNome} (${f.municipioNome})", itens, limiar)
            }
            .sortedWith(compareBy({ it.rotulo }))

    /** Agregado de todos os tipos — só exibir quando solicitado conscientemente. */
    fun agregadoTodosTipos(lista: List<LinhaServico>, limiar: Int): EstatisticaGrupo =
        grupo("TODOS", "Todos os tipos (agregado)", lista, limiar)
}
