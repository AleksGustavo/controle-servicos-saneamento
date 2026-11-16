package br.controleservicos.app.feature.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.ParRotuloValor
import br.controleservicos.app.core.design.Secao
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.BaseTempo
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.feature.team.ListaParticipantes
import br.controleservicos.app.feature.share.DialogoCompartilhar
import androidx.compose.material.icons.filled.Share
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.feature.completion.ResumoTempos
import br.controleservicos.app.feature.comum.fabrica
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetalheViewModel(container: AppContainer, private val id: String) : ViewModel() {
    private val repo = container.repositorio
    val servico = repo.observarLinha(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val condicoes = repo.observarCondicoesDe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val eventos = repo.observarEventos(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val participantes = repo.observarParticipantesDe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ligacoes = repo.observarLigacoesDe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mensagem = MutableStateFlow<String?>(null)

    private fun executar(sucesso: String, bloco: suspend () -> Unit) {
        viewModelScope.launch {
            mensagem.value = try {
                bloco(); sucesso
            } catch (e: Exception) {
                e.message ?: "Falha ao gravar."
            }
        }
    }

    fun excluir() = executar("Registro excluído (pode ser restaurado em Histórico › Mostrar excluídos).") { repo.excluir(id) }
    fun restaurar() = executar("Registro restaurado.") { repo.restaurar(id) }

    /** Retorna erro de validação, ou null se enviado para gravação. */
    fun corrigir(inicio: String, rede: String, fim: String, justificativa: String): String? {
        val atual = servico.value ?: return "Registro não carregado."
        // Campo não alterado preserva o valor original com milissegundos.
        fun ler(texto: String, original: Long?): Long? =
            if (original != null && texto.trim() == Tempo.dataHora(original)) original else Tempo.parseDataHora(texto)
        val i = ler(inicio, atual.inicioUtcEpochMs) ?: return "Início inválido (use dd/mm/aaaa hh:mm:ss)."
        val r = if (rede.isBlank()) null else (ler(rede, atual.redeEncontradaUtcEpochMs) ?: return "Rede encontrada inválida.")
        val f = ler(fim, atual.fimUtcEpochMs) ?: return "Fim inválido."
        Tempo.validarOrdem(i, r, f)?.let { return it }
        if (justificativa.trim().length < 5) return "Informe uma justificativa (mín. 5 caracteres)."
        executar("Horários corrigidos. Valores anteriores ficaram na auditoria.") { repo.corrigirHorarios(id, i, r, f, justificativa) }
        return null
    }
}

@Composable
fun TelaDetalhe(container: AppContainer, servicoId: String, aoVoltar: () -> Unit, aoComplementar: () -> Unit, aoCronometro: () -> Unit, aoParticipantes: () -> Unit = {}) {
    val vm: DetalheViewModel = viewModel(key = "det_$servicoId", factory = fabrica { DetalheViewModel(container, servicoId) })
    val s by vm.servico.collectAsStateWithLifecycle()
    val condicoes by vm.condicoes.collectAsStateWithLifecycle()
    val eventos by vm.eventos.collectAsStateWithLifecycle()
    val participantes by vm.participantes.collectAsStateWithLifecycle()
    val ligacoes by vm.ligacoes.collectAsStateWithLifecycle()
    var compartilhando by remember { mutableStateOf(false) }
    val msg by vm.mensagem.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var confirmarExclusao by remember { mutableStateOf(false) }
    var corrigindo by remember { mutableStateOf(false) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); vm.mensagem.value = null } }

    Scaffold(topBar = { BarraTopo("Detalhes do serviço", aoVoltar) }, snackbarHost = { SnackbarHost(snack) }, containerColor = Tokens.Background) { pad ->
        val l = s
        if (l == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                l.status.rotulo + if (l.excluido) " • EXCLUÍDO" else "",
                color = when {
                    l.excluido || l.status == StatusServico.CANCELADO -> Tokens.Error
                    l.incompleto -> Tokens.Warning
                    else -> Tokens.Success
                },
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("status_detalhe"),
            )
            if (l.requerRevisao) Aviso(l.motivoRevisao ?: "Horários precisam de revisão.")
            if (l.foraDoMunicipioOperacional) {
                Aviso("Registro de versão anterior com município ${l.municipioNome}${l.municipioUf?.let { "/$it" } ?: ""} (diferente de ${LocalOperacional.ROTULO}). Mantido como foi gravado.")
            }
            ResumoTempos(l)
            l.baseTempo?.takeIf { it != BaseTempo.MONOTONICO }?.let {
                Aviso("Duração sujeita a ajuste de relógio: ${it.rotulo}.", modifier = Modifier.testTag("aviso_base_tempo"))
            }

            Secao("Local") {
                ParRotuloValor("Município", l.municipioNome + (l.municipioUf?.let { "/$it" } ?: ""))
                ParRotuloValor("Bairro", l.bairroNome)
                ParRotuloValor("Endereço", l.enderecoResumido)
                l.equipeIdentificador?.let { ParRotuloValor("Equipe (texto antigo)", it) }
            }
            Secao("Participantes") {
                ListaParticipantes(participantes)
                if (!l.excluido) {
                    TextButton(onClick = aoParticipantes, modifier = Modifier.testTag("botao_corrigir_participantes")) {
                        Text(if (participantes.isEmpty()) "Adicionar participantes" else "Corrigir participantes")
                    }
                }
            }
            Secao(if (l.ramalAguaTrocado) "Ligações trocadas (${ligacoes.size})" else "Ligações trocadas") {
                if (!l.ramalAguaTrocado) {
                    Text("Não houve troca de ligação/ramal.", color = Tokens.TextSecondary)
                } else {
                    ligacoes.forEach { lg ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("detalhe_ligacao_${lg.ordem}")) {
                            Text("Ligação ${lg.ordem}: ${lg.descricaoImovel(l.logradouro, l.bairroNome)}", fontWeight = FontWeight.Medium)
                            Text("${lg.lado.rotulo} • ${Numeros.formatarMetrosBr(lg.comprimentoCm)} m", modifier = Modifier.testTag("detalhe_comprimento_ramal_${lg.ordem}"))
                            if (lg.pendenteIdentificacao) Text("Número do imóvel pendente de identificação (registro antigo).", style = MaterialTheme.typography.bodySmall, color = Tokens.Warning)
                            lg.observacoes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
                        }
                    }
                    if (ligacoes.size > 1) Text("Total: ${Numeros.formatarMetrosBr(ligacoes.sumOf { it.comprimentoCm })} m", style = MaterialTheme.typography.bodySmall)
                }
            }
            Secao("Dados da rede") {
                ParRotuloValor("Rede encontrada?", l.situacaoRede?.rotulo ?: "—")
                ParRotuloValor("Diâmetro", l.diametroMm?.let { "$it mm" } ?: "Não informado")
                ParRotuloValor("Material", l.materialNome ?: "Não informado")
                ParRotuloValor("Profundidade", l.profundidadeMetros?.let { "${Numeros.formatarDecimalBr(it)} m" } ?: "Não informada")
                ParRotuloValor("Ligações existentes no local", l.quantidadeLigacoes?.toString() ?: "Não informado")
            }
            Secao("Condições observadas") {
                if (condicoes.isEmpty()) Text("Nenhuma registrada", color = Tokens.TextSecondary)
                condicoes.forEach { c -> Text("• ${c.nome}" + (c.descricao?.let { ": $it" } ?: "")) }
            }
            if (!l.observacoes.isNullOrBlank()) Secao("Observações") { Text(l.observacoes) }

            if (l.fimUtcEpochMs != null && !l.excluido) {
                BotaoPrimario("Compartilhar serviço", { compartilhando = true }, icone = Icons.Filled.Share, modifier = Modifier.testTag("botao_compartilhar_servico"))
            }
            when {
                l.status == StatusServico.EM_ANDAMENTO && !l.excluido -> BotaoPrimario("Abrir cronômetro", aoCronometro, icone = Icons.Filled.PlayArrow)
                l.status == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS && !l.excluido -> BotaoPrimario("Complementar", aoComplementar, icone = Icons.Filled.Edit)
                l.status == StatusServico.CONCLUIDO && !l.excluido -> BotaoSecundario("Editar complementos", aoComplementar, icone = Icons.Filled.Edit)
            }
            if (l.status != StatusServico.EM_ANDAMENTO && !l.excluido && l.fimUtcEpochMs != null) {
                BotaoSecundario("Corrigir horários", { corrigindo = true }, icone = Icons.Filled.Edit, modifier = Modifier.testTag("botao_corrigir"))
            }
            if (l.excluido) {
                BotaoSecundario("Restaurar registro", vm::restaurar, icone = Icons.Filled.Refresh)
            } else if (l.status != StatusServico.EM_ANDAMENTO) {
                BotaoSecundario("Excluir registro", { confirmarExclusao = true }, icone = Icons.Filled.Delete, modifier = Modifier.testTag("botao_excluir"))
            }

            Secao("Auditoria (eventos)") {
                eventos.forEach { ev ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${ev.tipoEvento.name} — ${Tempo.dataHora(ev.timestampUtcEpochMs)}", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        if (ev.wallClockBrutoMs != null && ev.wallClockBrutoMs != ev.timestampUtcEpochMs) {
                            Text("Relógio do aparelho no toque: ${Tempo.dataHora(ev.wallClockBrutoMs)}", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                        }
                        ev.motivo?.let { Text("Motivo: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
                        ev.dadosAnteriores?.let { Text("Antes: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
                        ev.dadosNovos?.let { Text("Depois: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
                    }
                    HorizontalDivider(color = Tokens.Border)
                }
            }
            Text("ID: ${l.id}", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
        }
    }

    if (compartilhando) {
        DialogoCompartilhar(container, servicoId, aoFechar = { compartilhando = false }, aoMensagem = { vm.mensagem.value = it })
    }
    if (confirmarExclusao) {
        AlertDialog(
            onDismissRequest = { confirmarExclusao = false },
            title = { Text("Excluir registro?") },
            text = { Text("O registro deixará de contar nas estatísticas e nas exportações padrão. É uma exclusão lógica: ele pode ser restaurado depois.") },
            confirmButton = { TextButton(onClick = { confirmarExclusao = false; vm.excluir() }, modifier = Modifier.testTag("confirmar_exclusao")) { Text("Excluir", color = Tokens.Error) } },
            dismissButton = { TextButton(onClick = { confirmarExclusao = false }) { Text("Voltar") } },
        )
    }
    val l = s
    if (corrigindo && l != null) DialogoCorrecao(l, aoFechar = { corrigindo = false }, aoSalvar = vm::corrigir)
}

@Composable
private fun DialogoCorrecao(l: LinhaServico, aoFechar: () -> Unit, aoSalvar: (String, String, String, String) -> String?) {
    var inicio by remember { mutableStateOf(Tempo.dataHora(l.inicioUtcEpochMs)) }
    var rede by remember { mutableStateOf(l.redeEncontradaUtcEpochMs?.let { Tempo.dataHora(it) } ?: "") }
    var fim by remember { mutableStateOf(Tempo.dataHora(l.fimUtcEpochMs)) }
    var just by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text("Corrigir horários") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Formato dd/mm/aaaa hh:mm:ss. Os valores originais ficam guardados na auditoria.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(inicio, { inicio = it }, label = { Text("Início") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                OutlinedTextField(rede, { rede = it }, label = { Text("Rede encontrada (vazio = sem marco)") }, singleLine = true)
                OutlinedTextField(fim, { fim = it }, label = { Text("Fim") }, singleLine = true)
                OutlinedTextField(just, { just = it.take(300) }, label = { Text("Justificativa *") })
                erro?.let { Text(it, color = Tokens.Error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                erro = aoSalvar(inicio, rede, fim, just)
                if (erro == null) aoFechar()
            }) { Text("Salvar correção") }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}
