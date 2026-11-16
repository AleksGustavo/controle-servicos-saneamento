package br.controleservicos.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import br.controleservicos.app.core.model.CodigosCatalogo

@Database(
    entities = [
        MunicipioEntity::class, BairroEntity::class, TipoServicoEntity::class, MaterialRedeEntity::class,
        CondicaoObservadaEntity::class, ServicoEntity::class, ServicoCondicaoEntity::class, EventoServicoEntity::class,
        FuncionarioEntity::class, ParticipacaoServicoEntity::class,
        LogradouroEntity::class, LogradouroBairroEntity::class, LigacaoTrocadaEntity::class,
    ],
    version = AppDatabase.VERSAO,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun servicoDao(): ServicoDao
    abstract fun localDao(): LocalDao
    abstract fun catalogoDao(): CatalogoDao
    abstract fun funcionarioDao(): FuncionarioDao
    abstract fun ligacaoDao(): LigacaoDao

    companion object {
        const val VERSAO = 3
        const val NOME_ARQUIVO = "controle_servicos.db"

        /**
         * v1 → v2: funcionários, participações por serviço, campos de ramal trocado e base de tempo.
         * Somente acrescenta tabelas/colunas: nenhum registro existente é alterado ou apagado.
         * Serviços antigos ficam com ramalAguaTrocado = 0 (lado/comprimento NULL), baseTempo NULL
         * e sem participantes ("equipe não registrada"). Municípios antigos são preservados como estão.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `ramalAguaTrocado` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `ladoRamalTrocado` TEXT")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `comprimentoRamalCm` INTEGER")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `baseTempo` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `funcionario` (`id` TEXT NOT NULL, `nomeExibicao` TEXT NOT NULL, `nomeNormalizado` TEXT NOT NULL, " +
                        "`nomeCompleto` TEXT, `apelidoOuCodigo` TEXT, `funcaoHabitual` TEXT, `ativo` INTEGER NOT NULL, `criadoEm` INTEGER NOT NULL, " +
                        "`atualizadoEm` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_funcionario_nomeNormalizado` ON `funcionario` (`nomeNormalizado`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `participacao_servico` (`servicoId` TEXT NOT NULL, `funcionarioId` TEXT NOT NULL, " +
                        "`funcaoNoServico` TEXT NOT NULL, `funcaoDescricao` TEXT, `nota` TEXT, `nomeSnapshot` TEXT NOT NULL, " +
                        "`funcaoSnapshot` TEXT NOT NULL, `adicionadoEm` INTEGER NOT NULL, `atualizadoEm` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`servicoId`, `funcionarioId`), " +
                        "FOREIGN KEY(`servicoId`) REFERENCES `servico`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`funcionarioId`) REFERENCES `funcionario`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_participacao_servico_funcionarioId` ON `participacao_servico` (`funcionarioId`)")
                // Gatilhos da v2 (ramal único). A v3 os substitui em MIGRATION_2_3.
                db.execSQL(Sementes.GATILHO_RAMAL_V2_INSERT)
                db.execSQL(Sementes.GATILHO_RAMAL_V2_UPDATE)
            }
        }

        /**
         * v2 → v3: catálogo de logradouros (N:N com bairros), origem dos bairros, endereço do serviço
         * ligado ao catálogo, e ligações trocadas por imóvel. Nada é apagado:
         * - ramal único da v2 vira UMA ligação com `legado = 1` e `pendenteIdentificacao = 1`
         *   (número da casa NULL — nunca "0" nem "S/N" inventado); as colunas antigas são preservadas;
         * - logradouros já digitados nos serviços entram no catálogo como "Base local", ligados ao bairro.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `bairro` ADD COLUMN `origem` TEXT NOT NULL DEFAULT 'LOCAL_SERVICO'")
                db.execSQL("ALTER TABLE `bairro` ADD COLUMN `ativo` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `bairro` ADD COLUMN `verificadoEm` INTEGER")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `logradouroId` TEXT")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `cep` TEXT")
                db.execSQL("ALTER TABLE `servico` ADD COLUMN `origemEndereco` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_servico_logradouroId` ON `servico` (`logradouroId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logradouro` (`id` TEXT NOT NULL, `tipo` TEXT, `nomeExibicao` TEXT NOT NULL, " +
                        "`nomeNormalizado` TEXT NOT NULL, `cep` TEXT, `origem` TEXT NOT NULL, `origemExternaId` TEXT, `verificadoEm` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `ativo` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_logradouro_nomeNormalizado` ON `logradouro` (`nomeNormalizado`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_logradouro_cep` ON `logradouro` (`cep`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `logradouro_bairro` (`logradouroId` TEXT NOT NULL, `bairroId` TEXT NOT NULL, " +
                        "`origem` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`logradouroId`, `bairroId`), " +
                        "FOREIGN KEY(`logradouroId`) REFERENCES `logradouro`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`bairroId`) REFERENCES `bairro`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_logradouro_bairro_bairroId` ON `logradouro_bairro` (`bairroId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ligacao_trocada` (`id` TEXT NOT NULL, `servicoId` TEXT NOT NULL, `ordem` INTEGER NOT NULL, " +
                        "`numeroTexto` TEXT, `bairroTexto` TEXT, `logradouroTexto` TEXT, `complemento` TEXT, `identificadorLigacao` TEXT, " +
                        "`lado` TEXT NOT NULL, `comprimentoCm` INTEGER NOT NULL, `observacoes` TEXT, `pendenteIdentificacao` INTEGER NOT NULL, " +
                        "`legado` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                        "FOREIGN KEY(`servicoId`) REFERENCES `servico`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ligacao_trocada_servicoId` ON `ligacao_trocada` (`servicoId`)")

                // Ramal único da v2 → uma ligação legada, pendente de identificação do imóvel.
                db.execSQL(
                    """INSERT INTO ligacao_trocada (id, servicoId, ordem, numeroTexto, bairroTexto, logradouroTexto, complemento,
                       identificadorLigacao, lado, comprimentoCm, observacoes, pendenteIdentificacao, legado, createdAt, updatedAt)
                       SELECT 'legado-' || id, id, 1, NULL, NULL, NULL, NULL, NULL, ladoRamalTrocado, comprimentoRamalCm,
                              'Migrado do campo único de ramal (v2): número do imóvel não registrado', 1, 1, updatedAt, updatedAt
                       FROM servico WHERE ramalAguaTrocado = 1 AND ladoRamalTrocado IS NOT NULL AND comprimentoRamalCm > 0""",
                )

                // Logradouros já usados em serviços entram no catálogo ("Base local").
                val vistos = HashMap<String, String>() // nomeNormalizado -> id
                val agora = System.currentTimeMillis()
                db.query("SELECT id, bairroId, logradouro FROM servico").use { c ->
                    while (c.moveToNext()) {
                        val servicoId = c.getString(0)
                        val bairroId = c.getString(1)
                        val nome = br.controleservicos.app.core.domain.Texto.limparNome(c.getString(2) ?: "")
                        if (nome.isEmpty()) continue
                        val norm = br.controleservicos.app.core.domain.Enderecos.normalizarLogradouro(nome)
                        val id = vistos.getOrPut(norm) {
                            val novo = java.util.UUID.randomUUID().toString()
                            db.execSQL(
                                "INSERT INTO logradouro (id, tipo, nomeExibicao, nomeNormalizado, cep, origem, origemExternaId, verificadoEm, createdAt, updatedAt, ativo) VALUES (?, ?, ?, ?, NULL, 'LOCAL_SERVICO', NULL, NULL, ?, ?, 1)",
                                arrayOf<Any?>(novo, br.controleservicos.app.core.domain.Enderecos.tipoDe(nome), nome, norm, agora, agora),
                            )
                            novo
                        }
                        db.execSQL(
                            "INSERT OR IGNORE INTO logradouro_bairro (logradouroId, bairroId, origem, createdAt) VALUES (?, ?, 'LOCAL_SERVICO', ?)",
                            arrayOf<Any?>(id, bairroId, agora),
                        )
                        db.execSQL("UPDATE servico SET logradouroId = ? WHERE id = ?", arrayOf<Any?>(id, servicoId))
                    }
                }
                db.execSQL("DROP TRIGGER IF EXISTS trg_servico_ramal_insert")
                db.execSQL("DROP TRIGGER IF EXISTS trg_servico_ramal_update")
                Sementes.criarGatilhos(db)
            }
        }

        /** Migrações explícitas, em ordem. Nunca usar migração destrutiva. */
        val MIGRACOES: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun construir(context: Context, nomeArquivo: String = NOME_ARQUIVO): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, nomeArquivo)
                .addMigrations(*MIGRACOES)
                .addCallback(Sementes)
                .build()

        fun emMemoria(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addCallback(Sementes)
                .build()
    }
}

