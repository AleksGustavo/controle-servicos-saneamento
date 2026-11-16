package br.controleservicos.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.database.Sementes
import br.controleservicos.app.core.export.Celula
import br.controleservicos.app.core.export.ContratoExportacao
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.repository.ServicoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * Migrações reais a partir do esquema exportado (app/schemas). DB-001: um banco v1 com dados
 * migra para v2 sem perda de serviços, endereços, marcos, eventos e condições.
 */
@RunWith(AndroidJUnit4::class)
class MigracaoTest {
    private val NOME = "migracao-teste.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun abrir(): AppDatabase {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return Room.databaseBuilder(ctx, AppDatabase::class.java, NOME).addMigrations(*AppDatabase.MIGRACOES).addCallback(Sementes).build()
    }

    private fun gatilhos(db: AppDatabase): List<String> {
        val c = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='trigger'")
        val nomes = mutableListOf<String>()
        while (c.moveToNext()) nomes += c.getString(0)
        c.close()
        return nomes
    }

    @Test fun esquemaV1ExportadoAbreComDadosPreservados() {
        helper.createDatabase(NOME, 1).apply {
            Sementes.semear(this); Sementes.criarGatilhosV1(this)
            execSQL("INSERT INTO municipio (id, nome, nomeNormalizado, uf, createdAt) VALUES ('m1','Cidade','cidade','SP',0)")
            execSQL("INSERT INTO bairro (id, municipioId, nomeOriginal, nomeNormalizado, createdAt) VALUES ('b1','m1','Centro','centro',0)")
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, status, inicioUtcEpochMs,
                   fimUtcEpochMs, duracaoTotalMs, requerRevisao, createdAt, updatedAt, version)
                   VALUES ('s1','LIGACAO_AGUA','m1','b1','Rua A','10','CONCLUIDO',1000,61000,60000,0,0,0,1)""",
            )
            close()
        }
        helper.runMigrationsAndValidate(NOME, AppDatabase.VERSAO, true, *AppDatabase.MIGRACOES)
        val db = abrir()
        runBlocking {
            val linhas = ServicoRepository(db, RelogioFalso()).observarLinhas().first()
            assertEquals(1, linhas.size)
            assertEquals(60000L, linhas.single().duracaoTotalMs)
            assertTrue(
                gatilhos(db).containsAll(
                    listOf("trg_servico_unico_ativo_insert", "trg_servico_unico_ativo_update", "trg_servico_ordem_tempos", "trg_servico_ramal_v3_insert", "trg_servico_ramal_v3_update", "trg_ligacao_insert", "trg_ligacao_update"),
                ),
            )
        }
        db.close()
    }

    /** DB-001 */
    @Test fun db001Migrar1Para2PreservaHistoricoCompleto() {
        helper.createDatabase(NOME, 1).apply {
            Sementes.semear(this); Sementes.criarGatilhosV1(this)
            // Município de versão anterior diferente de Leme: deve ser preservado, nunca sobrescrito.
            execSQL("INSERT INTO municipio (id, nome, nomeNormalizado, uf, createdAt) VALUES ('m1','Araras','araras','SP',0)")
            execSQL("INSERT INTO municipio (id, nome, nomeNormalizado, uf, createdAt) VALUES ('m2','Leme','leme','SP',0)")
            execSQL("INSERT INTO bairro (id, municipioId, nomeOriginal, nomeNormalizado, createdAt) VALUES ('b1','m1','São João','sao joao',0)")
            execSQL("INSERT INTO bairro (id, municipioId, nomeOriginal, nomeNormalizado, createdAt) VALUES ('b2','m2','Centro','centro',0)")
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, complemento, equipeIdentificador, status,
                   inicioUtcEpochMs, inicioElapsedRealtimeMs, inicioBootCount, inicioZoneId, redeEncontradaUtcEpochMs, fimUtcEpochMs,
                   duracaoTotalMs, duracaoAteRedeMs, situacaoRede, diametroMm, materialRedeId, profundidadeMetros, quantidadeLigacoes,
                   observacoes, requerRevisao, createdAt, updatedAt, version)
                   VALUES ('s1','PRP_NAO_VISIVEL','m1','b1','Rua das Flores','S/N','fundos','Equipe 2','CONCLUIDO',
                   1763000000000, 5000, 3, 'America/Sao_Paulo', 1763000600000, 1763002400000,
                   2400000, 600000, 'MARCADA', 100, 'PVC', 1.2, 1, 'obs antiga', 0, 1763000000000, 1763002500000, 2)""",
            )
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, status, inicioUtcEpochMs,
                   fimUtcEpochMs, duracaoTotalMs, requerRevisao, createdAt, updatedAt, version)
                   VALUES ('s2','LIGACAO_AGUA','m2','b2','Rua B','20','ENCERRADO_PENDENTE_COMPLEMENTOS',1763100000000,1763101800000,1800000,0,0,0,1)""",
            )
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, status, inicioUtcEpochMs, requerRevisao, createdAt, updatedAt, version)
                   VALUES ('s3','LIGACAO_ESGOTO','m2','b2','Rua C','30','EM_ANDAMENTO',1763200000000,0,0,0,1)""",
            )
            execSQL("INSERT INTO evento_servico (id, servicoId, tipoEvento, timestampUtcEpochMs, createdAt) VALUES ('e1','s1','INICIO',1763000000000,1763000000000)")
            execSQL("INSERT INTO evento_servico (id, servicoId, tipoEvento, timestampUtcEpochMs, createdAt) VALUES ('e2','s1','FIM',1763002400000,1763002400000)")
            execSQL("INSERT INTO servico_condicao (servicoId, condicaoId, descricao) VALUES ('s1','OUTRAS','raiz')")
            close()
        }
        val migrado = helper.runMigrationsAndValidate(NOME, 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
        migrado.query("SELECT COUNT(*) FROM servico").use { it.moveToFirst(); assertEquals(3, it.getInt(0)) }
        migrado.close()

        val db = abrir()
        runBlocking {
            val repo = ServicoRepository(db, RelogioFalso())
            val linhas = repo.observarLinhas().first().associateBy { it.id }
            assertEquals(3, linhas.size)
            val s1 = linhas.getValue("s1")
            assertEquals("Araras", s1.municipioNome) // não sobrescrito com Leme
            assertTrue(s1.foraDoMunicipioOperacional) // sinalizado, sem impedir leitura
            assertEquals("São João", s1.bairroNome)
            assertEquals("Rua das Flores", s1.logradouro); assertEquals("S/N", s1.numero); assertEquals("fundos", s1.complemento)
            assertEquals("Equipe 2", s1.equipeIdentificador)
            assertEquals(1763000000000L, s1.inicioUtcEpochMs); assertEquals(1763000600000L, s1.redeEncontradaUtcEpochMs); assertEquals(1763002400000L, s1.fimUtcEpochMs)
            assertEquals(2400000L, s1.duracaoTotalMs); assertEquals(600000L, s1.duracaoAteRedeMs)
            assertEquals(1.2, s1.profundidadeMetros!!, 0.0); assertEquals(100, s1.diametroMm); assertEquals("obs antiga", s1.observacoes)
            // Campos novos com defaults seguros: nada inventado.
            assertFalse(s1.ramalAguaTrocado); assertNull(s1.ladoRamalTrocado); assertNull(s1.comprimentoRamalCm); assertNull(s1.baseTempo)
            assertTrue(repo.participantesDe("s1").isEmpty()) // "equipe não registrada"
            assertTrue(repo.observarFuncionarios().first().isEmpty()) // nenhum funcionário criado
            assertEquals(StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS, linhas.getValue("s2").status)
            assertEquals(linhas.getValue("s3").id, repo.observarAtivo().first()?.id) // serviço ativo continua ativo
            assertEquals(2, db.servicoDao().eventos("s1").size)
            assertEquals("raiz", repo.condicoesDe("s1").single().descricao)
            // Exportação dos dados migrados funciona e mantém uma linha por serviço.
            val inst = repo.instantaneoExportacao()
            val tab = ContratoExportacao.registros(inst.linhas, inst.condicoes, ZoneId.of("America/Sao_Paulo"), inst.participantes)
            assertEquals(3, tab.linhas.size)
            val r1 = tab.linhas.first { (it[0] as Celula.Txt).valor == "s1" }
            assertEquals(Celula.Num(2400L), r1[ContratoExportacao.COLUNAS.indexOf("duracao_total_segundos")])
            assertEquals(Celula.Vazia, r1[ContratoExportacao.COLUNAS.indexOf("comprimento_ramal_m")])
            // O serviço ativo migrado pode ser finalizado e um novo serviço fica em Leme/SP.
            repo.finalizar("s3", RelogioFalso(wall = 1763203600000L, elapsed = 1, boot = 99).agora())
            repo.salvarComplementos("s3", complementosBasicos())
            val novo = repo.iniciarServico(novo(), RelogioFalso(wall = 1763300000000L).agora())
            assertEquals("Leme", repo.observarLinha(novo).first()!!.municipioNome)
            assertEquals(2, repo.observarMunicipios().first().size) // reutilizou o Leme existente
        }
        db.close()
    }

    /** DB-001 (v3): banco v2 real com ramal único migra para ligação legada SEM número inventado. */
    @Test fun migrar2Para3PreservaRamalComoLigacaoLegada() {
        helper.createDatabase(NOME, 2).apply {
            Sementes.semear(this)
            execSQL("INSERT INTO municipio (id, nome, nomeNormalizado, uf, createdAt) VALUES ('m1','Leme','leme','SP',0)")
            execSQL("INSERT INTO bairro (id, municipioId, nomeOriginal, nomeNormalizado, createdAt) VALUES ('b1','m1','Centro','centro',0)")
            execSQL("INSERT INTO bairro (id, municipioId, nomeOriginal, nomeNormalizado, createdAt) VALUES ('b2','m1','Jardim Sol','jardim sol',0)")
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, status, inicioUtcEpochMs, fimUtcEpochMs, duracaoTotalMs,
                   requerRevisao, createdAt, updatedAt, version, ramalAguaTrocado, ladoRamalTrocado, comprimentoRamalCm, baseTempo, quantidadeLigacoes)
                   VALUES ('s1','LIGACAO_AGUA','m1','b1','Av. Brasil','100','CONCLUIDO',1000,61000,60000,0,0,5,1,1,'LONGO',350,'MONOTONICO',2)""",
            )
            execSQL(
                """INSERT INTO servico (id, tipoServicoId, municipioId, bairroId, logradouro, numero, status, inicioUtcEpochMs, fimUtcEpochMs, duracaoTotalMs,
                   requerRevisao, createdAt, updatedAt, version, ramalAguaTrocado)
                   VALUES ('s2','PRP_NAO_VISIVEL','m1','b2','Avenida  BRASIL','7','CONCLUIDO',2000,62000,60000,0,0,0,1,0)""",
            )
            close()
        }
        helper.runMigrationsAndValidate(NOME, 3, true, AppDatabase.MIGRATION_2_3).close()
        val db = abrir()
        runBlocking {
            val repo = ServicoRepository(db, RelogioFalso())
            val l = repo.ligacoesDe("s1").single()
            assertNull(l.numeroTexto) // nunca "0" ou "S/N" inventado
            assertTrue(l.pendenteIdentificacao); assertTrue(l.legado)
            assertEquals(350, l.comprimentoCm)
            assertTrue(repo.ligacoesDe("s2").isEmpty())
            val s1 = repo.observarLinha("s1").first()!!
            assertEquals(350, s1.comprimentoRamalCm) // valor original preservado
            assertEquals(2, s1.quantidadeLigacoes) // campo legado distinto, preservado
            // "Av. Brasil" e "Avenida  BRASIL" viram UM logradouro no catálogo, ligado aos dois bairros.
            val ruas = repo.observarLogradouros().first()
            assertEquals(1, ruas.size)
            assertEquals("Avenida", ruas.single().tipo)
            assertEquals(setOf("b1", "b2"), repo.observarVinculosLogradouro().first().map { it.bairroId }.toSet())
            assertEquals(ruas.single().id, s1.logradouroId)
            assertEquals("Av. Brasil", s1.logradouro) // texto histórico preservado
        }
        db.close()
    }
}
