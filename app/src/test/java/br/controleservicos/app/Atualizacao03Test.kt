package br.controleservicos.app

import br.controleservicos.app.Fixtures.MIN
import br.controleservicos.app.Fixtures.T0
import br.controleservicos.app.Fixtures.linha
import br.controleservicos.app.core.address.BuscadorExterno
import br.controleservicos.app.core.address.ClienteHttp
import br.controleservicos.app.core.address.FonteEnderecos
import br.controleservicos.app.core.address.FonteViaCep
import br.controleservicos.app.core.address.ResultadoBusca
import br.controleservicos.app.core.domain.EnderecoExterno
import br.controleservicos.app.core.domain.Enderecos
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.LigacaoForm
import br.controleservicos.app.core.domain.Ligacoes
import br.controleservicos.app.core.export.Celula
import br.controleservicos.app.core.export.ContratoExportacao
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.export.GeradorExportacao
import br.controleservicos.app.core.export.PedidoExportacao
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.share.FormatoRelatorio
import br.controleservicos.app.core.share.GeradorDocx
import br.controleservicos.app.core.share.MontadorRelatorio
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipInputStream

/** Atualização 03: endereços, ligações por imóvel, histórico/exportação filtrada e relatório individual. Dados SINTÉTICOS. */
@OptIn(ExperimentalCoroutinesApi::class)
class Atualizacao03Test {
    private val zona = ZoneId.of("America/Sao_Paulo")

    // ---------------- ViaCEP / endereços ----------------

    private val respostaViaCep = """
        [
          {"cep":"16250-029","logradouro":"Rua São Paulo","bairro":"Centro","localidade":"Clementina","uf":"SP","ibge":"3511904"},
          {"cep":"13615-752","logradouro":"Rua São Caetano","bairro":"Taquari","localidade":"Leme","uf":"SP","ibge":"3526704"},
          {"cep":"13615-752","logradouro":"Rua São Caetano","bairro":"Taquari","localidade":"Leme","uf":"SP","ibge":"3526704"},
          {"cep":"13615-753","logradouro":"Rua São Caetano","bairro":"Jardim Águia","localidade":"Leme","uf":"SP","ibge":"3526704"},
          {"cep":"99999-000","logradouro":"Rua Falsa","bairro":"X","localidade":"Leme","uf":"MG","ibge":"0000000"},
          {"cep":"13610-000","logradouro":"","bairro":"","localidade":"Leme","uf":"SP","ibge":"3526704"}
        ]
    """.trimIndent()

    @Test fun `ViaCEP - rejeita outro municipio e outra UF, deduplica sem perder trecho de CEP`() {
        val r = Enderecos.interpretarViaCep(respostaViaCep)
        assertEquals(2, r.size) // Clementina, MG e item sem logradouro descartados; duplicata removida
        assertTrue(r.all { it.localidade == "Leme" && it.uf == "SP" })
        assertEquals(setOf("13615-752", "13615-753"), r.map { it.cep }.toSet()) // mesma rua em 2 trechos/bairros mantida
        assertEquals("Jardim Águia", r.single { it.cep == "13615-753" }.bairro) // escape Á decodificado
        assertEquals("Rua", r.first().tipo)
        assertTrue(Enderecos.interpretarViaCep("""{"erro": "true"}""").isEmpty())
        assertTrue(Enderecos.interpretarViaCep("[]").isEmpty())
        assertTrue(Enderecos.interpretarViaCep("<html>").isEmpty())
    }

    @Test fun `normalizacao de logradouro trata abreviacoes, acentos e espacos sem fundir nomes diferentes`() {
        assertEquals(Enderecos.normalizarLogradouro("Av. Brasil"), Enderecos.normalizarLogradouro("Avenida  BRASIL "))
        assertEquals(Enderecos.normalizarLogradouro("R. São João"), Enderecos.normalizarLogradouro("rua sao joao"))
        assertFalse(Enderecos.normalizarLogradouro("Rua Brasil") == Enderecos.normalizarLogradouro("Avenida Brasil"))
        assertEquals("Avenida", Enderecos.tipoDe("Av. Brasil"))
        assertNull(Enderecos.tipoDe("Brasil"))
        assertEquals("13615-752", Enderecos.normalizarCep("13615752"))
        assertNull(Enderecos.normalizarCep("1361"))
    }

