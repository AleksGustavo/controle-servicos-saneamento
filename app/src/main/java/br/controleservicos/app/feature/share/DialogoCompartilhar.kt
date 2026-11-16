package br.controleservicos.app.feature.share

import android.content.ActivityNotFoundException
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.share.ArquivosRelatorio
import br.controleservicos.app.core.share.CompartilharServico
import br.controleservicos.app.core.share.FormatoRelatorio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seletor compacto de formato para compartilhar UM serviço (PDF, PNG, DOCX), com opção
 * "Ocultar nomes dos funcionários". Gera localmente e abre o seletor nativo do Android ou
 * salva no destino escolhido (SAF). Falhas são mostradas sem afetar o registro salvo.
 */
@Composable
fun DialogoCompartilhar(container: AppContainer, servicoId: String, aoFechar: () -> Unit, aoMensagem: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var formato by rememberSaveable { mutableStateOf(FormatoRelatorio.PDF) }
    var ocultar by rememberSaveable { mutableStateOf(false) }
    var gerando by remember { mutableStateOf(false) }
    var erro by remember { mutableStateOf<String?>(null) }
    var pendenteSalvar by remember { mutableStateOf<ArquivosRelatorio?>(null) }

    suspend fun gerar(): ArquivosRelatorio? = try {
        CompartilharServico.gerar(context, container, servicoId, formato, ocultar)
    } catch (e: Exception) {
        erro = "Não foi possível gerar o arquivo: ${e.message ?: e.javaClass.simpleName}. O serviço continua salvo."
        null
    }

    suspend fun copiar(origem: java.io.File, destino: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destino, "w")!!.use { out -> origem.inputStream().use { it.copyTo(out) } }
    }

    val salvarUm = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(formato.mime)) { uri: Uri? ->
        val a = pendenteSalvar ?: return@rememberLauncherForActivityResult
        pendenteSalvar = null
        if (uri == null) { aoMensagem("Salvamento cancelado."); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.Main) {
            try { copiar(a.arquivos.first(), uri); aoMensagem("Arquivo salvo."); aoFechar() } catch (e: Exception) { erro = "Falha ao salvar: ${e.message}" }
        }
    }
    val salvarVarios = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { arvore: Uri? ->
        val a = pendenteSalvar ?: return@rememberLauncherForActivityResult
        pendenteSalvar = null
        if (arvore == null) { aoMensagem("Salvamento cancelado."); return@rememberLauncherForActivityResult }
        scope.launch(Dispatchers.Main) {
            try {
                val pai = DocumentsContract.buildDocumentUriUsingTree(arvore, DocumentsContract.getTreeDocumentId(arvore))
                a.arquivos.forEach { f ->
                    val novo = withContext(Dispatchers.IO) { DocumentsContract.createDocument(context.contentResolver, pai, a.formato.mime, f.name) }
                        ?: throw IllegalStateException("não foi possível criar ${f.name}")
                    copiar(f, novo)
                }
                aoMensagem("${a.arquivos.size} imagens salvas."); aoFechar()
            } catch (e: Exception) { erro = "Falha ao salvar: ${e.message}" }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!gerando) aoFechar() },
        title = { Text("Compartilhar serviço") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.selectableGroup()) {
                    FormatoRelatorio.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(formato == f, role = Role.RadioButton) { formato = f }.testTag("formato_relatorio_${f.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = formato == f, onClick = null)
                            Text(f.rotulo, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(ocultar, role = Role.Switch) { ocultar = it }.testTag("ocultar_nomes"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Ocultar nomes dos funcionários", modifier = Modifier.weight(1f))
                    Switch(checked = ocultar, onCheckedChange = null)
                }
                Text(
                    "O arquivo é gerado neste aparelho e contém o endereço do serviço. Envie só a quem precisa.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
                )
                if (gerando) LinearProgressIndicator(Modifier.fillMaxWidth())
                erro?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("erro_compartilhar")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !gerando,
                onClick = {
                    gerando = true; erro = null
                    scope.launch(Dispatchers.Main) {
                        val a = gerar()
                        gerando = false
                        if (a != null) {
                            try {
                                context.startActivity(CompartilharServico.intentCompartilhar(a, "Serviço — Leme/SP").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                aoFechar()
                            } catch (_: ActivityNotFoundException) {
                                erro = "Nenhum aplicativo aceita este tipo de arquivo. Use \"Salvar no dispositivo\"."
                            }
                        }
                    }
                },
                modifier = Modifier.testTag("botao_compartilhar_relatorio"),
            ) { Text("Compartilhar") }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = !gerando,
                    onClick = {
                        gerando = true; erro = null
                        scope.launch(Dispatchers.Main) {
                            val a = gerar()
                            gerando = false
                            if (a != null) {
                                pendenteSalvar = a
                                if (a.arquivos.size == 1) salvarUm.launch(a.arquivos.first().name) else salvarVarios.launch(null)
                            }
                        }
                    },
                    modifier = Modifier.testTag("botao_salvar_relatorio"),
                ) { Text("Salvar no dispositivo") }
                TextButton(onClick = aoFechar, enabled = !gerando) { Text("Fechar") }
            }
        },
    )
}