/**
 * Proteções no nível do banco + catálogo inicial.
 * O gatilho garante "um único serviço ativo" mesmo se a checagem da aplicação falhar.
 */
object Sementes : RoomDatabase.Callback() {

    const val GATILHO_ATIVO_INSERT = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_unico_ativo_insert
        BEFORE INSERT ON servico
        WHEN NEW.status = 'EM_ANDAMENTO' AND NEW.deletedAt IS NULL AND
             (SELECT COUNT(*) FROM servico WHERE status = 'EM_ANDAMENTO' AND deletedAt IS NULL) > 0
        BEGIN SELECT RAISE(ABORT, 'servico_ativo_existente'); END
    """

    const val GATILHO_ATIVO_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_unico_ativo_update
        BEFORE UPDATE OF status, deletedAt ON servico
        WHEN NEW.status = 'EM_ANDAMENTO' AND NEW.deletedAt IS NULL AND
             (SELECT COUNT(*) FROM servico WHERE status = 'EM_ANDAMENTO' AND deletedAt IS NULL AND id <> NEW.id) > 0
        BEGIN SELECT RAISE(ABORT, 'servico_ativo_existente'); END
    """

    const val GATILHO_ORDEM_TEMPOS = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_ordem_tempos
        BEFORE UPDATE OF inicioUtcEpochMs, redeEncontradaUtcEpochMs, fimUtcEpochMs ON servico
        WHEN (NEW.fimUtcEpochMs IS NOT NULL AND NEW.fimUtcEpochMs < NEW.inicioUtcEpochMs)
          OR (NEW.redeEncontradaUtcEpochMs IS NOT NULL AND NEW.redeEncontradaUtcEpochMs < NEW.inicioUtcEpochMs)
          OR (NEW.redeEncontradaUtcEpochMs IS NOT NULL AND NEW.fimUtcEpochMs IS NOT NULL
              AND NEW.redeEncontradaUtcEpochMs > NEW.fimUtcEpochMs)
        BEGIN SELECT RAISE(ABORT, 'ordem_tempos_invalida'); END
    """

    override fun onCreate(db: SupportSQLiteDatabase) {
        criarGatilhos(db)
        semear(db)
    }

    /** Gatilhos que já existiam na v1 (usados por testes que reproduzem um banco v1 real). */
    fun criarGatilhosV1(db: SupportSQLiteDatabase) {
        db.execSQL(GATILHO_ATIVO_INSERT)
        db.execSQL(GATILHO_ATIVO_UPDATE)
        db.execSQL(GATILHO_ORDEM_TEMPOS)
    }

    /** Catálogo inicial (tipos, materiais, condições). Nunca bairros, ruas, pessoas ou serviços. */
    fun semear(db: SupportSQLiteDatabase) {
        val agora = System.currentTimeMillis()
        val tipos = listOf(
            Triple(CodigosCatalogo.TIPO_VAZAMENTO_VISIVEL, "Conserto de vazamento visível", true),
            Triple(CodigosCatalogo.TIPO_PRP_NAO_VISIVEL, "PRP — vazamento não visível", true),
            Triple(CodigosCatalogo.TIPO_LIGACAO_AGUA, "Ligação de água", true),
            Triple(CodigosCatalogo.TIPO_LIGACAO_ESGOTO, "Ligação de esgoto", true),
        )
        tipos.forEachIndexed { i, (id, nome, aplica) ->
            db.execSQL(
                "INSERT OR IGNORE INTO tipo_servico (id, nome, ativo, ordemExibicao, aplicaRedeEncontrada, createdAt) VALUES (?, ?, 1, ?, ?, ?)",
                arrayOf<Any>(id, nome, i + 1, if (aplica) 1 else 0, agora),
            )
        }
        val materiais = listOf(
            "PVC" to "PVC", "PEAD" to "PEAD", "FERRO_FUNDIDO" to "Ferro fundido", "ACO" to "Aço",
            "FIBROCIMENTO" to "Fibrocimento", "OUTRO" to "Outro", "NAO_IDENTIFICADO" to "Não identificado",
        )
        materiais.forEachIndexed { i, (id, nome) ->
            db.execSQL(
                "INSERT OR IGNORE INTO material_rede (id, nome, ativo, ordemExibicao, createdAt) VALUES (?, ?, 1, ?, ?)",
                arrayOf<Any>(id, nome, i + 1, agora),
            )
        }
        data class C(val codigo: String, val nome: String, val exclusiva: Boolean, val descricao: Boolean)
        val condicoes = listOf(
            C(CodigosCatalogo.COND_ESGOTO_ACIMA_REDE, "Esgoto acima da rede", false, false),
            C(CodigosCatalogo.COND_REDE_DANIFICADA, "Rede danificada durante a abertura", false, false),
            C(CodigosCatalogo.COND_MAIS_DE_UMA_LIGACAO, "Mais de uma ligação", false, false),
            C(CodigosCatalogo.COND_OUTRAS, "Outras", false, true),
            C(CodigosCatalogo.COND_NENHUMA, "Nenhuma das condições listadas", true, false),
        )
        condicoes.forEachIndexed { i, c ->
            db.execSQL(
                "INSERT OR IGNORE INTO condicao_observada (id, codigo, nome, ativo, ordemExibicao, exclusiva, exigeDescricao, createdAt) VALUES (?, ?, ?, 1, ?, ?, ?, ?)",
                arrayOf<Any>(c.codigo, c.codigo, c.nome, i + 1, if (c.exclusiva) 1 else 0, if (c.descricao) 1 else 0, agora),
            )
        }
    }

    /** v2 (histórico): ramal único. Mantido só para a migração 1→2 reproduzir o esquema v2. */
    private const val CONDICAO_RAMAL_V2_INVALIDO = """
        (NEW.ramalAguaTrocado = 0 AND (NEW.ladoRamalTrocado IS NOT NULL OR NEW.comprimentoRamalCm IS NOT NULL))
        OR (NEW.ramalAguaTrocado = 1 AND (NEW.ladoRamalTrocado IS NULL OR NEW.ladoRamalTrocado NOT IN ('LONGO', 'CURTO')
            OR NEW.comprimentoRamalCm IS NULL OR NEW.comprimentoRamalCm <= 0))
        OR NEW.ramalAguaTrocado NOT IN (0, 1)
    """
    const val GATILHO_RAMAL_V2_INSERT = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_ramal_insert BEFORE INSERT ON servico
        WHEN $CONDICAO_RAMAL_V2_INVALIDO
        BEGIN SELECT RAISE(ABORT, 'ramal_inconsistente'); END
    """
    const val GATILHO_RAMAL_V2_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_ramal_update
        BEFORE UPDATE OF ramalAguaTrocado, ladoRamalTrocado, comprimentoRamalCm ON servico
        WHEN $CONDICAO_RAMAL_V2_INVALIDO
        BEGIN SELECT RAISE(ABORT, 'ramal_inconsistente'); END
    """

    /**
     * v3: os dados de cada troca ficam em `ligacao_trocada`. No serviço, "Não" ⇒ colunas legadas
     * de ramal NULL (nunca valor oculto); "Sim" não exige mais as colunas legadas.
     */
    private const val CONDICAO_RAMAL_INVALIDO = """
        (NEW.ramalAguaTrocado = 0 AND (NEW.ladoRamalTrocado IS NOT NULL OR NEW.comprimentoRamalCm IS NOT NULL))
        OR NEW.ramalAguaTrocado NOT IN (0, 1)
        OR (NEW.ladoRamalTrocado IS NOT NULL AND NEW.ladoRamalTrocado NOT IN ('LONGO', 'CURTO'))
        OR (NEW.comprimentoRamalCm IS NOT NULL AND NEW.comprimentoRamalCm <= 0)
    """

    const val GATILHO_RAMAL_INSERT = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_ramal_v3_insert BEFORE INSERT ON servico
        WHEN $CONDICAO_RAMAL_INVALIDO
        BEGIN SELECT RAISE(ABORT, 'ramal_inconsistente'); END
    """