    private class HttpFalso(var resposta: () -> Pair<Int, String>) : ClienteHttp {
        val urls = mutableListOf<String>()
        override fun get(url: String): Pair<Int, String> { urls += url; return resposta() }
    }

    @Test fun `ViaCEP - minimo 3 caracteres, URL codificada, cache, 429 e falha de rede`() = runTest {
        val http = HttpFalso { 200 to respostaViaCep }
        var agora = 0L
        val fonte = FonteViaCep(http, agora = { agora }, maxPorMinuto = 3, pausaApos429Ms = 60_000)
        assertEquals(ResultadoBusca.Sucesso(emptyList()), fonte.buscarLogradouros("Ru")) // < 3: nenhuma chamada
        assertTrue(http.urls.isEmpty())
        val r1 = fonte.buscarLogradouros("Rua São")
        assertEquals("https://viacep.com.br/ws/SP/Leme/Rua%20S%C3%A3o/json/", http.urls.single())
        assertEquals(2, (r1 as ResultadoBusca.Sucesso).itens.size)
        fonte.buscarLogradouros("rua sao") // mesma consulta normalizada ⇒ cache
        assertEquals(1, http.urls.size)
        http.resposta = { 429 to "" }
        assertEquals(ResultadoBusca.LimiteExcedido, fonte.buscarLogradouros("Avenida"))
        assertEquals(ResultadoBusca.LimiteExcedido, fonte.buscarLogradouros("Travessa")) // pausa após 429, sem nova chamada
        assertEquals(2, http.urls.size)
        agora += 61_000
        http.resposta = { throw UnknownHostException("sem rede") }
        assertEquals(ResultadoBusca.SemConexao, fonte.buscarLogradouros("Alameda"))
        // Limite por minuto.
        http.resposta = { 200 to "[]" }
        fonte.buscarLogradouros("Rua A1"); fonte.buscarLogradouros("Rua A2")
        assertEquals(ResultadoBusca.LimiteExcedido, fonte.buscarLogradouros("Rua A3"))
    }

    @Test fun `busca externa - debounce, minimo e resposta atrasada de Rua A nao sobrescreve Rua B`() = runTest {
        val chamadas = mutableListOf<String>()
        val fonte = FonteEnderecos { termo ->
            chamadas += termo
            if (termo == "Rua A") delay(3_000) // resposta lenta
            ResultadoBusca.Sucesso(listOf(EnderecoExterno(null, termo, "Rua", "Centro", "Leme", "SP")))
        }
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val b = BuscadorExterno(fonte, escopo, debounceMs = 500)
        b.pesquisar("R"); b.pesquisar("Ru")
        escopo.advanceTimeBy(1_000)
        assertTrue(chamadas.isEmpty()) // nenhuma chamada abaixo de 3 caracteres
        b.pesquisar("Rua A")
        escopo.advanceTimeBy(600) // debounce venceu: consulta A iniciada (lenta)
        b.pesquisar("Rua B")
        escopo.advanceUntilIdle()
        assertEquals(listOf("Rua A", "Rua B"), chamadas)
        assertEquals("Rua B", b.estado.value.itens.single().logradouro)
        // Digitação rápida: só a última consulta é feita.
        chamadas.clear()
        b.pesquisar("Rua C"); escopo.advanceTimeBy(100); b.pesquisar("Rua Cx"); escopo.advanceTimeBy(100); b.pesquisar("Rua Cxy")
        escopo.advanceUntilIdle()
        assertEquals(listOf("Rua Cxy"), chamadas)
        escopo.cancel()
    }

