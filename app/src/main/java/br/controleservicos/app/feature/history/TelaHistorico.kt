package br.controleservicos.app.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.EstadoVazio
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.feature.comum.ChipsFiltrosAtivos
import br.controleservicos.app.feature.comum.OpcoesFiltro
import br.controleservicos.app.feature.comum.PainelFiltros
import br.controleservicos.app.feature.share.DialogoCompartilhar
import kotlinx.coroutines.flow.combine

/** Aba Finalizados (padrão) exclui em andamento; Em andamento mostra só ativos (sem misturar durações). */
fun filtrarAba(lista: List<LinhaServico>, emAndamento: Boolean, f: FiltroServicos): List<LinhaServico> =
    if (emAndamento) lista.filter { it.status == StatusServico.EM_ANDAMENTO }
    else lista.filter { it.status != StatusServico.EM_ANDAMENTO && (f.status != null || it.status != StatusServico.CANCELADO) }

@Composable
fun TelaHistorico(container: AppContainer, aoVoltar: () -> Unit, aoAbrir: (String) -> Unit, aoExportar: () -> Unit) {
    val repo = container.repositorio
    val filtro by container.filtro.collectAsStateWithLifecycle()
    val tipos by remember { repo.observarTipos() }.collectAsStateWithLifecycle(emptyList())
    val bairros by remember { repo.observarBairros() }.collectAsStateWithLifecycle(emptyList())
    val funcionarios by remember { repo.observarFuncionarios() }.collectAsStateWithLifecycle(emptyList())
    val materiais by remember { repo.observarMateriais() }.collectAsStateWithLifecycle(emptyList())
    val ligacoes by remember { repo.observarLigacoes() }.collectAsStateWithLifecycle(emptyMap())
    val base by remember {
        combine(repo.observarLinhas(), container.filtro, repo.observarParticipacoes()) { l, f, p -> f.aplicar(l, participantes = p) }
    }.collectAsStateWithLifecycle(null)
    var emAndamento by rememberSaveable { mutableStateOf(false) }
    var painel by rememberSaveable { mutableStateOf(false) }
    var compartilhar by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); msg = null } }
    val participacoes by remember { repo.observarParticipacoes() }.collectAsStateWithLifecycle(emptyMap())
    val opcoes = OpcoesFiltro(bairros, tipos.filter { it.ativo }, funcionarios, materiais, Composicao.opcoes(participacoes, comNomes = true))

    val lista = base?.let { filtrarAba(it, emAndamento, filtro) }
    val nAndamento = base?.count { it.status == StatusServico.EM_ANDAMENTO } ?: 0

    Scaffold(
        topBar = {
            BarraTopo("Histórico", aoVoltar) {
                IconButton(onClick = aoExportar) { Icon(Icons.Filled.Share, contentDescription = "Exportar com filtros") }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Tokens.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !emAndamento, onClick = { emAndamento = false }, label = { Text("Finalizados") }, modifier = Modifier.heightIn(min = 44.dp).testTag("aba_finalizados"))
                    FilterChip(selected = emAndamento, onClick = { emAndamento = true }, label = { Text("Em andamento ($nAndamento)") }, modifier = Modifier.heightIn(min = 44.dp).testTag("aba_andamento"))
                }
                OutlinedTextField(
                    value = filtro.texto,
                    onValueChange = { v -> container.filtro.value = filtro.copy(texto = v.take(80)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filtro.texto.isNotEmpty()) IconButton(onClick = { container.filtro.value = filtro.copy(texto = "") }) { Icon(Icons.Filled.Clear, contentDescription = "Limpar busca") }
                    },
                    placeholder = { Text("Buscar bairro, rua, número, tipo…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("campo_busca"),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { painel = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("botao_filtros")) { Text("Filtros") }
                    if (filtro.ativo) TextButton(onClick = { container.filtro.value = FiltroServicos() }, modifier = Modifier.heightIn(min = 48.dp).testTag("limpar_filtros")) { Text("Limpar filtros") }
                }
                ChipsFiltrosAtivos(filtro, opcoes, mostrarFuncionario = true) { container.filtro.value = it }
                Text("${lista?.size ?: 0} serviço(s)", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.testTag("contagem_historico"))
            }
            when {
                lista == null -> EstadoVazio("Carregando…")
                lista.isEmpty() -> EstadoVazio(
                    when {
                        filtro.ativo -> "Nenhum serviço com estes filtros. Toque em \"Limpar filtros\"."
                        emAndamento -> "Nenhum serviço em andamento."
                        else -> "Nenhum serviço finalizado ainda."
                    },
                )
                else -> LazyColumn(Modifier.fillMaxSize().testTag("lista_historico")) {
                    items(lista, key = { it.id }) { s ->
                        CartaoServico(
                            s, ligacoes[s.id]?.size ?: 0, aoAbrir = { aoAbrir(s.id) },
                            aoCompartilhar = if (s.fimUtcEpochMs != null && !s.excluido) ({ compartilhar = s.id }) else null,
                        )
                    }
                }
            }
        }
    }
    if (painel) {
        PainelFiltros(
            filtro, opcoes, mostrarStatus = true, mostrarSomenteFinalizados = false,
            aoAplicar = { container.filtro.value = it; painel = false }, aoFechar = { painel = false },
        )
    }
    compartilhar?.let { id -> DialogoCompartilhar(container, id, aoFechar = { compartilhar = null }, aoMensagem = { msg = it }) }
}

@Composable
private fun CartaoServico(s: LinhaServico, nLigacoes: Int, aoAbrir: () -> Unit, aoCompartilhar: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = aoAbrir).heightIn(min = 64.dp).padding(start = 16.dp, top = 8.dp, bottom = 8.dp).testTag("item_${s.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.tipoNome, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    if (s.status == StatusServico.EM_ANDAMENTO) "em curso" else Tempo.formatarDuracao(s.duracaoTotalMs),
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
            Text("${Tempo.data(s.inicioUtcEpochMs)} • ${s.bairroNome}", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            Text(
                "${s.logradouro}, ${s.numero}" + if (nLigacoes > 0) " • $nLigacoes ligação(ões) trocada(s)" else "",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
            val selo = when {
                s.excluido -> "EXCLUÍDO" to Tokens.Error
                s.status == StatusServico.CANCELADO -> "CANCELADO" to Tokens.Error
                s.status == StatusServico.EM_ANDAMENTO -> "EM ANDAMENTO" to Tokens.Primary
                s.requerRevisao -> "REVISAR HORÁRIOS" to Tokens.Warning
                s.status == StatusServico.ENCERRADO_PENDENTE_COMPLEMENTOS -> "DADOS INCOMPLETOS" to Tokens.Warning
                else -> null
            }
            selo?.let { (t, c) ->
                Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text(t, color = c, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        if (aoCompartilhar != null) {
            IconButton(onClick = aoCompartilhar, modifier = Modifier.testTag("compartilhar_${s.id}")) {
                Icon(Icons.Filled.Share, contentDescription = "Compartilhar serviço", tint = Tokens.Primary)
            }
        }
        IconButton(onClick = aoAbrir) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Ver detalhes") }
    }
    HorizontalDivider(color = Tokens.Border)
}
