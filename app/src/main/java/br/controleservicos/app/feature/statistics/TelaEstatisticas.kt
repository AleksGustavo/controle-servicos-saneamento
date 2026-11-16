package br.controleservicos.app.feature.statistics

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.Preferencias
import br.controleservicos.app.core.design.Aviso
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.EstadoVazio
import br.controleservicos.app.core.design.ParRotuloValor
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.EstatisticaEquipe
import br.controleservicos.app.core.domain.EstatisticaGrupo
import br.controleservicos.app.core.domain.Estatisticas
import br.controleservicos.app.core.domain.EstatisticasEquipe
import br.controleservicos.app.core.domain.QualidadeAmostra
import br.controleservicos.app.core.domain.Resumo
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.feature.comum.BarraFiltros
import kotlinx.coroutines.flow.combine

private fun fmt(ms: Double?): String = Tempo.formatarDuracao(ms?.toLong())

/** Abas de visão. Nenhuma ranqueia: listas em ordem alfabética. */
private enum class Visao(val rotulo: String) {
    TIPO("Por tipo"),
    COMPOSICAO("Equipes"),
    PARTICIPACAO("Participação"),
    FUNCOES("Funções"),
}

@Composable
fun TelaEstatisticas(container: AppContainer, aoVoltar: () -> Unit) {
    val repo = container.repositorio
    val filtro by container.filtro.collectAsStateWithLifecycle()
    val prefs by remember { container.preferencias.preferencias }.collectAsStateWithLifecycle(Preferencias())
    val tipos by remember { repo.observarTipos() }.collectAsStateWithLifecycle(emptyList())
    val municipios by remember { repo.observarMunicipios() }.collectAsStateWithLifecycle(emptyList())
    val bairros by remember { repo.observarBairros() }.collectAsStateWithLifecycle(emptyList())
    val funcionarios by remember { repo.observarFuncionarios() }.collectAsStateWithLifecycle(emptyList())
    val participacoes by remember { repo.observarParticipacoes() }.collectAsStateWithLifecycle(emptyMap())
    // Estatísticas ignoram estado/excluídos do filtro: a regra de validade decide o que entra.
    val lista by remember {
        combine(repo.observarLinhas(), container.filtro, repo.observarParticipacoes()) { l, f, p ->
            f.copy(status = null, incluirExcluidos = true).aplicar(l, participantes = p)
        }
    }.collectAsStateWithLifecycle(emptyList<LinhaServico>())
    var mostrarAgregado by rememberSaveable { mutableStateOf(false) }
    var visao by rememberSaveable { mutableStateOf(Visao.TIPO) }

    val limiar = prefs.limiarAmostra
    val exclusoes = remember(lista) { Estatisticas.contarExclusoes(lista) }

    Scaffold(topBar = { BarraTopo("Estatísticas", aoVoltar) }, containerColor = Tokens.Background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BarraFiltros(
                filtro, tipos, municipios, bairros, aoMudar = { container.filtro.value = it },
                mostrarStatus = false, mostrarOrdenacao = false, mostrarExcluidos = false, funcionarios = funcionarios,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visao.entries.forEach { v ->
                    FilterChip(
                        selected = visao == v, onClick = { visao = v }, label = { Text(v.rotulo) },
                        modifier = Modifier.heightIn(min = 44.dp).testTag("aba_${v.name}"),
                    )
                }
            }
            if (exclusoes.total > 0) {
                Text(
                    "Fora das métricas: ${exclusoes.emAndamento} em andamento, ${exclusoes.cancelados} cancelado(s), ${exclusoes.excluidos} excluído(s), " +
                        "${exclusoes.emRevisao} em revisão, ${exclusoes.semDuracaoValida} sem duração válida.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.testTag("texto_exclusoes"),
                )
            }
            when (visao) {
                Visao.TIPO -> VisaoPorTipo(lista, limiar, filtro.bairroId == null, mostrarAgregado) { mostrarAgregado = it }
                Visao.COMPOSICAO -> {
                    Explicacao(
                        "Equipe = exatamente as mesmas pessoas (pela identidade do cadastro, não pelo nome). Cada serviço conta uma vez, " +
                            "mesmo com quatro participantes. Trocar uma pessoa forma outra equipe. Lista em ordem alfabética, sem ranking: " +
                            "profundidade, diâmetro, solo e acesso influenciam muito a duração.",
                    )
                    NotaSemEquipe(EstatisticasEquipe.semEquipe(lista, participacoes))
                    val grupos = remember(lista, participacoes, limiar) { EstatisticasEquipe.porComposicao(lista, participacoes, limiar) }
                    GruposPorTipo(grupos, limiar, "Nenhum serviço válido com equipe registrada para os filtros atuais.")
                    val porBairro = remember(lista, participacoes, limiar) { EstatisticasEquipe.porBairroEComposicao(lista, participacoes, limiar) }
                    if (porBairro.isNotEmpty()) {
                        Text("Por bairro + equipe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                        GruposPorTipo(porBairro, limiar, "")
                    }
                }
                Visao.PARTICIPACAO -> {
                    Explicacao(
                        "Estatística de PARTICIPAÇÃO: mostra a duração dos serviços em que a pessoa esteve presente. " +
                            "O tempo é da equipe inteira que executou o serviço, não produtividade individual. " +
                            "Um mesmo serviço aparece para cada participante.",
                    )
                    NotaSemEquipe(EstatisticasEquipe.semEquipe(lista, participacoes))
                    val grupos = remember(lista, participacoes, limiar) { EstatisticasEquipe.porFuncionario(lista, participacoes, limiar) }
                    GruposPorTipo(grupos, limiar, "Nenhum serviço válido com participantes para os filtros atuais.")
                }
                Visao.FUNCOES -> {
                    Explicacao(
                        "Tipo de composição por funções (ex.: 2 encanadores + 1 maquinista + 1 op. basculante). " +
                            "Métrica complementar: pessoas diferentes caem no mesmo grupo. Para comparar equipes, use a aba Equipes.",
                    )
                    val grupos = remember(lista, participacoes, limiar) { EstatisticasEquipe.porPerfilFuncoes(lista, participacoes, limiar) }
                    GruposPorTipo(grupos, limiar, "Nenhum serviço válido com equipe registrada para os filtros atuais.")
                }
            }
        }
    }
}

