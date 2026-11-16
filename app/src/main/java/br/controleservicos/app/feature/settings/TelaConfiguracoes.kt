package br.controleservicos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import br.controleservicos.app.BuildConfig
import br.controleservicos.app.core.backup.BackupBanco
import br.controleservicos.app.core.backup.BackupInvalidoException
import br.controleservicos.app.core.backup.InfoBackup
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.domain.Tempo
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.Secao
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.core.model.RegrasNegocio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun TelaConfiguracoes(container: AppContainer, aoVoltar: () -> Unit) {
    val repo = container.repositorio
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); msg = null } }

    var limiar by remember { mutableStateOf("") }
    var limiteRamal by remember { mutableStateOf("") }
    var formato by remember { mutableStateOf(FormatoExportacao.XLSX) }
    LaunchedEffect(Unit) {
        val p = container.preferencias.preferencias.first()
        limiar = p.limiarAmostra.toString(); limiteRamal = p.limiteRamalMetros.toString(); formato = p.formatoExportacao
    }
    val tipos by remember { repo.observarTipos() }.collectAsStateWithLifecycle(emptyList())
    val materiais by remember { repo.observarMateriais() }.collectAsStateWithLifecycle(emptyList())
    val condicoes by remember { repo.observarCondicoes() }.collectAsStateWithLifecycle(emptyList())

    fun acao(sucesso: String, bloco: suspend () -> Unit) {
        scope.launch { msg = try { bloco(); sucesso } catch (e: Exception) { "Falha: ${e.message}" } }
    }

    Scaffold(topBar = { BarraTopo("Configurações", aoVoltar) }, snackbarHost = { SnackbarHost(snack) }, containerColor = Tokens.Background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Secao("Padrões") {
                Text("Município: ${LocalOperacional.MUNICIPIO}/${LocalOperacional.UF} (fixo nesta versão)", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    limiar, { limiar = it.filter(Char::isDigit).take(3) }, label = { Text("Limiar de amostra pequena (n)") }, singleLine = true,
                    supportingText = { Text("Abaixo deste n, as estatísticas mostram aviso. Padrão: 5.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    limiteRamal, { limiteRamal = it.filter(Char::isDigit).take(4) }, label = { Text("Limite do comprimento de ramal trocado (m)") }, singleLine = true,
                    supportingText = { Text("Valores acima pedem correção (proteção contra erro de digitação). Padrão: ${RegrasNegocio.LIMITE_RAMAL_METROS_PADRAO} m.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                Text("Formato de exportação preferido")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(FormatoExportacao.XLSX, FormatoExportacao.CSV).forEach { f -> FilterChip(selected = formato == f, onClick = { formato = f }, label = { Text(f.rotulo) }) }
                }
                BotaoPrimario("Salvar padrões", {
                    val n = limiar.toIntOrNull()
                    if (n == null || n < 1) { msg = "Limiar deve ser um inteiro ≥ 1."; return@BotaoPrimario }
                    val lr = limiteRamal.toIntOrNull()
                    if (lr == null || lr < 1) { msg = "Limite do ramal deve ser um inteiro ≥ 1."; return@BotaoPrimario }
                    acao("Padrões salvos.") {
                        container.preferencias.salvar { it.copy(limiarAmostra = n, limiteRamalMetros = lr, formatoExportacao = formato) }
                    }
                })
            }

            SecaoBackup(container, aoMensagem = { msg = it })

            SecaoEnderecos(container, aoMensagem = { msg = it })

            Secao("Tipos de serviço") {
                tipos.forEach { t ->
                    LinhaCatalogo(t.nome, t.ativo, { a -> acao("Atualizado.") { repo.atualizarTipo(t.copy(ativo = a)) } }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Usa marco \"rede encontrada\"", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = Tokens.TextSecondary)
                            Switch(checked = t.aplicaRedeEncontrada, onCheckedChange = { v -> acao("Atualizado.") { repo.atualizarTipo(t.copy(aplicaRedeEncontrada = v)) } })
                        }
                    }
                }
                Adicionar("Novo tipo de serviço") { nome -> acao("Tipo adicionado.") { repo.adicionarTipo(nome, true) } }
            }

            Secao("Materiais da rede") {
                materiais.forEach { m -> LinhaCatalogo(m.nome, m.ativo, { a -> acao("Atualizado.") { repo.atualizarMaterial(m.copy(ativo = a)) } }) }
                Adicionar("Novo material") { nome -> acao("Material adicionado.") { repo.adicionarMaterial(nome) } }
            }

            Secao("Condições observadas") {
                condicoes.forEach { c -> LinhaCatalogo(c.nome, c.ativo, { a -> acao("Atualizado.") { repo.atualizarCondicao(c.copy(ativo = a)) } }) }
                Adicionar("Nova condição") { nome -> acao("Condição adicionada.") { repo.adicionarCondicao(nome) } }
                Text("Itens desativados deixam de aparecer em novos registros, mas continuam nos registros antigos.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }

            Secao("Privacidade") {
                Text(
                    "Todos os dados (inclusive nomes de funcionários) ficam somente neste aparelho, sem internet, login, localização ou rastreamento. " +
                        "O backup automático do Android está desativado porque os registros contêm endereços e nomes. " +
                        "ATENÇÃO: desinstalar o aplicativo ou limpar seus dados apaga todos os registros deste aparelho — mantenha um backup recente fora dele.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
        }
    }
}

@Composable
private fun LinhaCatalogo(nome: String, ativo: Boolean, aoAlternar: (Boolean) -> Unit, extra: @Composable () -> Unit = {}) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(nome, modifier = Modifier.weight(1f), color = if (ativo) Tokens.TextPrimary else Tokens.TextSecondary)
            Text(if (ativo) "Ativo" else "Inativo", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.padding(end = 8.dp))
            Switch(checked = ativo, onCheckedChange = aoAlternar)
        }
        extra()
        HorizontalDivider(color = Tokens.Border)
    }
}

@Composable
private fun Adicionar(rotulo: String, aoAdicionar: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(texto, { texto = it.take(80) }, label = { Text(rotulo) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        IconButton(onClick = { if (texto.isNotBlank()) { aoAdicionar(texto); texto = "" } }) { Icon(Icons.Filled.Add, contentDescription = "Adicionar") }
    }
}

/**
 * Backup local: gera uma cópia íntegra do banco (arquivo .db) no destino escolhido pelo usuário
 * e restaura com validação, confirmação e cópia de segurança do banco atual.
 */
@Composable
private fun SecaoBackup(container: AppContainer, aoMensagem: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by remember { container.preferencias.preferencias }.collectAsStateWithLifecycle(null)
    var trabalhando by remember { mutableStateOf(false) }
    var candidato by remember { mutableStateOf<Pair<java.io.File, InfoBackup>?>(null) }
    var reiniciar by remember { mutableStateOf(false) }
    val arquivoBanco = remember { context.getDatabasePath(AppDatabase.NOME_ARQUIVO) }

    val gerar = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupBanco.MIME)) { uri: Uri? ->
        if (uri == null) { aoMensagem("Backup cancelado."); return@rememberLauncherForActivityResult }
        trabalhando = true
        scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val tmp = java.io.File(context.cacheDir, "backup_tmp.db")
                    try {
                        val i = BackupBanco.gerarCopia(container.database, arquivoBanco, tmp)
                        context.contentResolver.openOutputStream(uri, "w")!!.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                        i
                    } finally {
                        tmp.delete()
                    }
                }
                container.preferencias.salvar { it.copy(ultimoBackupMs = container.relogio.agora().wallClockMs) }
                aoMensagem("Backup salvo e verificado: ${info.servicos} serviço(s), ${info.funcionarios} funcionário(s).")
            } catch (e: Exception) {
                aoMensagem("Falha no backup: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                trabalhando = false
            }
        }
    }

    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        trabalhando = true
        scope.launch {
            try {
                candidato = withContext(Dispatchers.IO) {
                    val f = java.io.File(context.cacheDir, "restaurar_candidato.db")
                    context.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    f to BackupBanco.validar(f)
                }
            } catch (e: BackupInvalidoException) {
                aoMensagem(e.message ?: "Arquivo inválido.")
            } catch (e: Exception) {
                aoMensagem("Não foi possível ler o arquivo: ${e.message}")
            } finally {
                trabalhando = false
            }
        }
    }

    Secao("Backup e restauração") {
        val ultimo = prefs?.ultimoBackupMs
        Text(
            if (ultimo == null) "Nenhum backup gerado por este aparelho ainda." else "Último backup: ${Tempo.dataHora(ultimo)}",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ultimo_backup"),
        )
        Text(
            "O backup é um arquivo .db com todos os dados (serviços, funcionários, auditoria). Guarde-o fora do celular " +
                "(computador, pen drive) — ele contém endereços e nomes. Um celular sozinho é ponto único de falha.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
        )
        if (trabalhando) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        BotaoPrimario(
            "Gerar backup agora", { gerar.launch(BackupBanco.nomeArquivo(System.currentTimeMillis(), LocalOperacional.ZONA)) },
            habilitado = !trabalhando, modifier = Modifier.testTag("botao_gerar_backup"),
        )
        BotaoSecundario("Restaurar de um backup…", { abrir.launch(arrayOf("*/*")) }, habilitado = !trabalhando, modifier = Modifier.testTag("botao_restaurar_backup"))
    }

    candidato?.let { (arquivo, info) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { candidato = null; arquivo.delete() },
            title = { Text("Restaurar backup?") },
            text = {
                Text(
                    "Arquivo válido: ${info.servicos} serviço(s), ${info.funcionarios} funcionário(s), esquema v${info.versaoEsquema}.\n\n" +
                        "TODOS os dados atuais deste aparelho serão substituídos pelos do backup. Uma cópia de segurança do estado atual " +
                        "será guardada internamente antes. O aplicativo será reiniciado.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        candidato = null
                        trabalhando = true
                        scope.launch {
                            try {
                                if (container.database.servicoDao().idAtivo() != null) {
                                    aoMensagem("Há um serviço em andamento. Finalize ou cancele antes de restaurar.")
                                    return@launch
                                }
                                withContext(Dispatchers.IO) {
                                    BackupBanco.restaurar(context, container.database, arquivo, container.relogio.agora().wallClockMs)
                                }
                                arquivo.delete()
                                reiniciar = true
                            } catch (e: Exception) {
                                aoMensagem("Falha ao restaurar: ${e.message}. Os dados atuais não foram alterados.")
                            } finally {
                                trabalhando = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirmar_restauracao"),
                ) { Text("Substituir dados", color = Tokens.Error) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { candidato = null; arquivo.delete() }) { Text("Cancelar") } },
        )
    }
    if (reiniciar) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {},
            title = { Text("Backup restaurado") },
            text = { Text("Os dados foram restaurados. O aplicativo será reiniciado agora.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { BackupBanco.reiniciarApp(context) }) { Text("Reiniciar") } },
        )
    }
}

