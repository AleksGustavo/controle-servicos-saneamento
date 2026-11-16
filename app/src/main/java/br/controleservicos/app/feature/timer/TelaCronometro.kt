package br.controleservicos.app.feature.timer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.EstiloCronometro
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.ResolvedorRelogio
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.repository.ResultadoMarco
import br.controleservicos.app.feature.comum.fabrica
import br.controleservicos.app.feature.comum.rememberAgora
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoCronometro(
    val gravandoRede: Boolean = false,
    val gravandoFim: Boolean = false,
    /** Diálogo de confirmação do fim aberto (o instante só é capturado ao confirmar). */
    val confirmandoFim: Boolean = false,
    val mensagem: String? = null,
    val sinalSucesso: Int = 0,
)

class CronometroViewModel(private val container: AppContainer, private val id: String) : ViewModel() {
    private val repo = container.repositorio
    val estado = MutableStateFlow(EstadoCronometro())
    val servico = repo.observarLinha(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val participantes = repo.observarParticipantesDe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val aplicaRede = combine(repo.observarLinha(id), repo.observarTipos()) { s, tipos ->
        s?.let { l -> tipos.firstOrNull { it.id == l.tipoServicoId }?.aplicaRedeEncontrada } ?: true
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun marcarRede() {
        val m = container.relogio.agora() // instante do toque
        if (estado.value.gravandoRede) return
        estado.update { it.copy(gravandoRede = true) }
        viewModelScope.launch {
            try {
                when (val r = repo.marcarRede(id, m)) {
                    is ResultadoMarco.Registrado -> estado.update {
                        it.copy(gravandoRede = false, sinalSucesso = it.sinalSucesso + 1, mensagem = r.avisoRevisao ?: "Rede encontrada registrada às ${Tempo.hora(r.instanteMs)}")
                    }
                    is ResultadoMarco.JaRegistrado -> estado.update { it.copy(gravandoRede = false) }
                }
            } catch (e: Exception) {
                estado.update { it.copy(gravandoRede = false, mensagem = "Falha ao gravar a rede encontrada. Toque novamente. (${e.message})") }
            }
        }
    }

    /** Toque em "Finalizar escavação": abre a confirmação contra toque acidental (nada é gravado ainda). */
    fun pedirFinalizacao() {
        if (estado.value.gravandoFim || estado.value.confirmandoFim) return
        estado.update { it.copy(confirmandoFim = true) }
    }

    fun desistirFinalizacao() = estado.update { it.copy(confirmandoFim = false) }

    /**
     * Confirmação do fim: o instante gravado é o DESTE toque (ato que efetivamente confirmou o fim),
     * capturado antes de qualquer trabalho assíncrono. Repetições são ignoradas (idempotente no banco).
     */
    fun confirmarFinalizacao(aoFinalizado: () -> Unit) {
        val m = container.relogio.agora()
        if (estado.value.gravandoFim || !estado.value.confirmandoFim) return
        estado.update { it.copy(gravandoFim = true) }
        viewModelScope.launch {
            try {
                val r = repo.finalizar(id, m)
                estado.update {
                    it.copy(gravandoFim = false, confirmandoFim = false, sinalSucesso = it.sinalSucesso + 1, mensagem = (r as? ResultadoMarco.Registrado)?.avisoRevisao)
                }
                // A navegação ocorre pela observação do status persistido (fonte de verdade).
                aoFinalizado()
            } catch (e: Exception) {
                estado.update { it.copy(gravandoFim = false, mensagem = "Falha ao gravar o fim. Toque em Finalizar novamente. (${e.message})") }
            }
        }
    }

    fun cancelarServico(motivo: String, aoCancelado: () -> Unit) {
        viewModelScope.launch {
            try {
                repo.cancelar(id, motivo)
                aoCancelado()
            } catch (e: Exception) {
                estado.update { it.copy(mensagem = "Falha ao cancelar: ${e.message}") }
            }
        }
    }

    fun mensagemExibida() = estado.update { it.copy(mensagem = null) }
}

@Composable
fun TelaCronometro(
    container: AppContainer,
    servicoId: String,
    aoVoltar: () -> Unit,
    aoFinalizado: () -> Unit,
    aoEncerradoExternamente: () -> Unit,
    aoParticipantes: () -> Unit = {},
) {
    val vm: CronometroViewModel = viewModel(key = "crono_$servicoId", factory = fabrica { CronometroViewModel(container, servicoId) })
    val s by vm.servico.collectAsStateWithLifecycle()
    val aplicaRede by vm.aplicaRede.collectAsStateWithLifecycle()
    val e by vm.estado.collectAsStateWithLifecycle()
    val participantes by vm.participantes.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    var confirmarCancelamento by remember { mutableStateOf(false) }
    var motivoCancelamento by remember { mutableStateOf("") }

    LaunchedEffect(e.mensagem) { e.mensagem?.let { snack.showSnackbar(it); vm.mensagemExibida() } }
    LaunchedEffect(e.sinalSucesso) { if (e.sinalSucesso > 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    // Aberto para um serviço que já não está em andamento (ex.: finalizado em outra tela).
    LaunchedEffect(s?.status, e.gravandoFim, e.confirmandoFim) {
        val st = s?.status
        if (st != null && st != StatusServico.EM_ANDAMENTO && !e.gravandoFim && !e.confirmandoFim) {
            if (st == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS) aoFinalizado() else aoEncerradoExternamente()
        }
    }

    Scaffold(
        topBar = {
            BarraTopo("Serviço em andamento", aoVoltar) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Mais opções") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Participantes (corrigir)") }, onClick = { menu = false; aoParticipantes() })
                    DropdownMenuItem(text = { Text("Cancelar serviço") }, onClick = { menu = false; confirmarCancelamento = true })
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Tokens.Background,
    ) { pad ->
        val l = s
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val agora = rememberAgora(container.relogio, ativo = l.status == StatusServico.EM_ANDAMENTO)
        val inicio = Marcacao(l.inicioUtcEpochMs, l.inicioElapsedRealtimeMs, l.inicioBootCount)
        // Recalculado do marco persistido a cada quadro exibido; nunca incrementado.
        val decorrido = ResolvedorRelogio.decorridoParaExibicao(inicio, agora)
        val reiniciou = ResolvedorRelogio.reiniciouDesde(inicio, agora)
        val textoTempo = Tempo.formatarDuracao(decorrido)

        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${l.tipoNome} • ${l.bairroNome}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(l.enderecoResumido, color = Tokens.TextSecondary, textAlign = TextAlign.Center)
            if (l.requerRevisao) Aviso(l.motivoRevisao ?: "Horários precisam de revisão.")
            if (reiniciou) {
                Aviso("O aparelho foi reiniciado durante o serviço: o tempo é calculado pelo relógio do sistema e fica sujeito a ajustes de hora.", modifier = Modifier.testTag("aviso_reinicio"))
            }
            TextButton(onClick = aoParticipantes, modifier = Modifier.testTag("resumo_equipe_cronometro")) {
                Text(
                    if (participantes.isEmpty()) "Equipe não registrada — adicionar" else "Equipe: ${participantes.size} participante(s) — alterar",
                    color = if (participantes.isEmpty()) Tokens.Warning else Tokens.Primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text("TEMPO TOTAL", color = Tokens.TextSecondary, letterSpacing = 2.sp, style = MaterialTheme.typography.labelLarge)
            Text(
                textoTempo,
                style = EstiloCronometro,
                modifier = Modifier.testTag("cronometro_total").semantics { contentDescription = "Tempo total $textoTempo" },
            )
            Text("Início às ${Tempo.hora(l.inicioUtcEpochMs)}", color = Tokens.TextSecondary)
            Spacer(Modifier.height(8.dp))

            if (aplicaRede) {
                val rede = l.redeEncontradaUtcEpochMs
                if (rede == null) {
                    Button(
                        onClick = vm::marcarRede,
                        enabled = !e.gravandoRede,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("botao_rede"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Tokens.PrimaryContainer, contentColor = Tokens.OnPrimaryContainer),
                    ) {
                        Text(if (e.gravandoRede) "Gravando…" else "MARCAR REDE ENCONTRADA", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("Rede: ainda não marcada", color = Tokens.TextSecondary)
                } else {
                    Surface(
                        color = Tokens.SuccessContainer, shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("rede_registrada").semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tokens.Success)
                            Text("Rede já registrada ✓", color = Tokens.Success, fontWeight = FontWeight.Bold)
                            Text("Rede encontrada às ${Tempo.hora(rede)}")
                            Text("Tempo até encontrar: ${Tempo.formatarDuracao(Tempo.medirTempoAteRede(l.inicioUtcEpochMs, rede))}", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                Text("Marco \"rede encontrada\" não se aplica a este tipo.", color = Tokens.TextSecondary)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = vm::pedirFinalizacao,
                enabled = !e.gravandoFim && !e.confirmandoFim,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("botao_finalizar"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Tokens.Primary),
            ) {
                Icon(Icons.Filled.Done, contentDescription = null)
                Text("  FINALIZAR ESCAVAÇÃO", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "O fim é gravado no toque de confirmação. A tela pode apagar à vontade: o tempo continua sendo contado. O preenchimento do formulário não conta como tempo de serviço.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, textAlign = TextAlign.Center,
            )
        }
    }

    if (e.confirmandoFim) {
        AlertDialog(
            onDismissRequest = { if (!e.gravandoFim) vm.desistirFinalizacao() },
            title = { Text("Finalizar escavação?") },
            text = {
                Text("A contagem para no instante em que você tocar \"Finalizar\" abaixo. Depois você preenche os dados do serviço.")
            },
            confirmButton = {
                Button(onClick = { vm.confirmarFinalizacao {} }, enabled = !e.gravandoFim, modifier = Modifier.testTag("confirmar_finalizar")) {
                    Icon(Icons.Filled.Check, contentDescription = null); Text(" Finalizar")
                }
            },
            dismissButton = { TextButton(onClick = vm::desistirFinalizacao, enabled = !e.gravandoFim) { Text("Voltar") } },
        )
    }

    if (confirmarCancelamento) {
        AlertDialog(
            onDismissRequest = { confirmarCancelamento = false },
            title = { Text("Cancelar serviço?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("O serviço ficará como CANCELADO e não entrará nas estatísticas. O registro e os horários são mantidos.")
                    OutlinedTextField(value = motivoCancelamento, onValueChange = { motivoCancelamento = it.take(300) }, label = { Text("Motivo (opcional)") })
                }
            },
            confirmButton = {
                TextButton(onClick = { confirmarCancelamento = false; vm.cancelarServico(motivoCancelamento) {} }) { Text("Cancelar serviço", color = Tokens.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmarCancelamento = false }) { Text("Voltar") } },
        )
    }
}
