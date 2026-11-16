package br.controleservicos.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.ParticipanteServico
import kotlinx.coroutines.flow.Flow

private const val SELECT_LINHA = """
    SELECT s.id, s.tipoServicoId, t.nome AS tipoNome, s.municipioId, m.nome AS municipioNome,
           NULLIF(m.uf, '') AS municipioUf, s.bairroId, b.nomeOriginal AS bairroNome,
           s.logradouro, s.numero, s.complemento, s.equipeIdentificador, s.status,
           s.inicioUtcEpochMs, s.inicioZoneId, s.inicioElapsedRealtimeMs, s.inicioBootCount, s.redeEncontradaUtcEpochMs, s.fimUtcEpochMs,
           s.duracaoTotalMs, s.duracaoAteRedeMs, s.situacaoRede, s.diametroMm, s.materialRedeId,
           mr.nome AS materialNome, s.profundidadeMetros, s.quantidadeLigacoes, s.observacoes,
           s.requerRevisao, s.motivoRevisao, s.createdAt, s.updatedAt, s.deletedAt,
           s.ramalAguaTrocado, s.ladoRamalTrocado, s.comprimentoRamalCm, s.baseTempo, s.logradouroId, s.cep
    FROM servico s
    JOIN tipo_servico t ON t.id = s.tipoServicoId
    JOIN municipio m ON m.id = s.municipioId
    JOIN bairro b ON b.id = s.bairroId
    LEFT JOIN material_rede mr ON mr.id = s.materialRedeId
"""

@Dao
interface ServicoDao {

    // ---------- Leitura ----------

    @Query("$SELECT_LINHA ORDER BY s.inicioUtcEpochMs DESC")
    fun observarLinhas(): Flow<List<LinhaServico>>

    @Query("$SELECT_LINHA ORDER BY s.inicioUtcEpochMs DESC")
    suspend fun listarLinhas(): List<LinhaServico>

    @Query("$SELECT_LINHA WHERE s.id = :id")
    fun observarLinha(id: String): Flow<LinhaServico?>

    @Query("$SELECT_LINHA WHERE s.id = :id")
    suspend fun linha(id: String): LinhaServico?

    @Query("$SELECT_LINHA WHERE s.status = 'EM_ANDAMENTO' AND s.deletedAt IS NULL ORDER BY s.inicioUtcEpochMs DESC LIMIT 1")
    fun observarAtivo(): Flow<LinhaServico?>

    @Query("$SELECT_LINHA WHERE s.status = 'ENCERRADO_PENDENTE_COMPLEMENTOS' AND s.deletedAt IS NULL ORDER BY s.fimUtcEpochMs DESC")
    fun observarPendentes(): Flow<List<LinhaServico>>

    @Query("SELECT * FROM servico WHERE id = :id")
    suspend fun buscar(id: String): ServicoEntity?

    @Query("SELECT id FROM servico WHERE status = 'EM_ANDAMENTO' AND deletedAt IS NULL LIMIT 1")
    suspend fun idAtivo(): String?

    // ---------- Escrita ----------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserir(servico: ServicoEntity)

    @Update
    suspend fun atualizar(servico: ServicoEntity): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirEvento(evento: EventoServicoEntity)

    /**
     * Marca a rede somente se ainda não marcada e o serviço estiver em andamento.
     * Idempotente: um segundo toque retorna 0 e não sobrescreve o primeiro horário.
     */
    @Query(
        """UPDATE servico SET redeEncontradaUtcEpochMs = :instante, duracaoAteRedeMs = :instante - inicioUtcEpochMs,
           updatedAt = :agora, version = version + 1
           WHERE id = :id AND status = 'EM_ANDAMENTO' AND deletedAt IS NULL
             AND redeEncontradaUtcEpochMs IS NULL AND :instante >= inicioUtcEpochMs""",
    )
    suspend fun marcarRedeSeAusente(id: String, instante: Long, agora: Long): Int

