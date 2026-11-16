package br.controleservicos.app.feature.export

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.BuildConfig
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.export.ArquivosAndroid
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.export.GeradorExportacao
import br.controleservicos.app.core.export.PedidoExportacao
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.feature.comum.ChipsFiltrosAtivos
import br.controleservicos.app.feature.comum.DialogoBairrosMulti
import br.controleservicos.app.feature.comum.OpcoesFiltro
import br.controleservicos.app.feature.comum.PainelFiltros
import br.controleservicos.app.feature.comum.descreverFiltro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Monta o pedido a partir de um instantâneo consistente do banco, aplicando EXATAMENTE os filtros. */
suspend fun montarPedido(
    container: AppContainer,
    formato: FormatoExportacao,
    filtro: FiltroServicos,
    descricao: String,
    incluirNomes: Boolean = false,
): PedidoExportacao {
    val inst = container.repositorio.instantaneoExportacao()
    val selecionadas = filtro.aplicar(inst.linhas, participantes = inst.participantes)
    val prefs = container.preferencias.preferencias.first()
    return PedidoExportacao(
        formato = formato,
        linhas = selecionadas.sortedBy { it.inicioUtcEpochMs },
        condicoes = inst.condicoes,
        descricaoFiltros = descricao,
        versaoApp = BuildConfig.VERSION_NAME,
        limiarAmostra = prefs.limiarAmostra,
        geradoEmMs = container.relogio.agora().wallClockMs,
        zona = LocalOperacional.ZONA,
        participantes = inst.participantes,
        incluirNomes = incluirNomes,
        ligacoes = inst.ligacoes,
    )
}

private fun mensagemErro(e: Throwable): String = when {
    e is IOException && (e.message?.contains("ENOSPC") == true || e.message?.contains("space", true) == true) ->
        "Sem espaço livre no aparelho para gerar o arquivo."
    e is IOException -> "Falha ao gravar o arquivo: ${e.message ?: "destino indisponível"}."
    e is SecurityException -> "Sem permissão para gravar no destino escolhido."
    else -> "Erro na exportação: ${e.message ?: e.javaClass.simpleName}."
}

/** Arquivo gerado no cache, aguardando o usuário escolher Salvar ou Compartilhar. */
private data class Gerado(val arquivo: File, val uri: Uri, val formato: FormatoExportacao, val registros: Int)

