package br.controleservicos.app

import br.controleservicos.app.core.domain.Condicoes
import br.controleservicos.app.core.domain.EntradaNumerica
import br.controleservicos.app.core.domain.JsonSimples
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.Texto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextoNumerosTest {

    @Test fun `normaliza acentos espacos e caixa`() {
        assertEquals("sao joao", Texto.normalizarNome("  São   João "))
        assertEquals("sao joao", Texto.normalizarNome("SAO JOAO"))
        assertEquals("jardim aguas claras", Texto.normalizarNome("Jardim Águas Claras"))
    }

    @Test fun `preserva o nome original para exibicao`() {
        assertEquals("São João", Texto.limparNome("  São   João "))
    }

    @Test fun `nao funde nomes distintos`() {
        assertNotEquals(Texto.normalizarNome("Vila Nova I"), Texto.normalizarNome("Vila Nova II"))
        assertNotEquals(Texto.normalizarNome("Jardim 1"), Texto.normalizarNome("Jardim 2"))
        assertNotEquals(Texto.normalizarNome("Santa Rita"), Texto.normalizarNome("Santa Rosa"))
    }

    @Test fun `nome de arquivo seguro`() {
        assertEquals("sao_joao_do_meio", Texto.paraNomeArquivo("São João / do Meio"))
    }

    @Test fun `profundidade com virgula decimal`() {
        assertEquals(EntradaNumerica.Valida(1.25), Numeros.decimal("1,25"))
        assertEquals(EntradaNumerica.Valida(0.8), Numeros.decimal("0,8"))
        assertEquals(EntradaNumerica.Valida(1.5), Numeros.decimal("1.5"))
        assertEquals(EntradaNumerica.Vazia, Numeros.decimal("  "))
    }

    @Test fun `profundidade negativa zero ou invalida`() {
        assertTrue(Numeros.decimal("-1,2") is EntradaNumerica.Invalida)
        assertTrue(Numeros.decimal("0") is EntradaNumerica.Invalida)
        assertTrue(Numeros.decimal("1,2,3") is EntradaNumerica.Invalida)
        assertTrue(Numeros.decimal("abc") is EntradaNumerica.Invalida)
        assertTrue(Numeros.decimal("60", maximo = 50.0) is EntradaNumerica.Invalida)
    }

    @Test fun `inteiros e nao informado`() {
        assertEquals(EntradaNumerica.Valida(0), Numeros.inteiro("0"))
        assertEquals(EntradaNumerica.Vazia, Numeros.inteiro(""))
        assertTrue(Numeros.inteiro("-1") is EntradaNumerica.Invalida)
        assertTrue(Numeros.inteiro("0", minimo = 1) is EntradaNumerica.Invalida)
    }

    @Test fun `formatacao decimal br e invariavel`() {
        assertEquals("1,2", Numeros.formatarDecimalBr(1.20))
        assertEquals("", Numeros.formatarDecimalBr(null))
        assertEquals("1.2", Numeros.formatarDecimalInvariavel(1.20))
        assertEquals("2", Numeros.formatarDecimalInvariavel(2.0))
    }

    @Test fun `nenhuma das condicoes e mutuamente exclusiva`() {
        var s = setOf<String>()
        s = Condicoes.alternar(s, "ESGOTO", "NENHUMA")
        s = Condicoes.alternar(s, "DANIFICADA", "NENHUMA")
        assertEquals(setOf("ESGOTO", "DANIFICADA"), s)
        s = Condicoes.alternar(s, "NENHUMA", "NENHUMA")
        assertEquals(setOf("NENHUMA"), s)
        s = Condicoes.alternar(s, "ESGOTO", "NENHUMA")
        assertEquals(setOf("ESGOTO"), s)
        s = Condicoes.alternar(s, "ESGOTO", "NENHUMA")
        assertEquals(emptySet<String>(), s)
    }

    @Test fun `validacao de condicoes`() {
        assertEquals(1, Condicoes.validar(setOf("NENHUMA", "X"), "NENHUMA", "OUTRAS", "", "MAIS", null).size)
        assertEquals(1, Condicoes.validar(setOf("OUTRAS"), "NENHUMA", "OUTRAS", " ", "MAIS", null).size)
        assertEquals(0, Condicoes.validar(setOf("OUTRAS"), "NENHUMA", "OUTRAS", "raiz de árvore", "MAIS", null).size)
        assertEquals(1, Condicoes.validar(setOf("MAIS"), "NENHUMA", "OUTRAS", "", "MAIS", 1).size)
        assertEquals(0, Condicoes.validar(setOf("MAIS"), "NENHUMA", "OUTRAS", "", "MAIS", 2).size)
        assertEquals(0, Condicoes.validar(setOf("MAIS"), "NENHUMA", "OUTRAS", "", "MAIS", null).size)
    }

    @Test fun `json de auditoria escapa caracteres`() {
        assertEquals("""{"a":"x\"y\\z\n","b":null,"c":[1,"q"]}""", JsonSimples.objeto(mapOf("a" to "x\"y\\z\n", "b" to null, "c" to listOf(1, "q"))))
    }
}
