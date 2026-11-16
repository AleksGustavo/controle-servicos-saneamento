package br.controleservicos.app.core.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.database.BairroEntity
import br.controleservicos.app.core.database.CondicaoObservadaEntity
import br.controleservicos.app.core.database.EventoServicoEntity
import br.controleservicos.app.core.database.FuncionarioEntity
import br.controleservicos.app.core.database.LigacaoTrocadaEntity
import br.controleservicos.app.core.database.LogradouroBairroEntity
import br.controleservicos.app.core.database.LogradouroEntity
import br.controleservicos.app.core.domain.EnderecoExterno
import br.controleservicos.app.core.domain.Enderecos
import br.controleservicos.app.core.domain.LigacaoValidada
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.OrigemEndereco
import br.controleservicos.app.core.database.ParticipacaoServicoEntity
import br.controleservicos.app.core.database.MaterialRedeEntity
import br.controleservicos.app.core.database.MunicipioEntity
import br.controleservicos.app.core.database.ServicoCondicaoEntity
import br.controleservicos.app.core.database.ServicoEntity
import br.controleservicos.app.core.database.TipoServicoEntity
import br.controleservicos.app.core.domain.JsonSimples
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.ResolvedorRelogio
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.model.TipoEvento
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** Fonte de instantes; abstraída para testes. */
fun interface Relogio {
    fun agora(): Marcacao
}

/** Participante escolhido para um serviço (função exercida NAQUELE serviço). */
data class ParticipanteNovo(
    val funcionarioId: String,
    val funcao: FuncaoEquipe,
    val funcaoDescricao: String? = null,
    val nota: String? = null,
)

/** Dados de um novo serviço. Município/UF são sempre Leme/SP (LocalOperacional), sem escolha. */
data class NovoServico(
    val tipoServicoId: String,
    val bairroNome: String,
    val logradouro: String,
    val numero: String,
    val complemento: String?,
    val zoneId: String = LocalOperacional.TIMEZONE,
    val participantes: List<ParticipanteNovo> = emptyList(),
    /** Campo livre legado (v1); mantido apenas por compatibilidade, não exibido em novos cadastros. */
    val equipeIdentificador: String? = null,
    /** Logradouro escolhido no catálogo; null = digitado (vira cadastro manual no catálogo). */
    val logradouroId: String? = null,
    val cep: String? = null,
)

data class DadosFuncionario(
    val nomeExibicao: String,
    val nomeCompleto: String? = null,
    val apelidoOuCodigo: String? = null,
    val funcaoHabitual: FuncaoEquipe? = null,
)

/** Instantâneo consistente para exportação. */
data class InstantaneoExportacao(
    val linhas: List<LinhaServico>,
    val condicoes: Map<String, List<CondicaoRegistro>>,
    val participantes: Map<String, List<ParticipanteServico>>,
    val ligacoes: Map<String, List<LigacaoTrocada>> = emptyMap(),
)

/** Prévia de importação de lista de endereços (CSV autorizado). */
data class PreviaImportacao(
    val linhasValidas: Int,
    val linhasInvalidas: Int,
    val bairrosNovos: Int,
    val logradourosNovos: Int,
    val jaExistentes: Int,
)

data class Complementos(
    val situacaoRede: SituacaoRede,
    val diametroMm: Int?,
    val materialRedeId: String?,
    val profundidadeMetros: Double?,
    val quantidadeLigacoes: Int?,
    /** condicaoId → descrição opcional */
    val condicoes: Map<String, String?>,
    val observacoes: String?,
    /** Houve troca de ligação/ramal de água. Se false, nenhuma ligação é gravada. */
    val ramalAguaTrocado: Boolean = false,
    /** Um item por imóvel atendido (já validado por Ligacoes.validar). */
    val ligacoes: List<LigacaoValidada> = emptyList(),
    /**
     * Exceção explícita: salvar sem nenhum participante (ex.: registro histórico sem identidade
     * verificável). O serviço fica fora das estatísticas de equipe; nenhum integrante é inventado.
     */
    val permitirSemParticipantes: Boolean = false,
)

class ServicoAtivoExistenteException(val idAtivo: String?) : IllegalStateException("Já existe um serviço em andamento.")
class OperacaoInvalidaException(msg: String) : IllegalStateException(msg)

sealed interface ResultadoMarco {
    data class Registrado(val instanteMs: Long, val avisoRevisao: String?) : ResultadoMarco
    data class JaRegistrado(val instanteMs: Long?) : ResultadoMarco
}

