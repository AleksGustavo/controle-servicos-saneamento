package br.controleservicos.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.backup.BackupBanco
import br.controleservicos.app.core.backup.BackupInvalidoException
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.repository.Complementos
import br.controleservicos.app.core.repository.DadosFuncionario
import br.controleservicos.app.core.repository.ParticipanteNovo
import br.controleservicos.app.core.repository.ServicoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Backup local e restauração testada (arquivo real em disco). Dados SINTÉTICOS. */
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nome = "backup-teste.db"
    private val relogio = RelogioFalso()
    private lateinit var db: AppDatabase
    private val tmp = File(ctx.cacheDir, "backup_teste_saida.db")

    @Before fun setup() {
        ctx.deleteDatabase(nome)
        db = AppDatabase.construir(ctx, nome)
    }

    @After fun fim() {
        runCatching { db.close() }
        ctx.deleteDatabase(nome)
        tmp.delete()
        File(ctx.filesDir, "backups_seguranca").deleteRecursively()
    }

    private suspend fun popular(repo: ServicoRepository, n: Int) {
        val f = repo.adicionarFuncionario(DadosFuncionario("Teste Backup", funcaoHabitual = FuncaoEquipe.ENCANADOR))
        repeat(n) {
            val id = repo.iniciarServico(novo(participantes = listOf(ParticipanteNovo(f, FuncaoEquipe.ENCANADOR))), relogio.agora())
            relogio.avancar(30 * 60_000L)
            repo.finalizar(id, relogio.agora())
            repo.salvarComplementos(id, complementosComLigacoes(lig("12A", LadoRamal.LONGO, 350)))
        }
    }

    @Test fun gerarBackupConsistenteEValidado() = runBlocking {
        val repo = ServicoRepository(db, relogio)
        popular(repo, 3)
        val info = BackupBanco.gerarCopia(db, ctx.getDatabasePath(nome), tmp)
        assertEquals(3, info.servicos)
        assertEquals(1, info.funcionarios)
        assertEquals(AppDatabase.VERSAO, info.versaoEsquema)
        assertTrue(!File(tmp.path + "-wal").exists()) // arquivo autocontido
        assertEquals(info, BackupBanco.validar(tmp))
    }

    @Test fun restaurarSubstituiDadosEGuardaCopiaDeSeguranca() = runBlocking {
        var repo = ServicoRepository(db, relogio)
        popular(repo, 2)
        BackupBanco.gerarCopia(db, ctx.getDatabasePath(nome), tmp)
        popular(repo, 3) // dados posteriores ao backup (5 serviços no total)
        assertEquals(5, repo.observarLinhas().first().size)

        val seguranca = BackupBanco.restaurar(ctx, db, tmp, relogio.wall, nome)
        assertEquals(5, BackupBanco.validar(seguranca).servicos) // estado anterior guardado

        db = AppDatabase.construir(ctx, nome) // equivalente ao reinício do app
        repo = ServicoRepository(db, relogio)
        val linhas = repo.observarLinhas().first()
        assertEquals(2, linhas.size)
        assertEquals(350, repo.ligacoesDe(linhas.first().id).single().comprimentoCm)
        assertEquals("12A", repo.ligacoesDe(linhas.first().id).single().numeroTexto)
        assertEquals(1, repo.participantesDe(linhas.first().id).size)
    }

    @Test fun arquivoInvalidoERejeitadoSemTocarNosDados() = runBlocking {
        val repo = ServicoRepository(db, relogio)
        popular(repo, 1)
        tmp.writeText("isto não é um banco de dados ".repeat(50))
        try { BackupBanco.validar(tmp); fail() } catch (_: BackupInvalidoException) {}
        try { BackupBanco.restaurar(ctx, db, tmp, relogio.wall, nome); fail() } catch (_: BackupInvalidoException) {}
        assertEquals(1, repo.observarLinhas().first().size) // banco atual intacto e aberto
    }

    @Test fun backupDeVersaoMaisNovaERejeitado() = runBlocking {
        val repo = ServicoRepository(db, relogio)
        popular(repo, 1)
        BackupBanco.gerarCopia(db, ctx.getDatabasePath(nome), tmp)
        android.database.sqlite.SQLiteDatabase.openDatabase(tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.version = AppDatabase.VERSAO + 1 }
        try { BackupBanco.validar(tmp); fail() } catch (e: BackupInvalidoException) { assertTrue(e.message!!.contains("mais nova")) }
    }
}