@Composable
fun TelaExportar(container: AppContainer, aoVoltar: () -> Unit) {
    val context = LocalContext.current
    val repo = container.repositorio
    val filtro by container.filtroExportacao.collectAsStateWithLifecycle()
    val tipos by remember { repo.observarTipos() }.collectAsStateWithLifecycle(emptyList())
    val municipios by remember { repo.observarMunicipios() }.collectAsStateWithLifecycle(emptyList())
    val bairros by remember { repo.observarBairros() }.collectAsStateWithLifecycle(emptyList())
    val funcionarios by remember { repo.observarFuncionarios() }.collectAsStateWithLifecycle(emptyList())
    val materiais by remember { repo.observarMateriais() }.collectAsStateWithLifecycle(emptyList())
    val selecao by remember {
        combine(repo.observarLinhas(), container.filtroExportacao, repo.observarParticipacoes()) { l, f, p -> f.aplicar(l, participantes = p) }
    }.collectAsStateWithLifecycle(null)
    // Padrão conservador: sem nomes de funcionários.
    var incluirNomes by rememberSaveable { mutableStateOf(false) }
    var painel by rememberSaveable { mutableStateOf(false) }
    var escolhendoBairros by rememberSaveable { mutableStateOf(false) }
    var trabalhando by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var gerado by remember { mutableStateOf<Gerado?>(null) }
    val snack = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); msg = null } }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ArquivosAndroid.limparAntigos(context) } }
    val participacoes by remember { repo.observarParticipacoes() }.collectAsStateWithLifecycle(emptyMap())
    // Sem nomes: as equipes aparecem pelos pseudônimos F-xxxxxx.
    val opcoes = OpcoesFiltro(bairros, tipos.filter { it.ativo }, funcionarios, materiais, Composicao.opcoes(participacoes, comNomes = incluirNomes))

    val qtd = selecao?.size ?: 0
    val periodo = selecao?.takeIf { it.isNotEmpty() }?.let { l -> "${Tempo.data(l.minOf { it.inicioUtcEpochMs })} a ${Tempo.data(l.maxOf { it.inicioUtcEpochMs })}" }
    // Sem nomes: a descrição do filtro também não cita o participante pelo nome.
    val descricao = descreverFiltro(filtro, tipos, municipios, bairros, if (incluirNomes) funcionarios else emptyList()) +
        (if (filtro.bairroIds.isNotEmpty()) "; Bairros: " + bairros.filter { it.id in filtro.bairroIds }.joinToString(", ") { it.nomeOriginal } else "")
    val bairroUnico = (filtro.bairroIds + listOfNotNull(filtro.bairroId)).singleOrNull()?.let { id -> bairros.firstOrNull { it.id == id }?.nomeOriginal }

    fun gerar(ctx: Context, formato: FormatoExportacao) {
        trabalhando = true
        job = scope.launch(Dispatchers.Main) {
            try {
                val pedido = montarPedido(container, formato, filtro, descricao, incluirNomes)
                if (pedido.linhas.isEmpty()) { msg = "Nenhum serviço corresponde aos filtros. Nada foi gerado."; return@launch }
                val nome = GeradorExportacao.nomeArquivo(formato, System.currentTimeMillis(), LocalOperacional.ZONA, bairroUnico)
                val uri = withContext(Dispatchers.IO) { ArquivosAndroid.gerarNoCache(ctx, nome) { GeradorExportacao.gerar(pedido, it) } }
                gerado = Gerado(File(ArquivosAndroid.pastaExportacao(ctx), nome), uri, formato, pedido.linhas.size)
            } catch (e: CancellationException) {
                msg = "Exportação cancelada."
            } catch (e: Throwable) {
                msg = mensagemErro(e)
            } finally {
                trabalhando = false
            }
        }
    }

    val salvarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val g = gerado ?: return@rememberLauncherForActivityResult
        if (uri == null) { msg = "Salvamento cancelado. Nenhum arquivo foi gravado."; return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) { ArquivosAndroid.gravarEmUri(context, uri) { out -> g.arquivo.inputStream().use { it.copyTo(out) } } }
                msg = "Arquivo salvo com ${g.registros} serviço(s)."
                gerado = null
            } catch (e: Throwable) { msg = mensagemErro(e) }
        }
    }

    Scaffold(topBar = { BarraTopo("Exportar", aoVoltar) }, snackbarHost = { SnackbarHost(snack) }, containerColor = Tokens.Background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Bairro — ${LocalOperacional.ROTULO}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tokens.Primary)
            val sel = filtro.bairroIds + listOfNotNull(filtro.bairroId)
            Surface(
                shape = RoundedCornerShape(12.dp), color = Tokens.PrimaryContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { escolhendoBairros = true }.testTag("export_bairros"),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (sel.isEmpty()) "Todos os bairros" else bairros.filter { it.id in sel }.joinToString(", ") { it.nomeOriginal }, fontWeight = FontWeight.Bold)
                    Text("Tocar para escolher um ou vários bairros", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                }
            }
            OutlinedButton(onClick = { painel = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("export_mais_filtros")) { Text("Período, tipo e outros filtros") }
            ChipsFiltrosAtivos(filtro.copy(bairroIds = emptySet(), bairroId = null), opcoes, mostrarFuncionario = incluirNomes) { novo ->
                container.filtroExportacao.value = novo.copy(bairroIds = filtro.bairroIds, bairroId = filtro.bairroId, somenteFinalizados = filtro.somenteFinalizados)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(filtro.somenteFinalizados, role = Role.Switch) { container.filtroExportacao.value = filtro.copy(somenteFinalizados = it) }
                    .testTag("export_somente_concluidos"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Somente concluídos", modifier = Modifier.weight(1f)); Switch(checked = filtro.somenteFinalizados, onCheckedChange = null)
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(incluirNomes, role = Role.Switch) { incluirNomes = it }.testTag("incluir_nomes"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Incluir nomes dos funcionários", modifier = Modifier.weight(1f)); Switch(checked = incluirNomes, onCheckedChange = null)
            }
            Text(
                if (incluirNomes) "Os nomes sairão no arquivo. Envie só a quem precisa dessa informação."
                else "Sem nomes: cada pessoa aparece como um código (ex.: F-3fa91c), o mesmo em todas as exportações.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
            Surface(color = Tokens.SurfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    // Enquanto a consulta carrega, não mostrar "0" (pareceria que não há serviços).
                    Text(if (selecao == null) "Contando serviços…" else "$qtd serviço(s) encontrado(s)", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("contagem_exportacao"))
                    Text(
                        periodo?.let { "Período efetivo: $it" } ?: if (selecao == null) "" else "Sem registros com estes filtros. Remova filtros para continuar.",
                        style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
                    )
                }
            }
            if (filtro != FiltroServicos(somenteFinalizados = true)) {
                TextButton(onClick = { container.filtroExportacao.value = FiltroServicos(somenteFinalizados = true) }, modifier = Modifier.heightIn(min = 48.dp).testTag("export_limpar_filtros")) { Text("Limpar filtros") }
            }
            Aviso("O arquivo contém endereços. Ao compartilhar, ele sai deste aparelho — envie apenas a quem precisa.")
            if (trabalhando) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                BotaoSecundario("Cancelar geração", { job?.cancel() })
            }
            BotaoPrimario("Exportar Excel (.xlsx)", { gerar(context, FormatoExportacao.XLSX) }, habilitado = !trabalhando && qtd > 0, icone = Icons.Filled.Done, modifier = Modifier.testTag("exportar_xlsx"))
            BotaoSecundario("Exportar CSV (.zip)", { gerar(context, FormatoExportacao.CSV_ZIP) }, habilitado = !trabalhando && qtd > 0, modifier = Modifier.testTag("exportar_csv"))
            TextButton(onClick = aoVoltar, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancelar") }
            Text(
                "Excel: abas Serviços (1 linha por serviço), Ligações trocadas (1 linha por imóvel), Participantes, Indicadores por bairro, Resumo_equipes e Metadados. " +
                    "CSV: arquivo .zip com servicos.csv, ligacoes_trocadas.csv, participantes.csv, indicadores_por_bairro.csv e metadados.csv (UTF-8, \";\").",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
        }
    }
    if (painel) {
        PainelFiltros(filtro, opcoes, mostrarStatus = true, mostrarSomenteFinalizados = true, aoAplicar = { container.filtroExportacao.value = it; painel = false }, aoFechar = { painel = false })
    }
    if (escolhendoBairros) {
        DialogoBairrosMulti(
            bairros, filtro.bairroIds + listOfNotNull(filtro.bairroId),
            aoConcluir = { container.filtroExportacao.value = filtro.copy(bairroIds = it, bairroId = null); escolhendoBairros = false },
            aoFechar = { escolhendoBairros = false },
        )
    }
    gerado?.let { g ->
        AlertDialog(
            onDismissRequest = { gerado = null },
            title = { Text("Arquivo pronto") },
            text = { Text("${g.arquivo.name}\n${g.registros} serviço(s). Escolha salvar no aparelho ou compartilhar.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!ArquivosAndroid.compartilhar(context, g.uri, g.formato.mime, g.arquivo.name)) msg = "Nenhum aplicativo aceita este arquivo. Use \"Salvar\"."
                    gerado = null
                }, modifier = Modifier.testTag("export_compartilhar")) {
                    Icon(Icons.Filled.Share, contentDescription = null); Text(" Compartilhar")
                }
            },
            dismissButton = { TextButton(onClick = { salvarLauncher.launch(g.arquivo.name) }, modifier = Modifier.testTag("export_salvar")) { Text("Salvar") } },
        )
    }
}
