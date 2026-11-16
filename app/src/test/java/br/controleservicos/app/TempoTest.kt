package br.controleservicos.app

import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.ResolvedorRelogio
import br.controleservicos.app.core.domain.Tempo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class TempoTest {
    private val seg = 1000L
    private val min = 60 * seg
    private val h = 60 * min
    private val t0 = 1_763_000_000_000L

    @Test fun `duracao total diferenca exata em ms`() {
        assertEquals(1234L, Tempo.medirDuracaoTotal(t0, t0 + 1234))
    }

    @Test fun `rede intermediaria calcula total, ate rede e apos rede`() {
        val rede = t0 + 16 * min + 50 * seg
        val fim = t0 + 1 * h + 24 * min + 35 * seg
        assertEquals(fim - t0, Tempo.medirDuracaoTotal(t0, fim))
        assertEquals(16 * min + 50 * seg, Tempo.medirTempoAteRede(t0, rede, fim))
        assertEquals(fim - rede, Tempo.medirTempoAposRede(t0, rede, fim))
        assertEquals("00:16:50", Tempo.formatarDuracao(Tempo.medirTempoAteRede(t0, rede, fim)))
        assertEquals("01:24:35", Tempo.formatarDuracao(Tempo.medirDuracaoTotal(t0, fim)))
    }

    @Test fun `sem marcacao de rede os tempos associados sao nulos e nao zero`() {
        assertNull(Tempo.medirTempoAteRede(t0, null, t0 + h))
        assertNull(Tempo.medirTempoAposRede(t0, null, t0 + h))
    }

    @Test fun `duracao zero e valida`() {
        assertEquals(0L, Tempo.medirDuracaoTotal(t0, t0))
        assertEquals("00:00:00", Tempo.formatarDuracao(0))
    }

    @Test fun `fim anterior ao inicio nao gera duracao negativa`() {
        assertNull(Tempo.medirDuracaoTotal(t0, t0 - 1))
        assertNotNull(Tempo.validarOrdem(t0, null, t0 - 1))
    }

    @Test fun `rede antes do inicio ou depois do fim e invalida`() {
        assertNull(Tempo.medirTempoAteRede(t0, t0 - 1, t0 + h))
        assertNull(Tempo.medirTempoAteRede(t0, t0 + 2 * h, t0 + h))
        assertNull(Tempo.medirTempoAposRede(t0, t0 + 2 * h, t0 + h))
        assertNotNull(Tempo.validarOrdem(t0, t0 - 1, t0 + h))
        assertNotNull(Tempo.validarOrdem(t0, t0 + 2 * h, t0 + h))
        assertNull(Tempo.validarOrdem(t0, t0 + 10, t0 + h))
    }

    @Test fun `servicos de 5 minutos, varias horas e mais de 24 horas`() {
        assertEquals("00:05:00", Tempo.formatarDuracao(Tempo.medirDuracaoTotal(t0, t0 + 5 * min)))
        assertEquals("07:30:15", Tempo.formatarDuracao(7 * h + 30 * min + 15 * seg))
        assertEquals("27:03:09", Tempo.formatarDuracao(27 * h + 3 * min + 9 * seg))
        assertEquals("100:00:00", Tempo.formatarDuracao(100 * h))
    }

    @Test fun `formatacao trunca milissegundos sem arredondar`() {
        assertEquals("00:00:59", Tempo.formatarDuracao(59_999))
        assertEquals("00:01:00", Tempo.formatarDuracao(60_000))
        assertEquals("--:--:--", Tempo.formatarDuracao(null))
        assertEquals(59L, Tempo.emSegundos(59_999))
    }

    @Test fun `passagem pela meia noite`() {
        val z = ZoneId.of("America/Sao_Paulo")
        val ini = LocalDateTime.of(2026, 3, 10, 23, 40, 0).atZone(z).toInstant().toEpochMilli()
        val fim = LocalDateTime.of(2026, 3, 11, 0, 25, 30).atZone(z).toInstant().toEpochMilli()
        assertEquals("00:45:30", Tempo.formatarDuracao(Tempo.medirDuracaoTotal(ini, fim)))
        assertEquals("10/03/2026 23:40:00", Tempo.dataHora(ini, z))
        assertEquals("11/03/2026 00:25:30", Tempo.dataHora(fim, z))
    }

    @Test fun `mudanca de horario de verao nao altera duracao em UTC`() {
        // Nova York: 2026-03-08 02:00 -> 03:00 (adianta 1 h).
        val z = ZoneId.of("America/New_York")
        val ini = LocalDateTime.of(2026, 3, 8, 1, 30).atZone(z).toInstant().toEpochMilli()
        val fim = LocalDateTime.of(2026, 3, 8, 3, 30).atZone(z).toInstant().toEpochMilli()
        // Relógio de parede mostra 2 h, mas transcorreu 1 h real.
        assertEquals("01:00:00", Tempo.formatarDuracao(Tempo.medirDuracaoTotal(ini, fim)))
        assertEquals("08/03/2026 03:30:00", Tempo.dataHora(fim, z))
    }

    @Test fun `armazenamento UTC invariavel e formatacao local por fuso`() {
        val instante = LocalDateTime.of(2026, 11, 13, 14, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("2026-11-13T14:00:00Z", Tempo.utcIso(instante))
        assertEquals("11:00:00", Tempo.hora(instante, ZoneId.of("America/Sao_Paulo")))
        assertEquals("10:00:00", Tempo.hora(instante, ZoneId.of("America/Manaus")))
        assertEquals("2026-11-13 11:00:00", Tempo.localParaExportacao(instante, ZoneId.of("America/Sao_Paulo")))
    }

    @Test fun `parse de data e hora digitada`() {
        val z = ZoneId.of("America/Sao_Paulo")
        val ms = Tempo.parseDataHora("13/11/2026 08:12:20", z)
        assertEquals("13/11/2026 08:12:20", Tempo.dataHora(ms, z))
        assertNull(Tempo.parseDataHora("31/02/2026 08:00:00", z))
        assertNull(Tempo.parseDataHora("ontem", z))
    }

    // ---------- Resolução de relógio (monotônico x parede) ----------

    @Test fun `mesmo boot usa relogio monotonico`() {
        val ini = Marcacao(t0, 1_000_000, 7)
        val agora = Marcacao(t0 + 10 * min, 1_000_000 + 10 * min, 7)
        val r = ResolvedorRelogio.resolver(ini, agora)
        assertEquals(t0 + 10 * min, r.instanteMs)
        assertFalse(r.requerRevisao)
    }

    @Test fun `hora do aparelho alterada durante a medicao e detectada`() {
        val ini = Marcacao(t0, 1_000_000, 7)
        // Usuário atrasou o relógio 1 h; passaram 30 min reais.
        val agora = Marcacao(t0 + 30 * min - h, 1_000_000 + 30 * min, 7)
        val r = ResolvedorRelogio.resolver(ini, agora)
        assertEquals(t0 + 30 * min, r.instanteMs)
        assertTrue(r.requerRevisao)
        assertNotNull(r.motivo)
    }

    @Test fun `apos reinicio usa relogio de parede`() {
        val ini = Marcacao(t0, 9_000_000, 7)
        val agora = Marcacao(t0 + 2 * h, 50_000, 8)
        val r = ResolvedorRelogio.resolver(ini, agora)
        assertEquals(t0 + 2 * h, r.instanteMs)
        assertFalse(r.requerRevisao)
        assertNotNull(r.motivo)
    }

    @Test fun `relogio anterior ao inicio apos reinicio nunca gera negativo`() {
        val ini = Marcacao(t0, 9_000_000, 7)
        val agora = Marcacao(t0 - h, 50_000, 8)
        val r = ResolvedorRelogio.resolver(ini, agora)
        assertEquals(t0, r.instanteMs)
        assertTrue(r.requerRevisao)
    }

    @Test fun `sem dados de boot usa relogio de parede`() {
        val r = ResolvedorRelogio.resolver(Marcacao(t0, null, null), Marcacao(t0 + 5 * min, null, null))
        assertEquals(t0 + 5 * min, r.instanteMs)
        assertFalse(r.requerRevisao)
    }

    @Test fun `exibicao do cronometro continua apos marcar rede`() {
        val ini = Marcacao(t0, 1_000, 1)
        assertEquals(20 * min, ResolvedorRelogio.decorridoParaExibicao(ini, Marcacao(t0 + 20 * min, 1_000 + 20 * min, 1)))
        assertEquals(21 * min, ResolvedorRelogio.decorridoParaExibicao(ini, Marcacao(t0 + 21 * min, 1_000 + 21 * min, 1)))
    }
}