@Composable
private fun Explicacao(texto: String) {
    Text(texto, style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary, modifier = Modifier.testTag("explicacao_visao"))
}

@Composable
private fun NotaSemEquipe(n: Int) {
    if (n > 0) {
        Text(
            "$n serviço(s) válido(s) sem equipe registrada: entram só nas estatísticas por tipo.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Warning, modifier = Modifier.testTag("nota_sem_equipe"),
        )
    }
}

@Composable
private fun VisaoPorTipo(lista: List<LinhaServico>, limiar: Int, mostrarBairros: Boolean, mostrarAgregado: Boolean, aoAgregado: (Boolean) -> Unit) {
    val porTipo = remember(lista, limiar) { Estatisticas.porTipo(lista, limiar) }
    val porBairro = remember(lista, limiar) { Estatisticas.porTipoEBairro(lista, limiar) }
    Explicacao(
        "Médias calculadas dentro de cada tipo de serviço, somente com registros válidos " +
            "(finalizados, não cancelados, não excluídos e sem revisão pendente). Durações dependem de profundidade, solo, material e equipe: use como referência, não como prazo garantido.",
    )
    if (porTipo.isEmpty()) EstadoVazio("Sem dados válidos para os filtros atuais.")
    porTipo.forEach { g ->
        CartaoGrupo(g, limiar)
        val doTipo = porBairro.filter { it.chave.startsWith(g.chave + "|") }
        if (doTipo.size > 1 || mostrarBairros) TabelaBairros(doTipo, limiar)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(mostrarAgregado, role = Role.Switch) { aoAgregado(it) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Mostrar agregado de todos os tipos (mistura serviços diferentes)", modifier = Modifier.weight(1f))
        Switch(checked = mostrarAgregado, onCheckedChange = null)
    }
    if (mostrarAgregado) {
        Aviso("Agregado mistura tipos de serviço diferentes: não é comparável entre si.")
        CartaoGrupo(Estatisticas.agregadoTodosTipos(lista, limiar), limiar)
    }
}

@Composable
private fun Qualidade(q: QualidadeAmostra, n: Int, limiar: Int) {
    when (q) {
        QualidadeAmostra.SEM_DADOS -> Text("Sem dados", color = Tokens.TextSecondary)
        QualidadeAmostra.AMOSTRA_PEQUENA -> Aviso(
            if (n == 1) "Amostra insuficiente (n = 1): apenas um serviço, não é média." else "Amostra pequena (n = $n < $limiar). Médias pouco confiáveis.",
        )
        QualidadeAmostra.SUFICIENTE -> Text("Calculado com n = $n", color = Tokens.Success, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LinhasResumo(total: Resumo, ateRede: Resumo) {
    ParRotuloValor("Serviços válidos (n)", total.n.toString())
    ParRotuloValor("Média (total)", fmt(total.mediaMs))
    ParRotuloValor("Mediana (total)", fmt(total.medianaMs))
    ParRotuloValor("Mínimo / Máximo", "${Tempo.formatarDuracao(total.minimoMs)} / ${Tempo.formatarDuracao(total.maximoMs)}")
    HorizontalDivider(color = Tokens.Border, modifier = Modifier.padding(vertical = 4.dp))
    if (ateRede.n == 0) {
        ParRotuloValor("Até encontrar a rede", "sem marcações")
    } else {
        ParRotuloValor("Média até a rede", "${fmt(ateRede.mediaMs)} (n = ${ateRede.n})")
        ParRotuloValor("Mediana até a rede", fmt(ateRede.medianaMs))
    }
}

/** Grupos de equipe sob cabeçalho do tipo de serviço (nunca mistura tipos). */
@Composable
private fun GruposPorTipo(grupos: List<EstatisticaEquipe>, limiar: Int, vazio: String) {
    if (grupos.isEmpty()) {
        if (vazio.isNotEmpty()) EstadoVazio(vazio)
        return
    }
    grupos.groupBy { it.tipoServicoId }.forEach { (_, doTipo) ->
        Text(doTipo.first().tipoNome, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Tokens.Primary, modifier = Modifier.semantics { heading() })
        doTipo.forEach { g ->
            Surface(color = Tokens.SurfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("equipe_${g.chave}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(g.rotulo, fontWeight = FontWeight.SemiBold)
                    g.bairroNome?.let { Text("Bairro: $it", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary) }
                    Text("${g.tipoNome} • ${Tempo.data(g.primeiroInicioMs)} a ${Tempo.data(g.ultimoInicioMs)}", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                    Qualidade(g.qualidade, g.total.n, limiar)
                    LinhasResumo(g.total, g.ateRede)
                }
            }
        }
    }
}

@Composable
private fun CartaoGrupo(g: EstatisticaGrupo, limiar: Int) {
    Surface(color = Tokens.SurfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("estat_${g.chave}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(g.rotulo, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tokens.Primary)
            Qualidade(g.qualidade, g.total.n, limiar)
            ParRotuloValor("Período", "${Tempo.data(g.primeiroInicioMs)} a ${Tempo.data(g.ultimoInicioMs)}")
            LinhasResumo(g.total, g.ateRede)
            if (g.total.p25Ms != null) {
                ParRotuloValor("P25 / P75 / P90", "${fmt(g.total.p25Ms)} / ${fmt(g.total.p75Ms)} / ${fmt(g.total.p90Ms)}")
            } else {
                Text("Percentis exibidos a partir de n = ${RegrasNegocio.N_MINIMO_PERCENTIS}.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
            }
        }
    }
}

@Composable
private fun TabelaBairros(grupos: List<EstatisticaGrupo>, limiar: Int) {
    if (grupos.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("Bairro", Modifier.weight(0.46f), style = MaterialTheme.typography.labelMedium, color = Tokens.TextSecondary)
            Text("n", Modifier.weight(0.1f), style = MaterialTheme.typography.labelMedium, color = Tokens.TextSecondary)
            Text("Média", Modifier.weight(0.22f), style = MaterialTheme.typography.labelMedium, color = Tokens.TextSecondary)
            Text("Mediana", Modifier.weight(0.22f), style = MaterialTheme.typography.labelMedium, color = Tokens.TextSecondary)
        }
        grupos.forEach { g ->
            val nome = g.rotulo.substringAfter(" • ")
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(nome + if (g.qualidade == QualidadeAmostra.AMOSTRA_PEQUENA) " *" else "", Modifier.weight(0.46f), style = MaterialTheme.typography.bodySmall)
                Text(g.total.n.toString(), Modifier.weight(0.1f), style = MaterialTheme.typography.bodySmall)
                Text(fmt(g.total.mediaMs), Modifier.weight(0.22f), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                Text(fmt(g.total.medianaMs), Modifier.weight(0.22f), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
            }
        }
        if (grupos.any { it.qualidade == QualidadeAmostra.AMOSTRA_PEQUENA }) {
            Text("* amostra pequena (n < $limiar)", style = MaterialTheme.typography.labelSmall, color = Tokens.Warning)
        }
    }
}
