package br.controleservicos.app

import br.controleservicos.app.Fixtures.MIN
import br.controleservicos.app.Fixtures.linha
import br.controleservicos.app.core.domain.Estatisticas
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Ordenacao
import br.controleservicos.app.core.domain.Periodo
import br.controleservicos.app.core.domain.QualidadeAmostra
import br.controleservicos.app.core.model.StatusServico
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class EstatisticasTest {

    @Test fun `exemplo 30 40 50 minutos`() {
        val r = Estatisticas.resumir(listOf(30 * MIN, 40 * MIN, 50 * MIN))
        assertEquals(3, r.n)
        assertEquals(40.0 * MIN, r.mediaMs!!, 0.0)
        assertEquals(40.0 * MIN, r.medianaMs!!, 0.0)
        assertEquals(30 * MIN, r.minimoMs)
        assertEquals(50 * MIN, r.maximoMs)
        assertNull("percentis só com n >= 10", r.p25Ms)
    }

    @Test fun `mediana com numero par de observacoes`() {
        val r = Estatisticas.resumir(listOf(10L, 40L, 20L, 30L))
        assertEquals(25.0, r.medianaMs!!, 0.0)
        assertEquals(25.0, r.mediaMs!!, 0.0)
    }

    @Test fun `percentis tipo 7 deterministas`() {
        val v = (1L..10L).toList() // 1..10
        val r = Estatisticas.resumir(v)
        // h = 9 * p
        assertEquals(3.25, r.p25Ms!!, 1e-9) // h=2.25 -> 3 + .25
        assertEquals(7.75, r.p75Ms!!, 1e-9) // h=6.75
        assertEquals(9.1, r.p90Ms!!, 1e-9) // h=8.1
        assertEquals(5.5, r.medianaMs!!, 1e-9)
        assertEquals(1.0, Estatisticas.percentil(v, 0.0)!!, 0.0)
        assertEquals(10.0, Estatisticas.percentil(v, 1.0)!!, 0.0)
    }

    @Test fun `sem amostra nao divide por zero`() {
        val r = Estatisticas.resumir(emptyList())
        assertEquals(0, r.n)
        assertNull(r.mediaMs)
        assertNull(r.medianaMs)
        assertEquals(QualidadeAmostra.SEM_DADOS, Estatisticas.qualidade(0, 5))
        assertEquals(QualidadeAmostra.AMOSTRA_PEQUENA, Estatisticas.qualidade(4, 5))
        assertEquals(QualidadeAmostra.SUFICIENTE, Estatisticas.qualidade(5, 5))
    }

    @Test fun `exclui cancelados, excluidos, em andamento, revisao e invalidos`() {
        val lista = listOf(
            linha("ok1", duracaoMin = 30),
            linha("ok2", duracaoMin = 50, status = StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS),
            linha("canc", duracaoMin = 999, status = StatusServico.CANCELADO),
            linha("del", duracaoMin = 999, deletado = true),
            linha("and", duracaoMin = null, status = StatusServico.EM_ANDAMENTO),
            linha("rev", duracaoMin = 999, revisao = true),
            linha("inv", duracaoMin = 30).copy(duracaoTotalMs = 1L), // snapshot divergente
        )
        val g = Estatisticas.porTipo(lista, 5).single()
        assertEquals(2, g.total.n)
        assertEquals(40.0 * MIN, g.total.mediaMs!!, 0.0)
        val e = Estatisticas.contarExclusoes(lista)
        assertEquals(1, e.cancelados); assertEquals(1, e.excluidos); assertEquals(1, e.emAndamento)
        assertEquals(1, e.emRevisao); assertEquals(1, e.semDuracaoValida); assertEquals(5, e.total)
    }

    @Test fun `media por bairro calculada dentro de cada tipo`() {
        val lista = listOf(
            linha("a", tipo = "PRP", tipoNome = "PRP", duracaoMin = 60),
            linha("b", tipo = "PRP", tipoNome = "PRP", duracaoMin = 80),
            linha("c", tipo = "ESG", tipoNome = "Esgoto", duracaoMin = 10),
        )
        val porTipo = Estatisticas.porTipo(lista, 5).associateBy { it.chave }
        assertEquals(70.0 * MIN, porTipo.getValue("PRP").total.mediaMs!!, 0.0)
        assertEquals(10.0 * MIN, porTipo.getValue("ESG").total.mediaMs!!, 0.0)
        assertEquals(50.0 * MIN, Estatisticas.agregadoTodosTipos(lista, 5).total.mediaMs!!, 0.0)
    }

    @Test fun `bairros homonimos em municipios diferentes ficam separados`() {
        val lista = listOf(
            linha("a", municipioId = "m1", municipio = "Cidade A", bairroId = "b1", bairro = "Centro", duracaoMin = 10),
            linha("b", municipioId = "m2", municipio = "Cidade B", bairroId = "b2", bairro = "Centro", duracaoMin = 90),
        )
        val g = Estatisticas.porTipoEBairro(lista, 5)
        assertEquals(2, g.size)
        assertEquals(setOf(10.0 * MIN, 90.0 * MIN), g.map { it.total.mediaMs }.toSet())
    }

    @Test fun `tempo ate rede so com marco valido e n proprio`() {
        val lista = listOf(
            linha("a", duracaoMin = 60, redeMin = 20),
            linha("b", duracaoMin = 60, redeMin = 40),
            linha("c", duracaoMin = 60, redeMin = null),
        )
        val g = Estatisticas.porTipo(lista, 5).single()
        assertEquals(3, g.total.n)
        assertEquals(2, g.ateRede.n)
        assertEquals(30.0 * MIN, g.ateRede.mediaMs!!, 0.0)
    }

    @Test fun `filtro por bairro, tipo, texto e periodo`() {
        val z = ZoneId.of("America/Sao_Paulo")
        val agora = LocalDateTime.of(2026, 11, 13, 12, 0).atZone(z).toInstant().toEpochMilli()
        val ontem = agora - 24 * 60 * MIN
        val mesPassado = agora - 40L * 24 * 60 * MIN
        val lista = listOf(
            linha("a", bairroId = "b1", bairro = "São João", inicio = ontem, observacoes = "Solo rochoso"),
            linha("b", bairroId = "b2", bairro = "Centro", inicio = mesPassado, tipo = "ESG"),
            linha("c", bairroId = "b1", bairro = "São João", inicio = agora - MIN, deletado = true),
        )
        assertEquals(listOf("a"), FiltroServicos(bairroId = "b1").aplicar(lista, agora, z).map { it.id })
        assertEquals(listOf("a", "c"), FiltroServicos(bairroId = "b1", incluirExcluidos = true, ordenacao = Ordenacao.ANTIGOS).aplicar(lista, agora, z).map { it.id })
        assertEquals(listOf("b"), FiltroServicos(tipoServicoId = "ESG").aplicar(lista, agora, z).map { it.id })
        assertEquals(listOf("a"), FiltroServicos(texto = "sao joao").aplicar(lista, agora, z).map { it.id })
        assertEquals(listOf("a"), FiltroServicos(texto = "ROCHOSO").aplicar(lista, agora, z).map { it.id })
        assertEquals(listOf("a"), FiltroServicos(periodo = Periodo.ULTIMOS_7).aplicar(lista, agora, z).map { it.id })
        assertEquals(2, FiltroServicos(periodo = Periodo.TODOS).aplicar(lista, agora, z).size)
    }

    @Test fun `ordenacao padrao mais recentes primeiro`() {
        val lista = listOf(linha("velho", inicio = 1000), linha("novo", inicio = 5000))
        assertEquals(listOf("novo", "velho"), FiltroServicos().aplicar(lista).map { it.id })
        assertEquals(listOf("velho", "novo"), FiltroServicos(ordenacao = Ordenacao.MENOR_DURACAO).aplicar(
            listOf(linha("velho", duracaoMin = 5), linha("novo", duracaoMin = 50)),
        ).map { it.id })
    }
}
