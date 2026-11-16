package br.controleservicos.app.feature.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.database.FuncionarioEntity
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.EstadoVazio
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.ParticipanteServico
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.repository.DadosFuncionario
import br.controleservicos.app.core.repository.ParticipanteNovo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Estado de UI de um participante selecionado (função pode faltar até o usuário escolher). */
data class ParticipanteForm(
    val funcionarioId: String,
    val nome: String,
    val apelido: String?,
    val funcao: FuncaoEquipe?,
    val descricao: String = "",
    val nota: String = "",
) {
    fun paraNovo(): ParticipanteNovo? = funcao?.let { ParticipanteNovo(funcionarioId, it, descricao.ifBlank { null }, nota.ifBlank { null }) }

    companion object {
        fun de(f: FuncionarioEntity) = ParticipanteForm(f.id, f.nomeExibicao, f.apelidoOuCodigo, f.funcaoHabitual)
        fun de(p: ParticipanteServico) = ParticipanteForm(p.funcionarioId, p.nomeAtual, p.apelidoOuCodigo, p.funcaoNoServico, p.funcaoDescricao ?: "", p.nota ?: "")
    }
}

/** Resumo por função: "2 Encanador · 1 Maquinista · 1 Op. basculante". */
fun resumoFuncoes(lista: List<ParticipanteForm>): String {
    val partes = FuncaoEquipe.entries.mapNotNull { f -> lista.count { it.funcao == f }.takeIf { it > 0 }?.let { "$it ${f.rotuloCurto}" } }
    val semFuncao = lista.count { it.funcao == null }
    return (partes + if (semFuncao > 0) listOf("$semFuncao sem função") else emptyList()).joinToString(" · ")
}

/** Erro de validação da seleção (null = ok). Lista vazia é tratada por quem chama. */
fun validarSelecao(lista: List<ParticipanteForm>): String? = when {
    lista.any { it.funcao == null } -> "Escolha a função de cada participante neste serviço."
    lista.map { it.funcionarioId }.toSet().size != lista.size -> "Funcionário repetido."
    else -> null
}

/** Busca rápida (sem caixa/acentos) por nome de exibição, nome completo ou apelido/código. */
fun filtrarFuncionarios(lista: List<FuncionarioEntity>, consulta: String, limite: Int = 60): List<FuncionarioEntity> =
    lista.asSequence().filter { Texto.correspondeBusca(consulta, it.nomeExibicao, it.nomeCompleto, it.apelidoOuCodigo) }.take(limite).toList()

