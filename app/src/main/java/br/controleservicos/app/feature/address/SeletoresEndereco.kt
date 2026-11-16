package br.controleservicos.app.feature.address

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.address.BuscadorExterno
import br.controleservicos.app.core.database.BairroEntity
import br.controleservicos.app.core.database.LogradouroEntity
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.EnderecoExterno
import br.controleservicos.app.core.domain.Enderecos
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.OrigemEndereco
import kotlinx.coroutines.launch

/** Bairro escolhido: id do catálogo + nome exibido. */
data class BairroSelecionado(val id: String, val nome: String)

/** Rua escolhida: id do catálogo + nome exibido + CEP (quando conhecido). */
data class RuaSelecionada(val id: String, val nome: String, val cep: String?)

/** Campo somente-toque que abre um seletor (acessível como botão). */
@Composable
fun CampoSeletor(
    rotulo: String,
    valor: String?,
    habilitado: Boolean,
    erro: String?,
    tag: String,
    ajuda: String? = null,
    aoAbrir: () -> Unit,
) {
    Column {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (habilitado) Tokens.Background else Tokens.SurfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (erro != null) Tokens.Error else Tokens.Border),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(enabled = habilitado, onClick = aoAbrir)
                .semantics { role = Role.Button; contentDescription = "$rotulo: ${valor ?: "não selecionado"}" }
                .testTag(tag),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rotulo, style = MaterialTheme.typography.labelMedium, color = if (erro != null) Tokens.Error else Tokens.TextSecondary)
                    Text(valor ?: "Tocar para escolher", color = if (valor == null) Tokens.TextSecondary else Tokens.TextPrimary, fontWeight = if (valor != null) FontWeight.Medium else FontWeight.Normal)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = if (habilitado) Tokens.Primary else Tokens.Border)
            }
        }
        (erro ?: ajuda)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (erro != null) Tokens.Error else Tokens.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 2.dp)) }
    }
}

@Composable
private fun CabecalhoBusca(titulo: String, consulta: String, aoMudar: (String) -> Unit, placeholder: String, tag: String, aoFechar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        IconButton(onClick = aoFechar) { Icon(Icons.Filled.Close, contentDescription = "Fechar") }
    }
    OutlinedTextField(
        value = consulta, onValueChange = { aoMudar(it.take(120)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = { if (consulta.isNotEmpty()) IconButton(onClick = { aoMudar("") }) { Icon(Icons.Filled.Clear, contentDescription = "Limpar") } },
        placeholder = { Text(placeholder) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun LinhaOpcao(titulo: String, detalhe: String?, tag: String, aoTocar: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = aoTocar).padding(vertical = 8.dp).testTag(tag), verticalArrangement = Arrangement.Center) {
        Text(titulo)
        detalhe?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
    }
    HorizontalDivider(color = Tokens.Border)
}

/**
 * Seletor de bairro de Leme/SP: lista rolável dos bairros conhecidos localmente (serviços,
 * ViaCEP, importação, manuais) com busca local a cada caractere, sem rede obrigatória.
 * Bairro não encontrado pode ser cadastrado manualmente após confirmação.
 */
@Composable
fun DialogoBairro(container: AppContainer, aoEscolher: (BairroSelecionado) -> Unit, aoFechar: () -> Unit) {
    val repo = container.repositorio
    val scope = rememberCoroutineScope()
    val bairros by remember { repo.observarBairrosLeme() }.collectAsStateWithLifecycle(emptyList())
    var consulta by rememberSaveable { mutableStateOf("") }
    var confirmarNovo by remember { mutableStateOf<String?>(null) }
    val filtrados = remember(bairros, consulta) { bairros.filter { Texto.correspondeBusca(consulta, it.nomeOriginal) }.sortedBy { it.nomeNormalizado } }
    val existeExato = bairros.any { it.nomeNormalizado == Texto.normalizarNome(consulta) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Tokens.Background) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CabecalhoBusca("Bairro — Leme · SP", consulta, { consulta = it }, "Pesquisar bairro", "campo_busca_bairro", aoFechar)
                Text(
                    "${bairros.size} bairro(s) conhecidos neste aparelho. A lista cresce com os serviços e consultas; não é a lista oficial completa.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
                )
                if (consulta.isNotBlank() && !existeExato) {
                    BotaoSecundario("Adicionar bairro \"${Texto.limparNome(consulta)}\"", { confirmarNovo = Texto.limparNome(consulta) }, icone = Icons.Filled.Add, modifier = Modifier.testTag("adicionar_bairro"))
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (filtrados.isEmpty()) item { Text(if (bairros.isEmpty()) "Nenhum bairro salvo ainda. Digite o nome para cadastrar." else "Nenhum bairro com \"$consulta\".", color = Tokens.TextSecondary, modifier = Modifier.padding(vertical = 12.dp)) }
                    items(filtrados, key = { it.id }) { b ->
                        LinhaOpcao(b.nomeOriginal, b.origem.takeIf { it == OrigemEndereco.MANUAL }?.rotulo, "opcao_bairro_${b.id}") { aoEscolher(BairroSelecionado(b.id, b.nomeOriginal)) }
                    }
                }
            }
        }
    }
    confirmarNovo?.let { nome ->
        AlertDialog(
            onDismissRequest = { confirmarNovo = null },
            title = { Text("Cadastrar bairro?") },
            text = { Text("\"$nome\" será salvo como bairro de Leme/SP inserido manualmente. Confira a grafia.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarNovo = null
                    scope.launch { val b = repo.adicionarBairroManual(nome); aoEscolher(BairroSelecionado(b.id, b.nomeOriginal)) }
                }, modifier = Modifier.testTag("confirmar_novo_bairro")) { Text("Cadastrar") }
            },
            dismissButton = { TextButton(onClick = { confirmarNovo = null }) { Text("Cancelar") } },
        )
    }
}

