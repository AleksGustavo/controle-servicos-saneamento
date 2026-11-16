package br.controleservicos.app

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.domain.Estatisticas
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.model.CodigosCatalogo
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.model.TipoEvento
import br.controleservicos.app.core.repository.Complementos
import br.controleservicos.app.core.repository.OperacaoInvalidaException
import br.controleservicos.app.core.repository.ResultadoMarco
import br.controleservicos.app.core.repository.ServicoAtivoExistenteException
import br.controleservicos.app.core.repository.ServicoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositorioTest {
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

    @Test fun catalogoInicialSemeado() = runBlocking {
        val tipos = repo.observarTipos().first().map { it.id }
        assertEquals(
            listOf(CodigosCatalogo.TIPO_VAZAMENTO_VISIVEL, CodigosCatalogo.TIPO_PRP_NAO_VISIVEL, CodigosCatalogo.TIPO_LIGACAO_AGUA, CodigosCatalogo.TIPO_LIGACAO_ESGOTO),
            tipos,
        )
        assertTrue(repo.observarMateriais().first().any { it.nome == "PVC" })
        assertTrue(repo.observarCondicoes().first().single { it.codigo == CodigosCatalogo.COND_NENHUMA }.exclusiva)
        assertTrue(repo.observarLinhas().first().isEmpty()) // nenhum serviço/bairro fictício
        assertTrue(repo.observarBairros().first().isEmpty())
    }

    @Test fun iniciarGravaServicoEEvento() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        val s = repo.observarLinha(id).first()!!
        assertEquals(StatusServico.EM_ANDAMENTO, s.status)
        assertEquals(relogio.wall, s.inicioUtcEpochMs)
        assertEquals("Jardim Exemplo", s.bairroNome)
        assertNull(s.fimUtcEpochMs)
        val ev = db.servicoDao().eventos(id)
        assertEquals(listOf(TipoEvento.INICIO), ev.map { it.tipoEvento })
    }

    @Test fun umUnicoServicoAtivo() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        try {
            repo.iniciarServico(novo(bairro = "Outro"), relogio.agora())
            fail("deveria impedir segundo ativo")
        } catch (e: ServicoAtivoExistenteException) {
            assertEquals(id, e.idAtivo)
        }
        // Gatilho do banco também impede, mesmo contornando o repositório.
        val ent = db.servicoDao().buscar(id)!!
        try {
            db.servicoDao().inserir(ent.copy(id = "outro"))
            fail("gatilho deveria abortar")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("servico_ativo_existente"))
        }
        assertEquals(1, repo.observarLinhas().first().size)
    }

    @Test fun iniciosConcorrentesGeramApenasUmAtivo() = runBlocking {
        val resultados = (1..8).map { i ->
            async(Dispatchers.IO) { runCatching { repo.iniciarServico(novo(bairro = "B$i"), relogio.agora()) } }
        }.awaitAll()
        assertEquals(1, resultados.count { it.isSuccess })
        assertEquals(1, repo.observarLinhas().first().count { it.status == StatusServico.EM_ANDAMENTO })
    }

    @Test fun marcarRedeNaoPausaEIdempotente() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.avancar(16 * MIN + 50_000)
        val primeiro = relogio.wall
        val toques = (1..10).map { async(Dispatchers.IO) { repo.marcarRede(id, relogio.agora().copy(wallClockMs = relogio.wall + it, elapsedRealtimeMs = relogio.elapsed + it)) } }.awaitAll()
        assertEquals(1, toques.count { it is ResultadoMarco.Registrado })
        val s = repo.observarLinha(id).first()!!
        assertEquals(StatusServico.EM_ANDAMENTO, s.status)
        assertTrue(s.redeEncontradaUtcEpochMs!! in primeiro..primeiro + 10)
        assertEquals(s.redeEncontradaUtcEpochMs!! - s.inicioUtcEpochMs, s.duracaoAteRedeMs)
        assertEquals(1, db.servicoDao().eventos(id).count { it.tipoEvento == TipoEvento.REDE_ENCONTRADA })
        // Toque posterior não sobrescreve.
        relogio.avancar(MIN)
        val r = repo.marcarRede(id, relogio.agora())
        assertTrue(r is ResultadoMarco.JaRegistrado)
        assertEquals(s.redeEncontradaUtcEpochMs, repo.observarLinha(id).first()!!.redeEncontradaUtcEpochMs)
    }

    @Test fun finalizarCongelaTempoEIdempotente() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.avancar(20 * MIN); repo.marcarRede(id, relogio.agora())
        relogio.avancar(64 * MIN + 35_000)
        val marca = relogio.agora()
        val r = (1..6).map { async(Dispatchers.IO) { repo.finalizar(id, marca) } }.awaitAll()
        assertEquals(1, r.count { it is ResultadoMarco.Registrado })
        val s = repo.observarLinha(id).first()!!
        assertEquals(StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS, s.status)
        assertEquals(84 * MIN + 35_000, s.duracaoTotalMs)
        assertEquals(1, db.servicoDao().eventos(id).count { it.tipoEvento == TipoEvento.FIM })
        // O tempo de preencher o formulário não conta.
        relogio.avancar(15 * MIN)
        repo.salvarComplementos(id, Complementos(SituacaoRede.MARCADA, 100, "PVC", 1.2, 1, emptyMap(), null, permitirSemParticipantes = true))
        assertEquals(84 * MIN + 35_000, repo.observarLinha(id).first()!!.duracaoTotalMs)
        assertFalse(repo.marcarRede(id, relogio.agora()) is ResultadoMarco.Registrado)
    }

    @Test fun pendenteDeComplementosSobreviveReaberturaEDepoisConclui() = runBlocking {
        val nome = "teste_reabertura.db"
        ctx.deleteDatabase(nome)
        val db1 = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, nome).addCallback(br.controleservicos.app.core.database.Sementes).build()
        val r1 = ServicoRepository(db1, relogio)
        val idAtivo = r1.iniciarServico(novo(), relogio.agora())
        relogio.avancar(30 * MIN)
        db1.close() // "processo morto" com serviço em andamento

        val db2 = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, nome).addCallback(br.controleservicos.app.core.database.Sementes).build()
        val r2 = ServicoRepository(db2, relogio)
        val ativo = r2.observarAtivo().first()
        assertEquals(idAtivo, ativo?.id)
        r2.finalizar(idAtivo, relogio.agora())
        db2.close() // fechado antes dos complementos

        val db3 = androidx.room.Room.databaseBuilder(ctx, AppDatabase::class.java, nome).addCallback(br.controleservicos.app.core.database.Sementes).build()
        val r3 = ServicoRepository(db3, relogio)
        val pend = r3.observarPendentes().first()
        assertEquals(listOf(idAtivo), pend.map { it.id })
        assertEquals(30 * MIN, pend.single().duracaoTotalMs)
        r3.salvarComplementos(idAtivo, Complementos(SituacaoRede.NAO_APLICAVEL, null, null, null, null, emptyMap(), "ok", permitirSemParticipantes = true))
        assertEquals(StatusServico.CONCLUIDO, r3.observarLinha(idAtivo).first()!!.status)
        db3.close()
        ctx.deleteDatabase(nome)
        Unit
    }

    @Test fun finalizarSemRedeEComplementos() = runBlocking {
        val id = repo.iniciarServico(novo(tipo = CodigosCatalogo.TIPO_LIGACAO_AGUA), relogio.agora())
        relogio.avancar(45 * MIN)
        repo.finalizar(id, relogio.agora())
        val conds = repo.observarCondicoes().first().associateBy { it.codigo }
        repo.salvarComplementos(
            id,
            Complementos(
                SituacaoRede.MARCADA, // pedido inválido: sem horário marcado
                null, null, null, 2,
                mapOf(conds.getValue(CodigosCatalogo.COND_MAIS_DE_UMA_LIGACAO).id to null, conds.getValue(CodigosCatalogo.COND_OUTRAS).id to "raiz de árvore"),
                "  ",
                permitirSemParticipantes = true,
            ),
        )
        val s = repo.observarLinha(id).first()!!
        assertEquals(SituacaoRede.NAO_MARCADA, s.situacaoRede) // não inventa horário
        assertNull(s.redeEncontradaUtcEpochMs)
        assertNull(s.duracaoAteRedeMs)
        assertNull(s.diametroMm)
        assertNull(s.observacoes)
        assertEquals(2, s.quantidadeLigacoes)
        val c = repo.condicoesDe(id)
        assertEquals(2, c.size)
        assertEquals("raiz de árvore", c.single { it.codigo == CodigosCatalogo.COND_OUTRAS }.descricao)
    }

    /** LEM-002: grafias equivalentes não duplicam bairro; bairro novo pode ser cadastrado. */
    @Test fun lem002BairrosNormalizadosEmLeme() = runBlocking {
        val a = repo.iniciarServico(novo(bairro = "São João"), relogio.agora()); relogio.avancar(MIN); repo.finalizar(a, relogio.agora())
        val b = repo.iniciarServico(novo(bairro = "  sao   joao "), relogio.agora()); relogio.avancar(MIN); repo.finalizar(b, relogio.agora())
        val c = repo.iniciarServico(novo(bairro = "SÃO JOÃO"), relogio.agora()); relogio.avancar(MIN); repo.finalizar(c, relogio.agora())
        val d = repo.iniciarServico(novo(bairro = "Centro"), relogio.agora()); relogio.avancar(MIN); repo.finalizar(d, relogio.agora())
        val linhas = repo.observarLinhas().first().associateBy { it.id }
        assertEquals(linhas.getValue(a).bairroId, linhas.getValue(b).bairroId)
        assertEquals(linhas.getValue(a).bairroId, linhas.getValue(c).bairroId)
        assertEquals("São João", linhas.getValue(b).bairroNome) // grafia amigável preservada
        assertFalse(linhas.getValue(a).bairroId == linhas.getValue(d).bairroId)
        assertEquals(2, repo.observarBairros().first().size)
        assertEquals(1, repo.observarMunicipios().first().size)
        assertEquals(3, FiltroServicos(bairroId = linhas.getValue(a).bairroId).aplicar(linhas.values.toList()).size)
    }

    /** LEM-001: novo serviço sempre Leme/SP, sem campo de seleção municipal. */
    @Test fun lem001NovoServicoSempreEmLemeSP() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        val s = repo.observarLinha(id).first()!!
        assertEquals("Leme", s.municipioNome)
        assertEquals("SP", s.municipioUf)
        assertFalse(s.foraDoMunicipioOperacional)
        assertEquals(listOf("Leme"), repo.observarMunicipios().first().map { it.nome })
    }

    @Test fun exclusaoLogicaSaiDasEstatisticasERestaura() = runBlocking {
        val ids = (1..3).map { i ->
            val id = repo.iniciarServico(novo(), relogio.agora()); relogio.avancar(i * 10 * MIN); repo.finalizar(id, relogio.agora()); id
        }
        repo.excluir(ids[2])
        var lista = repo.observarLinhas().first()
        assertEquals(2, Estatisticas.porTipo(lista, 5).single().total.n)
        assertEquals(2, FiltroServicos().aplicar(lista).size) // exportação padrão
        assertEquals(3, lista.size) // registro preservado
        repo.restaurar(ids[2])
        lista = repo.observarLinhas().first()
        assertEquals(3, Estatisticas.porTipo(lista, 5).single().total.n)
        val eventos = db.servicoDao().eventos(ids[2]).map { it.tipoEvento }
        assertTrue(TipoEvento.EXCLUSAO in eventos && TipoEvento.RESTAURACAO in eventos)
    }

    @Test fun naoExcluiEmAndamentoECancelamentoForaDasMetricas() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        try { repo.excluir(id); fail() } catch (_: OperacaoInvalidaException) {}
        repo.cancelar(id, "iniciado por engano")
        val s = repo.observarLinha(id).first()!!
        assertEquals(StatusServico.CANCELADO, s.status)
        assertNull(repo.observarAtivo().first())
        assertTrue(Estatisticas.porTipo(listOf(s), 5).isEmpty())
        // Pode iniciar outro após cancelar.
        assertNotNull(repo.iniciarServico(novo(), relogio.agora()))
    }

    @Test fun correcaoAuditavelDeHorarios() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora()); val ini = relogio.wall
        relogio.avancar(60 * MIN); repo.finalizar(id, relogio.agora())
        try { repo.corrigirHorarios(id, ini, ini + 70 * MIN, ini + 60 * MIN, "rede depois do fim"); fail() } catch (_: OperacaoInvalidaException) {}
        try { repo.corrigirHorarios(id, ini, null, ini + 30 * MIN, ""); fail() } catch (_: OperacaoInvalidaException) {}
        repo.corrigirHorarios(id, ini, ini + 10 * MIN, ini + 50 * MIN, "Esqueci de marcar a rede no campo")
        val s = repo.observarLinha(id).first()!!
        assertEquals(50 * MIN, s.duracaoTotalMs)
        assertEquals(10 * MIN, s.duracaoAteRedeMs)
        val corr = db.servicoDao().eventos(id).single { it.tipoEvento == TipoEvento.CORRECAO }
        assertTrue(corr.dadosAnteriores!!.contains("\"rede\":null"))
        assertEquals("Esqueci de marcar a rede no campo", corr.motivo)
        // O gatilho de ordem protege contra atualização direta inválida.
        val ent = db.servicoDao().buscar(id)!!
        try { db.servicoDao().atualizar(ent.copy(fimUtcEpochMs = ent.inicioUtcEpochMs - 1)); fail() } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("ordem_tempos_invalida"))
        }
    }

    @Test fun relogioAlteradoDuranteMedicaoMarcaRevisao() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.elapsed += 40 * MIN
        relogio.wall += 40 * MIN - 3 * 60 * MIN // usuário atrasou o relógio em 3 h
        val r = repo.finalizar(id, relogio.agora()) as ResultadoMarco.Registrado
        assertNotNull(r.avisoRevisao)
        val s = repo.observarLinha(id).first()!!
        assertEquals(40 * MIN, s.duracaoTotalMs) // calculado pelo relógio monotônico
        assertTrue(s.requerRevisao)
        assertTrue(Estatisticas.porTipo(listOf(s), 5).isEmpty())
        val fim = db.servicoDao().eventos(id).single { it.tipoEvento == TipoEvento.FIM }
        assertEquals(relogio.wall, fim.wallClockBrutoMs) // valor bruto preservado para auditoria
    }

    @Test fun reinicioComRelogioAnteriorNaoGeraNegativo() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        relogio.boot += 1; relogio.elapsed = 1000; relogio.wall -= 10 * MIN
        repo.finalizar(id, relogio.agora())
        val s = repo.observarLinha(id).first()!!
        assertEquals(0L, s.duracaoTotalMs)
        assertTrue(s.requerRevisao)
    }

    @Test fun baseComMuitosRegistros() = runBlocking {
        val inicio = System.currentTimeMillis()
        repeat(1500) { i ->
            val id = repo.iniciarServico(novo(bairro = "Bairro ${i % 40}"), relogio.agora())
            relogio.avancar((10 + i % 50) * MIN)
            repo.finalizar(id, relogio.agora())
        }
        val tInsert = System.currentTimeMillis() - inicio
        val t1 = System.currentTimeMillis()
        val lista = repo.observarLinhas().first()
        val estat = Estatisticas.porTipoEBairro(lista, 5)
        val tConsulta = System.currentTimeMillis() - t1
        assertEquals(1500, lista.size)
        assertEquals(40, estat.size)
        android.util.Log.i("RepositorioTest", "1500 inserções em ${tInsert}ms; consulta+estatística em ${tConsulta}ms")
        assertTrue("consulta lenta: $tConsulta ms", tConsulta < 5000)
    }

    @Test fun bancoVazio() = runBlocking {
        val lista = repo.observarLinhas().first()
        assertTrue(Estatisticas.porTipo(lista, 5).isEmpty())
        assertNull(repo.observarAtivo().first())
        assertTrue(repo.instantaneoExportacao().linhas.isEmpty())
    }
}
