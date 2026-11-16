package br.controleservicos.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.OrigemEndereco
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.model.TipoEvento

@Entity(
    tableName = "municipio",
    indices = [Index(value = ["nomeNormalizado", "uf"], unique = true)],
)
data class MunicipioEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val nomeNormalizado: String,
    /** UF em maiúsculas ou "" quando não informada (vazio em vez de null para a unicidade funcionar). */
    val uf: String,
    val createdAt: Long,
)

@Entity(
    tableName = "bairro",
    foreignKeys = [ForeignKey(entity = MunicipioEntity::class, parentColumns = ["id"], childColumns = ["municipioId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["municipioId", "nomeNormalizado"], unique = true)],
)
data class BairroEntity(
    @PrimaryKey val id: String,
    val municipioId: String,
    val nomeOriginal: String,
    val nomeNormalizado: String,
    val createdAt: Long,
    // ---- v3: catálogo ----
    @ColumnInfo(defaultValue = "LOCAL_SERVICO") val origem: OrigemEndereco = OrigemEndereco.LOCAL_SERVICO,
    @ColumnInfo(defaultValue = "1") val ativo: Boolean = true,
    val verificadoEm: Long? = null,
)

@Entity(tableName = "tipo_servico")
data class TipoServicoEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val ativo: Boolean,
    val ordemExibicao: Int,
    val aplicaRedeEncontrada: Boolean,
    val createdAt: Long,
)

@Entity(tableName = "material_rede")
data class MaterialRedeEntity(
    @PrimaryKey val id: String,
    val nome: String,
    val ativo: Boolean,
    val ordemExibicao: Int,
    val createdAt: Long,
)

@Entity(tableName = "condicao_observada", indices = [Index(value = ["codigo"], unique = true)])
data class CondicaoObservadaEntity(
    @PrimaryKey val id: String,
    val codigo: String,
    val nome: String,
    val ativo: Boolean,
    val ordemExibicao: Int,
    /** Mutuamente exclusiva com as demais (ex.: "Nenhuma das condições listadas"). */
    val exclusiva: Boolean,
    /** Exige descrição livre (ex.: "Outras"). */
    val exigeDescricao: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "servico",
    foreignKeys = [
        ForeignKey(entity = TipoServicoEntity::class, parentColumns = ["id"], childColumns = ["tipoServicoId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = MunicipioEntity::class, parentColumns = ["id"], childColumns = ["municipioId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BairroEntity::class, parentColumns = ["id"], childColumns = ["bairroId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = MaterialRedeEntity::class, parentColumns = ["id"], childColumns = ["materialRedeId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("status"), Index("tipoServicoId"), Index("municipioId"), Index("bairroId"),
        Index("materialRedeId"), Index("inicioUtcEpochMs"), Index("logradouroId"),
    ],
)
data class ServicoEntity(
    @PrimaryKey val id: String,
    val tipoServicoId: String,
    val municipioId: String,
    val bairroId: String,
    val logradouro: String,
    val numero: String,
    val complemento: String?,
    val equipeIdentificador: String?,
    val status: StatusServico,
    val inicioUtcEpochMs: Long,
    /** SystemClock.elapsedRealtime() no início — base monotônica válida só no mesmo boot. */
    val inicioElapsedRealtimeMs: Long?,
    /** Settings.Global.BOOT_COUNT no início, para saber se houve reinício. */
    val inicioBootCount: Int?,
    /** Fuso do aparelho no início (ex.: America/Sao_Paulo), para exibição/auditoria. */
    val inicioZoneId: String?,
    val redeEncontradaUtcEpochMs: Long?,
    val fimUtcEpochMs: Long?,
    /** Snapshot validado de fim - início; sempre recalculado junto com os timestamps. */
    val duracaoTotalMs: Long?,
    /** Snapshot validado de rede - início. */
    val duracaoAteRedeMs: Long?,
    val situacaoRede: SituacaoRede?,
    val diametroMm: Int?,
    val materialRedeId: String?,
    val profundidadeMetros: Double?,
    val quantidadeLigacoes: Int?,
    val observacoes: String?,
    val requerRevisao: Boolean,
    val motivoRevisao: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long?,
    val version: Int,
    // ---- v2 ----
    @ColumnInfo(defaultValue = "0") val ramalAguaTrocado: Boolean = false,
    val ladoRamalTrocado: LadoRamal? = null,
    /** Centímetros inteiros (3,5 m = 350): representação decimal exata, sem ponto flutuante. */
    val comprimentoRamalCm: Int? = null,
    val baseTempo: BaseTempo? = null,
    // ---- v3 ----
    /** Logradouro do catálogo escolhido (o texto em [logradouro] é a fotografia histórica). */
    val logradouroId: String? = null,
    val cep: String? = null,
    val origemEndereco: OrigemEndereco? = null,
)

@Entity(
    tableName = "servico_condicao",
    primaryKeys = ["servicoId", "condicaoId"],
    foreignKeys = [
        ForeignKey(entity = ServicoEntity::class, parentColumns = ["id"], childColumns = ["servicoId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = CondicaoObservadaEntity::class, parentColumns = ["id"], childColumns = ["condicaoId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("condicaoId")],
)
data class ServicoCondicaoEntity(
    val servicoId: String,
    val condicaoId: String,
    val descricao: String?,
)

@Entity(
    tableName = "evento_servico",
    foreignKeys = [ForeignKey(entity = ServicoEntity::class, parentColumns = ["id"], childColumns = ["servicoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("servicoId"), Index(value = ["servicoId", "tipoEvento"])],
)
data class EventoServicoEntity(
    @PrimaryKey val id: String,
    val servicoId: String,
    val tipoEvento: TipoEvento,
    /** Instante efetivamente registrado para o marco. */
    val timestampUtcEpochMs: Long,
    /** Relógio de parede bruto lido no toque (pode diferir se houve correção monotônica). */
    val wallClockBrutoMs: Long?,
    val timestampMonotonicMs: Long?,
    val bootCount: Int?,
    val dadosAnteriores: String?,
    val dadosNovos: String?,
    val motivo: String?,
    val createdAt: Long,
)

/** Funcionário cadastrado localmente. Identidade estável = [id] (UUID), nunca o nome. */
@Entity(tableName = "funcionario", indices = [Index("nomeNormalizado")])
data class FuncionarioEntity(
    @PrimaryKey val id: String,
    val nomeExibicao: String,
    /** Sem acentos, minúsculo, espaços colapsados — para busca e aviso de duplicidade. */
    val nomeNormalizado: String,
    val nomeCompleto: String?,
    /** Apelido ou identificador interno opcional para desambiguar homônimos (sem dados sensíveis). */
    val apelidoOuCodigo: String?,
    val funcaoHabitual: FuncaoEquipe?,
    val ativo: Boolean,
    val criadoEm: Long,
    val atualizadoEm: Long,
)

/**
 * Participação de um funcionário em um serviço (composição da equipe naquela execução).
 * PK (servicoId, funcionarioId) impede a mesma pessoa duas vezes no mesmo serviço.
 */
@Entity(
    tableName = "participacao_servico",
    primaryKeys = ["servicoId", "funcionarioId"],
    foreignKeys = [
        ForeignKey(entity = ServicoEntity::class, parentColumns = ["id"], childColumns = ["servicoId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = FuncionarioEntity::class, parentColumns = ["id"], childColumns = ["funcionarioId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("funcionarioId")],
)
data class ParticipacaoServicoEntity(
    val servicoId: String,
    val funcionarioId: String,
    val funcaoNoServico: FuncaoEquipe,
    /** Texto livre opcional quando a função é OUTRA. */
    val funcaoDescricao: String?,
    /** Nota opcional (ex.: também atuou como maquinista). */
    val nota: String?,
    val nomeSnapshot: String,
    val funcaoSnapshot: String,
    val adicionadoEm: Long,
    val atualizadoEm: Long,
)

/**
 * Logradouro do catálogo local de Leme/SP. Não é lista oficial completa: cresce com serviços,
 * consultas ao ViaCEP, cadastro manual e importação autorizada.
 */
@Entity(tableName = "logradouro", indices = [Index("nomeNormalizado"), Index("cep")])
data class LogradouroEntity(
    @PrimaryKey val id: String,
    /** Rua, Avenida, Travessa… quando identificável. */
    val tipo: String?,
    /** Nome completo como exibido (ex.: "Avenida Joaquim Lopes Aguila"). */
    val nomeExibicao: String,
    val nomeNormalizado: String,
    val cep: String?,
    val origem: OrigemEndereco,
    val origemExternaId: String?,
    val verificadoEm: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val ativo: Boolean,
)

/** Relação N:N logradouro ↔ bairro (uma rua pode atravessar vários bairros). */
@Entity(
    tableName = "logradouro_bairro",
    primaryKeys = ["logradouroId", "bairroId"],
    foreignKeys = [
        ForeignKey(entity = LogradouroEntity::class, parentColumns = ["id"], childColumns = ["logradouroId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = BairroEntity::class, parentColumns = ["id"], childColumns = ["bairroId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("bairroId")],
)
data class LogradouroBairroEntity(
    val logradouroId: String,
    val bairroId: String,
    val origem: OrigemEndereco,
    val createdAt: Long,
)

/**
 * Uma ligação/ramal de água trocado = um imóvel atendido. Quantidade de ligações trocadas é a
 * contagem destas linhas (derivada). Endereço próprio só quando diferente do principal.
 */
@Entity(
    tableName = "ligacao_trocada",
    foreignKeys = [ForeignKey(entity = ServicoEntity::class, parentColumns = ["id"], childColumns = ["servicoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("servicoId")],
)
data class LigacaoTrocadaEntity(
    @PrimaryKey val id: String,
    val servicoId: String,
    val ordem: Int,
    /** Número do imóvel atendido (texto: "12A", "S/N"). NULL só em registro legado pendente de identificação. */
    val numeroTexto: String?,
    /** Preenchidos somente quando o imóvel fica em bairro/rua diferente do endereço principal. */
    val bairroTexto: String?,
    val logradouroTexto: String?,
    val complemento: String?,
    /** Distingue duas ligações no mesmo imóvel. */
    val identificadorLigacao: String?,
    val lado: LadoRamal,
    /** Centímetros inteiros (> 0). */
    val comprimentoCm: Int,
    val observacoes: String?,
    val pendenteIdentificacao: Boolean,
    /** Migrado do campo único de ramal da v2. */
    val legado: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