    /** Congela fim e duração; idempotente (só age em EM_ANDAMENTO sem fim). */
    @Query(
        """UPDATE servico SET fimUtcEpochMs = :instante, duracaoTotalMs = :instante - inicioUtcEpochMs,
           status = 'ENCERRADO_PENDENTE_COMPLEMENTOS', baseTempo = :baseTempo,
           requerRevisao = CASE WHEN :revisar THEN 1 ELSE requerRevisao END,
           motivoRevisao = CASE WHEN :revisar THEN :motivo ELSE motivoRevisao END,
           updatedAt = :agora, version = version + 1
           WHERE id = :id AND status = 'EM_ANDAMENTO' AND deletedAt IS NULL AND fimUtcEpochMs IS NULL
             AND :instante >= inicioUtcEpochMs
             AND (redeEncontradaUtcEpochMs IS NULL OR :instante >= redeEncontradaUtcEpochMs)""",
    )
    suspend fun finalizarSeAberto(id: String, instante: Long, revisar: Boolean, motivo: String?, agora: Long, baseTempo: String): Int

    @Query("UPDATE servico SET requerRevisao = 1, motivoRevisao = :motivo, updatedAt = :agora WHERE id = :id")
    suspend fun marcarRevisao(id: String, motivo: String, agora: Long)

    // ---------- Condições ----------

    @Query("DELETE FROM servico_condicao WHERE servicoId = :servicoId")
    suspend fun limparCondicoes(servicoId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirCondicoes(lista: List<ServicoCondicaoEntity>)

    @Query(
        """SELECT sc.servicoId, sc.condicaoId, c.codigo, c.nome, sc.descricao
           FROM servico_condicao sc JOIN condicao_observada c ON c.id = sc.condicaoId
           WHERE sc.servicoId = :servicoId ORDER BY c.ordemExibicao""",
    )
    suspend fun condicoesDe(servicoId: String): List<CondicaoRegistro>

    @Query(
        """SELECT sc.servicoId, sc.condicaoId, c.codigo, c.nome, sc.descricao
           FROM servico_condicao sc JOIN condicao_observada c ON c.id = sc.condicaoId
           WHERE sc.servicoId = :servicoId ORDER BY c.ordemExibicao""",
    )
    fun observarCondicoesDe(servicoId: String): Flow<List<CondicaoRegistro>>

    @Query(
        """SELECT sc.servicoId, sc.condicaoId, c.codigo, c.nome, sc.descricao
           FROM servico_condicao sc JOIN condicao_observada c ON c.id = sc.condicaoId
           ORDER BY sc.servicoId, c.ordemExibicao""",
    )
    suspend fun todasCondicoes(): List<CondicaoRegistro>

    // ---------- Eventos ----------

    @Query("SELECT * FROM evento_servico WHERE servicoId = :servicoId ORDER BY createdAt, timestampUtcEpochMs")
    fun observarEventos(servicoId: String): Flow<List<EventoServicoEntity>>

    @Query("SELECT * FROM evento_servico WHERE servicoId = :servicoId ORDER BY createdAt, timestampUtcEpochMs")
    suspend fun eventos(servicoId: String): List<EventoServicoEntity>
}

@Dao
interface LocalDao {
    @Query("SELECT * FROM municipio WHERE nomeNormalizado = :nomeNormalizado AND uf = :uf LIMIT 1")
    suspend fun municipioPorNome(nomeNormalizado: String, uf: String): MunicipioEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirMunicipio(m: MunicipioEntity)

    @Query("SELECT * FROM municipio ORDER BY nome")
    fun observarMunicipios(): Flow<List<MunicipioEntity>>

    @Query("SELECT * FROM bairro WHERE municipioId = :municipioId AND nomeNormalizado = :nomeNormalizado LIMIT 1")
    suspend fun bairroPorNome(municipioId: String, nomeNormalizado: String): BairroEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirBairro(b: BairroEntity)

    @Query("SELECT * FROM bairro ORDER BY nomeOriginal")
    fun observarBairros(): Flow<List<BairroEntity>>

    @Query("SELECT * FROM bairro WHERE id = :id")
    suspend fun bairro(id: String): BairroEntity?