    const val GATILHO_RAMAL_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_servico_ramal_v3_update
        BEFORE UPDATE OF ramalAguaTrocado, ladoRamalTrocado, comprimentoRamalCm ON servico
        WHEN $CONDICAO_RAMAL_INVALIDO
        BEGIN SELECT RAISE(ABORT, 'ramal_inconsistente'); END
    """

    /** Cada ligação: lado válido, comprimento > 0 e número do imóvel obrigatório (exceto legado pendente). */
    private const val CONDICAO_LIGACAO_INVALIDA = """
        NEW.lado NOT IN ('LONGO', 'CURTO') OR NEW.comprimentoCm <= 0
        OR ((NEW.numeroTexto IS NULL OR TRIM(NEW.numeroTexto) = '') AND NEW.pendenteIdentificacao = 0)
    """

    const val GATILHO_LIGACAO_INSERT = """
        CREATE TRIGGER IF NOT EXISTS trg_ligacao_insert BEFORE INSERT ON ligacao_trocada
        WHEN $CONDICAO_LIGACAO_INVALIDA
        BEGIN SELECT RAISE(ABORT, 'ligacao_invalida'); END
    """

    const val GATILHO_LIGACAO_UPDATE = """
        CREATE TRIGGER IF NOT EXISTS trg_ligacao_update BEFORE UPDATE ON ligacao_trocada
        WHEN $CONDICAO_LIGACAO_INVALIDA
        BEGIN SELECT RAISE(ABORT, 'ligacao_invalida'); END
    """

    override fun onOpen(db: SupportSQLiteDatabase) {
        // Idempotente: garante os gatilhos também em bancos vindos de migração.
        criarGatilhos(db)
    }

    fun criarGatilhos(db: SupportSQLiteDatabase) {
        db.execSQL(GATILHO_ATIVO_INSERT)
        db.execSQL(GATILHO_ATIVO_UPDATE)
        db.execSQL(GATILHO_ORDEM_TEMPOS)
        db.execSQL(GATILHO_RAMAL_INSERT)
        db.execSQL(GATILHO_RAMAL_UPDATE)
        db.execSQL(GATILHO_LIGACAO_INSERT)
        db.execSQL(GATILHO_LIGACAO_UPDATE)
    }
}
