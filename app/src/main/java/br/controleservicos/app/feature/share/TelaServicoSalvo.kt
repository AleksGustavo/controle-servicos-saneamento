package br.controleservicos.app.feature.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.BotaoSecundario
import br.controleservicos.app.core.design.ParRotuloValor
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Tempo

/**
 * Resumo de sucesso após salvar: o registro já está gravado; compartilhar é opcional e
 * qualquer falha na geração do arquivo não desfaz o serviço (usa o ID persistido).
 */
@Composable
fun TelaServicoSalvo(
    container: AppContainer,
    servicoId: String,
    aoInicio: () -> Unit,
    aoHistorico: () -> Unit,
    aoDetalhe: () -> Unit,
) {
    val s by remember { container.repositorio.observarLinha(servicoId) }.collectAsStateWithLifecycle(null)
    val ligacoes by remember { container.repositorio.observarLigacoesDe(servicoId) }.collectAsStateWithLifecycle(emptyList())
    var compartilhando by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { msg?.let { snack.showSnackbar(it); msg = null } }

    Scaffold(topBar = { BarraTopo("Serviço salvo") }, snackbarHost = { SnackbarHost(snack) }, containerColor = Tokens.Background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Tokens.Success)
            Text("Registro salvo neste aparelho", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("texto_servico_salvo"))
            s?.let { l ->
                Column(Modifier.padding(horizontal = 4.dp)) {
                    ParRotuloValor("Tipo", l.tipoNome)
                    ParRotuloValor("Endereço", "${l.logradouro}, ${l.numero} — ${l.bairroNome}")
                    ParRotuloValor("Duração total", Tempo.formatarDuracao(l.duracaoTotalMs))
                    ParRotuloValor("Ligações trocadas", if (!l.ramalAguaTrocado) "Nenhuma" else "${ligacoes.size}: nº " + ligacoes.joinToString(", ") { it.numeroTexto ?: "?" })
                }
            }
            BotaoPrimario("Compartilhar", { compartilhando = true }, icone = Icons.Filled.Share, modifier = Modifier.testTag("salvo_compartilhar"))
            BotaoSecundario("Ver detalhes", aoDetalhe, icone = Icons.Filled.Info)
            BotaoSecundario("Histórico", aoHistorico, icone = Icons.AutoMirrored.Filled.List, modifier = Modifier.testTag("salvo_historico"))
            BotaoSecundario("Voltar ao início", aoInicio, icone = Icons.Filled.Home, modifier = Modifier.testTag("salvo_inicio"))
        }
    }
    if (compartilhando) DialogoCompartilhar(container, servicoId, aoFechar = { compartilhando = false }, aoMensagem = { msg = it })
}