/** Logradouros do catálogo associados a um bairro, e os demais (para busca). */
fun separarLogradouros(
    todos: List<LogradouroEntity>,
    vinculos: Map<String, Set<String>>,
    bairroId: String,
    consulta: String,
): Pair<List<LogradouroEntity>, List<LogradouroEntity>> {
    val n = Enderecos.normalizarLogradouro(consulta)
    val corresponde = { l: LogradouroEntity -> n.isEmpty() || n.split(' ').all { l.nomeNormalizado.contains(it) } }
    val doBairro = todos.filter { bairroId in (vinculos[it.id] ?: emptySet()) && corresponde(it) }
    val outros = if (n.isEmpty()) emptyList() else todos.filter { bairroId !in (vinculos[it.id] ?: emptySet()) && corresponde(it) }
    return doBairro to outros.take(30)
}

/**
 * Seletor de rua, habilitado após o bairro: lista dos logradouros já relacionados ao bairro,
 * busca local imediata e, a partir de 3 caracteres e com internet, sugestões do ViaCEP
 * (debounce 500 ms, cancelamento, limite de consultas). Resultados de outro bairro pedem confirmação.
 */
@Composable
fun DialogoLogradouro(
    container: AppContainer,
    bairro: BairroSelecionado,
    aoEscolher: (RuaSelecionada) -> Unit,
    aoFechar: () -> Unit,
) {
    val repo = container.repositorio
    val scope = rememberCoroutineScope()
    val todos by remember { repo.observarLogradouros() }.collectAsStateWithLifecycle(emptyList())
    val vinculosLista by remember { repo.observarVinculosLogradouro() }.collectAsStateWithLifecycle(emptyList())
    val vinculos = remember(vinculosLista) { vinculosLista.groupBy({ it.logradouroId }, { it.bairroId }).mapValues { it.value.toSet() } }
    val bairrosPorId by remember { repo.observarBairros() }.collectAsStateWithLifecycle(emptyList())
    val nomeBairro = remember(bairrosPorId) { bairrosPorId.associate { it.id to it.nomeOriginal } }
    var consulta by rememberSaveable { mutableStateOf("") }
    val buscador = remember { BuscadorExterno(container.fonteEnderecos, scope, aoReceber = { repo.registrarResultadosExternos(it) }) }
    val externo by buscador.estado.collectAsStateWithLifecycle()
    LaunchedEffect(consulta) { buscador.pesquisar(consulta) }
    var confirmarExterno by remember { mutableStateOf<EnderecoExterno?>(null) }
    var confirmarOutro by remember { mutableStateOf<LogradouroEntity?>(null) }
    var confirmarManual by remember { mutableStateOf<String?>(null) }
    val (doBairro, outros) = remember(todos, vinculos, consulta, bairro) { separarLogradouros(todos, vinculos, bairro.id, consulta) }
    val existeExato = (doBairro + outros).any { it.nomeNormalizado == Enderecos.normalizarLogradouro(consulta) }
    // Externos que ainda não estão na lista local do bairro.
    // Resultados online já gravados no catálogo aparecem uma única vez (na seção local correspondente).
    val externosVisiveis = externo.itens.filter { e ->
        (doBairro + outros).none { it.nomeNormalizado == Enderecos.normalizarLogradouro(e.logradouro) && (it.cep == null || it.cep == e.cep) }
    }

    fun escolher(l: LogradouroEntity) = aoEscolher(RuaSelecionada(l.id, l.nomeExibicao, l.cep))

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Tokens.Background) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CabecalhoBusca("Rua — ${bairro.nome}", consulta, { consulta = it }, "Pesquisar rua, avenida…", "campo_busca_rua", aoFechar)
                if (externo.carregando) LinearProgressIndicator(Modifier.fillMaxWidth())
                externo.aviso?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.testTag("aviso_busca_externa")) }
                if (consulta.isNotBlank() && !existeExato) {
                    BotaoSecundario("Usar \"${Texto.limparNome(consulta)}\" (cadastrar)", { confirmarManual = Texto.limparNome(consulta) }, icone = Icons.Filled.Add, modifier = Modifier.testTag("adicionar_rua"))
                }
                LazyColumn(Modifier.weight(1f)) {
                    item { Text("Neste bairro", style = MaterialTheme.typography.labelLarge, color = Tokens.Primary, modifier = Modifier.padding(top = 4.dp)) }
                    if (doBairro.isEmpty()) item { Text(if (consulta.isBlank()) "Nenhuma rua salva para este bairro. Pesquise pelo nome." else "Nenhuma rua salva com \"$consulta\" neste bairro.", color = Tokens.TextSecondary, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(doBairro, key = { "b_" + it.id }) { l ->
                        LinhaOpcao(l.nomeExibicao, listOfNotNull(l.cep?.let { "CEP $it" }, l.origem.takeIf { it == OrigemEndereco.MANUAL }?.rotulo).joinToString(" · ").ifEmpty { null }, "opcao_rua_${l.id}") { escolher(l) }
                    }
                    if (outros.isNotEmpty()) {
                        item { Text("Salvas em outros bairros", style = MaterialTheme.typography.labelLarge, color = Tokens.Primary, modifier = Modifier.padding(top = 12.dp)) }
                        items(outros, key = { "o_" + it.id }) { l ->
                            val bs = (vinculos[l.id] ?: emptySet()).mapNotNull { nomeBairro[it] }.joinToString(", ").ifEmpty { "bairro não informado" }
                            LinhaOpcao(l.nomeExibicao, "Em: $bs${l.cep?.let { " · CEP $it" } ?: ""}", "opcao_rua_outra_${l.id}") { confirmarOutro = l }
                        }
                    }
                    if (externosVisiveis.isNotEmpty()) {
                        item { Text("Sugestões online (ViaCEP)", style = MaterialTheme.typography.labelLarge, color = Tokens.Primary, modifier = Modifier.padding(top = 12.dp)) }
                        items(externosVisiveis, key = { "e_" + it.logradouro + it.cep + it.bairro }) { e ->
                            LinhaOpcao(e.logradouro, "Bairro: ${e.bairro ?: "não informado"}${e.cep?.let { " · CEP $it" } ?: ""}", "opcao_rua_externa_${e.cep}") {
                                val mesmo = e.bairro != null && Texto.normalizarNome(e.bairro) == Texto.normalizarNome(bairro.nome)
                                if (mesmo) scope.launch { escolher(repo.usarResultadoExterno(e, bairro.id)) } else confirmarExterno = e
                            }
                        }
                    }
                }
            }
        }
    }
    confirmarExterno?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmarExterno = null },
            title = { Text("Rua de outro bairro?") },
            text = { Text("A fonte associa \"${e.logradouro}\" ao bairro ${e.bairro ?: "(não informado)"}. Usar mesmo assim no bairro ${bairro.nome}?") },
            confirmButton = { TextButton(onClick = { confirmarExterno = null; scope.launch { escolher(repo.usarResultadoExterno(e, bairro.id)) } }, modifier = Modifier.testTag("confirmar_rua_outro_bairro")) { Text("Usar em ${bairro.nome}") } },
            dismissButton = { TextButton(onClick = { confirmarExterno = null }) { Text("Escolher outra") } },
        )
    }
    confirmarOutro?.let { l ->
        AlertDialog(
            onDismissRequest = { confirmarOutro = null },
            title = { Text("Rua salva em outro bairro") },
            text = { Text("\"${l.nomeExibicao}\" está registrada em outro bairro. Confirmar que este serviço é nesta rua em ${bairro.nome}?") },
            confirmButton = { TextButton(onClick = { confirmarOutro = null; aoEscolher(RuaSelecionada(l.id, l.nomeExibicao, l.cep)) }, modifier = Modifier.testTag("confirmar_rua_outro_bairro")) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { confirmarOutro = null }) { Text("Cancelar") } },
        )
    }
    confirmarManual?.let { nome ->
        AlertDialog(
            onDismissRequest = { confirmarManual = null },
            title = { Text("Cadastrar rua?") },
            text = { Text("\"$nome\" será salva no bairro ${bairro.nome} como inserida manualmente. Confira a grafia (ex.: \"Rua\", \"Avenida\").") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarManual = null
                    scope.launch { escolher(repo.adicionarLogradouroManual(nome, bairro.id)) }
                }, modifier = Modifier.testTag("confirmar_nova_rua")) { Text("Cadastrar") }
            },
            dismissButton = { TextButton(onClick = { confirmarManual = null }) { Text("Cancelar") } },
        )
    }
}

@Suppress("unused")
private fun bairroDe(b: BairroEntity) = BairroSelecionado(b.id, b.nomeOriginal)