    @Test fun `busca externa - falha mostra aviso e nao bloqueia`() = runTest {
        val escopo = TestScope(StandardTestDispatcher(testScheduler))
        val b = BuscadorExterno({ ResultadoBusca.SemConexao }, escopo, debounceMs = 500)
        b.pesquisar("Rua X"); escopo.advanceUntilIdle()
        assertEquals(BuscadorExterno.AVISO_FALHA, b.estado.value.aviso)
        assertTrue(b.estado.value.itens.isEmpty())
        escopo.cancel()
    }

    // ---------------- Ligações por imóvel ----------------

    private fun f(numero: String = "10", lado: LadoRamal? = LadoRamal.LONGO, comp: String = "3,5", rua: String = "", dif: Boolean = false, ident: String = "", legado: Boolean = false) =
        LigacaoForm(chave = numero + rua + ident, numero = numero, lado = lado, comprimento = comp, enderecoDiferente = dif, rua = rua, identificador = ident, legado = legado)

    @Test fun `ligacoes - campos obrigatorios, metragem e valores invalidos`() {
        val (_, e) = Ligacoes.validar(listOf(f(numero = ""), f(lado = null), f(comp = ""), f(comp = "0"), f(comp = "abc"), f(comp = "-2"), f(comp = "3,5m")), "Rua P", "Centro", 100)
        assertEquals("Informe o número do imóvel atendido.", e["0:numero"])
        assertTrue(e.containsKey("1:lado"))
        listOf(2, 3, 4, 5, 6).forEach { assertTrue("comprimento $it", e.containsKey("$it:comprimento")) }
        val (ok, e2) = Ligacoes.validar(listOf(f(numero = "12a", comp = "3,5"), f(numero = "s/n", comp = "2.25", rua = "Rua Q", dif = true)), "Rua P", "Centro", 100)
        assertTrue(e2.isEmpty())
        assertEquals(listOf("12A", "S/N"), ok.map { it.numero })
        assertEquals(listOf(350, 225), ok.map { it.comprimentoCm })
        assertEquals("Rua Q", ok[1].rua)
        assertNull(ok[0].rua) // sem endereço próprio ⇒ não grava rua
    }

    @Test fun `ligacoes - duplicidade no mesmo imovel exige identificadores distintos, mesma numeracao em ruas distintas e aceita`() {
        val (_, dup) = Ligacoes.validar(listOf(f("12"), f("12")), "Rua P", "Centro", 100)
        assertTrue(dup.containsKey("1:duplicada"))
        val (_, ok1) = Ligacoes.validar(listOf(f("12", ident = "A"), f("12", ident = "B")), "Rua P", "Centro", 100)
        assertTrue(ok1.isEmpty())
        val (_, ok2) = Ligacoes.validar(listOf(f("12"), f("12", rua = "Rua Q", dif = true)), "Rua P", "Centro", 100)
        assertTrue(ok2.isEmpty())
        // Endereço próprio igual ao principal ainda conta como o mesmo imóvel.
        val (_, dup2) = Ligacoes.validar(listOf(f("12"), f("12", rua = "R. P", dif = true)), "Rua P", "Centro", 100)
        assertTrue(dup2.containsKey("1:duplicada"))
    }

    @Test fun `ligacoes - legado pode ficar sem numero (pendente), novo nao`() {
        val (ok, e) = Ligacoes.validar(listOf(f(numero = "", legado = true)), "Rua P", "Centro", 100)
        assertTrue(e.isEmpty())
        assertTrue(ok.single().pendenteIdentificacao)
        assertNull(ok.single().numero)
    }

    // ---------------- Filtros ----------------

