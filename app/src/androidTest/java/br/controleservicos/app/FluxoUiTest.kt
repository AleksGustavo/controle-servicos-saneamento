package br.controleservicos.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasText
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.activity.compose.setContent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.repository.DadosFuncionario
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.ControleTheme
import br.controleservicos.app.core.model.CodigosCatalogo
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.model.TipoEvento
import br.controleservicos.app.core.navigation.AppNavHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FluxoUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer

    @Before fun setup() {
        container = containerDeTeste(ctx)
        // Em aparelho físico: manter a tela da Activity de teste acesa durante o teste (só no teste).
        compose.activityRule.scenario.onActivity {
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.setShowWhenLocked(true)
            it.setTurnScreenOn(true)
        }
    }
    /** Desmonta a UI (cancela coletas do Room) antes de fechar o banco em memória. */
    @After fun fim() {
        compose.runOnUiThread { compose.activity.setContent { } }
        compose.waitForIdle()
        container.database.close()
    }

    private fun iniciarApp() = compose.setContent { ControleTheme { AppNavHost(container) } }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun esperarTag(t: String) = compose.waitUntilAtLeastOneExists(hasTestTag(t), 10_000)

    /** Preenche o novo serviço. Com [participante], cadastra-o pela própria busca (fluxo real). */
    private fun preencherNovoServico(tipo: String = CodigosCatalogo.TIPO_PRP_NAO_VISIVEL, participante: String? = "Fulano Teste") {
        tag("botao_novo_servico").performClick()
        esperarTag("tipo_$tipo")
        tag("tipo_$tipo").performClick()
        compose.waitUntil(5_000) { runCatching { tag("subtitulo_local").assertTextContains("Leme · SP") }.isSuccess } // município fixo
        assertEquals(0, compose.onAllNodesWithTag("campo_municipio").fetchSemanticsNodes().size)
        escolherEndereco("Jardim Exemplo", "Rua Exemplo")
        tag("campo_numero").performTextInput("100")
        if (participante != null) {
            abrirAte("botao_selecionar_funcionarios", "campo_busca_funcionario")
            tag("campo_busca_funcionario").performTextInput(participante)
            tag("cadastrar_novo_funcionario").performClick()
            esperarTag("campo_nome_funcionario")
            tag("habitual_ENCANADOR").performScrollTo().performClick()
            tag("salvar_funcionario").performClick()
            compose.waitUntil(5_000) { runBlocking { container.repositorio.observarFuncionarios().first() }.isNotEmpty() }
            tag("concluir_selecao").performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag("resumo_funcoes"), 5_000)
            tag("botao_iniciar").performScrollTo().performClick()
        } else {
            abrirAte("botao_iniciar", "confirmar_sem_equipe")
            tag("confirmar_sem_equipe").performClick()
        }
        esperarTag("cronometro_total")
    }

    private fun linhas() = runBlocking { container.repositorio.observarLinhas().first() }

    /** Toca [botao] (repetindo se o teclado deslocou a tela) até [alvo] aparecer. */
    private fun abrirAte(botao: String, alvo: String) {
        repeat(3) {
            tag(botao).performScrollTo().performClick()
            if (runCatching { compose.waitUntilAtLeastOneExists(hasTestTag(alvo), 4_000) }.isSuccess) return
        }
        esperarTag(alvo)
    }

    /** Bairro e rua pelos seletores: cadastra manualmente quando não existem (fluxo offline). */
    private fun escolherEndereco(bairro: String, rua: String) {
        tag("campo_bairro").performScrollTo().performClick()
        esperarTag("campo_busca_bairro")
        tag("campo_busca_bairro").performTextInput(bairro)
        val existente = runBlocking { container.repositorio.observarBairrosLeme().first() }.firstOrNull { it.nomeOriginal == bairro }
        if (existente != null) {
            esperarTag("opcao_bairro_${existente.id}"); tag("opcao_bairro_${existente.id}").performClick()
        } else {
            tag("adicionar_bairro").performClick(); tag("confirmar_novo_bairro").performClick()
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("campo_busca_bairro").fetchSemanticsNodes().isEmpty() }
        tag("campo_logradouro").performScrollTo().performClick()
        esperarTag("campo_busca_rua")
        tag("campo_busca_rua").performTextInput(rua)
        esperarTag("adicionar_rua")
        tag("adicionar_rua").performClick(); tag("confirmar_nova_rua").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("campo_busca_rua").fetchSemanticsNodes().isEmpty() }
    }

    /**
     * Em aparelho físico o teclado anima e redimensiona a tela (imePadding) entre a rolagem e o toque;
     * repete o toque até o chip ficar selecionado (evita intermitência do teste, não do app).
     */
    private fun clicarAteSelecionar(t: String) {
        repeat(3) {
            tag(t).performScrollTo().performClick()
            compose.waitForIdle()
            if (runCatching { tag(t).assertIsSelected() }.isSuccess) return
        }
        tag(t).assertIsSelected()
    }

    @Test fun fluxoCompletoComRedeEncontrada() {
        iniciarApp()
        preencherNovoServico()
        // Cronômetro em andamento.
        val id = linhas().single().id
        assertEquals(StatusServico.EM_ANDAMENTO, linhas().single().status)
        // Duplo toque rápido em "Rede encontrada".
        tag("botao_rede").performClick()
        runCatching { tag("botao_rede").performClick() }
        esperarTag("rede_registrada")
        tag("rede_registrada").assertIsDisplayed()
        val eventos = runBlocking { container.database.servicoDao().eventos(id) }
        assertEquals(1, eventos.count { it.tipoEvento == TipoEvento.REDE_ENCONTRADA })
        // Cronômetro continua em andamento após marcar a rede.
        assertEquals(StatusServico.EM_ANDAMENTO, linhas().single().status)

        // Duplo toque em finalizar abre um único diálogo; confirmar grava o fim.
        tag("botao_finalizar").performScrollTo().performClick()
        tag("botao_finalizar").assertIsNotEnabled()
        tag("confirmar_finalizar").performClick()
        esperarTag("resumo_tempos")
        val encerrado = linhas().single()
        assertEquals(StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS, encerrado.status)
        assertNotNull(encerrado.fimUtcEpochMs)
        val duracaoCongelada = encerrado.duracaoTotalMs

        tag("campo_diametro").performScrollTo().performTextInput("100")
        clicarAteSelecionar("material_PVC")
        tag("campo_profundidade").performScrollTo().performTextInput("1,20")
        tag("cond_${CodigosCatalogo.COND_ESGOTO_ACIMA_REDE}").performScrollTo().performClick()
        tag("campo_observacoes").performScrollTo().performTextInput("Teste instrumentado")
        Thread.sleep(1200) // tempo de formulário não deve alterar a duração
        tag("botao_salvar_registro").performScrollTo().performClick()
        esperarTag("texto_servico_salvo")
        tag("salvo_compartilhar").assertIsDisplayed() // compartilhamento opcional após salvar

        val s = linhas().single()
        assertEquals(StatusServico.CONCLUIDO, s.status)
        assertEquals("Leme", s.municipioNome)
        assertEquals(1, runBlocking { container.repositorio.participantesDe(id) }.size)
        assertEquals(duracaoCongelada, s.duracaoTotalMs)
        assertEquals(100, s.diametroMm)
        assertEquals(1.2, s.profundidadeMetros!!, 1e-9)
        assertEquals("PVC", s.materialNome)
        assertEquals(SituacaoRede.MARCADA, s.situacaoRede)
        assertEquals(1, runBlocking { container.repositorio.condicoesDe(id) }.size)
    }

    @Test fun fluxoSemRedeEncontrada() {
        iniciarApp()
        preencherNovoServico(CodigosCatalogo.TIPO_LIGACAO_ESGOTO, participante = null)
        tag("botao_finalizar").performScrollTo().performClick()
        tag("confirmar_finalizar").performClick()
        esperarTag("resumo_tempos")
        tag("equipe_nao_registrada").assertIsDisplayed()
        compose.onNodeWithText("Não aplicável").performScrollTo().performClick()
        tag("botao_salvar_registro").performScrollTo().performClick()
        // Sem participantes: pede confirmação explícita para salvar sem equipe.
        esperarTag("salvar_sem_equipe")
        tag("salvar_sem_equipe").performClick()
        esperarTag("texto_servico_salvo")
        val s = linhas().single()
        assertEquals(StatusServico.CONCLUIDO, s.status)
        assertNull(s.redeEncontradaUtcEpochMs)
        assertEquals(SituacaoRede.NAO_APLICAVEL, s.situacaoRede)
    }

    @Test fun validacaoImpedeIniciarSemCampos() {
        iniciarApp()
        tag("botao_novo_servico").performClick()
        esperarTag("botao_iniciar")
        tag("botao_iniciar").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Escolha o tipo de serviço."), 15_000)
        assertEquals(0, linhas().size)
    }

    @Test fun servicoEmAndamentoRecuperadoAoAbrirApp() {
        val id = runBlocking { container.repositorio.iniciarServico(novo(), container.relogio.agora()) }
        iniciarApp()
        esperarTag("cartao_ativo")
        compose.onNodeWithText("Continuar").performClick()
        esperarTag("cronometro_total")
        // Tentar novo serviço com um ativo oferece continuar.
        assertEquals(id, linhas().single().id)
    }

    @Test fun pendenteDeComplementosApareceNaInicio() {
        runBlocking {
            val id = container.repositorio.iniciarServico(novo(), container.relogio.agora())
            container.repositorio.finalizar(id, container.relogio.agora())
        }
        iniciarApp()
        compose.waitUntilAtLeastOneExists(hasText("Pendentes de complementos (1)"), 15_000)
        compose.onNodeWithText("Complementar").performClick()
        esperarTag("resumo_tempos")
    }

    @Test fun historicoFiltraPorBairroEDetalhe() {
        runBlocking {
            listOf("Centro", "São João", "Centro").forEach { b ->
                val id = container.repositorio.iniciarServico(novo(bairro = b), container.relogio.agora())
                container.repositorio.finalizar(id, container.relogio.agora())
            }
        }
        iniciarApp()
        compose.onNodeWithText("Histórico").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 serviço(s)"), 5_000)
        val alvo = linhas().single { it.bairroNome == "São João" }
        tag("botao_filtros").performClick()
        esperarTag("filtro_bairros")
        tag("filtro_bairros").performClick()
        tag("campo_busca_bairros_filtro").performTextInput("sao joao") // sem acento
        tag("filtro_bairro_${alvo.bairroId}").performClick()
        tag("concluir_bairros").performClick()
        tag("aplicar_filtros").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 serviço(s)"), 5_000)
        esperarTag("chips_filtros_ativos")
        // Limpar filtros volta aos 3; rotação mantém o filtro (estado no container).
        tag("limpar_filtros").performClick()
        compose.waitUntilAtLeastOneExists(hasText("3 serviço(s)"), 5_000)
        tag("campo_busca").performTextInput("joao")
        compose.waitUntilAtLeastOneExists(hasText("1 serviço(s)"), 5_000)
        // Sair e voltar ao Histórico mantém busca/filtros (estado fora da tela).
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Histórico").performClick()
        compose.waitUntilAtLeastOneExists(hasText("1 serviço(s)"), 10_000)
        val id = alvo.id
        tag("item_$id").performClick()
        esperarTag("status_detalhe")
    }

    @Test fun estatisticasEExportacaoContamRegistros() {
        runBlocking {
            repeat(2) {
                val id = container.repositorio.iniciarServico(novo(), container.relogio.agora())
                container.repositorio.finalizar(id, container.relogio.agora())
            }
        }
        iniciarApp()
        compose.onNodeWithText("Estatísticas").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Amostra pequena", substring = true), 5_000)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Exportar").performClick()
        esperarTag("contagem_exportacao")
        compose.waitUntil(15_000) { runCatching { tag("contagem_exportacao").assertTextContains("2 serviço(s) encontrado(s)") }.isSuccess }
        tag("exportar_xlsx").assertIsEnabled()
    }

    @Test fun limpaCampoNumeroAoMarcarSN() {
        iniciarApp()
        tag("botao_novo_servico").performClick()
        esperarTag("campo_numero")
        tag("campo_numero").performTextInput("12")
        tag("check_sn").performClick()
        tag("campo_numero").assertTextContains("S/N")
    }

    // ---------------- Aditivo: equipes, ramal, cronômetro ----------------

    private fun finalizarAteComplementos() {
        tag("botao_finalizar").performScrollTo().performClick()
        tag("confirmar_finalizar").performClick()
        esperarTag("resumo_tempos")
    }

    /** EQP-003 / UX-001: 2 encanadores + maquinista + operador via pesquisa, com papéis corretos. */
    @Test fun eqp003SelecaoDeQuatroParticipantesPorPesquisa() {
        val ids = runBlocking {
            listOf(
                "Antônio Encanador" to FuncaoEquipe.ENCANADOR, "Bruno Encanador" to FuncaoEquipe.ENCANADOR,
                "Caio Maquinista" to FuncaoEquipe.MAQUINISTA, "Davi Basculante" to null,
            ).map { (n, f) -> container.repositorio.adicionarFuncionario(DadosFuncionario(n, funcaoHabitual = f)) }
        }
        iniciarApp()
        tag("botao_novo_servico").performClick()
        esperarTag("tipo_${CodigosCatalogo.TIPO_LIGACAO_AGUA}")
        tag("tipo_${CodigosCatalogo.TIPO_LIGACAO_AGUA}").performClick()
        escolherEndereco("Centro", "Rua Um")
        tag("campo_numero").performTextInput("1")
        abrirAte("botao_selecionar_funcionarios", "campo_busca_funcionario")
        listOf("antonio", "BRUNO", "caio", "davi").forEachIndexed { i, busca ->
            tag("campo_busca_funcionario").performTextClearance()
            tag("campo_busca_funcionario").performTextInput(busca)
            esperarTag("resultado_func_${ids[i]}")
            tag("resultado_func_${ids[i]}").performClick()
        }
        tag("concluir_selecao").performClick()
        // Davi não tem função habitual: iniciar é bloqueado até escolher a função no serviço.
        tag("botao_iniciar").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Escolha a função de cada participante neste serviço."), 5_000)
        tag("funcao_${ids[3]}_OPERADOR_BASCULANTE").performScrollTo().performClick()
        tag("resumo_funcoes").assertTextContains("2 Encanador · 1 Maquinista · 1 Op. basculante")
        tag("botao_iniciar").performScrollTo().performClick()
        esperarTag("cronometro_total")
        val id = linhas().single().id
        val ps = runBlocking { container.repositorio.participantesDe(id) }.associate { it.funcionarioId to it.funcaoNoServico }
        assertEquals(
            mapOf(ids[0] to FuncaoEquipe.ENCANADOR, ids[1] to FuncaoEquipe.ENCANADOR, ids[2] to FuncaoEquipe.MAQUINISTA, ids[3] to FuncaoEquipe.OPERADOR_BASCULANTE),
            ps,
        )
        compose.onNodeWithTag("resumo_equipe_cronometro").assertTextContains("4 participante(s)", substring = true)
    }

    /** Ligações trocadas por imóvel pela interface (RAM-001/002/003/004 + múltiplas ligações). */
    @Test fun ligacoesTrocadasNaInterface() {
        iniciarApp()
        preencherNovoServico()
        finalizarAteComplementos()
        // Não (padrão): nada de ramal aparece.
        tag("ramal_nao").performScrollTo().assertIsSelected()
        assertEquals(0, compose.onAllNodesWithTag("ligacao_0").fetchSemanticsNodes().size)
        // Sim: um cartão; salvar vazio é bloqueado (número, lado e metros obrigatórios).
        tag("ramal_sim").performScrollTo().performClick()
        esperarTag("ligacao_0")
        tag("botao_salvar_registro").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Informe o número do imóvel atendido."), 5_000)
        assertEquals(StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS, linhas().single().status)
        // Sim → Não com dados pede confirmação; confirmado, limpa; Sim de novo começa vazio.
        tag("ligacao_numero_0").performScrollTo().performTextInput("99")
        tag("ramal_nao").performScrollTo().performClick()
        tag("confirmar_descartar_ligacoes").performClick()
        assertEquals(0, compose.onAllNodesWithTag("ligacao_0").fetchSemanticsNodes().size)
        tag("ramal_sim").performScrollTo().performClick()
        esperarTag("ligacao_numero_0")
        assertEquals(0, compose.onAllNodes(hasText("99")).fetchSemanticsNodes().size)
        // Ligação 1: sugestão do nº do endereço (editável), lado longo, 3,5 m.
        tag("ligacao_usar_numero_0").performScrollTo().performClick()
        tag("ligacao_lado_0_LONGO").performScrollTo().performClick()
        tag("ligacao_comprimento_0").performScrollTo().performTextInput("3,5")
        compose.waitUntilAtLeastOneExists(hasText("Será salvo: 3,5 m"), 5_000)
        // Ligação 2: mesmo número sem identificador ⇒ duplicada; com identificador ⇒ aceita.
        tag("adicionar_ligacao").performScrollTo().performClick()
        tag("ligacao_numero_1").performScrollTo().performTextInput("100")
        tag("ligacao_lado_1_CURTO").performScrollTo().performClick()
        tag("ligacao_comprimento_1").performScrollTo().performTextInput("2")
        tag("botao_salvar_registro").performScrollTo().performClick()
        esperarTag("erro_duplicada_1")
        tag("ligacao_identificador_0").performScrollTo().performTextInput("hidrômetro A")
        tag("ligacao_identificador_1").performScrollTo().performTextInput("hidrômetro B")
        // Ligação 3: imóvel em outra rua, nº 12A.
        tag("adicionar_ligacao").performScrollTo().performClick()
        tag("ligacao_numero_2").performScrollTo().performTextInput("12a")
        tag("ligacao_lado_2_LONGO").performScrollTo().performClick()
        tag("ligacao_comprimento_2").performScrollTo().performTextInput("4,25")
        tag("ligacao_endereco_diferente_2").performScrollTo().performClick()
        tag("ligacao_rua_2").performScrollTo().performTextInput("Rua Vizinha")
        tag("resumo_ligacoes").performScrollTo().assertTextContains("3 ligações trocadas: nº 100, 100, 12a")
        tag("botao_salvar_registro").performScrollTo().performClick()
        esperarTag("texto_servico_salvo")
        val id = linhas().single().id
        val ls = runBlocking { container.repositorio.ligacoesDe(id) }
        assertEquals(listOf("100", "100", "12A"), ls.map { it.numeroTexto })
        assertEquals(listOf(350, 200, 425), ls.map { it.comprimentoCm })
        assertEquals(listOf(LadoRamal.LONGO, LadoRamal.CURTO, LadoRamal.LONGO), ls.map { it.lado })
        assertEquals("Rua Vizinha", ls[2].logradouroTexto)
        assertEquals(listOf("hidrômetro A", "hidrômetro B", null), ls.map { it.identificadorLigacao })
    }

    private fun SemanticsNodeInteraction.assertIsNotSelectedCompat() =
        this.assert(androidx.compose.ui.test.isNotSelected())

    /** EQP-001/002/005 pela tela Funcionários: cadastro, aviso de homônimo e inativação. */
    @Test fun telaFuncionariosCadastroHomonimoEInativacao() {
        iniciarApp()
        tag("menu_funcionarios").performScrollTo().performClick()
        esperarTag("botao_adicionar_funcionario")
        tag("botao_adicionar_funcionario").performClick()
        tag("campo_nome_funcionario").performTextInput("José Pereira")
        tag("salvar_funcionario").performClick()
        compose.waitUntil(5_000) { runBlocking { container.repositorio.observarFuncionarios().first() }.size == 1 }
        // Homônimo: aviso e confirmação, sem impedir.
        tag("botao_adicionar_funcionario").performClick()
        tag("campo_nome_funcionario").performTextInput("jose pereira")
        tag("salvar_funcionario").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Já existe", substring = true), 5_000)
        tag("campo_apelido_funcionario").performTextInput("Zé da Vila")
        tag("salvar_funcionario").performClick()
        compose.waitUntil(5_000) { runBlocking { container.repositorio.observarFuncionarios().first() }.size == 2 }
        // Busca sem acento encontra os dois.
        tag("campo_busca_cadastro").performTextInput("JOSE")
        val todos = runBlocking { container.repositorio.observarFuncionarios().first() }
        todos.forEach { tag("func_${it.id}").assertIsDisplayed() }
        // Inativar.
        tag("ativo_${todos.first().id}").performClick()
        compose.waitUntil(5_000) { runBlocking { container.repositorio.observarFuncionarios().first() }.count { !it.ativo } == 1 }
    }

    /** TEM-005: o fim é o instante do toque de confirmação, e o formulário não altera a duração. */
    @Test fun tem005FimFixadoNaConfirmacao() {
        val relogio = RelogioFalso()
        container.database.close()
        container = AppContainer(ctx, br.controleservicos.app.core.database.AppDatabase.emMemoria(ctx), relogio, PreferenciasMemoria())
        iniciarApp()
        preencherNovoServico(participante = null)
        relogio.avancar(10 * 60_000L)
        tag("botao_finalizar").performScrollTo().performClick()
        relogio.avancar(7_000L) // hesitou 7 s no diálogo antes de confirmar
        tag("confirmar_finalizar").performClick()
        esperarTag("resumo_tempos")
        assertEquals(10 * 60_000L + 7_000L, linhas().single().duracaoTotalMs)
        relogio.avancar(5 * 60_000L) // preenchendo observações
        compose.onNodeWithText("Não aplicável").performScrollTo().performClick()
        tag("botao_salvar_registro").performScrollTo().performClick()
        tag("salvar_sem_equipe").performClick()
        esperarTag("texto_servico_salvo")
        assertEquals(10 * 60_000L + 7_000L, linhas().single().duracaoTotalMs)
    }

    /** TEM-003 + correção de participantes durante o serviço, com trilha de auditoria. */
    @Test fun corrigirParticipantesDuranteServico() {
        val f = runBlocking { container.repositorio.adicionarFuncionario(DadosFuncionario("Chegou Depois", funcaoHabitual = FuncaoEquipe.MAQUINISTA)) }
        iniciarApp()
        preencherNovoServico(participante = null)
        tag("resumo_equipe_cronometro").performScrollTo().performClick()
        esperarTag("botao_selecionar_funcionarios")
        tag("botao_selecionar_funcionarios").performScrollTo().performClick()
        esperarTag("resultado_func_$f")
        tag("resultado_func_$f").performClick()
        tag("concluir_selecao").performClick()
        tag("salvar_participantes").performScrollTo().performClick()
        esperarTag("cronometro_total")
        val id = linhas().single().id
        assertEquals(listOf(f), runBlocking { container.repositorio.participantesDe(id) }.map { it.funcionarioId })
        val ev = runBlocking { container.database.servicoDao().eventos(id) }
        assertEquals(1, ev.count { it.tipoEvento == TipoEvento.PARTICIPANTES })
        assertEquals(StatusServico.EM_ANDAMENTO, linhas().single().status)
    }
}