/**
 * Catálogo de endereços de Leme/SP: contagem local e importação opcional de uma lista
 * autorizada (CSV `bairro;logradouro;cep`) com prévia e confirmação. Não há lista oficial embutida.
 */
@Composable
private fun SecaoEnderecos(container: AppContainer, aoMensagem: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = container.repositorio
    val bairros by remember { repo.observarBairrosLeme() }.collectAsStateWithLifecycle(emptyList())
    val ruas by remember { repo.observarLogradouros() }.collectAsStateWithLifecycle(emptyList())
    var previa by remember { mutableStateOf<Pair<List<String>, br.controleservicos.app.core.repository.PreviaImportacao>?>(null) }
    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val linhas = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        .toString(Charsets.UTF_8).removePrefix("\uFEFF").lines().take(20_000)
                }
                previa = linhas to repo.importarEnderecos(linhas, aplicar = false)
            } catch (e: Exception) { aoMensagem("Não foi possível ler o arquivo: ${e.message}") }
        }
    }
    Secao("Endereços de Leme/SP") {
        Text("${bairros.size} bairro(s) e ${ruas.size} logradouro(s) neste aparelho.", style = MaterialTheme.typography.bodyMedium)
        Text(
            "A lista cresce com os serviços, com as sugestões do ViaCEP (quando há internet) e com cadastros manuais. " +
                "Não existe lista oficial completa embutida no app. Se você tiver uma lista autorizada (CSV com colunas bairro;logradouro;cep), pode importá-la.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
        )
        BotaoSecundario("Importar lista de endereços (CSV)…", { abrir.launch(arrayOf("text/*", "application/csv", "*/*")) }, modifier = Modifier.testTag("botao_importar_enderecos"))
    }
    previa?.let { (linhas, p) ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { previa = null },
            title = { Text("Importar endereços?") },
            text = {
                Text(
                    "Linhas válidas: ${p.linhasValidas} (inválidas: ${p.linhasInvalidas})\n" +
                        "Bairros novos: ${p.bairrosNovos}\nLogradouros novos: ${p.logradourosNovos}\nJá existentes (não duplicados): ${p.jaExistentes}\n\n" +
                        "Os itens serão marcados como \"Lista importada\". Serviços já registrados não mudam.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    previa = null
                    scope.launch {
                        aoMensagem(try { repo.importarEnderecos(linhas, aplicar = true); "Lista importada." } catch (e: Exception) { "Falha na importação: ${e.message}" })
                    }
                }) { Text("Importar") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { previa = null }) { Text("Cancelar") } },
        )
    }
}