    @Test fun `filtros combinados por AND - varios bairros, datas, tipos, material e somente concluidos`() {
        val l = listOf(
            linha(id = "a", bairroId = "b1", bairro = "Centro", inicio = T0),
            linha(id = "b", bairroId = "b2", bairro = "Taquari", inicio = T0 + 2 * 86_400_000L),
            linha(id = "c", bairroId = "b3", bairro = "Jardim", inicio = T0),
            linha(id = "d", bairroId = "b1", bairro = "Centro", inicio = T0, status = StatusServico.EM_ANDAMENTO, duracaoMin = null),
            linha(id = "e", bairroId = "b1", bairro = "Centro", inicio = T0, tipo = "AGUA"),
        )
        assertEquals(setOf("a", "b", "d", "e"), FiltroServicos(bairroIds = setOf("b1", "b2")).aplicar(l).map { it.id }.toSet())
        assertEquals(setOf("a", "e"), FiltroServicos(bairroIds = setOf("b1"), somenteFinalizados = true).aplicar(l).map { it.id }.toSet())
        assertEquals(setOf("e"), FiltroServicos(bairroIds = setOf("b1"), tipoIds = setOf("AGUA")).aplicar(l).map { it.id }.toSet())
        val dia0 = java.time.Instant.ofEpochMilli(T0).atZone(zona).toLocalDate()
        assertEquals(setOf("b"), FiltroServicos(dataInicial = dia0.plusDays(1)).aplicar(l, zona = zona).map { it.id }.toSet())
        assertEquals(setOf("a", "c", "d", "e"), FiltroServicos(dataFinal = dia0).aplicar(l, zona = zona).map { it.id }.toSet())
        assertTrue(FiltroServicos(dataInicial = LocalDate.of(2030, 1, 1)).aplicar(l, zona = zona).isEmpty())
        assertTrue(FiltroServicos(materialId = "PVC").aplicar(l).isEmpty())
        // Número como texto (12A) na busca.
        assertEquals(listOf("x"), FiltroServicos(texto = "12a").aplicar(listOf(linha(id = "x").copy(numero = "12A"), linha(id = "y"))).map { it.id })
    }

    // ---------------- Exportação v3 ----------------

    private fun ligacao(s: String, ordem: Int, numero: String?, cm: Int, lado: LadoRamal = LadoRamal.LONGO, rua: String? = null) =
        LigacaoTrocada("$s-$ordem", s, ordem, numero, null, rua, null, null, lado, cm, null, numero == null, false)

    private fun comTroca(l: LinhaServico) = l.copy(ramalAguaTrocado = true)

    @Test fun `indicadores por bairro - n conta servicos, nao ligacoes, media e mediana corretas e ordem por bairro`() {
        val linhas = listOf(
            comTroca(linha(id = "s1", bairroId = "b2", bairro = "Taquari", duracaoMin = 30)),
            linha(id = "s2", bairroId = "b2", bairro = "Taquari", duracaoMin = 60),
            linha(id = "s3", bairroId = "b2", bairro = "Taquari", duracaoMin = 90),
            linha(id = "s4", bairroId = "b1", bairro = "Centro", duracaoMin = 20),
            linha(id = "s5", bairroId = "b1", bairro = "Centro", status = StatusServico.EM_ANDAMENTO, duracaoMin = null),
        )
        val ligs = mapOf("s1" to listOf(ligacao("s1", 1, "1", 100), ligacao("s1", 2, "2", 100), ligacao("s1", 3, "3", 100)))
        val t = ContratoExportacao.indicadoresPorBairro(linhas, ligs, 5)
        val c = ContratoExportacao.COLUNAS_INDICADORES
        assertEquals(listOf("Centro", "Taquari"), t.linhas.map { (it[0] as Celula.Txt).valor })
        val taq = t.linhas[1]
        assertEquals(Celula.Num(3), taq[c.indexOf("n")]) // 3 serviços, não 5 (3 ligações + 2)
        assertEquals(Celula.Num(3600.0), taq[c.indexOf("media_segundos")]) // (30+60+90)/3 = 60 min
        assertEquals(Celula.Num(3600.0), taq[c.indexOf("mediana_segundos")])
        assertEquals(Celula.Num(60.0), taq[c.indexOf("media_minutos")])
        assertEquals(Celula.Num(1), taq[c.indexOf("servicos_com_troca")])
        assertEquals(Celula.Num(3), taq[c.indexOf("ligacoes_trocadas")])
        assertEquals(Celula.Txt("AMOSTRA_PEQUENA"), taq[c.indexOf("amostra")])
        assertEquals(Celula.Num(1), t.linhas[0][c.indexOf("n")]) // em andamento fora das métricas
    }