    @Query("SELECT * FROM bairro WHERE municipioId = :municipioId AND ativo = 1 ORDER BY nomeNormalizado")
    fun observarBairrosDe(municipioId: String): Flow<List<BairroEntity>>

    @Query("UPDATE bairro SET verificadoEm = :quando WHERE id = :id")
    suspend fun marcarBairroVerificado(id: String, quando: Long)

    // ---------- Logradouros ----------

    @Query("SELECT * FROM logradouro WHERE nomeNormalizado = :nomeNormalizado")
    suspend fun logradourosPorNome(nomeNormalizado: String): List<LogradouroEntity>

    @Query("SELECT * FROM logradouro WHERE id = :id")
    suspend fun logradouro(id: String): LogradouroEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirLogradouro(l: LogradouroEntity)

    @Update
    suspend fun atualizarLogradouro(l: LogradouroEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun vincularLogradouroBairro(v: LogradouroBairroEntity): Long

    @Query("SELECT * FROM logradouro WHERE ativo = 1 ORDER BY nomeNormalizado")
    fun observarLogradouros(): Flow<List<LogradouroEntity>>

    @Query("SELECT * FROM logradouro_bairro")
    fun observarVinculos(): Flow<List<LogradouroBairroEntity>>

    @Query("SELECT COUNT(*) FROM logradouro")
    suspend fun contarLogradouros(): Int
}

@Dao
interface LigacaoDao {
    @Query(
        """SELECT id, servicoId, ordem, numeroTexto, bairroTexto, logradouroTexto, complemento, identificadorLigacao, lado,
           comprimentoCm, observacoes, pendenteIdentificacao, legado FROM ligacao_trocada WHERE servicoId = :servicoId ORDER BY ordem""",
    )
    fun observarDe(servicoId: String): Flow<List<LigacaoTrocada>>

    @Query(
        """SELECT id, servicoId, ordem, numeroTexto, bairroTexto, logradouroTexto, complemento, identificadorLigacao, lado,
           comprimentoCm, observacoes, pendenteIdentificacao, legado FROM ligacao_trocada WHERE servicoId = :servicoId ORDER BY ordem""",
    )
    suspend fun de(servicoId: String): List<LigacaoTrocada>

    @Query(
        """SELECT id, servicoId, ordem, numeroTexto, bairroTexto, logradouroTexto, complemento, identificadorLigacao, lado,
           comprimentoCm, observacoes, pendenteIdentificacao, legado FROM ligacao_trocada ORDER BY servicoId, ordem""",
    )
    fun observarTodas(): Flow<List<LigacaoTrocada>>

    @Query(
        """SELECT id, servicoId, ordem, numeroTexto, bairroTexto, logradouroTexto, complemento, identificadorLigacao, lado,
           comprimentoCm, observacoes, pendenteIdentificacao, legado FROM ligacao_trocada ORDER BY servicoId, ordem""",
    )
    suspend fun todas(): List<LigacaoTrocada>

    @Query("SELECT * FROM ligacao_trocada WHERE servicoId = :servicoId ORDER BY ordem")
    suspend fun entidadesDe(servicoId: String): List<LigacaoTrocadaEntity>

    @Query("DELETE FROM ligacao_trocada WHERE servicoId = :servicoId")
    suspend fun limpar(servicoId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserir(lista: List<LigacaoTrocadaEntity>)
}

@Dao
interface CatalogoDao {
    @Query("SELECT * FROM tipo_servico ORDER BY ordemExibicao, nome")
    fun observarTipos(): Flow<List<TipoServicoEntity>>

    @Query("SELECT * FROM tipo_servico WHERE id = :id")
    suspend fun tipo(id: String): TipoServicoEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirTipo(t: TipoServicoEntity)

    @Update
    suspend fun atualizarTipo(t: TipoServicoEntity)

    @Query("SELECT COALESCE(MAX(ordemExibicao), 0) FROM tipo_servico")
    suspend fun maxOrdemTipo(): Int

    @Query("SELECT * FROM material_rede ORDER BY ordemExibicao, nome")
    fun observarMateriais(): Flow<List<MaterialRedeEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirMaterial(m: MaterialRedeEntity)

