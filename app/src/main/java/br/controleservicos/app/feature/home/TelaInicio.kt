package br.controleservicos.app.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.BarraTopo
import br.controleservicos.app.core.design.BotaoPrimario
import br.controleservicos.app.core.design.EstiloCronometro
import br.controleservicos.app.core.design.Tokens
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.ResolvedorRelogio
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.LocalOperacional
import br.controleservicos.app.feature.comum.rememberAgora

@Composable
fun TelaInicio(
    container: AppContainer,
    aoNovo: () -> Unit,
    aoContinuar: (String) -> Unit,
    aoComplementar: (String) -> Unit,
    aoHistorico: () -> Unit,
    aoEstatisticas: () -> Unit,
    aoExportar: () -> Unit,
    aoConfiguracoes: () -> Unit,
    aoFuncionarios: () -> Unit = {},
) {
    val repo = container.repositorio
    val ativo by remember { repo.observarAtivo() }.collectAsStateWithLifecycle(initialValue = null)
    val pendentes by remember { repo.observarPendentes() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var avisoAtivo by remember { mutableStateOf(false) }
    val prefs by remember { container.preferencias.preferencias }.collectAsStateWithLifecycle(initialValue = null)
    val linhas by remember { repo.observarLinhas() }.collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(topBar = { BarraTopo("Controle de Serviços", subtitulo = LocalOperacional.ROTULO) }, containerColor = Tokens.Background) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ativo?.let { CartaoAtivo(it, container, aoContinuar) }

            BotaoPrimario(
                texto = "Novo serviço",
                icone = Icons.Filled.Add,
                aoClicar = { if (ativo != null) avisoAtivo = true else aoNovo() },
                modifier = Modifier.testTag("botao_novo_servico"),
            )

            if (pendentes.isNotEmpty()) {
                Surface(color = Tokens.WarningContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Pendentes de complementos (${pendentes.size})",
                            fontWeight = FontWeight.SemiBold, color = Tokens.Warning,
                        )
                        Text("Tempo já registrado. Falta preencher os dados da rede.", style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary)
                        pendentes.take(5).forEach { p ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { aoComplementar(p.id) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = Tokens.Warning)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${p.tipoNome} • ${p.bairroNome}", fontWeight = FontWeight.Medium)
                                    Text("${Tempo.dataHora(p.fimUtcEpochMs)} • ${Tempo.formatarDuracao(p.duracaoTotalMs)}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Complementar", color = Tokens.Primary, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            Surface(shape = RoundedCornerShape(12.dp), color = Tokens.SurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column {
                    ItemMenu("Histórico", Icons.AutoMirrored.Filled.List, aoHistorico)
                    HorizontalDivider(color = Tokens.Border)
                    ItemMenu("Estatísticas", Icons.Filled.Info, aoEstatisticas)
                    HorizontalDivider(color = Tokens.Border)
                    ItemMenu("Funcionários", Icons.Filled.Person, aoFuncionarios, Modifier.testTag("menu_funcionarios"))
                    HorizontalDivider(color = Tokens.Border)
                    ItemMenu("Exportar", Icons.Filled.Share, aoExportar)
                    HorizontalDivider(color = Tokens.Border)
                    ItemMenu("Configurações", Icons.Filled.Settings, aoConfiguracoes)
                }
            }
            Text(
                "Dados guardados somente neste aparelho. Funciona sem internet.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.TextSecondary,
            )
            // Lembrete discreto de backup (celular único = ponto único de falha).
            val p = prefs
            if (p != null && linhas.isNotEmpty()) {
                val semBackupRecente = p.ultimoBackupMs == null ||
                    container.relogio.agora().wallClockMs - p.ultimoBackupMs > 7L * 24 * 3600 * 1000
                if (semBackupRecente) {
                    Text(
                        if (p.ultimoBackupMs == null) "Nenhum backup feito ainda — Configurações › Backup." else "Último backup há mais de 7 dias — Configurações › Backup.",
                        style = MaterialTheme.typography.bodySmall, color = Tokens.Warning,
                        modifier = Modifier.clickable(onClick = aoConfiguracoes).padding(vertical = 4.dp).testTag("lembrete_backup"),
                    )
                }
            }
        }
    }

    if (avisoAtivo) {
        val a = ativo
        AlertDialog(
            onDismissRequest = { avisoAtivo = false },
            title = { Text("Serviço em andamento") },
            text = { Text("Já existe um serviço sendo cronometrado. Conclua ou continue o serviço atual antes de iniciar outro.") },
            confirmButton = { TextButton(onClick = { avisoAtivo = false; a?.let { aoContinuar(it.id) } }) { Text("Continuar") } },
            dismissButton = { TextButton(onClick = { avisoAtivo = false }) { Text("Fechar") } },
        )
    }
}

@Composable
private fun CartaoAtivo(s: LinhaServico, container: AppContainer, aoContinuar: (String) -> Unit) {
    val agora = rememberAgora(container.relogio)
    val inicio = Marcacao(s.inicioUtcEpochMs, s.inicioElapsedRealtimeMs, s.inicioBootCount)
    Surface(
        color = Tokens.PrimaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { aoContinuar(s.id) }.testTag("cartao_ativo"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Serviço em andamento", color = Tokens.OnPrimaryContainer, fontWeight = FontWeight.SemiBold)
            Text("${s.tipoNome} • ${s.bairroNome}", color = Tokens.TextPrimary)
            Text(s.enderecoResumido, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(
                Tempo.formatarDuracao(ResolvedorRelogio.decorridoParaExibicao(inicio, agora)),
                style = EstiloCronometro.copy(fontSize = 36.sp),
            )
            BotaoPrimario("Continuar", aoClicar = { aoContinuar(s.id) }, icone = Icons.Filled.PlayArrow)
        }
    }
}

@Composable
private fun ItemMenu(texto: String, icone: ImageVector, aoClicar: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = aoClicar).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icone, contentDescription = null, tint = Tokens.Primary)
        Spacer(Modifier.width(16.dp))
        Text(texto, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Tokens.TextSecondary)
    }
}