    @Test fun `exportacao - Servicos 1 linha por servico, Ligacoes N linhas, ZIP com arquivos relacionaveis`() {
        val linhas = listOf(comTroca(linha(id = "s1", duracaoMin = 30)), linha(id = "s2", duracaoMin = 45))
        val ligs = mapOf("s1" to listOf(ligacao("s1", 1, "12A", 350), ligacao("s1", 2, null, 200, LadoRamal.CURTO, rua = "Rua Vizinha")))
        val part = mapOf("s1" to listOf(ParticipanteServico("s1", "f1", "Fulano", null, true, "Fulano", FuncaoEquipe.ENCANADOR, null, null)))
        val p = PedidoExportacao(FormatoExportacao.CSV_ZIP, linhas, emptyMap(), "Bairro: Centro", "1", 5, T0, zona, part, false, ligs)
        val bytes = ByteArrayOutputStream().also { GeradorExportacao.gerar(p, it) }.toByteArray()
        val arquivos = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; arquivos[e.name] = z.readBytes().toString(Charsets.UTF_8).removePrefix("﻿") }
        }
        assertEquals(listOf("servicos.csv", "ligacoes_trocadas.csv", "participantes.csv", "indicadores_por_bairro.csv", "metadados.csv"), arquivos.keys.toList())
        assertEquals(3, arquivos.getValue("servicos.csv").trim().lines().size) // cabeçalho + 2 serviços
        val ligCsv = arquivos.getValue("ligacoes_trocadas.csv").trim().lines()
        assertEquals(3, ligCsv.size) // cabeçalho + 2 ligações
        assertTrue(ligCsv[1].startsWith("s1;1;12A;NAO;Rua Teste;Centro;NAO;"))
        assertTrue(ligCsv[2].startsWith("s1;2;;SIM;Rua Vizinha;Centro;SIM;"))
        assertFalse(arquivos.getValue("participantes.csv").contains("Fulano")) // sem nomes por padrão
        val cab = arquivos.getValue("servicos.csv").lines().first().split(';')
        val s1 = arquivos.getValue("servicos.csv").lines()[1].split(';')
        assertEquals("2", s1[cab.indexOf("quantidade_ligacoes_trocadas")])
        assertEquals("5.5", s1[cab.indexOf("comprimento_total_ligacoes_m")])
        assertEquals("", s1[cab.indexOf("lado_ramal_trocado")]) // mais de uma ligação: coluna única vazia
        assertEquals("30", s1[cab.indexOf("duracao_total_minutos")])
    }

    // ---------------- Relatório individual ----------------

    private fun relatorio(ocultar: Boolean, rede: Long? = null) = MontadorRelatorio.montar(
        comTroca(linha(id = "abc-123-def", redeMin = rede, duracaoMin = 95, observacoes = "Raiz <grande> & \"pedra\"")),
        emptyList(),
        listOf(ParticipanteServico("abc-123-def", "f1", "José da Silva", null, true, "José da Silva", FuncaoEquipe.MAQUINISTA, null, null)),
        listOf(ligacao("abc-123-def", 1, "12A", 350), ligacao("abc-123-def", 2, "14", 225, LadoRamal.CURTO, rua = "Rua Vizinha")),
        ocultar, T0,
    )

    @Test fun `relatorio - Nao registrado sem rede, rede separada da duracao total e ligacoes por imovel`() {
        val r = relatorio(false)
        val t = r.textos()
        assertTrue(t.contains("Relatório de serviço — Leme/SP"))
        assertEquals("Não registrado", r.secoes.single { it.titulo == "Horários e durações" }.pares.toMap()["Tempo até encontrar a rede (início → rede)"])
        assertEquals("01:35:00", r.secoes.single { it.titulo == "Horários e durações" }.pares.toMap()["Duração total (início → fim)"])
        val ligs = r.secoes.single { it.titulo.startsWith("Ligações trocadas") }.tabela!!
        assertEquals(2, ligs.linhas.size)
        assertTrue(ligs.linhas[1][1].contains("nº 14 — Rua Vizinha"))
        assertTrue(t.contains("José da Silva"))
        val r2 = relatorio(true, rede = 20)
        assertFalse(r2.textos().any { it.contains("José") }) // ocultar nomes
        assertEquals("00:20:00", r2.secoes.single { it.titulo == "Horários e durações" }.pares.toMap()["Tempo até encontrar a rede (início → rede)"])
        // T0 = 13/11/2025 02:13 UTC = 12/11/2025 23:13 em Leme (data local no nome).
        assertEquals("servico_LEME_20251112_abc123de.pdf", MontadorRelatorio.nomeArquivo(linha(id = "abc-123-def"), FormatoRelatorio.PDF))
    }

    @Test fun `DOCX real - abre no Apache POI com secoes, tabela de ligacoes e texto escapado`() {
        val bytes = ByteArrayOutputStream().also { GeradorDocx.escrever(relatorio(false), it) }.toByteArray()
        XWPFDocument(ByteArrayInputStream(bytes)).use { doc ->
            val texto = doc.paragraphs.joinToString("\n") { it.text }
            assertTrue(texto.contains("Relatório de serviço — Leme/SP"))
            listOf("Serviço", "Endereço", "Horários e durações", "Equipe", "Rede", "Ligações trocadas (2)", "Condições observadas", "Observações").forEach {
                assertTrue("seção $it", texto.contains(it))
            }
            assertTrue(texto.contains("Raiz <grande> & \"pedra\""))
            val tabLig = doc.tables.single { it.getRow(0).getCell(0).text == "Nº" }
            assertEquals(3, tabLig.numberOfRows)
            assertEquals("12A", tabLig.getRow(1).getCell(1).text.substringAfter("nº ").substringBefore(" "))
        }
    }

    // ---------------- Filtro por composição exata ----------------

    private fun pp(servico: String, func: String, nome: String) =
        ParticipanteServico(servico, func, nome, null, true, nome, FuncaoEquipe.ENCANADOR, null, null)

    @Test fun `filtro por composicao exata - mesma equipe em qualquer ordem, troca de pessoa e outra equipe`() {
        val lista = listOf(linha(id = "s1"), linha(id = "s2"), linha(id = "s3"), linha(id = "s4"))
        val part = mapOf(
            "s1" to listOf(pp("s1", "A", "Ana"), pp("s1", "B", "Beto")),
            "s2" to listOf(pp("s2", "B", "Beto"), pp("s2", "A", "Ana")),
            "s3" to listOf(pp("s3", "A", "Ana"), pp("s3", "E", "Edu")),
        )
        val chaveAB = br.controleservicos.app.core.domain.Composicao.chave(listOf("B", "A"))
        assertEquals(setOf("s1", "s2"), FiltroServicos(chaveComposicao = chaveAB).aplicar(lista, participantes = part).map { it.id }.toSet())
        assertTrue(FiltroServicos(chaveComposicao = chaveAB).ativo)
        val opcoes = br.controleservicos.app.core.domain.Composicao.opcoes(part, comNomes = true)
        assertEquals(listOf("Ana, Beto" to 2, "Ana, Edu" to 1), opcoes.map { it.second to it.third }) // s4 sem equipe não vira opção
        val anon = br.controleservicos.app.core.domain.Composicao.opcoes(part, comNomes = false)
        assertFalse(anon.any { it.second.contains("Ana") })
        assertTrue(anon.all { it.second.startsWith("F-") })
    }
}
