package br.controleservicos.app.feature.completion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.EntradaNumerica
import br.controleservicos.app.core.domain.LigacaoForm
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.model.LadoRamal
import java.util.UUID

fun LigacaoForm.preenchida(): Boolean =
    numero.isNotBlank() || lado != null || comprimento.isNotBlank() || bairro.isNotBlank() || rua.isNotBlank() ||
        complemento.isNotBlank() || identificador.isNotBlank() || observacoes.isNotBlank()

fun novaLigacao() = LigacaoForm(chave = UUID.randomUUID().toString())

/** "3 ligações trocadas: nº 12, 14A, S/N" para revisão antes de salvar. */
fun resumoLigacoes(lista: List<LigacaoForm>): String {
    if (lista.isEmpty()) return "Nenhuma ligação informada."
    val nums = lista.joinToString(", ") { it.numero.trim().ifEmpty { "?" } }
    return "${lista.size} ${if (lista.size == 1) "ligação trocada" else "ligações trocadas"}: nº $nums"
}

@Composable
private fun BotaoSimNao(sim: Boolean, aoMudar: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(false to "Não", true to "Sim").forEach { (valor, rotulo) ->
            val sel = sim == valor
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (sel) Tokens.Primary else Tokens.SurfaceVariant,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                    .selectable(sel, role = Role.RadioButton) { aoMudar(valor) }
                    .testTag(if (valor) "ramal_sim" else "ramal_nao"),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(rotulo, color = if (sel) Color.White else Tokens.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
                }
            }
        }
    }
}

/**
 * "Houve troca de ligação/ramal?" — Não: nada é pedido. Sim: um cartão por imóvel atendido
 * (número obrigatório, lado, metros, endereço próprio opcional, identificador, observações).
 * A quantidade é a contagem dos cartões (derivada).
 */
