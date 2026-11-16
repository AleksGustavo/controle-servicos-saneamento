package br.controleservicos.app.core.domain

import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.RegrasNegocio
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Regras puras de tempo. Todas as durações em milissegundos (Long), sem arredondamento.
 * Resultado `null` significa "não disponível/inválido" — nunca zero inventado.
 */
object Tempo {

    /** `fim - inicio`; null se faltar o fim ou se for negativo. */
    fun medirDuracaoTotal(inicio: Long, fim: Long?): Long? {
        if (fim == null) return null
        val d = fim - inicio
        return if (d >= 0) d else null
    }

    /** `rede - inicio`; null se não houver marco ou se a rede estiver fora de [inicio, fim]. */
    fun medirTempoAteRede(inicio: Long, rede: Long?, fim: Long? = null): Long? {
        if (rede == null) return null
        if (rede < inicio) return null
        if (fim != null && rede > fim) return null
        return rede - inicio
    }

    /** `fim - rede`; null se faltar algum marco ou se a ordem for inválida. */
    fun medirTempoAposRede(inicio: Long, rede: Long?, fim: Long?): Long? {
        if (rede == null || fim == null) return null
        if (rede < inicio || rede > fim) return null
        return fim - rede
    }

    /** Valida a ordem `inicio <= rede <= fim`. Retorna mensagem de erro ou null se válido. */
    fun validarOrdem(inicio: Long, rede: Long?, fim: Long?): String? {
        if (fim != null && fim < inicio) return "O fim não pode ser anterior ao início."
        if (rede != null && rede < inicio) return "A rede encontrada não pode ser anterior ao início."
        if (rede != null && fim != null && rede > fim) return "A rede encontrada não pode ser posterior ao fim."
        return null
    }

    /**
     * Formata duração como `HH:mm:ss`, truncando (não arredondando) os milissegundos.
     * Horas podem passar de 24 (ex.: `27:03:09`). Nulo → "--:--:--".
     */
    fun formatarDuracao(millis: Long?): String {
        if (millis == null) return "--:--:--"
        val negativo = millis < 0
        val totalSeg = kotlin.math.abs(millis) / 1000
        val h = totalSeg / 3600
        val m = (totalSeg % 3600) / 60
        val s = totalSeg % 60
        val txt = "%02d:%02d:%02d".format(h, m, s)
        return if (negativo) "-$txt" else txt
    }

    /** Segundos inteiros (truncados) para exportação; null preservado. */
    fun emSegundos(millis: Long?): Long? = millis?.let { it / 1000 }

    private val FMT_HORA = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FMT_DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    private val FMT_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val FMT_EXPORT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val FMT_ENTRADA = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

    fun hora(epochMs: Long?, zona: ZoneId = LocalOperacional.ZONA): String =
        epochMs?.let { FMT_HORA.format(Instant.ofEpochMilli(it).atZone(zona)) } ?: "—"

    fun dataHora(epochMs: Long?, zona: ZoneId = LocalOperacional.ZONA): String =
        epochMs?.let { FMT_DATA_HORA.format(Instant.ofEpochMilli(it).atZone(zona)) } ?: "—"

    fun data(epochMs: Long?, zona: ZoneId = LocalOperacional.ZONA): String =
        epochMs?.let { FMT_DATA.format(Instant.ofEpochMilli(it).atZone(zona)) } ?: "—"

    /** Data/hora local para planilhas (ordenável): `yyyy-MM-dd HH:mm:ss`. */
    fun localParaExportacao(epochMs: Long?, zona: ZoneId): String? =
        epochMs?.let { FMT_EXPORT_LOCAL.format(Instant.ofEpochMilli(it).atZone(zona)) }

    /** Instante ISO 8601 em UTC, ex.: `2026-11-13T11:12:20.123Z`. */
    fun utcIso(epochMs: Long?): String? = epochMs?.let { Instant.ofEpochMilli(it).toString() }