class ServicoRepository(
    private val db: AppDatabase,
    private val relogio: Relogio,
) {
    private val servicoDao = db.servicoDao()
    private val localDao = db.localDao()
    private val catalogoDao = db.catalogoDao()
    private val funcionarioDao = db.funcionarioDao()
    private val ligacaoDao = db.ligacaoDao()

    private fun uuid() = UUID.randomUUID().toString()

    // ================= Observação =================

    fun observarLinhas(): Flow<List<LinhaServico>> = servicoDao.observarLinhas()
    fun observarLinha(id: String) = servicoDao.observarLinha(id)
    fun observarAtivo() = servicoDao.observarAtivo()
    fun observarPendentes() = servicoDao.observarPendentes()
    fun observarCondicoesDe(id: String) = servicoDao.observarCondicoesDe(id)
    fun observarEventos(id: String) = servicoDao.observarEventos(id)
    fun observarTipos() = catalogoDao.observarTipos()
    fun observarMateriais() = catalogoDao.observarMateriais()
    fun observarCondicoes() = catalogoDao.observarCondicoes()
    fun observarMunicipios() = localDao.observarMunicipios()
    fun observarBairros() = localDao.observarBairros()
    fun observarFuncionarios() = funcionarioDao.observarTodos()
    fun observarLigacoesDe(id: String) = ligacaoDao.observarDe(id)
    fun observarLigacoes(): Flow<Map<String, List<LigacaoTrocada>>> = ligacaoDao.observarTodas().map { l -> l.groupBy { it.servicoId } }
    suspend fun ligacoesDe(id: String) = ligacaoDao.de(id)
    fun observarLogradouros() = localDao.observarLogradouros()
    fun observarVinculosLogradouro() = localDao.observarVinculos()
    fun observarParticipantesDe(id: String) = funcionarioDao.observarParticipantesDe(id)

    /** Todas as participações agrupadas por serviço. */
    fun observarParticipacoes(): Flow<Map<String, List<ParticipanteServico>>> =
        funcionarioDao.observarTodosParticipantes().map { l -> l.groupBy { it.servicoId } }

    suspend fun condicoesDe(id: String) = servicoDao.condicoesDe(id)
    suspend fun participantesDe(id: String) = funcionarioDao.participantesDe(id)

    /** Instantâneo consistente (em transação) para exportação. */
    suspend fun instantaneoExportacao(): InstantaneoExportacao =
        db.withTransaction {
            InstantaneoExportacao(
                servicoDao.listarLinhas(),
                servicoDao.todasCondicoes().groupBy { it.servicoId },
                funcionarioDao.todosParticipantes().groupBy { it.servicoId },
                ligacaoDao.todas().groupBy { it.servicoId },
            )
        }

    // ================= Fluxo de campo =================

    /**
     * Cria o serviço e registra o evento INICIO na mesma transação.
     * Garante um único serviço ativo (checagem + gatilho no banco).
     */
    suspend fun iniciarServico(dados: NovoServico, marcacao: Marcacao): String {
        val id = uuid()
        try {
            db.withTransaction {
                servicoDao.idAtivo()?.let { throw ServicoAtivoExistenteException(it) }
                catalogoDao.tipo(dados.tipoServicoId)?.takeIf { it.ativo }
                    ?: throw OperacaoInvalidaException("Tipo de serviço inválido ou inativo.")
                validarParticipantes(dados.participantes, exigirAtivos = true)
                val municipio = obterOuCriarMunicipio(LocalOperacional.MUNICIPIO, LocalOperacional.UF, marcacao.wallClockMs)
                val bairro = obterOuCriarBairro(municipio.id, dados.bairroNome, marcacao.wallClockMs)
                val logradouro = if (dados.logradouroId != null) {
                    val l = localDao.logradouro(dados.logradouroId) ?: throw OperacaoInvalidaException("Logradouro não encontrado no catálogo.")
                    localDao.vincularLogradouroBairro(LogradouroBairroEntity(l.id, bairro.id, OrigemEndereco.MANUAL, marcacao.wallClockMs))
                    l
                } else {
                    registrarLogradouroInterno(dados.logradouro, bairro.id, OrigemEndereco.MANUAL, Enderecos.normalizarCep(dados.cep), null, marcacao.wallClockMs)
                }
                servicoDao.inserir(
                    ServicoEntity(
                        id = id,
                        tipoServicoId = dados.tipoServicoId,
                        municipioId = municipio.id,
                        bairroId = bairro.id,
                        logradouro = Texto.limparNome(dados.logradouro),
                        numero = Texto.limparNome(dados.numero).uppercase().let { if (it == "SN" || it == "S/N") "S/N" else it },
                        complemento = dados.complemento?.let(Texto::limparNome)?.ifBlank { null },
                        equipeIdentificador = dados.equipeIdentificador?.let(Texto::limparNome)?.ifBlank { null },
                        logradouroId = logradouro.id,
                        cep = Enderecos.normalizarCep(dados.cep) ?: logradouro.cep,
                        origemEndereco = if (dados.logradouroId != null) logradouro.origem else OrigemEndereco.MANUAL,
                        status = StatusServico.EM_ANDAMENTO,
                        inicioUtcEpochMs = marcacao.wallClockMs,
                        inicioElapsedRealtimeMs = marcacao.elapsedRealtimeMs,
                        inicioBootCount = marcacao.bootCount,
                        inicioZoneId = dados.zoneId,
                        redeEncontradaUtcEpochMs = null,
                        fimUtcEpochMs = null,
                        duracaoTotalMs = null,
                        duracaoAteRedeMs = null,
                        situacaoRede = null,
                        diametroMm = null,
                        materialRedeId = null,
                        profundidadeMetros = null,
                        quantidadeLigacoes = null,
                        observacoes = null,
                        requerRevisao = false,
                        motivoRevisao = null,
                        createdAt = marcacao.wallClockMs,
                        updatedAt = marcacao.wallClockMs,
                        deletedAt = null,
                        version = 1,
                    ),
                )
                servicoDao.inserirEvento(evento(id, TipoEvento.INICIO, marcacao.wallClockMs, marcacao))
                if (dados.participantes.isNotEmpty()) {
                    funcionarioDao.inserirParticipacoes(dados.participantes.map { participacao(id, it, marcacao.wallClockMs) })
                }
            }
        } catch (e: SQLiteConstraintException) {
            if (e.message?.contains("servico_ativo_existente") == true) throw ServicoAtivoExistenteException(servicoDao.idAtivo())
            throw e
        }
        return id
    }

    private suspend fun obterOuCriarMunicipio(nome: String, uf: String, agora: Long): MunicipioEntity {
        val limpo = Texto.limparNome(nome)
        require(limpo.isNotEmpty()) { "Município obrigatório" }
        val ufNorm = uf.trim().uppercase()
        val norm = Texto.normalizarNome(limpo)
        localDao.municipioPorNome(norm, ufNorm)?.let { return it }
        val m = MunicipioEntity(uuid(), limpo, norm, ufNorm, agora)
        localDao.inserirMunicipio(m)
        return m
    }

    private suspend fun obterOuCriarBairro(municipioId: String, nome: String, agora: Long): BairroEntity {
        val limpo = Texto.limparNome(nome)
        require(limpo.isNotEmpty()) { "Bairro obrigatório" }
        val norm = Texto.normalizarNome(limpo)
        localDao.bairroPorNome(municipioId, norm)?.let { return it }
        val b = BairroEntity(uuid(), municipioId, limpo, norm, agora)
        localDao.inserirBairro(b)
        return b
    }

    private fun inicioDe(s: ServicoEntity) = Marcacao(s.inicioUtcEpochMs, s.inicioElapsedRealtimeMs, s.inicioBootCount)

    /** Marca "rede encontrada" sem pausar a contagem. Idempotente contra toques repetidos. */
    suspend fun marcarRede(id: String, marcacao: Marcacao): ResultadoMarco = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.redeEncontradaUtcEpochMs != null) return@withTransaction ResultadoMarco.JaRegistrado(s.redeEncontradaUtcEpochMs)
        if (s.status != StatusServico.EM_ANDAMENTO || s.deletedAt != null) {
            throw OperacaoInvalidaException("O serviço não está em andamento.")
        }
        val r = ResolvedorRelogio.resolver(inicioDe(s), marcacao)
        val alterados = servicoDao.marcarRedeSeAusente(id, r.instanteMs, marcacao.wallClockMs)
        if (alterados == 0) return@withTransaction ResultadoMarco.JaRegistrado(servicoDao.buscar(id)?.redeEncontradaUtcEpochMs)
        servicoDao.inserirEvento(evento(id, TipoEvento.REDE_ENCONTRADA, r.instanteMs, marcacao, motivo = r.motivo))
        if (r.requerRevisao) servicoDao.marcarRevisao(id, r.motivo ?: "Revisar horários", marcacao.wallClockMs)
        ResultadoMarco.Registrado(r.instanteMs, if (r.requerRevisao) r.motivo else null)
    }

    /** Congela o fim e a duração total imediatamente. Idempotente. */
    suspend fun finalizar(id: String, marcacao: Marcacao): ResultadoMarco = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.fimUtcEpochMs != null) return@withTransaction ResultadoMarco.JaRegistrado(s.fimUtcEpochMs)
        if (s.status != StatusServico.EM_ANDAMENTO || s.deletedAt != null) {
            throw OperacaoInvalidaException("O serviço não está em andamento.")
        }
        val r = ResolvedorRelogio.resolver(inicioDe(s), marcacao)
        var instante = r.instanteMs
        var revisar = r.requerRevisao
        var motivo = r.motivo
        val rede = s.redeEncontradaUtcEpochMs
        if (rede != null && instante < rede) {
            instante = rede
            revisar = true
            motivo = "Hora do aparelho anterior à marcação da rede. Fim ajustado; corrija manualmente."
        }
        val alterados = servicoDao.finalizarSeAberto(id, instante, revisar, if (revisar) motivo else null, marcacao.wallClockMs, r.base.name)
        if (alterados == 0) return@withTransaction ResultadoMarco.JaRegistrado(servicoDao.buscar(id)?.fimUtcEpochMs)
        servicoDao.inserirEvento(evento(id, TipoEvento.FIM, instante, marcacao, motivo = motivo))
        ResultadoMarco.Registrado(instante, if (revisar) motivo else null)
    }

    // ================= Complementos e correções =================

    suspend fun salvarComplementos(id: String, c: Complementos) = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.fimUtcEpochMs == null || s.status == StatusServico.EM_ANDAMENTO) {
            throw OperacaoInvalidaException("Finalize o serviço antes de complementar.")
        }
        if (s.status == StatusServico.CANCELADO) throw OperacaoInvalidaException("Serviço cancelado não pode ser complementado.")
        if (!c.permitirSemParticipantes && funcionarioDao.participacoesEntidade(id).isEmpty()) {
            throw OperacaoInvalidaException("Informe ao menos um participante (ou confirme salvar sem equipe registrada).")
        }
        if (c.ramalAguaTrocado) {
            if (c.ligacoes.isEmpty()) throw OperacaoInvalidaException("Informe ao menos uma ligação trocada (imóvel atendido).")
            c.ligacoes.forEach { l ->
                if (l.comprimentoCm <= 0) throw OperacaoInvalidaException("Comprimento da ligação deve ser maior que zero.")
                if (l.numero.isNullOrBlank() && !l.legado) throw OperacaoInvalidaException("Informe o número de cada imóvel atendido.")
            }
        }
        val ligacoesAntes = ligacaoDao.de(id)
        val situacao = when {
            s.redeEncontradaUtcEpochMs != null -> SituacaoRede.MARCADA
            c.situacaoRede == SituacaoRede.MARCADA -> SituacaoRede.NAO_MARCADA // sem horário: nunca inventar
            else -> c.situacaoRede
        }
        val obs = c.observacoes?.trim()?.take(RegrasNegocio.LIMITE_OBSERVACOES)?.ifBlank { null }
        val agora = relogio.agora().wallClockMs
        val anteriores = servicoDao.condicoesDe(id)
        val novo = s.copy(
            situacaoRede = situacao,
            diametroMm = c.diametroMm?.takeIf { it > 0 },
            materialRedeId = c.materialRedeId,
            profundidadeMetros = c.profundidadeMetros?.takeIf { it > 0 },
            quantidadeLigacoes = c.quantidadeLigacoes?.takeIf { it >= 0 },
            observacoes = obs,
            // v3: os dados de cada troca ficam em ligacao_trocada. As colunas de ramal único (v2) só
            // são preservadas em registros legados com troca; "Não" sempre as limpa.
            ramalAguaTrocado = c.ramalAguaTrocado,
            ladoRamalTrocado = if (c.ramalAguaTrocado) s.ladoRamalTrocado else null,
            comprimentoRamalCm = if (c.ramalAguaTrocado) s.comprimentoRamalCm else null,
            status = StatusServico.CONCLUIDO,
            updatedAt = agora,
            version = s.version + 1,
        )
        servicoDao.atualizar(novo)
        ligacaoDao.limpar(id)
        if (c.ramalAguaTrocado) {
            ligacaoDao.inserir(
                c.ligacoes.mapIndexed { i, l ->
                    LigacaoTrocadaEntity(
                        id = uuid(), servicoId = id, ordem = i + 1, numeroTexto = l.numero, bairroTexto = l.bairro,
                        logradouroTexto = l.rua, complemento = l.complemento, identificadorLigacao = l.identificador,
                        lado = l.lado, comprimentoCm = l.comprimentoCm, observacoes = l.observacoes,
                        pendenteIdentificacao = l.numero.isNullOrBlank(), legado = l.legado, createdAt = agora, updatedAt = agora,
                    )
                },
            )
        }
        val ligacoesDepois = ligacaoDao.de(id)
        servicoDao.limparCondicoes(id)
        servicoDao.inserirCondicoes(
            c.condicoes.map { (condId, desc) ->
                ServicoCondicaoEntity(id, condId, desc?.trim()?.take(RegrasNegocio.LIMITE_DESCRICAO_CONDICAO)?.ifBlank { null })
            },
        )
        val edicao = s.status == StatusServico.CONCLUIDO
        servicoDao.inserirEvento(
            EventoServicoEntity(
                id = uuid(), servicoId = id,
                tipoEvento = if (edicao) TipoEvento.CORRECAO else TipoEvento.COMPLEMENTO,
                timestampUtcEpochMs = agora, wallClockBrutoMs = agora, timestampMonotonicMs = null, bootCount = null,
                dadosAnteriores = if (edicao) jsonComplementos(s, anteriores.associate { it.condicaoId to it.descricao }, ligacoesAntes) else null,
                dadosNovos = jsonComplementos(novo, c.condicoes, ligacoesDepois),
                motivo = if (edicao) "Edição de complementos" else null,
                createdAt = agora,
            ),
        )
    }

    private fun jsonComplementos(s: ServicoEntity, cond: Map<String, String?>, ligacoes: List<LigacaoTrocada>) = JsonSimples.objeto(
        mapOf(
            "situacaoRede" to s.situacaoRede?.name, "diametroMm" to s.diametroMm, "materialRedeId" to s.materialRedeId,
            "profundidadeMetros" to s.profundidadeMetros, "quantidadeLigacoes" to s.quantidadeLigacoes,
            "condicoes" to cond.entries.map { "${it.key}${it.value?.let { d -> ": $d" } ?: ""}" }, "observacoes" to s.observacoes,
            "ramalAguaTrocado" to s.ramalAguaTrocado, "ladoRamalTrocado" to s.ladoRamalTrocado?.name, "comprimentoRamalCm" to s.comprimentoRamalCm,
            "ligacoes" to ligacoes.map { "${it.ordem}:${it.numeroTexto ?: "pendente"}/${it.lado.name}/${it.comprimentoCm}cm${it.logradouroTexto?.let { r -> "/$r" } ?: ""}${it.identificadorLigacao?.let { d -> "/$d" } ?: ""}" },
        ),
    )

    /**
     * Correção auditável de horários com justificativa. Valores anteriores ficam no evento CORRECAO.
     * Limpa a marcação de revisão.
     */
    suspend fun corrigirHorarios(id: String, inicio: Long, rede: Long?, fim: Long, justificativa: String) = db.withTransaction {
        val just = justificativa.trim()
        if (just.length < RegrasNegocio.TAMANHO_MINIMO_JUSTIFICATIVA) throw OperacaoInvalidaException("Informe uma justificativa.")
        Tempo.validarOrdem(inicio, rede, fim)?.let { throw OperacaoInvalidaException(it) }
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.status == StatusServico.EM_ANDAMENTO) throw OperacaoInvalidaException("Finalize o serviço antes de corrigir horários.")
        val agora = relogio.agora().wallClockMs
        val novo = s.copy(
            inicioUtcEpochMs = inicio,
            redeEncontradaUtcEpochMs = rede,
            fimUtcEpochMs = fim,
            duracaoTotalMs = Tempo.medirDuracaoTotal(inicio, fim),
            duracaoAteRedeMs = Tempo.medirTempoAteRede(inicio, rede, fim),
            situacaoRede = when {
                rede != null -> SituacaoRede.MARCADA
                s.situacaoRede == SituacaoRede.MARCADA -> SituacaoRede.NAO_MARCADA
                else -> s.situacaoRede
            },
            // Base monotônica deixa de valer após correção manual.
            inicioElapsedRealtimeMs = if (inicio == s.inicioUtcEpochMs) s.inicioElapsedRealtimeMs else null,
            requerRevisao = false,
            motivoRevisao = null,
            updatedAt = agora,
            version = s.version + 1,
        )
        servicoDao.atualizar(novo)
        servicoDao.inserirEvento(
            EventoServicoEntity(
                id = uuid(), servicoId = id, tipoEvento = TipoEvento.CORRECAO,
                timestampUtcEpochMs = agora, wallClockBrutoMs = agora, timestampMonotonicMs = null, bootCount = null,
                dadosAnteriores = JsonSimples.objeto(mapOf("inicio" to Tempo.utcIso(s.inicioUtcEpochMs), "rede" to Tempo.utcIso(s.redeEncontradaUtcEpochMs), "fim" to Tempo.utcIso(s.fimUtcEpochMs))),
                dadosNovos = JsonSimples.objeto(mapOf("inicio" to Tempo.utcIso(inicio), "rede" to Tempo.utcIso(rede), "fim" to Tempo.utcIso(fim))),
                motivo = just,
                createdAt = agora,
            ),
        )
    }

    suspend fun cancelar(id: String, motivo: String?) = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.status == StatusServico.CANCELADO) return@withTransaction
        val agora = relogio.agora().wallClockMs
        servicoDao.atualizar(s.copy(status = StatusServico.CANCELADO, updatedAt = agora, version = s.version + 1))
        servicoDao.inserirEvento(
            EventoServicoEntity(uuid(), id, TipoEvento.CANCELAMENTO, agora, agora, null, null, s.status.name, StatusServico.CANCELADO.name, motivo?.trim()?.ifBlank { null }, agora),
        )
    }

    /** Exclusão lógica (recuperável). Some de estatísticas e exportações padrão. */
    suspend fun excluir(id: String) = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.deletedAt != null) return@withTransaction
        if (s.status == StatusServico.EM_ANDAMENTO) throw OperacaoInvalidaException("Cancele ou finalize o serviço em andamento antes de excluir.")
        val agora = relogio.agora().wallClockMs
        servicoDao.atualizar(s.copy(deletedAt = agora, updatedAt = agora, version = s.version + 1))
        servicoDao.inserirEvento(EventoServicoEntity(uuid(), id, TipoEvento.EXCLUSAO, agora, agora, null, null, null, null, null, agora))
    }

    suspend fun restaurar(id: String) = db.withTransaction {
        val s = servicoDao.buscar(id) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.deletedAt == null) return@withTransaction
        val agora = relogio.agora().wallClockMs
        servicoDao.atualizar(s.copy(deletedAt = null, updatedAt = agora, version = s.version + 1))
        servicoDao.inserirEvento(EventoServicoEntity(uuid(), id, TipoEvento.RESTAURACAO, agora, agora, null, null, null, null, null, agora))
    }

    private fun evento(id: String, tipo: TipoEvento, instante: Long, m: Marcacao, motivo: String? = null) = EventoServicoEntity(
        id = uuid(), servicoId = id, tipoEvento = tipo, timestampUtcEpochMs = instante,
        wallClockBrutoMs = m.wallClockMs, timestampMonotonicMs = m.elapsedRealtimeMs, bootCount = m.bootCount,
        dadosAnteriores = null, dadosNovos = null, motivo = motivo, createdAt = m.wallClockMs,
    )

    // ================= Participantes =================

    private suspend fun validarParticipantes(lista: List<ParticipanteNovo>, exigirAtivos: Boolean) {
        val ids = lista.map { it.funcionarioId }
        if (ids.size != ids.toSet().size) throw OperacaoInvalidaException("O mesmo funcionário não pode participar duas vezes do mesmo serviço.")
        for (p in lista) {
            val f = funcionarioDao.buscar(p.funcionarioId) ?: throw OperacaoInvalidaException("Funcionário não encontrado.")
            if (exigirAtivos && !f.ativo) throw OperacaoInvalidaException("${f.nomeExibicao} está inativo. Reative o cadastro para selecioná-lo.")
        }
    }

    private suspend fun participacao(servicoId: String, p: ParticipanteNovo, agora: Long): ParticipacaoServicoEntity {
        val f = funcionarioDao.buscar(p.funcionarioId) ?: throw OperacaoInvalidaException("Funcionário não encontrado.")
        val descricao = p.funcaoDescricao?.let(Texto::limparNome)?.take(RegrasNegocio.LIMITE_NOTA_PARTICIPANTE)?.ifBlank { null }
            ?.takeIf { p.funcao == FuncaoEquipe.OUTRA }
        return ParticipacaoServicoEntity(
            servicoId = servicoId,
            funcionarioId = f.id,
            funcaoNoServico = p.funcao,
            funcaoDescricao = descricao,
            nota = p.nota?.trim()?.take(RegrasNegocio.LIMITE_NOTA_PARTICIPANTE)?.ifBlank { null },
            nomeSnapshot = f.nomeExibicao,
            funcaoSnapshot = if (descricao != null) "${p.funcao.rotulo}: $descricao" else p.funcao.rotulo,
            adicionadoEm = agora,
            atualizadoEm = agora,
        )
    }

    private fun jsonParticipantes(l: List<ParticipacaoServicoEntity>) =
        l.sortedBy { it.funcionarioId }.map { "${it.funcionarioId}=${it.nomeSnapshot}/${it.funcaoSnapshot}${it.nota?.let { n -> " ($n)" } ?: ""}" }

    /**
     * Substitui a composição de um serviço (ajuste durante a execução ou na finalização).
     * Auditável: grava evento PARTICIPANTES com antes/depois. Participações mantidas preservam
     * a data de inclusão original; nomes são refotografados somente para quem foi incluído agora.
     */
    suspend fun atualizarParticipantes(servicoId: String, lista: List<ParticipanteNovo>, motivo: String? = null) = db.withTransaction {
        val s = servicoDao.buscar(servicoId) ?: throw OperacaoInvalidaException("Serviço não encontrado.")
        if (s.deletedAt != null) throw OperacaoInvalidaException("Restaure o registro antes de alterar participantes.")
        validarParticipantes(lista, exigirAtivos = false)
        val agora = relogio.agora().wallClockMs
        val antes = funcionarioDao.participacoesEntidade(servicoId)
        val antesPorId = antes.associateBy { it.funcionarioId }
        val novosIds = lista.map { it.funcionarioId }.toSet()
        // Pessoas novas precisam estar ativas; pessoas já presentes podem ter sido inativadas depois.
        lista.filter { it.funcionarioId !in antesPorId }.forEach { p ->
            val f = funcionarioDao.buscar(p.funcionarioId)!!
            if (!f.ativo) throw OperacaoInvalidaException("${f.nomeExibicao} está inativo. Reative o cadastro para selecioná-lo.")
        }
        antes.filter { it.funcionarioId !in novosIds }.forEach { funcionarioDao.removerParticipacao(servicoId, it.funcionarioId) }
        val depois = mutableListOf<ParticipacaoServicoEntity>()
        for (p in lista) {
            val nova = participacao(servicoId, p, agora)
            val existente = antesPorId[p.funcionarioId]
            if (existente == null) {
                funcionarioDao.inserirParticipacoes(listOf(nova))
                depois += nova
            } else {
                val atualizada = existente.copy(
                    funcaoNoServico = nova.funcaoNoServico, funcaoDescricao = nova.funcaoDescricao, nota = nova.nota,
                    funcaoSnapshot = nova.funcaoSnapshot,
                    atualizadoEm = if (existente.funcaoSnapshot != nova.funcaoSnapshot || existente.nota != nova.nota) agora else existente.atualizadoEm,
                )
                if (atualizada != existente) funcionarioDao.atualizarParticipacao(atualizada)
                depois += atualizada
            }
        }
        if (jsonParticipantes(antes) != jsonParticipantes(depois)) {
            servicoDao.inserirEvento(
                EventoServicoEntity(
                    id = uuid(), servicoId = servicoId, tipoEvento = TipoEvento.PARTICIPANTES,
                    timestampUtcEpochMs = agora, wallClockBrutoMs = agora, timestampMonotonicMs = null, bootCount = null,
                    dadosAnteriores = JsonSimples.objeto(mapOf("participantes" to jsonParticipantes(antes))),
                    dadosNovos = JsonSimples.objeto(mapOf("participantes" to jsonParticipantes(depois))),
                    motivo = motivo?.trim()?.ifBlank { null },
                    createdAt = agora,
                ),
            )
        }
    }

    /** Participantes do último serviço com equipe (para a ação explícita "Reutilizar"). */
    suspend fun participantesDoUltimoServico(exceto: String? = null): List<ParticipanteServico> {
        val id = funcionarioDao.ultimoServicoComParticipantes(exceto ?: "") ?: return emptyList()
        return funcionarioDao.participantesDe(id)
    }

    // ================= Funcionários =================

    /** Cadastros com o mesmo nome normalizado (para aviso de possível duplicidade). */
    suspend fun possiveisDuplicados(nome: String, excetoId: String? = null): List<FuncionarioEntity> =
        funcionarioDao.porNomeNormalizado(Texto.normalizarNome(nome)).filter { it.id != excetoId }

    private fun limparDados(d: DadosFuncionario): DadosFuncionario {
        val nome = Texto.limparNome(d.nomeExibicao).take(RegrasNegocio.LIMITE_NOME_FUNCIONARIO)
        if (nome.isEmpty()) throw OperacaoInvalidaException("Informe o nome do funcionário.")
        return DadosFuncionario(
            nomeExibicao = nome,
            nomeCompleto = d.nomeCompleto?.let(Texto::limparNome)?.take(120)?.ifBlank { null },
            apelidoOuCodigo = d.apelidoOuCodigo?.let(Texto::limparNome)?.take(40)?.ifBlank { null },
            funcaoHabitual = d.funcaoHabitual,
        )
    }

    /** Cadastra e devolve o id estável (UUID). Homônimos são permitidos (a UI avisa antes). */
    suspend fun adicionarFuncionario(dados: DadosFuncionario): String {
        val d = limparDados(dados)
        val agora = relogio.agora().wallClockMs
        val id = uuid()
        funcionarioDao.inserir(
            FuncionarioEntity(id, d.nomeExibicao, Texto.normalizarNome(d.nomeExibicao), d.nomeCompleto, d.apelidoOuCodigo, d.funcaoHabitual, true, agora, agora),
        )
        return id
    }

    /** Edita o cadastro. A identidade (id) não muda; participações antigas mantêm o nome fotografado. */
    suspend fun atualizarFuncionario(id: String, dados: DadosFuncionario) {
        val d = limparDados(dados)
        val f = funcionarioDao.buscar(id) ?: throw OperacaoInvalidaException("Funcionário não encontrado.")
        funcionarioDao.atualizar(
            f.copy(
                nomeExibicao = d.nomeExibicao, nomeNormalizado = Texto.normalizarNome(d.nomeExibicao), nomeCompleto = d.nomeCompleto,
                apelidoOuCodigo = d.apelidoOuCodigo, funcaoHabitual = d.funcaoHabitual, atualizadoEm = relogio.agora().wallClockMs,
            ),
        )
    }

    /** Inativar (preferível a excluir): some da seleção, permanece nos serviços históricos. */
    suspend fun definirFuncionarioAtivo(id: String, ativo: Boolean) {
        val f = funcionarioDao.buscar(id) ?: throw OperacaoInvalidaException("Funcionário não encontrado.")
        if (f.ativo != ativo) funcionarioDao.atualizar(f.copy(ativo = ativo, atualizadoEm = relogio.agora().wallClockMs))
    }

    suspend fun funcionario(id: String) = funcionarioDao.buscar(id)

    // ================= Catálogo de endereços (Leme/SP) =================

    private suspend fun leme(agora: Long) = obterOuCriarMunicipio(LocalOperacional.MUNICIPIO, LocalOperacional.UF, agora)

    /** Bairros do catálogo de Leme/SP (sem lista inventada: só os conhecidos localmente). */
    fun observarBairrosLeme(): Flow<List<BairroEntity>> =
        localDao.observarMunicipios().map { ms ->
            ms.firstOrNull { it.nomeNormalizado == Texto.normalizarNome(LocalOperacional.MUNICIPIO) && it.uf == LocalOperacional.UF }?.id
        }.let { idFlow ->
            kotlinx.coroutines.flow.combine(idFlow, localDao.observarBairros()) { id, bs -> bs.filter { it.municipioId == id && it.ativo } }
        }

    /** Cadastro manual explícito de bairro (deduplicado por nome normalizado). */
    suspend fun adicionarBairroManual(nome: String): BairroEntity = db.withTransaction {
        val agora = relogio.agora().wallClockMs
        val limpo = Texto.limparNome(nome)
        if (limpo.isEmpty()) throw OperacaoInvalidaException("Informe o nome do bairro.")
        val m = leme(agora)
        localDao.bairroPorNome(m.id, Texto.normalizarNome(limpo))
            ?: BairroEntity(uuid(), m.id, limpo, Texto.normalizarNome(limpo), agora, OrigemEndereco.MANUAL).also { localDao.inserirBairro(it) }
    }

    private suspend fun registrarLogradouroInterno(
        nome: String, bairroId: String?, origem: OrigemEndereco, cep: String?, verificadoEm: Long?, agora: Long,
    ): LogradouroEntity {
        val limpo = Texto.limparNome(nome)
        if (limpo.isEmpty()) throw OperacaoInvalidaException("Informe o logradouro.")
        val norm = Enderecos.normalizarLogradouro(limpo)
        val existentes = localDao.logradourosPorNome(norm)
        // Mesmo nome: reutiliza se o CEP coincide ou se algum lado não tem CEP (não cria duplicata).
        val l = existentes.firstOrNull { it.cep == cep } ?: existentes.firstOrNull { it.cep == null || cep == null }
        val final = if (l != null) {
            val melhorado = l.copy(
                cep = l.cep ?: cep,
                verificadoEm = verificadoEm ?: l.verificadoEm,
                origem = if (origem == OrigemEndereco.VIACEP && l.origem != OrigemEndereco.IMPORTACAO) OrigemEndereco.VIACEP else l.origem,
                updatedAt = agora,
            )
            if (melhorado != l.copy(updatedAt = agora)) localDao.atualizarLogradouro(melhorado)
            melhorado
        } else {
            LogradouroEntity(uuid(), Enderecos.tipoDe(limpo), limpo, norm, cep, origem, null, verificadoEm, agora, agora, true)
                .also { localDao.inserirLogradouro(it) }
        }
        if (bairroId != null) localDao.vincularLogradouroBairro(LogradouroBairroEntity(final.id, bairroId, origem, agora))
        return final
    }

    /** Logradouro digitado manualmente para um bairro (confirmação explícita na UI). */
    suspend fun adicionarLogradouroManual(nome: String, bairroId: String): LogradouroEntity = db.withTransaction {
        registrarLogradouroInterno(nome, bairroId, OrigemEndereco.MANUAL, null, null, relogio.agora().wallClockMs)
    }

    /**
     * Guarda no catálogo local os resultados (já filtrados para Leme/SP) de uma consulta externa,
     * com o bairro informado pela fonte. Nenhuma relação rua↔bairro é inventada.
     */
    suspend fun registrarResultadosExternos(itens: List<EnderecoExterno>) {
        if (itens.isEmpty()) return
        db.withTransaction {
            val agora = relogio.agora().wallClockMs
            val m = leme(agora)
            for (e in itens) {
                val bairro = e.bairro?.let { nome ->
                    localDao.bairroPorNome(m.id, Texto.normalizarNome(nome))?.also { localDao.marcarBairroVerificado(it.id, agora) }
                        ?: BairroEntity(uuid(), m.id, nome, Texto.normalizarNome(nome), agora, OrigemEndereco.VIACEP, true, agora)
                            .also { localDao.inserirBairro(it) }
                }
                registrarLogradouroInterno(e.logradouro, bairro?.id, OrigemEndereco.VIACEP, e.cep, agora, agora)
            }
        }
    }

    /**
     * Seleciona um resultado externo para o bairro escolhido. Se a fonte associa a rua a OUTRO bairro,
     * a UI pede confirmação; confirmado, o vínculo com o bairro escolhido é gravado como manual.
     */
    suspend fun usarResultadoExterno(e: EnderecoExterno, bairroIdEscolhido: String): LogradouroEntity = db.withTransaction {
        registrarResultadosExternos(listOf(e))
        val agora = relogio.agora().wallClockMs
        val l = registrarLogradouroInterno(e.logradouro, null, OrigemEndereco.VIACEP, e.cep, agora, agora)
        localDao.vincularLogradouroBairro(LogradouroBairroEntity(l.id, bairroIdEscolhido, OrigemEndereco.MANUAL, agora))
        l
    }

    /**
     * Lista autorizada de endereços em CSV (`bairro;logradouro;cep`, cabeçalho opcional).
     * [aplicar] = false só calcula a prévia. Duplicados não são recriados.
     */
    suspend fun importarEnderecos(linhas: List<String>, aplicar: Boolean): PreviaImportacao {
        var validas = 0; var invalidas = 0; var bairrosNovos = 0; var logNovos = 0; var existentes = 0
        val bairrosVistos = HashSet<String>(); val logVistos = HashSet<String>()
        val bloco: suspend () -> Unit = {
            val agora = relogio.agora().wallClockMs
            val m = leme(agora)
            for ((i, bruta) in linhas.withIndex()) {
                val cols = bruta.split(';', ',', '	').map { it.trim().trim('"') }
                if (i == 0 && cols.firstOrNull()?.let { Texto.normalizarNome(it) } == "bairro") continue
                if (bruta.isBlank()) continue
                val b = Texto.limparNome(cols.getOrNull(0) ?: "")
                val r = Texto.limparNome(cols.getOrNull(1) ?: "")
                if (b.isEmpty()) { invalidas++; continue }
                validas++
                val bn = Texto.normalizarNome(b)
                var bairro = localDao.bairroPorNome(m.id, bn)
                if (bairro == null && bairrosVistos.add(bn)) bairrosNovos++
                if (aplicar && bairro == null) {
                    bairro = BairroEntity(uuid(), m.id, b, bn, agora, OrigemEndereco.IMPORTACAO).also { localDao.inserirBairro(it) }
                }
                if (r.isNotEmpty()) {
                    val rn = Enderecos.normalizarLogradouro(r)
                    val ja = localDao.logradourosPorNome(rn).isNotEmpty()
                    if (ja) existentes++ else if (logVistos.add(rn)) logNovos++
                    if (aplicar) registrarLogradouroInterno(r, bairro?.id, OrigemEndereco.IMPORTACAO, Enderecos.normalizarCep(cols.getOrNull(2)), null, agora)
                }
            }
        }
        if (aplicar) db.withTransaction { bloco() } else bloco()
        return PreviaImportacao(validas, invalidas, bairrosNovos, logNovos, existentes)
    }

    // ================= Catálogos =================

    suspend fun adicionarTipo(nome: String, aplicaRede: Boolean) {
        val n = Texto.limparNome(nome)
        require(n.isNotEmpty())
        catalogoDao.inserirTipo(TipoServicoEntity(uuid(), n, true, catalogoDao.maxOrdemTipo() + 1, aplicaRede, relogio.agora().wallClockMs))
    }

    suspend fun atualizarTipo(t: TipoServicoEntity) = catalogoDao.atualizarTipo(t)

    suspend fun adicionarMaterial(nome: String) {
        val n = Texto.limparNome(nome)
        require(n.isNotEmpty())
        catalogoDao.inserirMaterial(MaterialRedeEntity(uuid(), n, true, catalogoDao.maxOrdemMaterial() + 1, relogio.agora().wallClockMs))
    }

    suspend fun atualizarMaterial(m: MaterialRedeEntity) = catalogoDao.atualizarMaterial(m)

    suspend fun adicionarCondicao(nome: String) {
        val n = Texto.limparNome(nome)
        require(n.isNotEmpty())
        val id = uuid()
        catalogoDao.inserirCondicao(CondicaoObservadaEntity(id, "PERSONALIZADA_$id", n, true, catalogoDao.maxOrdemCondicao() + 1, false, false, relogio.agora().wallClockMs))
    }

    suspend fun atualizarCondicao(c: CondicaoObservadaEntity) = catalogoDao.atualizarCondicao(c)
}