@Composable
fun EditorLigacoes(
    trocado: Boolean,
    lista: List<LigacaoForm>,
    erros: Map<String, String>,
    numeroPrincipal: String,
    limiteMetros: Int,
    aoMudarTrocado: (Boolean) -> Unit,
    aoMudarLista: (List<LigacaoForm>) -> Unit,
) {
    var confirmarNao by remember { mutableStateOf(false) }
    var confirmarRemover by remember { mutableStateOf<LigacaoForm?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BotaoSimNao(trocado) { sim ->
            when {
                sim == trocado -> {}
                sim -> { aoMudarTrocado(true); if (lista.isEmpty()) aoMudarLista(listOf(novaLigacao())) }
                lista.any { it.preenchida() } -> confirmarNao = true
                else -> { aoMudarTrocado(false); aoMudarLista(emptyList()) }
            }
        }
        if (!trocado) {
            Text("Sem troca: nenhum imóvel, lado ou metragem é registrado.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            return@Column
        }
        Text(
            "Registre cada imóvel que teve a ligação trocada — inclusive quando é só um.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
        )
        erros["geral"]?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall) }
        lista.forEachIndexed { i, l ->
            fun mudar(f: (LigacaoForm) -> LigacaoForm) = aoMudarLista(lista.map { if (it.chave == l.chave) f(it) else it })
            fun erro(campo: String) = erros["$i:$campo"]
            Surface(color = Tokens.SurfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("ligacao_$i")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Ligação ${i + 1}", fontWeight = FontWeight.SemiBold, color = Tokens.Primary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { if (l.preenchida()) confirmarRemover = l else aoMudarLista(lista - l) }, modifier = Modifier.testTag("remover_ligacao_$i")) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remover ligação ${i + 1}")
                        }
                    }
                    if (l.legado) {
                        Text("Registro antigo: o número do imóvel não foi registrado na época. Preencha se souber.", style = MaterialTheme.typography.bodySmall, color = Tokens.Warning)
                    }
                    OutlinedTextField(
                        value = l.numero, onValueChange = { v -> mudar { it.copy(numero = v.take(20)) } },
                        label = { Text("Número do imóvel atendido *") }, singleLine = true,
                        isError = erro("numero") != null, supportingText = { Text(erro("numero") ?: "Aceita 12A, S/N…") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("ligacao_numero_$i"),
                    )
                    if (l.numero.isBlank() && numeroPrincipal.isNotBlank()) {
                        AssistChip(onClick = { mudar { it.copy(numero = numeroPrincipal) } }, label = { Text("Usar nº do endereço ($numeroPrincipal)") }, modifier = Modifier.testTag("ligacao_usar_numero_$i"))
                    }
                    Text("Lado", style = MaterialTheme.typography.bodyMedium)
                    erro("lado")?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LadoRamal.entries.forEach { lado ->
                            Row(
                                Modifier.heightIn(min = 48.dp).selectable(l.lado == lado, role = Role.RadioButton) { mudar { it.copy(lado = lado) } }
                                    .testTag("ligacao_lado_${i}_${lado.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = l.lado == lado, onClick = null)
                                Text(lado.rotulo, modifier = Modifier.padding(start = 4.dp, end = 8.dp))
                            }
                        }
                    }
                    val previa = Numeros.metrosParaCentimetros(l.comprimento, limiteMetros)
                    OutlinedTextField(
                        value = l.comprimento, onValueChange = { v -> mudar { it.copy(comprimento = v.filter { c -> c.isDigit() || c == ',' || c == '.' }.take(8)) } },
                        label = { Text("Comprimento utilizado (m) *") }, singleLine = true,
                        isError = erro("comprimento") != null,
                        supportingText = { Text(erro("comprimento") ?: if (previa is EntradaNumerica.Valida) "Será salvo: ${Numeros.formatarMetrosBr(previa.valor)} m" else "Ex.: 3,5") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().testTag("ligacao_comprimento_$i"),
                    )
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(l.enderecoDiferente, role = Role.Switch) { v -> mudar { it.copy(enderecoDiferente = v) } }
                            .testTag("ligacao_endereco_diferente_$i"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bairro/rua diferentes do endereço principal?", modifier = Modifier.weight(1f))
                        Switch(checked = l.enderecoDiferente, onCheckedChange = null)
                    }
                    if (l.enderecoDiferente) {
                        erro("endereco")?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall) }
                        OutlinedTextField(l.rua, { v -> mudar { it.copy(rua = v.take(150)) } }, label = { Text("Rua do imóvel") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth().testTag("ligacao_rua_$i"))
                        OutlinedTextField(l.bairro, { v -> mudar { it.copy(bairro = v.take(150)) } }, label = { Text("Bairro do imóvel") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(l.complemento, { v -> mudar { it.copy(complemento = v.take(60)) } }, label = { Text("Complemento (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    erro("duplicada")?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("erro_duplicada_$i")) }
                    OutlinedTextField(l.identificador, { v -> mudar { it.copy(identificador = v.take(40)) } }, label = { Text("Identificador da ligação (opcional)") }, singleLine = true,
                        supportingText = { Text("Use para distinguir duas ligações no mesmo imóvel.") }, modifier = Modifier.fillMaxWidth().testTag("ligacao_identificador_$i"))
                    OutlinedTextField(l.observacoes, { v -> mudar { it.copy(observacoes = v.take(300)) } }, label = { Text("Observações da ligação (opcional)") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        BotaoSecundario("Adicionar outra ligação", { aoMudarLista(lista + novaLigacao()) }, icone = Icons.Filled.Add, modifier = Modifier.testTag("adicionar_ligacao"))
        Text(resumoLigacoes(lista), fontWeight = FontWeight.Medium, modifier = Modifier.testTag("resumo_ligacoes"))
    }
    if (confirmarNao) {
        AlertDialog(
            onDismissRequest = { confirmarNao = false },
            title = { Text("Descartar ligações?") },
            text = { Text("As ${lista.size} ligação(ões) preenchida(s) serão descartadas deste formulário.") },
            confirmButton = { TextButton(onClick = { confirmarNao = false; aoMudarTrocado(false); aoMudarLista(emptyList()) }, modifier = Modifier.testTag("confirmar_descartar_ligacoes")) { Text("Descartar", color = Tokens.Error) } },
            dismissButton = { TextButton(onClick = { confirmarNao = false }) { Text("Manter") } },
        )
    }
    confirmarRemover?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmarRemover = null },
            title = { Text("Remover ligação?") },
            text = { Text("Remover a ligação do imóvel ${l.numero.ifBlank { "sem número" }}?") },
            confirmButton = { TextButton(onClick = { confirmarRemover = null; aoMudarLista(lista.filterNot { it.chave == l.chave }) }, modifier = Modifier.testTag("confirmar_remover_ligacao")) { Text("Remover", color = Tokens.Error) } },
            dismissButton = { TextButton(onClick = { confirmarRemover = null }) { Text("Cancelar") } },
        )
    }
}
