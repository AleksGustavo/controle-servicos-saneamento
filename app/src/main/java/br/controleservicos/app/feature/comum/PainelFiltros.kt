package br.controleservicos.app.feature.comum

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import br.controleservicos.app.core.database.BairroEntity
import br.controleservicos.app.core.database.FuncionarioEntity
import br.controleservicos.app.core.database.MaterialRedeEntity
import br.controleservicos.app.core.database.TipoServicoEntity
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Periodo
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.StatusServico
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Dados de catálogo usados pelos filtros. */
data class OpcoesFiltro(
    val bairros: List<BairroEntity>,
    val tipos: List<TipoServicoEntity>,
    val funcionarios: List<FuncionarioEntity>,
    val materiais: List<MaterialRedeEntity>,
    /** Composições exatas existentes: (chave, rótulo, nº de serviços). */
    val composicoes: List<Triple<String, String, Int>> = emptyList(),
)

private val FMT_DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Chips resumidos SOMENTE dos filtros ativos, cada um removível. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipsFiltrosAtivos(f: FiltroServicos, o: OpcoesFiltro, mostrarFuncionario: Boolean, aoMudar: (FiltroServicos) -> Unit) {
    val chips = mutableListOf<Pair<String, FiltroServicos>>()
    val bairros = f.bairroIds + listOfNotNull(f.bairroId)
    if (bairros.isNotEmpty()) {
        val nomes = o.bairros.filter { it.id in bairros }.map { it.nomeOriginal }
        chips += (if (nomes.size <= 2) "Bairro: ${nomes.joinToString(", ")}" else "Bairros: ${nomes.size}") to f.copy(bairroIds = emptySet(), bairroId = null)
    }
    if (f.tipoIds.isNotEmpty() || f.tipoServicoId != null) {
        val ids = f.tipoIds + listOfNotNull(f.tipoServicoId)
        chips += "Tipo: " + o.tipos.filter { it.id in ids }.joinToString(", ") { it.nome } to f.copy(tipoIds = emptySet(), tipoServicoId = null)
    }
    if (f.dataInicial != null || f.dataFinal != null) {
        chips += "${f.dataInicial?.format(FMT_DATA) ?: "…"} a ${f.dataFinal?.format(FMT_DATA) ?: "…"}" to f.copy(dataInicial = null, dataFinal = null)
    } else if (f.periodo != Periodo.TODOS) chips += f.periodo.rotulo to f.copy(periodo = Periodo.TODOS)
    f.funcionarioId?.let { id -> chips += (if (mostrarFuncionario) "Participante: " + (o.funcionarios.firstOrNull { it.id == id }?.nomeExibicao ?: "?") else "Participante selecionado") to f.copy(funcionarioId = null) }
    f.chaveComposicao?.let { k ->
        chips += "Equipe: " + (o.composicoes.firstOrNull { it.first == k }?.second ?: "selecionada") to f.copy(chaveComposicao = null)
    }
    f.materialId?.let { id -> chips += "Material: " + (o.materiais.firstOrNull { it.id == id }?.nome ?: "?") to f.copy(materialId = null) }
    f.status?.let { chips += "Estado: ${it.rotulo}" to f.copy(status = null) }
    if (f.incluirExcluidos) chips += "Incluindo excluídos" to f.copy(incluirExcluidos = false)
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("chips_filtros_ativos")) {
        chips.forEach { (t, sem) ->
            InputChip(selected = true, onClick = { aoMudar(sem) }, label = { Text(t, maxLines = 1) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remover filtro $t") })
        }
    }
}

/** Seleção de um ou vários bairros com busca local (sem rede). */
@Composable
fun DialogoBairrosMulti(bairros: List<BairroEntity>, selecionados: Set<String>, aoConcluir: (Set<String>) -> Unit, aoFechar: () -> Unit) {
    var sel by remember { mutableStateOf(selecionados) }
    var consulta by rememberSaveable { mutableStateOf("") }
    val visiveis = remember(bairros, consulta) { bairros.filter { Texto.correspondeBusca(consulta, it.nomeOriginal) }.sortedBy { it.nomeNormalizado } }
    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Tokens.Background) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bairros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Filled.Close, contentDescription = "Fechar") }
                }
                OutlinedTextField(consulta, { consulta = it.take(80) }, leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Pesquisar bairro") },
                    singleLine = true, modifier = Modifier.fillMaxWidth().testTag("campo_busca_bairros_filtro"))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { sel = emptySet() }.testTag("todos_bairros"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = sel.isEmpty(), onCheckedChange = null)
                    Text("Todos os bairros", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                }
                HorizontalDivider(color = Tokens.Border)
                LazyColumn(Modifier.weight(1f)) {
                    items(visiveis, key = { it.id }) { b ->
                        val marcado = b.id in sel
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(marcado, role = Role.Checkbox) { sel = if (it) sel + b.id else sel - b.id }
                                .testTag("filtro_bairro_${b.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = marcado, onCheckedChange = null)
                            Text(b.nomeOriginal, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                BotaoPrimario(if (sel.isEmpty()) "Usar todos os bairros" else "Usar ${sel.size} bairro(s)", { aoConcluir(sel) }, modifier = Modifier.testTag("concluir_bairros"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeletorData(rotulo: String, valor: LocalDate?, tag: String, aoMudar: (LocalDate?) -> Unit) {
    var aberto by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { aberto = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) { Text("$rotulo: ${valor?.format(FMT_DATA) ?: "—"}") }
        if (valor != null) IconButton(onClick = { aoMudar(null) }) { Icon(Icons.Filled.Close, contentDescription = "Limpar $rotulo") }
    }
    if (aberto) {
        val estado = rememberDatePickerState(initialSelectedDateMillis = valor?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { aberto = false },
            confirmButton = {
                TextButton(onClick = {
                    aberto = false
                    estado.selectedDateMillis?.let { aoMudar(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { aberto = false }) { Text("Cancelar") } },
        ) { DatePicker(state = estado) }
    }
}

/**
 * Painel (bottom sheet) de filtros combinados por AND: bairro em destaque (um ou vários),
 * período (atalhos ou datas), tipo, participante, material e estado. Não altera dados.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PainelFiltros(
    inicial: FiltroServicos,
    o: OpcoesFiltro,
    mostrarStatus: Boolean,
    mostrarSomenteFinalizados: Boolean,
    aoAplicar: (FiltroServicos) -> Unit,
    aoFechar: () -> Unit,
) {
    val estadoSheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var f by remember { mutableStateOf(inicial) }
    var escolhendoBairros by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = aoFechar, sheetState = estadoSheet, containerColor = Tokens.Background) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Filtros", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Bairro — ${LocalOperacional.ROTULO}", style = MaterialTheme.typography.titleMedium, color = Tokens.Primary)
            val bairrosSel = f.bairroIds + listOfNotNull(f.bairroId)
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = Tokens.PrimaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { escolhendoBairros = true }.testTag("filtro_bairros"),
            ) {
                Text(
                    if (bairrosSel.isEmpty()) "Todos os bairros" else o.bairros.filter { it.id in bairrosSel }.joinToString(", ") { it.nomeOriginal },
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp),
                )
            }
            Text("Período", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Periodo.entries.forEach { p ->
                    FilterChip(
                        selected = f.dataInicial == null && f.dataFinal == null && f.periodo == p,
                        onClick = { f = f.copy(periodo = p, dataInicial = null, dataFinal = null) },
                        label = { Text(p.rotulo) },
                    )
                }
            }
            SeletorData("De", f.dataInicial, "filtro_data_inicial") { f = f.copy(dataInicial = it) }
            SeletorData("Até", f.dataFinal, "filtro_data_final") { f = f.copy(dataFinal = it) }
            if (f.dataInicial != null && f.dataFinal != null && f.dataInicial!! > f.dataFinal!!) {
                Text("A data inicial é posterior à final.", color = Tokens.Error, style = MaterialTheme.typography.bodySmall)
            }
            Text("Tipo de serviço", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val tiposSel = f.tipoIds + listOfNotNull(f.tipoServicoId)
                o.tipos.forEach { t ->
                    val sel = t.id in tiposSel
                    FilterChip(selected = sel, onClick = { f = f.copy(tipoServicoId = null, tipoIds = if (sel) tiposSel - t.id else tiposSel + t.id) }, label = { Text(t.nome) },
                        modifier = Modifier.testTag("filtro_tipo_${t.id}"))
                }
            }
            if (o.funcionarios.isNotEmpty()) {
                Text("Participante", style = MaterialTheme.typography.titleSmall)
                ChipMenu(
                    rotulo = o.funcionarios.firstOrNull { it.id == f.funcionarioId }?.nomeExibicao ?: "Todos",
                    selecionado = f.funcionarioId != null,
                    opcoes = listOf<Pair<String?, String>>(null to "Todos") + o.funcionarios.sortedBy { it.nomeNormalizado }.map { it.id to it.nomeExibicao },
                    aoEscolher = { f = f.copy(funcionarioId = it) },
                )
            }
            if (o.composicoes.isNotEmpty()) {
                Text("Equipe (composição exata)", style = MaterialTheme.typography.titleSmall)
                ChipMenu(
                    rotulo = o.composicoes.firstOrNull { it.first == f.chaveComposicao }?.second ?: "Todas",
                    selecionado = f.chaveComposicao != null,
                    opcoes = listOf<Pair<String?, String>>(null to "Todas") + o.composicoes.map { (k, r, n) -> k to "$r ($n)" },
                    aoEscolher = { f = f.copy(chaveComposicao = it) },
                )
                Text("Mesmas pessoas, em qualquer ordem. Trocar uma pessoa forma outra equipe.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }
            Text("Material da rede", style = MaterialTheme.typography.titleSmall)
            ChipMenu(
                rotulo = o.materiais.firstOrNull { it.id == f.materialId }?.nome ?: "Todos",
                selecionado = f.materialId != null,
                opcoes = listOf<Pair<String?, String>>(null to "Todos") + o.materiais.map { it.id to it.nome },
                aoEscolher = { f = f.copy(materialId = it) },
            )
            if (mostrarStatus) {
                Text("Estado", style = MaterialTheme.typography.titleSmall)
                ChipMenu(
                    rotulo = f.status?.rotulo ?: "Todos",
                    selecionado = f.status != null,
                    opcoes = listOf<Pair<StatusServico?, String>>(null to "Todos") + StatusServico.entries.map { it to it.rotulo },
                    aoEscolher = { f = f.copy(status = it) },
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(f.incluirExcluidos, role = Role.Switch) { f = f.copy(incluirExcluidos = it) }, verticalAlignment = Alignment.CenterVertically) {
                    Text("Mostrar excluídos", modifier = Modifier.weight(1f)); Switch(checked = f.incluirExcluidos, onCheckedChange = null)
                }
            }
            if (mostrarSomenteFinalizados) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(f.somenteFinalizados, role = Role.Switch) { f = f.copy(somenteFinalizados = it) }.testTag("somente_concluidos"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Somente concluídos", modifier = Modifier.weight(1f)); Switch(checked = f.somenteFinalizados, onCheckedChange = null)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { f = FiltroServicos(texto = f.texto, somenteFinalizados = mostrarSomenteFinalizados) }, modifier = Modifier.heightIn(min = 48.dp).testTag("limpar_filtros_painel")) { Text("Limpar filtros") }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = aoFechar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") }
            }
            BotaoPrimario("Aplicar filtros", { aoAplicar(f) }, habilitado = !(f.dataInicial != null && f.dataFinal != null && f.dataInicial!! > f.dataFinal!!), modifier = Modifier.testTag("aplicar_filtros"))
            androidx.compose.foundation.layout.Spacer(Modifier.heightIn(min = 16.dp))
        }
    }
    if (escolhendoBairros) {
        DialogoBairrosMulti(o.bairros, f.bairroIds + listOfNotNull(f.bairroId), aoConcluir = { f = f.copy(bairroIds = it, bairroId = null); escolhendoBairros = false }, aoFechar = { escolhendoBairros = false })
    }
}