    /** Interpreta `dd/MM/aaaa HH:mm:ss` no fuso informado. Null se inválido. */
    fun parseDataHora(texto: String, zona: ZoneId = LocalOperacional.ZONA): Long? = try {
        LocalDateTime.parse(texto.trim(), FMT_ENTRADA).atZone(zona).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/** Instante capturado no momento de uma ação: relógio de parede + monotônico + boot. */
data class Marcacao(
    val wallClockMs: Long,
    val elapsedRealtimeMs: Long?,
    val bootCount: Int?,
)

/** Resultado da resolução de um instante relativo ao início do serviço. */
data class InstanteResolvido(
    val instanteMs: Long,
    /** Diferença (monotônico - parede) quando comparável; null se não comparável. */
    val divergenciaMs: Long?,
    val requerRevisao: Boolean,
    val motivo: String?,
    /** Relógio que efetivamente determinou o instante (indicador de confiança da duração). */
    val base: BaseTempo = BaseTempo.RELOGIO_SISTEMA,
)

/**
 * Resolve o instante de um marco (rede/fim) a partir da marcação de início.
 *
 * - Mesmo boot e base monotônica disponível: usa `inicio + (elapsedAgora - elapsedInicio)`,
 *   imune a ajustes manuais do relógio. Se divergir do relógio de parede além da tolerância,
 *   marca para revisão (o valor bruto do relógio fica no evento de auditoria).
 * - Boot diferente (aparelho reiniciado) ou sem base monotônica: usa o relógio de parede.
 *   Se ele estiver antes do início (relógio voltou), usa o próprio início (duração 0)
 *   e marca para revisão — nunca gera duração negativa.
 */
object ResolvedorRelogio {
    fun resolver(inicio: Marcacao, agora: Marcacao, tolerancia: Long = RegrasNegocio.TOLERANCIA_DIVERGENCIA_RELOGIO_MS): InstanteResolvido {
        val mesmoBoot = inicio.bootCount != null && inicio.bootCount == agora.bootCount &&
            inicio.elapsedRealtimeMs != null && agora.elapsedRealtimeMs != null &&
            agora.elapsedRealtimeMs >= inicio.elapsedRealtimeMs
        if (mesmoBoot) {
            val decorridoMono = agora.elapsedRealtimeMs!! - inicio.elapsedRealtimeMs!!
            val instante = inicio.wallClockMs + decorridoMono
            val divergencia = instante - agora.wallClockMs
            val revisar = kotlin.math.abs(divergencia) > tolerancia
            return InstanteResolvido(
                instanteMs = instante,
                divergenciaMs = divergencia,
                requerRevisao = revisar,
                motivo = if (revisar) "Hora do aparelho alterada durante a medição (diferença de ${Tempo.formatarDuracao(kotlin.math.abs(divergencia))}). Tempo calculado pelo relógio interno; confira." else null,
                base = BaseTempo.MONOTONICO,
            )
        }
        val reiniciou = reiniciouDesde(inicio, agora)
        val base = if (reiniciou) BaseTempo.RELOGIO_SISTEMA_APOS_REINICIO else BaseTempo.RELOGIO_SISTEMA
        if (agora.wallClockMs < inicio.wallClockMs) {
            return InstanteResolvido(
                instanteMs = inicio.wallClockMs,
                divergenciaMs = null,
                requerRevisao = true,
                motivo = "Hora do aparelho anterior ao início do serviço. Horário ajustado ao início; corrija manualmente.",
                base = base,
            )
        }
        return InstanteResolvido(
            instanteMs = agora.wallClockMs,
            divergenciaMs = null,
            requerRevisao = false,
            motivo = if (reiniciou) "Aparelho reiniciado durante a medição; tempo calculado pelo relógio do sistema (sujeito a ajuste de hora)." else null,
            base = base,
        )
    }

    /**
     * Houve reinício entre as marcações? Contador de boots diferente, ou relógio monotônico
     * menor que o do início (elapsedRealtime zera no boot). Âncoras de boots diferentes nunca são comparadas.
     */
    fun reiniciouDesde(inicio: Marcacao, agora: Marcacao): Boolean =
        (inicio.bootCount != null && agora.bootCount != null && inicio.bootCount != agora.bootCount) ||
            (inicio.elapsedRealtimeMs != null && agora.elapsedRealtimeMs != null && agora.elapsedRealtimeMs < inicio.elapsedRealtimeMs)

    /** Tempo decorrido para exibição do cronômetro (somente apresentação). */
    fun decorridoParaExibicao(inicio: Marcacao, agora: Marcacao): Long =
        resolver(inicio, agora, Long.MAX_VALUE).instanteMs - inicio.wallClockMs
}
