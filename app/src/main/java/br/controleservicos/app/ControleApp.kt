package br.controleservicos.app

import android.app.Application
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.design.ControleTheme
import br.controleservicos.app.core.navigation.AppNavHost

class ControleApp : Application() {
    val container: AppContainer by lazy { AppContainer.producao(this) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        val container = (application as ControleApp).container
        setContent {
            ControleTheme {
                AppNavHost(container)
            }
        }
    }
}
