package br.controleservicos.app.core.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.feature.completion.TelaComplementos
import br.controleservicos.app.feature.create.TelaNovoServico
import br.controleservicos.app.feature.details.TelaDetalhe
import br.controleservicos.app.feature.export.TelaExportar
import br.controleservicos.app.feature.history.TelaHistorico
import br.controleservicos.app.feature.home.TelaInicio
import br.controleservicos.app.feature.settings.TelaConfiguracoes
import br.controleservicos.app.feature.statistics.TelaEstatisticas
import br.controleservicos.app.feature.team.TelaFuncionarios
import br.controleservicos.app.feature.share.TelaServicoSalvo
import br.controleservicos.app.feature.team.TelaParticipantesServico
import br.controleservicos.app.feature.timer.TelaCronometro

object Rotas {
    const val INICIO = "inicio"
    const val NOVO = "novo"
    const val CRONOMETRO = "cronometro/{id}"
    const val COMPLEMENTOS = "complementos/{id}"
    const val DETALHE = "detalhe/{id}"
    const val HISTORICO = "historico"
    const val ESTATISTICAS = "estatisticas"
    const val EXPORTAR = "exportar"
    const val CONFIGURACOES = "configuracoes"
    const val FUNCIONARIOS = "funcionarios"
    const val PARTICIPANTES = "participantes/{id}"
    const val SALVO = "salvo/{id}"

    fun cronometro(id: String) = "cronometro/$id"
    fun complementos(id: String) = "complementos/$id"
    fun detalhe(id: String) = "detalhe/$id"
    fun participantes(id: String) = "participantes/$id"
    fun salvo(id: String) = "salvo/$id"
}

private fun NavHostController.irLimpandoAteInicio(rota: String) = navigate(rota) {
    popUpTo(Rotas.INICIO)
    launchSingleTop = true
}

@Composable
fun AppNavHost(container: AppContainer, nav: NavHostController = rememberNavController()) {
    val idArg = listOf(navArgument("id") { type = NavType.StringType })
    NavHost(navController = nav, startDestination = Rotas.INICIO) {
        composable(Rotas.INICIO) {
            TelaInicio(
                container = container,
                aoNovo = { nav.navigate(Rotas.NOVO) },
                aoContinuar = { nav.navigate(Rotas.cronometro(it)) { launchSingleTop = true } },
                aoComplementar = { nav.navigate(Rotas.complementos(it)) },
                aoHistorico = { nav.navigate(Rotas.HISTORICO) },
                aoEstatisticas = { nav.navigate(Rotas.ESTATISTICAS) },
                aoExportar = { nav.navigate(Rotas.EXPORTAR) },
                aoConfiguracoes = { nav.navigate(Rotas.CONFIGURACOES) },
                aoFuncionarios = { nav.navigate(Rotas.FUNCIONARIOS) },
            )
        }
        composable(Rotas.NOVO) {
            TelaNovoServico(
                container = container,
                aoVoltar = { nav.popBackStack() },
                aoIniciado = { nav.irLimpandoAteInicio(Rotas.cronometro(it)) },
            )
        }
        composable(Rotas.CRONOMETRO, arguments = idArg) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TelaCronometro(
                container = container,
                servicoId = id,
                aoVoltar = { if (!nav.popBackStack(Rotas.INICIO, false)) nav.irLimpandoAteInicio(Rotas.INICIO) },
                aoFinalizado = { nav.irLimpandoAteInicio(Rotas.complementos(id)) },
                aoEncerradoExternamente = { nav.irLimpandoAteInicio(Rotas.detalhe(id)) },
                aoParticipantes = { nav.navigate(Rotas.participantes(id)) { launchSingleTop = true } },
            )
        }
        composable(Rotas.COMPLEMENTOS, arguments = idArg) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TelaComplementos(
                container = container,
                servicoId = id,
                aoVoltar = { nav.popBackStack() },
                aoSalvo = { nav.irLimpandoAteInicio(Rotas.salvo(id)) },
                aoParticipantes = { nav.navigate(Rotas.participantes(id)) { launchSingleTop = true } },
            )
        }
        composable(Rotas.DETALHE, arguments = idArg) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TelaDetalhe(
                container = container,
                servicoId = id,
                aoVoltar = { nav.popBackStack() },
                aoComplementar = { nav.navigate(Rotas.complementos(id)) },
                aoCronometro = { nav.navigate(Rotas.cronometro(id)) },
                aoParticipantes = { nav.navigate(Rotas.participantes(id)) { launchSingleTop = true } },
            )
        }
        composable(Rotas.HISTORICO) {
            TelaHistorico(container, aoVoltar = { nav.popBackStack() }, aoAbrir = { nav.navigate(Rotas.detalhe(it)) }, aoExportar = { nav.navigate(Rotas.EXPORTAR) })
        }
        composable(Rotas.ESTATISTICAS) {
            TelaEstatisticas(container, aoVoltar = { nav.popBackStack() })
        }
        composable(Rotas.EXPORTAR) {
            TelaExportar(container, aoVoltar = { nav.popBackStack() })
        }
        composable(Rotas.CONFIGURACOES) {
            TelaConfiguracoes(container, aoVoltar = { nav.popBackStack() })
        }
        composable(Rotas.SALVO, arguments = idArg) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TelaServicoSalvo(
                container, id,
                aoInicio = { if (!nav.popBackStack(Rotas.INICIO, false)) nav.irLimpandoAteInicio(Rotas.INICIO) },
                aoHistorico = { nav.irLimpandoAteInicio(Rotas.HISTORICO) },
                aoDetalhe = { nav.irLimpandoAteInicio(Rotas.detalhe(id)) },
            )
        }
        composable(Rotas.FUNCIONARIOS) {
            TelaFuncionarios(container, aoVoltar = { nav.popBackStack() })
        }
        composable(Rotas.PARTICIPANTES, arguments = idArg) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            TelaParticipantesServico(container, id, aoVoltar = { nav.popBackStack() })
        }
    }
}