    @Update
    suspend fun atualizarMaterial(m: MaterialRedeEntity)

    @Query("SELECT COALESCE(MAX(ordemExibicao), 0) FROM material_rede")
    suspend fun maxOrdemMaterial(): Int

    @Query("SELECT * FROM condicao_observada ORDER BY ordemExibicao, nome")
    fun observarCondicoes(): Flow<List<CondicaoObservadaEntity>>

    @Query("SELECT * FROM condicao_observada")
    suspend fun condicoes(): List<CondicaoObservadaEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirCondicao(c: CondicaoObservadaEntity)

    @Update
    suspend fun atualizarCondicao(c: CondicaoObservadaEntity)

    @Query("SELECT COALESCE(MAX(ordemExibicao), 0) FROM condicao_observada")
    suspend fun maxOrdemCondicao(): Int
}

private const val SELECT_PARTICIPANTE = """
    SELECT p.servicoId, p.funcionarioId, f.nomeExibicao AS nomeAtual, f.apelidoOuCodigo, f.ativo AS funcionarioAtivo,
           p.nomeSnapshot, p.funcaoNoServico, p.funcaoDescricao, p.nota
    FROM participacao_servico p JOIN funcionario f ON f.id = p.funcionarioId
"""

@Dao
interface FuncionarioDao {
    @Query("SELECT * FROM funcionario ORDER BY nomeNormalizado, id")
    fun observarTodos(): Flow<List<FuncionarioEntity>>

    @Query("SELECT * FROM funcionario WHERE id = :id")
    suspend fun buscar(id: String): FuncionarioEntity?

    @Query("SELECT * FROM funcionario WHERE nomeNormalizado = :nomeNormalizado")
    suspend fun porNomeNormalizado(nomeNormalizado: String): List<FuncionarioEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserir(f: FuncionarioEntity)

    @Update
    suspend fun atualizar(f: FuncionarioEntity): Int

    @Query("SELECT COUNT(*) FROM participacao_servico WHERE funcionarioId = :id")
    suspend fun contarParticipacoes(id: String): Int

    // ---------- Participações ----------

    @Query("SELECT * FROM participacao_servico WHERE servicoId = :servicoId")
    suspend fun participacoesEntidade(servicoId: String): List<ParticipacaoServicoEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun inserirParticipacoes(lista: List<ParticipacaoServicoEntity>)

    @Update
    suspend fun atualizarParticipacao(p: ParticipacaoServicoEntity): Int

    @Query("DELETE FROM participacao_servico WHERE servicoId = :servicoId AND funcionarioId = :funcionarioId")
    suspend fun removerParticipacao(servicoId: String, funcionarioId: String): Int

    @Query("$SELECT_PARTICIPANTE WHERE p.servicoId = :servicoId ORDER BY p.adicionadoEm, f.nomeNormalizado")
    fun observarParticipantesDe(servicoId: String): Flow<List<ParticipanteServico>>

    @Query("$SELECT_PARTICIPANTE WHERE p.servicoId = :servicoId ORDER BY p.adicionadoEm, f.nomeNormalizado")
    suspend fun participantesDe(servicoId: String): List<ParticipanteServico>

    @Query("$SELECT_PARTICIPANTE ORDER BY p.servicoId, p.adicionadoEm, f.nomeNormalizado")
    fun observarTodosParticipantes(): Flow<List<ParticipanteServico>>

    @Query("$SELECT_PARTICIPANTE ORDER BY p.servicoId, p.adicionadoEm, f.nomeNormalizado")
    suspend fun todosParticipantes(): List<ParticipanteServico>

    /** Último serviço (por início) não excluído que tem participantes, exceto [exceto]. */
    @Query(
        """SELECT s.id FROM servico s WHERE s.deletedAt IS NULL AND s.id <> :exceto
           AND EXISTS (SELECT 1 FROM participacao_servico p WHERE p.servicoId = s.id)
           ORDER BY s.inicioUtcEpochMs DESC LIMIT 1""",
    )
    suspend fun ultimoServicoComParticipantes(exceto: String): String?
}
