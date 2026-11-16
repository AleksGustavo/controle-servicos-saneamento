package br.controleservicos.app.feature.completion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.database.CondicaoObservadaEntity
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.ParRotuloValor
import br.controleservicos.app.core.design.Secao
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Condicoes
import br.controleservicos.app.core.domain.EntradaNumerica
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.CodigosCatalogo
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.domain.LigacaoForm
import br.controleservicos.app.core.domain.Ligacoes
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.feature.team.ListaParticipantes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.flow.map
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.repository.Complementos
import br.controleservicos.app.feature.comum.fabrica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FormComplementos(
    val carregado: Boolean = false,
    val situacaoRede: SituacaoRede = SituacaoRede.NAO_MARCADA,
    val diametro: String = "",
    val materialId: String? = null,
    val profundidade: String = "",
    val quantidadeLigacoes: String = "",
    /** códigos selecionados */
    val condicoes: Set<String> = emptySet(),
    val descricoes: Map<String, String> = emptyMap(),
    val observacoes: String = "",
    /** Houve troca de ligação/ramal. Não ⇒ nenhuma ligação é gravada. */
    val ramalTrocado: Boolean = false,
    /** Um item por imóvel atendido. */
    val ligacoes: List<LigacaoForm> = emptyList(),
    val erros: Map<String, String> = emptyMap(),
    val salvando: Boolean = false,
    val mensagem: String? = null,
    val perguntarSemEquipe: Boolean = false,
)

class ComplementosViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val repo = container.repositorio
    val servico = repo.observarLinha(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val materiais = repo.observarMateriais().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val condicoesCatalogo = repo.observarCondicoes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val participantes = repo.observarParticipantesDe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val limiteRamal = container.preferencias.preferencias.map { it.limiteRamalMetros }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RegrasNegocio.LIMITE_RAMAL_METROS_PADRAO)
    val form = MutableStateFlow(FormComplementos())

    init {
        // Carrega valores atuais uma única vez (edição) ou padrões (primeiro preenchimento).
        viewModelScope.launch {
            val s = repo.observarLinha(id).filterNotNull().first()
            val tipo = repo.observarTipos().first().firstOrNull { it.id == s.tipoServicoId }
            val atuais = repo.condicoesDe(id)
            val ligs = repo.ligacoesDe(id)
            form.value = FormComplementos(
                carregado = true,
                situacaoRede = when {
                    s.redeEncontradaUtcEpochMs != null -> SituacaoRede.MARCADA
                    s.situacaoRede != null && s.situacaoRede != SituacaoRede.MARCADA -> s.situacaoRede
                    tipo?.aplicaRedeEncontrada == false -> SituacaoRede.NAO_APLICAVEL
                    else -> SituacaoRede.NAO_MARCADA
                },
                diametro = s.diametroMm?.toString() ?: "",
                materialId = s.materialRedeId,
                profundidade = Numeros.formatarDecimalBr(s.profundidadeMetros),
                quantidadeLigacoes = s.quantidadeLigacoes?.toString() ?: "",
                condicoes = atuais.map { it.codigo }.toSet(),
                descricoes = atuais.filter { it.descricao != null }.associate { it.codigo to it.descricao!! },
                observacoes = s.observacoes ?: "",
                ramalTrocado = s.ramalAguaTrocado,
                ligacoes = if (s.ramalAguaTrocado) ligs.map { l ->
                    LigacaoForm(
                        chave = l.id, numero = l.numeroTexto ?: "", lado = l.lado, comprimento = Numeros.formatarMetrosBr(l.comprimentoCm),
                        enderecoDiferente = l.bairroTexto != null || l.logradouroTexto != null, bairro = l.bairroTexto ?: "", rua = l.logradouroTexto ?: "",
                        complemento = l.complemento ?: "", identificador = l.identificadorLigacao ?: "", observacoes = l.observacoes ?: "", legado = l.legado,
                    )
                } else emptyList(),
            )
        }
    }

    fun editar(f: (FormComplementos) -> FormComplementos) = form.update { f(it).copy(mensagem = null) }

    /**
     * Sim → Não descarta lado e comprimento (não ficam ocultos para reaparecer);
     * Não → Sim começa vazio e exige novos valores.
     */
    fun definirRamal(trocado: Boolean) = editar { it.copy(ramalTrocado = trocado, erros = it.erros.filterKeys { k -> !k.contains(':') && k != "geral" }) }

    fun definirLigacoes(l: List<LigacaoForm>) = editar { it.copy(ligacoes = l) }

    fun alternarCondicao(codigo: String) {
        val exclusiva = condicoesCatalogo.value.filter { it.exclusiva }.map { it.codigo }.firstOrNull() ?: CodigosCatalogo.COND_NENHUMA
        editar { it.copy(condicoes = Condicoes.alternar(it.condicoes, codigo, exclusiva)) }
    }

    fun salvar(aoSalvo: () -> Unit, permitirSemEquipe: Boolean = false) {
        val f = form.value
        if (f.salvando || !f.carregado) return
        val erros = mutableMapOf<String, String>()
        var ligacoesOk = emptyList<br.controleservicos.app.core.domain.LigacaoValidada>()
        if (f.ramalTrocado) {
            val s = servico.value
            if (f.ligacoes.isEmpty()) erros["geral"] = "Adicione ao menos uma ligação trocada ou marque \"Não\"."
            val (ok, e) = Ligacoes.validar(f.ligacoes, s?.logradouro ?: "", s?.bairroNome ?: "", limiteRamal.value)
            erros.putAll(e)
            ligacoesOk = ok
        }
        val diam = Numeros.inteiro(f.diametro, minimo = 1, maximo = 5000)
        if (diam is EntradaNumerica.Invalida) erros["diametro"] = diam.mensagem
        val prof = Numeros.decimal(f.profundidade, positivo = true, maximo = 50.0)
        if (prof is EntradaNumerica.Invalida) erros["profundidade"] = prof.mensagem
        val qtd = Numeros.inteiro(f.quantidadeLigacoes, minimo = 0, maximo = 999)
        if (qtd is EntradaNumerica.Invalida) erros["ligacoes"] = qtd.mensagem
        val qtdValor = (qtd as? EntradaNumerica.Valida)?.valor
        val catalogo = condicoesCatalogo.value
        val porCodigo = catalogo.associateBy { it.codigo }
        val exigeDescricao = catalogo.filter { it.exigeDescricao && it.codigo in f.condicoes }
        exigeDescricao.forEach { c -> if (f.descricoes[c.codigo].isNullOrBlank()) erros["cond_${c.codigo}"] = "Descreva \"${c.nome}\"." }
        Condicoes.validar(
            f.condicoes, CodigosCatalogo.COND_NENHUMA, "__nenhuma__", "", CodigosCatalogo.COND_MAIS_DE_UMA_LIGACAO, qtdValor,
        ).forEach { msg -> if (msg.contains("ligação")) erros["ligacoes"] = msg else erros["condicoes"] = msg }
        if (erros.isNotEmpty()) {
            form.update { it.copy(erros = erros, mensagem = "Verifique os campos destacados.") }
            return
        }
        if (!permitirSemEquipe && participantes.value.isNullOrEmpty()) {
            form.update { it.copy(erros = emptyMap(), perguntarSemEquipe = true) }
            return
        }
        form.update { it.copy(salvando = true, erros = emptyMap(), perguntarSemEquipe = false) }
        viewModelScope.launch {
            try {
                repo.salvarComplementos(
                    id,
                    Complementos(
                        situacaoRede = f.situacaoRede,
                        diametroMm = (diam as? EntradaNumerica.Valida)?.valor,
                        materialRedeId = f.materialId,
                        profundidadeMetros = (prof as? EntradaNumerica.Valida)?.valor,
                        quantidadeLigacoes = qtdValor,
                        condicoes = f.condicoes.mapNotNull { cod -> porCodigo[cod]?.let { it.id to f.descricoes[cod] } }.toMap(),
                        observacoes = f.observacoes,
                        ramalAguaTrocado = f.ramalTrocado,
                        ligacoes = if (f.ramalTrocado) ligacoesOk else emptyList(),
                        permitirSemParticipantes = permitirSemEquipe,
                    ),
                )
                form.update { it.copy(salvando = false) }
                aoSalvo()
            } catch (e: Exception) {
                form.update { it.copy(salvando = false, mensagem = "Não foi possível salvar: ${e.message}. Os tempos continuam gravados.") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaComplementos(container: AppContainer, servicoId: String, aoVoltar: () -> Unit, aoSalvo: () -> Unit, aoParticipantes: () -> Unit = {}) {
    val vm: ComplementosViewModel = viewModel(key = "comp_$servicoId", factory = fabrica { ComplementosViewModel(container, servicoId) })
    val s by vm.servico.collectAsStateWithLifecycle()
    val f by vm.form.collectAsStateWithLifecycle()
    val materiais by vm.materiais.collectAsStateWithLifecycle()
    val condicoes by vm.condicoesCatalogo.collectAsStateWithLifecycle()
    val participantes by vm.participantes.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(f.mensagem) { f.mensagem?.let { snack.showSnackbar(it) } }

    val edicao = s?.status == StatusServico.CONCLUIDO
    Scaffold(
        topBar = { BarraTopo(if (edicao) "Editar complementos" else "Complementos", aoVoltar) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Tokens.Background,
    ) { pad ->
        val l = s
        if (l == null || !f.carregado) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ResumoTempos(l)
            if (l.requerRevisao) Aviso(l.motivoRevisao ?: "Horários precisam de revisão (Detalhes › Corrigir horários).")
            if (l.status == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS) {
                Text("Tempo já gravado. Se sair agora, o registro fica como \"pendente de complementos\".", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }

            Secao("Equipe") {
                ListaParticipantes(participantes.orEmpty())
                TextButton(onClick = aoParticipantes, modifier = Modifier.heightIn(min = 48.dp).testTag("botao_corrigir_participantes_comp")) {
                    Text(if (participantes.isNullOrEmpty()) "Adicionar participantes" else "Corrigir participantes")
                }
            }

            Secao("Rede encontrada?") {
                if (l.redeEncontradaUtcEpochMs != null) {
                    Text("Sim — marcada às ${Tempo.hora(l.redeEncontradaUtcEpochMs)}", fontWeight = FontWeight.Medium)
                } else {
                    Column(Modifier.selectableGroup()) {
                        listOf(SituacaoRede.NAO_MARCADA, SituacaoRede.NAO_APLICAVEL).forEach { op ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(f.situacaoRede == op, role = Role.RadioButton) { vm.editar { it.copy(situacaoRede = op) } },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = f.situacaoRede == op, onClick = null)
                                Text(op.rotulo, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Text("Sem marcação durante a medição, nenhum horário é inventado.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                }
            }

            Secao("Rede") {
                CampoNum("Diâmetro (mm)", f.diametro, { v -> vm.editar { it.copy(diametro = v.filter(Char::isDigit).take(5)) } }, f.erros["diametro"], KeyboardType.Number, "Vazio = não informado", "campo_diametro")
                Text("Material da rede", style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = f.materialId == null, onClick = { vm.editar { it.copy(materialId = null) } }, label = { Text("Não informado") }, modifier = Modifier.heightIn(min = 48.dp))
                    materiais.filter { it.ativo || it.id == f.materialId }.forEach { m ->
                        FilterChip(
                            selected = f.materialId == m.id,
                            onClick = { vm.editar { it.copy(materialId = m.id) } },
                            label = { Text(m.nome) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("material_${m.id}"),
                        )
                    }
                }
                CampoNum("Profundidade (m)", f.profundidade, { v -> vm.editar { it.copy(profundidade = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(8)) } }, f.erros["profundidade"], KeyboardType.Decimal, "Ex.: 1,20 — vazio = não informada", "campo_profundidade")
            }

            Secao("Condições observadas") {
                f.erros["condicoes"]?.let { Text(it, color = Tokens.Error) }
                condicoes.filter { it.ativo || it.codigo in f.condicoes }.forEach { c ->
                    LinhaCondicao(c, c.codigo in f.condicoes) { vm.alternarCondicao(c.codigo) }
                    if (c.exigeDescricao && c.codigo in f.condicoes) {
                        OutlinedTextField(
                            value = f.descricoes[c.codigo] ?: "",
                            onValueChange = { v -> vm.editar { it.copy(descricoes = it.descricoes + (c.codigo to v.take(RegrasNegocio.LIMITE_DESCRICAO_CONDICAO))) } },
                            label = { Text("Descreva: ${c.nome}") },
                            isError = f.erros["cond_${c.codigo}"] != null,
                            supportingText = f.erros["cond_${c.codigo}"]?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                CampoNum("Ligações existentes no local (opcional)", f.quantidadeLigacoes, { v -> vm.editar { it.copy(quantidadeLigacoes = v.filter(Char::isDigit).take(3)) } }, f.erros["ligacoes"], KeyboardType.Number, "Opcional", "campo_ligacoes")
            }

            Secao("Houve troca de ligação/ramal de água?") {
                EditorLigacoes(
                    trocado = f.ramalTrocado,
                    lista = f.ligacoes,
                    erros = f.erros,
                    numeroPrincipal = l.numero,
                    limiteMetros = vm.limiteRamal.collectAsStateWithLifecycle().value,
                    aoMudarTrocado = vm::definirRamal,
                    aoMudarLista = vm::definirLigacoes,
                )
            }

            Secao("Observações") {
                OutlinedTextField(
                    value = f.observacoes,
                    onValueChange = { v -> vm.editar { it.copy(observacoes = v.take(RegrasNegocio.LIMITE_OBSERVACOES)) } },
                    label = { Text("Observações (opcional)") },
                    supportingText = { Text("${f.observacoes.length}/${RegrasNegocio.LIMITE_OBSERVACOES}") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag("campo_observacoes"),
                )
            }

            BotaoPrimario(
                if (f.salvando) "Salvando…" else "Salvar registro",
                aoClicar = { vm.salvar(aoSalvo) },
                habilitado = !f.salvando,
                icone = Icons.Filled.Done,
                modifier = Modifier.testTag("botao_salvar_registro"),
            )
        }
    }
    if (f.perguntarSemEquipe) {
        DialogoSemEquipe(
            aoAdicionar = { vm.editar { it.copy(perguntarSemEquipe = false) }; aoParticipantes() },
            aoSalvarSemEquipe = { vm.editar { it.copy(perguntarSemEquipe = false) }; vm.salvar(aoSalvo, permitirSemEquipe = true) },
            aoFechar = { vm.editar { it.copy(perguntarSemEquipe = false) } },
        )
    }
}

@Composable
private fun DialogoSemEquipe(aoAdicionar: () -> Unit, aoSalvarSemEquipe: () -> Unit, aoFechar: () -> Unit) {
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Nenhum participante") },
        text = { Text("Informe quem executou o serviço. Se não for possível identificar a equipe, o registro pode ser salvo como \"equipe não registrada\" e ficará fora das estatísticas de equipe.") },
        confirmButton = { TextButton(onClick = aoAdicionar, modifier = Modifier.testTag("dialogo_adicionar_participantes")) { Text("Adicionar participantes") } },
        dismissButton = { TextButton(onClick = aoSalvarSemEquipe, modifier = Modifier.testTag("salvar_sem_equipe")) { Text("Salvar sem equipe") } },
    )
}

@Composable
fun ResumoTempos(l: LinhaServico) {
    Surface(color = Tokens.PrimaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("resumo_tempos")) {
        Column(Modifier.padding(12.dp)) {
            Text("${l.tipoNome} • ${l.bairroNome}", fontWeight = FontWeight.SemiBold)
            Text(l.enderecoResumido, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            ParRotuloValor("Início", Tempo.dataHora(l.inicioUtcEpochMs))
            ParRotuloValor("Rede encontrada", l.redeEncontradaUtcEpochMs?.let { Tempo.hora(it) } ?: "Não registrado")
            ParRotuloValor("Fim", l.fimUtcEpochMs?.let { Tempo.dataHora(it) } ?: "Não registrado")
            ParRotuloValor("Duração total (início → fim)", Tempo.medirDuracaoTotal(l.inicioUtcEpochMs, l.fimUtcEpochMs)?.let { Tempo.formatarDuracao(it) } ?: "Não registrado")
            ParRotuloValor("Até encontrar a rede", Tempo.medirTempoAteRede(l.inicioUtcEpochMs, l.redeEncontradaUtcEpochMs, l.fimUtcEpochMs)?.let { Tempo.formatarDuracao(it) } ?: "Não registrado")
            ParRotuloValor("Após a rede", Tempo.medirTempoAposRede(l.inicioUtcEpochMs, l.redeEncontradaUtcEpochMs, l.fimUtcEpochMs)?.let { Tempo.formatarDuracao(it) } ?: "Não registrado")
        }
    }
}

@Composable
private fun LinhaCondicao(c: CondicaoObservadaEntity, marcada: Boolean, aoAlternar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(marcada, role = Role.Checkbox) { aoAlternar() }.testTag("cond_${c.codigo}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = marcada, onCheckedChange = null)
        Text(c.nome, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun CampoNum(rotulo: String, valor: String, aoMudar: (String) -> Unit, erro: String?, tipo: KeyboardType, ajuda: String, tag: String) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        isError = erro != null,
        supportingText = { Text(erro ?: ajuda) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = tipo, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}
