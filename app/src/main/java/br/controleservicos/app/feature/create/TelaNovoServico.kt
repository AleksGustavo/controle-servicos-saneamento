package br.controleservicos.app.feature.create

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.feature.team.EditorParticipantes
import br.controleservicos.app.feature.address.BairroSelecionado
import br.controleservicos.app.feature.address.CampoSeletor
import br.controleservicos.app.feature.address.DialogoBairro
import br.controleservicos.app.feature.address.DialogoLogradouro
import br.controleservicos.app.feature.address.RuaSelecionada
import androidx.compose.runtime.saveable.rememberSaveable
import br.controleservicos.app.feature.team.ParticipanteForm
import br.controleservicos.app.feature.team.validarSelecao
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.repository.NovoServico
import br.controleservicos.app.core.repository.ServicoAtivoExistenteException
import br.controleservicos.app.feature.comum.fabrica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

data class FormNovo(
    val tipoId: String? = null,
    val bairro: BairroSelecionado? = null,
    val rua: RuaSelecionada? = null,
    val numero: String = "",
    val semNumero: Boolean = false,
    val complemento: String = "",
    val participantes: List<ParticipanteForm> = emptyList(),
    /** Usuário confirmou iniciar sem participantes (poderá adicioná-los depois). */
    val confirmarSemEquipe: Boolean = false,
    val erros: Map<String, String> = emptyMap(),
    val iniciando: Boolean = false,
    val mensagem: String? = null,
    val idAtivoExistente: String? = null,
    val perguntarSemEquipe: Boolean = false,
)

class NovoServicoViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repositorio
    val form = MutableStateFlow(FormNovo())
    val tipos = repo.observarTipos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ativo = repo.observarAtivo().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)


    fun editar(f: (FormNovo) -> FormNovo) = form.update { f(it).copy(mensagem = null) }

    private fun validar(f: FormNovo): Map<String, String> {
        val e = mutableMapOf<String, String>()
        if (f.tipoId == null) e["tipo"] = "Escolha o tipo de serviço."
        if (f.bairro == null) e["bairro"] = "Escolha o bairro."
        if (f.rua == null) e["logradouro"] = "Escolha a rua/logradouro."
        if (!f.semNumero && f.numero.isBlank()) e["numero"] = "Informe o número ou marque S/N."
        validarSelecao(f.participantes)?.let { e["participantes"] = it }
        return e
    }

    /** Ação explícita: copia a composição do último serviço com equipe (nunca automático). */
    fun reutilizarUltimos() {
        viewModelScope.launch {
            val ultimos = repo.participantesDoUltimoServico()
            if (ultimos.isEmpty()) {
                form.update { it.copy(mensagem = "Nenhum serviço anterior com participantes.") }
                return@launch
            }
            val ativos = ultimos.filter { it.funcionarioAtivo }
            form.update { f ->
                val existentes = f.participantes.map { it.funcionarioId }.toSet()
                f.copy(
                    participantes = f.participantes + ativos.filter { it.funcionarioId !in existentes }.map(ParticipanteForm::de),
                    mensagem = "Participantes do último serviço adicionados. Confira as funções." +
                        if (ativos.size < ultimos.size) " (${ultimos.size - ativos.size} inativo(s) ignorado(s))" else "",
                )
            }
        }
    }

    /** Captura o instante no toque e persiste antes de navegar. */
    fun iniciar(aoIniciado: (String) -> Unit) {
        val marcacao = container.relogio.agora()
        val f = form.value
        if (f.iniciando) return
        val erros = validar(f)
        if (erros.isNotEmpty()) {
            form.update { it.copy(erros = erros, mensagem = "Corrija os campos destacados.") }
            return
        }
        if (f.participantes.isEmpty() && !f.confirmarSemEquipe) {
            form.update { it.copy(erros = emptyMap(), perguntarSemEquipe = true) }
            return
        }
        form.update { it.copy(iniciando = true, erros = emptyMap(), perguntarSemEquipe = false) }
        viewModelScope.launch {
            try {
                val id = repo.iniciarServico(
                    NovoServico(
                        tipoServicoId = f.tipoId!!,
                        bairroNome = f.bairro!!.nome,
                        logradouro = f.rua!!.nome,
                        logradouroId = f.rua.id,
                        cep = f.rua.cep,
                        numero = if (f.semNumero) "S/N" else f.numero,
                        complemento = f.complemento,
                        zoneId = ZoneId.systemDefault().id,
                        participantes = f.participantes.mapNotNull { it.paraNovo() },
                    ),
                    marcacao,
                )
                aoIniciado(id)
            } catch (e: ServicoAtivoExistenteException) {
                form.update { it.copy(iniciando = false, idAtivoExistente = e.idAtivo, mensagem = "Já existe um serviço em andamento.") }
            } catch (e: Exception) {
                form.update { it.copy(iniciando = false, mensagem = "Não foi possível iniciar: ${e.message ?: "erro ao gravar"}. Tente novamente.") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TelaNovoServico(container: AppContainer, aoVoltar: () -> Unit, aoIniciado: (String) -> Unit, aoContinuarAtivo: ((String) -> Unit)? = null) {
    val vm: NovoServicoViewModel = viewModel(factory = fabrica { NovoServicoViewModel(container) })
    val f by vm.form.collectAsStateWithLifecycle()
    val tipos by vm.tipos.collectAsStateWithLifecycle()
    val ativo by vm.ativo.collectAsStateWithLifecycle()
    var escolhendoBairro by rememberSaveable { mutableStateOf(false) }
    var escolhendoRua by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(f.mensagem) { f.mensagem?.let { snack.showSnackbar(it) } }

    Scaffold(
        topBar = { BarraTopo("Novo serviço", aoVoltar, subtitulo = LocalOperacional.ROTULO) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Tokens.Background,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ativo != null) {
                Aviso("Há um serviço em andamento (${ativo!!.tipoNome} • ${ativo!!.bairroNome}). Conclua-o antes de iniciar outro.", erro = true)
            }
            Text("Tipo de serviço", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            f.erros["tipo"]?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall) }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tipos.filter { it.ativo }.forEach { t ->
                    val sel = f.tipoId == t.id
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (sel) Tokens.PrimaryContainer else Tokens.SurfaceVariant,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .selectable(selected = sel, role = Role.RadioButton, onClick = { vm.editar { it.copy(tipoId = t.id) } })
                            .testTag("tipo_${t.id}"),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sel, onClick = null)
                            Text(t.nome, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp), fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }

            Text("Endereço em ${LocalOperacional.ROTULO}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            CampoSeletor("Bairro *", f.bairro?.nome, true, f.erros["bairro"], "campo_bairro") { escolhendoBairro = true }
            CampoSeletor(
                "Rua / Avenida / Logradouro *", f.rua?.let { r -> r.nome + (r.cep?.let { " · CEP $it" } ?: "") }, f.bairro != null, f.erros["logradouro"],
                "campo_logradouro", ajuda = if (f.bairro == null) "Escolha o bairro primeiro." else null,
            ) { escolhendoRua = true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Campo(
                    "Número *", if (f.semNumero) "S/N" else f.numero, { v -> vm.editar { it.copy(numero = v.take(20)) } }, f.erros["numero"],
                    Modifier.weight(1f), habilitado = !f.semNumero, tag = "campo_numero",
                )
                Row(
                    Modifier.heightIn(min = 48.dp).selectable(selected = f.semNumero, role = Role.Checkbox, onClick = { vm.editar { it.copy(semNumero = !it.semNumero) } }).testTag("check_sn"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = f.semNumero, onCheckedChange = null)
                    Text("S/N", modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                }
            }
            Campo("Complemento (opcional)", f.complemento, { v -> vm.editar { it.copy(complemento = v.take(RegrasNegocio.LIMITE_CAMPO_ENDERECO)) } }, null, ultimo = true)

            Text("Equipe neste serviço", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            EditorParticipantes(
                container = container,
                selecionados = f.participantes,
                aoMudar = { l -> vm.editar { it.copy(participantes = l, confirmarSemEquipe = false) } },
                erro = f.erros["participantes"],
                aoReutilizar = vm::reutilizarUltimos,
            )

            BotaoPrimario(
                texto = if (f.iniciando) "Iniciando…" else "Iniciar escavação",
                icone = Icons.Filled.PlayArrow,
                habilitado = !f.iniciando && ativo == null,
                aoClicar = { vm.iniciar(aoIniciado) },
                modifier = Modifier.testTag("botao_iniciar"),
            )
            Text("O tempo começa a contar no toque em \"Iniciar escavação\".", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
        }
    }

    if (escolhendoBairro) {
        DialogoBairro(container, aoEscolher = { b ->
            escolhendoBairro = false
            // Trocar o bairro invalida a rua escolhida (evita combinação inválida silenciosa).
            vm.editar { it.copy(bairro = b, rua = if (it.bairro?.id == b.id) it.rua else null) }
        }, aoFechar = { escolhendoBairro = false })
    }
    val bairroAtual = f.bairro
    if (escolhendoRua && bairroAtual != null) {
        DialogoLogradouro(container, bairroAtual, aoEscolher = { r -> escolhendoRua = false; vm.editar { it.copy(rua = r) } }, aoFechar = { escolhendoRua = false })
    }

    if (f.perguntarSemEquipe) {
        AlertDialog(
            onDismissRequest = { vm.editar { it.copy(perguntarSemEquipe = false) } },
            title = { Text("Iniciar sem participantes?") },
            text = { Text("Você poderá adicionar a equipe durante o serviço ou na finalização. Para salvar o registro, será pedido ao menos um participante.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.editar { it.copy(perguntarSemEquipe = false, confirmarSemEquipe = true) }; vm.iniciar(aoIniciado) },
                    modifier = Modifier.testTag("confirmar_sem_equipe"),
                ) { Text("Iniciar assim") }
            },
            dismissButton = { TextButton(onClick = { vm.editar { it.copy(perguntarSemEquipe = false) } }) { Text("Selecionar equipe") } },
        )
    }
}

@Composable
private fun Campo(
    rotulo: String,
    valor: String,
    aoMudar: (String) -> Unit,
    erro: String?,
    modifier: Modifier = Modifier.fillMaxWidth(),
    habilitado: Boolean = true,
    capital: KeyboardCapitalization = KeyboardCapitalization.Words,
    ultimo: Boolean = false,
    tag: String? = null,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = aoMudar,
        label = { Text(rotulo) },
        isError = erro != null,
        supportingText = erro?.let { { Text(it) } },
        singleLine = true,
        enabled = habilitado,
        keyboardOptions = KeyboardOptions(capitalization = capital, imeAction = if (ultimo) ImeAction.Done else ImeAction.Next),
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

private fun Modifier.onFocusChangedCompat(aoMudar: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { aoMudar(it.isFocused) }
