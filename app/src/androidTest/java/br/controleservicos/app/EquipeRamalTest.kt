package br.controleservicos.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.domain.EstatisticasEquipe
import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.model.TipoEvento
import br.controleservicos.app.core.repository.Complementos
import br.controleservicos.app.core.repository.DadosFuncionario
import br.controleservicos.app.core.repository.OperacaoInvalidaException
import br.controleservicos.app.core.repository.ParticipanteNovo
import br.controleservicos.app.core.repository.ServicoRepository
import br.controleservicos.app.feature.team.filtrarFuncionarios
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Funcionários, participações, ramal trocado e base de tempo (Room real, em memória). Dados SINTÉTICOS. */
@RunWith(AndroidJUnit4::class)
class EquipeRamalTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var relogio: RelogioFalso
    private lateinit var repo: ServicoRepository
    private val MIN = 60_000L

    @Before fun setup() {
        db = AppDatabase.emMemoria(ctx)
        relogio = RelogioFalso()
        repo = ServicoRepository(db, relogio)
    }

    @After fun fim() = db.close()

    private suspend fun func(nome: String, funcao: FuncaoEquipe? = null, apelido: String? = null) =
        repo.adicionarFuncionario(DadosFuncionario(nome, apelidoOuCodigo = apelido, funcaoHabitual = funcao))

    private suspend fun servicoConcluido(participantes: List<ParticipanteNovo>, minutos: Long, ligacoes: List<br.controleservicos.app.core.domain.LigacaoValidada>? = null): String {
        val id = repo.iniciarServico(novo(participantes = participantes), relogio.agora())
        relogio.avancar(minutos * MIN)
        repo.finalizar(id, relogio.agora())
        repo.salvarComplementos(
            id,
            Complementos(SituacaoRede.NAO_APLICAVEL, null, null, null, null, emptyMap(), null, ligacoes != null, ligacoes.orEmpty(), permitirSemParticipantes = participantes.isEmpty()),
        )
        return id
    }

    @Test fun eqp001CadastroEBuscaParcialSemCaixaEAcentos() = runBlocking {
        val id = func("João Antônio da Silva", FuncaoEquipe.ENCANADOR)
        func("Maria Souza")
        val todos = repo.observarFuncionarios().first()
        assertEquals(listOf(id), filtrarFuncionarios(todos, "joao").map { it.id })
        assertEquals(listOf(id), filtrarFuncionarios(todos, "ANTONIO sil").map { it.id })
        assertTrue(filtrarFuncionarios(todos, "pedro").isEmpty())
        assertEquals(2, filtrarFuncionarios(todos, "").size)
        // Identificador estável gerado (UUID), não derivado do nome.
        assertTrue(id.matches(Regex("[0-9a-f-]{36}")))
    }

    @Test fun eqp002HomonimosPermitidosEEdicaoNaoMudaIdentidadeHistorica() = runBlocking {
        val a = func("Carlos", FuncaoEquipe.ENCANADOR)
        val b = func("carlos ", FuncaoEquipe.MAQUINISTA, apelido = "Carlão")
        assertNotEquals(a, b)
        assertEquals(2, repo.possiveisDuplicados("CARLOS").size) // aviso de duplicidade disponível
        val s = servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 30)
        repo.atualizarFuncionario(a, DadosFuncionario("Carlos Alberto", funcaoHabitual = FuncaoEquipe.ENCANADOR))
        val p = repo.participantesDe(s).single()
        assertEquals(a, p.funcionarioId)
        assertEquals("Carlos Alberto", p.nomeAtual) // histórico consultável após renomear
        assertEquals("Carlos", p.nomeSnapshot) // fotografia preservada
        // A composição continua a mesma série estatística após renomear.
        servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 50)
        val linhas = repo.observarLinhas().first()
        val part = repo.observarParticipacoes().first()
        assertEquals(2, EstatisticasEquipe.porComposicao(linhas, part, 5).single().total.n)
    }

    @Test fun eqp003DoisEncanadoresMaquinistaEBasculanteComPapeisCorretos() = runBlocking {
        val e1 = func("Encanador Um", FuncaoEquipe.ENCANADOR)
        val e2 = func("Encanador Dois", FuncaoEquipe.ENCANADOR)
        val m = func("Maquinista Um", FuncaoEquipe.MAQUINISTA)
        val o = func("Operador Um", FuncaoEquipe.OPERADOR_BASCULANTE)
        val id = repo.iniciarServico(
            novo(
                participantes = listOf(
                    ParticipanteNovo(e1, FuncaoEquipe.ENCANADOR), ParticipanteNovo(e2, FuncaoEquipe.ENCANADOR),
                    ParticipanteNovo(m, FuncaoEquipe.MAQUINISTA), ParticipanteNovo(o, FuncaoEquipe.OPERADOR_BASCULANTE),
                ),
            ),
            relogio.agora(),
        )
        val ps = repo.participantesDe(id).associateBy { it.funcionarioId }
        assertEquals(4, ps.size)
        assertEquals(FuncaoEquipe.ENCANADOR, ps.getValue(e1).funcaoNoServico)
        assertEquals(FuncaoEquipe.ENCANADOR, ps.getValue(e2).funcaoNoServico)
        assertEquals(FuncaoEquipe.MAQUINISTA, ps.getValue(m).funcaoNoServico)
        assertEquals(FuncaoEquipe.OPERADOR_BASCULANTE, ps.getValue(o).funcaoNoServico)
        assertEquals("Encanador", ps.getValue(e1).funcaoTexto)
        assertEquals("Encanador Um", ps.getValue(e1).nomeSnapshot)
    }

    @Test fun eqp004ComposicoesLivresEPessoaRepetidaProibida() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        val b = func("B", FuncaoEquipe.MAQUINISTA)
        // Função diferente da habitual e composição 1+1 (não 2+1+1) são aceitas.
        val id = repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(a, FuncaoEquipe.MAQUINISTA), ParticipanteNovo(b, FuncaoEquipe.OUTRA, "Apoio"))), relogio.agora())
        assertEquals(2, repo.participantesDe(id).size)
        assertEquals("Outra: Apoio", repo.participantesDe(id).single { it.funcionarioId == b }.funcaoTexto)
        repo.cancelar(id, "teste")
        try {
            repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR), ParticipanteNovo(a, FuncaoEquipe.MAQUINISTA))), relogio.agora())
            fail("pessoa repetida deveria ser rejeitada")
        } catch (_: OperacaoInvalidaException) {}
        assertNull(repo.observarAtivo().first()) // nada foi gravado pela tentativa inválida
        // A PK (servicoId, funcionarioId) também impede no banco.
        try {
            db.funcionarioDao().inserirParticipacoes(listOf(db.funcionarioDao().participacoesEntidade(id).first()))
            fail("PK deveria impedir duplicidade")
        } catch (_: SQLiteConstraintException) {}
    }

    @Test fun eqp005InativarNaoRemoveReferenciasAntigas() = runBlocking {
        val a = func("Aposentado", FuncaoEquipe.ENCANADOR)
        val s = servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 20)
        repo.definirFuncionarioAtivo(a, false)
        val p = repo.participantesDe(s).single()
        assertEquals(a, p.funcionarioId)
        assertFalse(p.funcionarioAtivo)
        // Inativo não pode ser selecionado para novo serviço.
        try {
            repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR))), relogio.agora()); fail()
        } catch (_: OperacaoInvalidaException) {}
        // Exclusão física de funcionário referenciado é bloqueada pela FK (RESTRICT).
        try {
            db.openHelper.writableDatabase.execSQL("DELETE FROM funcionario WHERE id = ?", arrayOf(a)); fail()
        } catch (_: SQLiteConstraintException) {}
    }

    @Test fun participantesAjustadosDuranteServicoSaoAuditados() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        val b = func("B", FuncaoEquipe.MAQUINISTA)
        val id = repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR))), relogio.agora())
        relogio.avancar(5 * MIN)
        repo.atualizarParticipantes(id, listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR), ParticipanteNovo(b, FuncaoEquipe.MAQUINISTA)), "chegou depois")
        val ev = db.servicoDao().eventos(id).single { it.tipoEvento == TipoEvento.PARTICIPANTES }
        assertTrue(ev.dadosAnteriores!!.contains(a) && !ev.dadosAnteriores!!.contains(b))
        assertTrue(ev.dadosNovos!!.contains(b))
        assertEquals("chegou depois", ev.motivo)
        // Sem mudança real ⇒ nenhum evento extra.
        repo.atualizarParticipantes(id, listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR), ParticipanteNovo(b, FuncaoEquipe.MAQUINISTA)))
        assertEquals(1, db.servicoDao().eventos(id).count { it.tipoEvento == TipoEvento.PARTICIPANTES })
        // O cronômetro não foi afetado.
        assertEquals(StatusServico.EM_ANDAMENTO, repo.observarLinha(id).first()!!.status)
    }

    @Test fun salvarExigeParticipanteSalvoExcecaoExplicita() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.avancar(10 * MIN); repo.finalizar(id, relogio.agora())
        try {
            repo.salvarComplementos(id, complementosBasicos(semEquipe = false)); fail()
        } catch (_: OperacaoInvalidaException) {}
        assertEquals(StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS, repo.observarLinha(id).first()!!.status)
        repo.salvarComplementos(id, complementosBasicos(semEquipe = true))
        assertEquals(StatusServico.CONCLUIDO, repo.observarLinha(id).first()!!.status)
        assertTrue(repo.participantesDe(id).isEmpty()) // nenhum integrante inventado
        val linhas = repo.observarLinhas().first()
        assertEquals(1, EstatisticasEquipe.semEquipe(linhas, repo.observarParticipacoes().first()))
    }

    @Test fun reutilizarUltimosParticipantesSoQuandoPedido() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 10)
        val id2 = repo.iniciarServico(novo(), relogio.agora())
        assertTrue(repo.participantesDe(id2).isEmpty()) // nada automático
        assertEquals(listOf(a), repo.participantesDoUltimoServico(exceto = id2).map { it.funcionarioId })
    }

    // ---------------- Ramal ----------------

    @Test fun ram001NaoNaoGravaLigacoes() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        val id = servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 10, null)
        val s = repo.observarLinha(id).first()!!
        assertFalse(s.ramalAguaTrocado)
        assertNull(s.ladoRamalTrocado); assertNull(s.comprimentoRamalCm)
        assertTrue(repo.ligacoesDe(id).isEmpty())
    }

    @Test fun ram002SimExigeLigacaoComNumeroLadoEMetragem() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        val id = repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR))), relogio.agora())
        relogio.avancar(10 * MIN); repo.finalizar(id, relogio.agora())
        // Sim sem nenhuma ligação, sem número, comprimento 0 ou negativo: rejeitado.
        listOf(complementosComLigacoes(), complementosComLigacoes(lig("")), complementosComLigacoes(lig("10", cm = 0)), complementosComLigacoes(lig("10", cm = -5)))
            .forEach { try { repo.salvarComplementos(id, it); fail("deveria rejeitar $it") } catch (_: OperacaoInvalidaException) {} }
        // Gatilhos no banco também rejeitam, mesmo contornando o repositório.
        val base = br.controleservicos.app.core.database.LigacaoTrocadaEntity("x", id, 1, null, null, null, null, null, LadoRamal.LONGO, 100, null, false, false, 0, 0)
        listOf(base, base.copy(numeroTexto = "  "), base.copy(numeroTexto = "1", comprimentoCm = 0)).forEach { inv ->
            try { db.ligacaoDao().inserir(listOf(inv)); fail("gatilho deveria abortar") } catch (e: SQLiteConstraintException) {
                assertTrue(e.message!!.contains("ligacao_invalida"))
            }
        }
        val ent = db.servicoDao().buscar(id)!!
        try { db.servicoDao().atualizar(ent.copy(ramalAguaTrocado = false, ladoRamalTrocado = LadoRamal.LONGO)); fail() } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("ramal_inconsistente"))
        }
        repo.salvarComplementos(id, complementosComLigacoes(lig("15", LadoRamal.CURTO, 350)))
        val l = repo.ligacoesDe(id).single()
        assertEquals("15", l.numeroTexto); assertEquals(LadoRamal.CURTO, l.lado); assertEquals(350, l.comprimentoCm) // RAM-003
        assertTrue(repo.observarLinha(id).first()!!.ramalAguaTrocado)
    }

    @Test fun variasLigacoesCadaImovelComQuantidadeDerivada() = runBlocking {
        val id = servicoConcluido(
            emptyList(), 30,
            listOf(lig("12A", LadoRamal.LONGO, 425), lig("S/N", LadoRamal.CURTO, 180), lig("12A", LadoRamal.CURTO, 250, rua = "Rua Outra", bairro = "Centro")),
        )
        val ls = repo.ligacoesDe(id)
        assertEquals(3, ls.size) // quantidade = contagem real de itens
        assertEquals(listOf(1, 2, 3), ls.map { it.ordem })
        assertEquals(listOf("12A", "S/N", "12A"), ls.map { it.numeroTexto })
        assertEquals("Rua Outra", ls[2].logradouroTexto) // mesmo número em rua diferente
        assertNull(ls[0].logradouroTexto) // sem endereço próprio ⇒ usa o principal
        assertEquals(1, repo.observarLinhas().first().size) // 3 ligações ≠ 3 serviços
    }

    @Test fun ram004SimParaNaoRemoveLigacoesEAudita() = runBlocking {
        val a = func("A", FuncaoEquipe.ENCANADOR)
        val id = servicoConcluido(listOf(ParticipanteNovo(a, FuncaoEquipe.ENCANADOR)), 10, listOf(lig("40", cm = 425)))
        repo.salvarComplementos(id, Complementos(SituacaoRede.NAO_APLICAVEL, null, null, null, null, emptyMap(), null, false, emptyList()))
        val s = repo.observarLinha(id).first()!!
        assertFalse(s.ramalAguaTrocado)
        assertTrue(repo.ligacoesDe(id).isEmpty())
        val ev = db.servicoDao().eventos(id).last { it.tipoEvento == TipoEvento.CORRECAO }
        assertTrue(ev.dadosAnteriores!!.contains("1:40/LONGO/425cm"))
        assertTrue(ev.dadosNovos!!.contains("\"ligacoes\":[]"))
    }

    @Test fun baseTempoRegistradaNaFinalizacao() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.avancar(10 * MIN); repo.finalizar(id, relogio.agora())
        assertEquals(BaseTempo.MONOTONICO, repo.observarLinha(id).first()!!.baseTempo)
        repo.salvarComplementos(id, complementosBasicos())
        val id2 = repo.iniciarServico(novo(), relogio.agora())
        relogio.avancar(10 * MIN); relogio.boot += 1; relogio.elapsed = 5_000
        repo.finalizar(id2, relogio.agora())
        val s2 = repo.observarLinha(id2).first()!!
        assertEquals(BaseTempo.RELOGIO_SISTEMA_APOS_REINICIO, s2.baseTempo)
        assertEquals(10 * MIN, s2.duracaoTotalMs)
    }
}
