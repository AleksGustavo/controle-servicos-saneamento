package br.controleservicos.app.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Tokens de cor (contraste AA sobre fundo branco). */
object Tokens {
    val Primary = Color(0xFF1565C0)
    val PrimaryDark = Color(0xFF0D47A1)
    val PrimaryContainer = Color(0xFFE3F0FD)
    val OnPrimaryContainer = Color(0xFF0B3A75)
    val Background = Color(0xFFFFFFFF)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFF3F6FA)
    val TextPrimary = Color(0xFF14233C)
    val TextSecondary = Color(0xFF4A5568)
    val Border = Color(0xFFCBD5E1)
    val Error = Color(0xFFB3261E)
    val ErrorContainer = Color(0xFFFDECEA)
    val Success = Color(0xFF1B7F3B)
    val SuccessContainer = Color(0xFFE6F4EA)
    val Warning = Color(0xFF8A5300)
    val WarningContainer = Color(0xFFFFF4E0)
}

private val Esquema = lightColorScheme(
    primary = Tokens.Primary,
    onPrimary = Color.White,
    primaryContainer = Tokens.PrimaryContainer,
    onPrimaryContainer = Tokens.OnPrimaryContainer,
    secondary = Tokens.PrimaryDark,
    onSecondary = Color.White,
    secondaryContainer = Tokens.PrimaryContainer,
    onSecondaryContainer = Tokens.OnPrimaryContainer,
    background = Tokens.Background,
    onBackground = Tokens.TextPrimary,
    surface = Tokens.Surface,
    onSurface = Tokens.TextPrimary,
    surfaceVariant = Tokens.SurfaceVariant,
    onSurfaceVariant = Tokens.TextSecondary,
    surfaceContainer = Tokens.SurfaceVariant,
    surfaceContainerLow = Tokens.SurfaceVariant,
    surfaceContainerHigh = Color(0xFFEDF2F7),
    surfaceContainerHighest = Color(0xFFE6ECF2),
    outline = Tokens.Border,
    outlineVariant = Color(0xFFE2E8F0),
    error = Tokens.Error,
    onError = Color.White,
    errorContainer = Tokens.ErrorContainer,
)

/** Tema claro obrigatório. Tema escuro não implementado no MVP (decisão documentada). */
@Composable
fun ControleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema, content = content)
}

/** Estilo do cronômetro: grande, com algarismos tabulares. */
val EstiloCronometro = TextStyle(
    fontSize = 64.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
    letterSpacing = 1.sp,
    color = Tokens.TextPrimary,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarraTopo(titulo: String, aoVoltar: (() -> Unit)? = null, subtitulo: String? = null, acoes: @Composable () -> Unit = {}) {
    CenterAlignedTopAppBar(
        title = {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(titulo, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                if (subtitulo != null) {
                    Text(subtitulo, style = MaterialTheme.typography.labelMedium, color = Tokens.TextSecondary, modifier = Modifier.testTag("subtitulo_local"))
                }
            }
        },
        navigationIcon = {
            if (aoVoltar != null) {
                IconButton(onClick = aoVoltar) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            }
        },
        actions = { acoes() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Tokens.Background),
    )
}

/** Botão primário grande (>= 56 dp). */
@Composable
fun BotaoPrimario(
    texto: String,
    aoClicar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    icone: ImageVector? = null,
    cor: Color = Tokens.Primary,
) {
    Button(
        onClick = aoClicar,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = cor),
    ) {
        if (icone != null) {
            Icon(icone, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(texto, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun BotaoSecundario(texto: String, aoClicar: () -> Unit, modifier: Modifier = Modifier, icone: ImageVector? = null, habilitado: Boolean = true) {
    OutlinedButton(
        onClick = aoClicar,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Tokens.Primary),
    ) {
        if (icone != null) {
            Icon(icone, contentDescription = null)
            Spacer(Modifier.width(8.dp))
        }
        Text(texto, fontSize = 16.sp)
    }
}

/** Faixa de aviso (amostra pequena, revisão, privacidade). */
@Composable
fun Aviso(texto: String, modifier: Modifier = Modifier, erro: Boolean = false) {
    Surface(
        color = if (erro) Tokens.ErrorContainer else Tokens.WarningContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = if (erro) Tokens.Error else Tokens.Warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(texto, color = if (erro) Tokens.Error else Tokens.Warning, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun Secao(titulo: String, modifier: Modifier = Modifier, conteudo: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(titulo, style = MaterialTheme.typography.titleMedium, color = Tokens.Primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        conteudo()
    }
}

@Composable
fun ParRotuloValor(rotulo: String, valor: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(rotulo, color = Tokens.TextSecondary, modifier = Modifier.weight(0.45f), style = MaterialTheme.typography.bodyMedium)
        Text(valor, color = Tokens.TextPrimary, modifier = Modifier.weight(0.55f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun EstadoVazio(texto: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(texto, color = Tokens.TextSecondary, style = MaterialTheme.typography.bodyLarge)
    }
}