/**
 * Editor da composição de um serviço: lista compacta dos selecionados com função naquele serviço
 * e remoção simples, botão de busca e, opcionalmente, "Reutilizar participantes do último serviço"
 * (ação explícita — nada é preenchido automaticamente).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorParticipantes(
    container: AppContainer,
    selecionados: List<ParticipanteForm>,
    aoMudar: (List<ParticipanteForm>) -> Unit,
    erro: String? = null,
    aoReutilizar: (() -> Unit)? = null,
) {
    var buscando by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selecionados.isEmpty()) {
            Text("Nenhum participante selecionado.", color = Tokens.TextSecondary)
        } else {
            Text(resumoFuncoes(selecionados), style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.testTag("resumo_funcoes"))
        }
        erro?.let { Text(it, color = Tokens.Error, style = MaterialTheme.typography.bodySmall) }
        selecionados.forEach { p ->
            Surface(color = Tokens.SurfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().testTag("participante_${p.funcionarioId}")) {
                Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = Tokens.Primary)
                        Text(
                            p.nome + (p.apelido?.let { " ($it)" } ?: ""),
                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        IconButton(onClick = { aoMudar(selecionados - p) }, modifier = Modifier.testTag("remover_${p.funcionarioId}")) {
                            Icon(Icons.Filled.Close, contentDescription = "Remover ${p.nome}")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("funcoes_${p.funcionarioId}")) {
                        FuncaoEquipe.entries.forEach { f ->
                            FilterChip(
                                selected = p.funcao == f,
                                onClick = { aoMudar(selecionados.map { if (it.funcionarioId == p.funcionarioId) it.copy(funcao = f) else it }) },
                                label = { Text(f.rotuloCurto) },
                                modifier = Modifier.heightIn(min = 40.dp).testTag("funcao_${p.funcionarioId}_${f.name}"),
                            )
                        }
                    }
                    if (p.funcao == null) Text("Escolha a função neste serviço", color = Tokens.Error, style = MaterialTheme.typography.bodySmall)
                    if (p.funcao == FuncaoEquipe.OUTRA) {
                        OutlinedTextField(
                            value = p.descricao,
                            onValueChange = { v -> aoMudar(selecionados.map { if (it.funcionarioId == p.funcionarioId) it.copy(descricao = v.take(RegrasNegocio.LIMITE_NOTA_PARTICIPANTE)) else it }) },
                            label = { Text("Qual função? (opcional)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(end = 12.dp),
                        )
                    }
                }
            }
        }
        BotaoSecundario(
            if (selecionados.isEmpty()) "Selecionar funcionários" else "Adicionar / alterar participantes",
            { buscando = true }, icone = Icons.Filled.Search, modifier = Modifier.testTag("botao_selecionar_funcionarios"),
        )
        if (aoReutilizar != null) {
            TextButton(onClick = aoReutilizar, modifier = Modifier.heightIn(min = 48.dp).testTag("botao_reutilizar_participantes")) {
                Text("Reutilizar participantes do último serviço")
            }
        }
    }
    if (buscando) {
        DialogoBuscaFuncionarios(container, selecionados, aoMudar, aoFechar = { buscando = false })
    }
}

/** Painel de busca em tela cheia: digitar parte do nome e tocar para adicionar. */
@Composable
fun DialogoBuscaFuncionarios(
    container: AppContainer,
    selecionados: List<ParticipanteForm>,
    aoMudar: (List<ParticipanteForm>) -> Unit,
    aoFechar: () -> Unit,
) {
    val todos by remember { container.repositorio.observarFuncionarios() }.collectAsStateWithLifecycle(emptyList())
    var consulta by rememberSaveable { mutableStateOf("") }
    var cadastrando by remember { mutableStateOf<String?>(null) }
    val idsSelecionados = selecionados.map { it.funcionarioId }.toSet()
    val ativos = remember(todos) { todos.filter { it.ativo } }
    val resultados = remember(ativos, consulta) { filtrarFuncionarios(ativos, consulta) }
    val existeExato = ativos.any { Texto.normalizarNome(it.nomeExibicao) == Texto.normalizarNome(consulta) }

    Dialog(onDismissRequest = aoFechar, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Tokens.Background) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Selecionar funcionários", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = aoFechar) { Icon(Icons.Filled.Close, contentDescription = "Fechar") }
                }
                OutlinedTextField(
                    value = consulta,
                    onValueChange = { consulta = it.take(RegrasNegocio.LIMITE_NOME_FUNCIONARIO) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = { if (consulta.isNotEmpty()) IconButton(onClick = { consulta = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Limpar") } },
                    placeholder = { Text("Digite parte do nome") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("campo_busca_funcionario"),
                )
                Text(
                    if (selecionados.isEmpty()) "Toque num nome para adicionar." else "Selecionados: ${resumoFuncoes(selecionados)}",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
                )
                if (consulta.isNotBlank() && !existeExato) {
                    BotaoSecundario(
                        "Cadastrar novo funcionário \"${Texto.limparNome(consulta)}\"",
                        { cadastrando = Texto.limparNome(consulta) }, icone = Icons.Filled.Add,
                        modifier = Modifier.testTag("cadastrar_novo_funcionario"),
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (resultados.isEmpty()) {
                        item { Text(if (todos.isEmpty()) "Nenhum funcionário cadastrado ainda." else "Ninguém encontrado com \"$consulta\".", color = Tokens.TextSecondary, modifier = Modifier.padding(vertical = 12.dp)) }
                    }
                    items(resultados, key = { it.id }) { f ->
                        val marcado = f.id in idsSelecionados
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .toggleable(marcado, role = Role.Checkbox) { sel ->
                                    aoMudar(if (sel) selecionados + ParticipanteForm.de(f) else selecionados.filterNot { it.funcionarioId == f.id })
                                }
                                .testTag("resultado_func_${f.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = marcado, onCheckedChange = null)
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(f.nomeExibicao + (f.apelidoOuCodigo?.let { " ($it)" } ?: ""), fontWeight = if (marcado) FontWeight.SemiBold else FontWeight.Normal)
                                Text(f.funcaoHabitual?.rotulo ?: "Função habitual não informada", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                            }
                        }
                        HorizontalDivider(color = Tokens.Border)
                    }
                }
                BotaoPrimario(
                    if (selecionados.isEmpty()) "Concluir" else "Concluir (${selecionados.size})",
                    aoFechar, icone = Icons.Filled.Done, modifier = Modifier.testTag("concluir_selecao"),
                )
            }
        }
    }
    cadastrando?.let { nome ->
        DialogoFuncionario(
            container = container,
            inicial = null,
            nomeSugerido = nome,
            aoFechar = { cadastrando = null },
            aoSalvo = { id ->
                cadastrando = null
                consulta = ""
                container.repositorio.funcionario(id)?.let { aoMudar(selecionados + ParticipanteForm.de(it)) }
            },
        )
    }
}

/**
 * Cadastro/edição de funcionário. Avisa sobre possível duplicidade (mesmo nome normalizado)
 * e pede confirmação, sem impedir homônimos legítimos.
 */
@Composable
fun DialogoFuncionario(
    container: AppContainer,
    inicial: FuncionarioEntity?,
    nomeSugerido: String = "",
    aoFechar: () -> Unit,
    aoSalvo: suspend (String) -> Unit,
) {
    val repo = container.repositorio
    val scope = rememberCoroutineScope()
    var nome by rememberSaveable { mutableStateOf(inicial?.nomeExibicao ?: nomeSugerido) }
    var completo by rememberSaveable { mutableStateOf(inicial?.nomeCompleto ?: "") }
    var apelido by rememberSaveable { mutableStateOf(inicial?.apelidoOuCodigo ?: "") }
    var funcao by rememberSaveable { mutableStateOf(inicial?.funcaoHabitual) }
    var avisoDuplicado by remember { mutableStateOf<String?>(null) }
    var nomeConfirmado by remember { mutableStateOf<String?>(null) }
    var erro by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }

    fun salvar() {
        if (salvando) return
        if (nome.isBlank()) { erro = "Informe o nome."; return }
        salvando = true
        scope.launch {
            try {
                val dup = repo.possiveisDuplicados(nome, inicial?.id)
                val norm = Texto.normalizarNome(nome)
                if (dup.isNotEmpty() && nomeConfirmado != norm) {
                    avisoDuplicado = "Já existe: " + dup.joinToString("; ") { d ->
                        d.nomeExibicao + listOfNotNull(d.apelidoOuCodigo, d.funcaoHabitual?.rotulo, if (!d.ativo) "inativo" else null)
                            .takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")").orEmpty()
                    } + ". Se for outra pessoa, informe um apelido/código para diferenciar e toque Salvar novamente."
                    nomeConfirmado = norm
                    return@launch
                }
                val dados = DadosFuncionario(nome, completo, apelido, funcao)
                val id = if (inicial == null) repo.adicionarFuncionario(dados) else { repo.atualizarFuncionario(inicial.id, dados); inicial.id }
                aoSalvo(id)
            } catch (e: Exception) {
                erro = e.message ?: "Falha ao salvar."
            } finally {
                salvando = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = aoFechar,
        title = { Text(if (inicial == null) "Novo funcionário" else "Editar funcionário") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    nome, { nome = it.take(RegrasNegocio.LIMITE_NOME_FUNCIONARIO); avisoDuplicado = null; erro = null },
                    label = { Text("Nome de exibição *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("campo_nome_funcionario"),
                )
                OutlinedTextField(
                    completo, { completo = it.take(120) }, label = { Text("Nome completo (opcional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    apelido, { apelido = it.take(40); avisoDuplicado = null }, label = { Text("Apelido ou código (opcional)") }, singleLine = true,
                    supportingText = { Text("Só para diferenciar homônimos. Não use CPF, RG ou telefone.") },
                    modifier = Modifier.fillMaxWidth().testTag("campo_apelido_funcionario"),
                )
                Text("Função habitual (opcional)", style = MaterialTheme.typography.bodyMedium)
                Column {
                    FuncaoEquipe.entries.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                .clickable { funcao = if (funcao == f) null else f }.testTag("habitual_${f.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = funcao == f, onClick = null)
                            Text(f.rotulo, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text("A função habitual só sugere a função no serviço; pode ser trocada a cada execução.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                avisoDuplicado?.let { Aviso(it) }
                erro?.let { Text(it, color = Tokens.Error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::salvar, enabled = !salvando, modifier = Modifier.testTag("salvar_funcionario")) {
                Text(if (avisoDuplicado != null) "Salvar mesmo assim" else "Salvar")
            }
        },
        dismissButton = { TextButton(onClick = aoFechar) { Text("Cancelar") } },
    )
}

/** Tela "Funcionários": busca, cadastro, edição e ativação/inativação. */
@Composable
fun TelaFuncionarios(container: AppContainer, aoVoltar: () -> Unit) {
    val repo = container.repositorio
    val todos by remember { repo.observarFuncionarios() }.collectAsStateWithLifecycle(emptyList())
    var consulta by rememberSaveable { mutableStateOf("") }
    var mostrarInativos by rememberSaveable { mutableStateOf(false) }
    var editando by remember { mutableStateOf<FuncionarioEntity?>(null) }
    var novo by remember { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); msg = null } }
    val visiveis = remember(todos, consulta, mostrarInativos) {
        filtrarFuncionarios(todos.filter { mostrarInativos || it.ativo }, consulta, limite = Int.MAX_VALUE)
    }

    Scaffold(
        topBar = { BarraTopo("Funcionários", aoVoltar) },
        snackbarHost = { SnackbarHost(snack) },
        containerColor = Tokens.Background,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BotaoPrimario("Adicionar funcionário", { novo = true }, icone = Icons.Filled.Add, modifier = Modifier.testTag("botao_adicionar_funcionario"))
            OutlinedTextField(
                value = consulta, onValueChange = { consulta = it.take(80) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Buscar por nome ou apelido") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("campo_busca_cadastro"),
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(mostrarInativos, role = Role.Switch) { mostrarInativos = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Mostrar inativos", modifier = Modifier.weight(1f))
                Switch(checked = mostrarInativos, onCheckedChange = null)
            }
            Text(
                "${todos.count { it.ativo }} ativo(s), ${todos.count { !it.ativo }} inativo(s). Dados só neste aparelho; não cadastre CPF, RG ou telefone.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
            if (visiveis.isEmpty()) {
                EstadoVazio(if (todos.isEmpty()) "Nenhum funcionário cadastrado. Cadastre aqui ou direto na seleção de um novo serviço." else "Ninguém encontrado.")
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(visiveis, key = { it.id }) { f ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editando = f }.testTag("func_${f.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.nomeExibicao + (f.apelidoOuCodigo?.let { " ($it)" } ?: ""),
                                    fontWeight = FontWeight.Medium, color = if (f.ativo) Tokens.TextPrimary else Tokens.TextSecondary,
                                )
                                Text(
                                    listOfNotNull(f.funcaoHabitual?.rotulo, if (!f.ativo) "INATIVO" else null).joinToString(" • ").ifEmpty { "Função habitual não informada" },
                                    style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
                                )
                            }
                            Switch(
                                checked = f.ativo,
                                onCheckedChange = { a ->
                                    scope.launch {
                                        msg = try {
                                            repo.definirFuncionarioAtivo(f.id, a)
                                            if (a) "${f.nomeExibicao} reativado." else "${f.nomeExibicao} inativado (continua nos serviços antigos)."
                                        } catch (e: Exception) { e.message }
                                    }
                                },
                                modifier = Modifier.testTag("ativo_${f.id}"),
                            )
                            IconButton(onClick = { editando = f }) { Icon(Icons.Filled.Edit, contentDescription = "Editar ${f.nomeExibicao}") }
                        }
                        HorizontalDivider(color = Tokens.Border)
                    }
                }
            }
        }
    }
    if (novo) DialogoFuncionario(container, null, consulta, aoFechar = { novo = false }, aoSalvo = { novo = false; msg = "Funcionário cadastrado." })
    editando?.let { f -> DialogoFuncionario(container, f, aoFechar = { editando = null }, aoSalvo = { editando = null; msg = "Cadastro atualizado." }) }
}

/**
 * Correção da composição de um serviço já iniciado/encerrado. Gera evento de auditoria
 * PARTICIPANTES com antes/depois e motivo opcional.
 */
@Composable
fun TelaParticipantesServico(container: AppContainer, servicoId: String, aoVoltar: () -> Unit) {
    val repo = container.repositorio
    val scope = rememberCoroutineScope()
    val s by remember { repo.observarLinha(servicoId) }.collectAsStateWithLifecycle(null)
    var lista by remember { mutableStateOf<List<ParticipanteForm>?>(null) }
    var motivo by rememberSaveable { mutableStateOf("") }
    var erro by remember { mutableStateOf<String?>(null) }
    var salvando by remember { mutableStateOf(false) }
    LaunchedEffect(servicoId) { if (lista == null) lista = repo.participantesDe(servicoId).map(ParticipanteForm::de) }

    Scaffold(topBar = { BarraTopo("Participantes do serviço", aoVoltar) }, containerColor = Tokens.Background) { pad ->
        val atual = lista
        if (atual == null) {
            Box(Modifier.fillMaxSize().padding(pad))
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            s?.let { Text("${it.tipoNome} • ${it.bairroNome}", fontWeight = FontWeight.SemiBold) }
            Text(
                "A alteração fica registrada na auditoria do serviço (antes/depois). Nomes já gravados não mudam se o cadastro for editado depois.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
            EditorParticipantes(container, atual, { lista = it; erro = null }, erro)
            OutlinedTextField(motivo, { motivo = it.take(200) }, label = { Text("Motivo da alteração (opcional)") }, modifier = Modifier.fillMaxWidth())
            BotaoPrimario(
                if (salvando) "Salvando…" else "Salvar participantes",
                {
                    val e = validarSelecao(atual)
                    if (e != null) { erro = e; return@BotaoPrimario }
                    salvando = true
                    // Navegação exige a thread principal: não depender do despachante do escopo.
                    scope.launch(Dispatchers.Main) {
                        try {
                            repo.atualizarParticipantes(servicoId, atual.mapNotNull { it.paraNovo() }, motivo)
                            aoVoltar()
                        } catch (ex: Exception) {
                            erro = ex.message ?: "Falha ao salvar."
                        } finally {
                            salvando = false
                        }
                    }
                },
                habilitado = !salvando, icone = Icons.Filled.Done, modifier = Modifier.testTag("salvar_participantes"),
            )
        }
    }
}

/** Lista somente leitura dos participantes (detalhes/complementos). */
@Composable
fun ListaParticipantes(participantes: List<ParticipanteServico>) {
    if (participantes.isEmpty()) {
        Text("Equipe não registrada", color = Tokens.Warning, modifier = Modifier.testTag("equipe_nao_registrada"))
        return
    }
    participantes.forEach { p ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text(
                "• ${p.nomeAtual}${p.apelidoOuCodigo?.let { " ($it)" } ?: ""} — ${p.funcaoTexto}",
                modifier = Modifier.testTag("part_${p.funcionarioId}"),
            )
            if (p.nomeSnapshot != p.nomeAtual) {
                Text("  registrado como \"${p.nomeSnapshot}\"", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }
            if (!p.funcionarioAtivo) Text("  (cadastro inativo)", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            p.nota?.let { Text("  $it", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
        }
    }
}

