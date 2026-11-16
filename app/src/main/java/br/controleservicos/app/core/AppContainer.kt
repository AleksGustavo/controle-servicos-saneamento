package br.controleservicos.app.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.model.RegrasNegocio
import br.controleservicos.app.core.repository.Relogio
import br.controleservicos.app.core.repository.ServicoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

data class Preferencias(
    val limiarAmostra: Int = RegrasNegocio.LIMIAR_AMOSTRA_PEQUENA_PADRAO,
    val formatoExportacao: FormatoExportacao = FormatoExportacao.XLSX,
    /** Limite técnico para o comprimento de ramal trocado (detecção de erro de digitação). */
    val limiteRamalMetros: Int = RegrasNegocio.LIMITE_RAMAL_METROS_PADRAO,
    /** Instante do último backup gerado pelo app (null = nunca). */
    val ultimoBackupMs: Long? = null,
)

interface RepositorioPreferencias {
    val preferencias: Flow<Preferencias>
    suspend fun salvar(transformar: (Preferencias) -> Preferencias)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "preferencias")

class PreferenciasDataStore(private val context: Context) : RepositorioPreferencias {
    // As chaves antigas municipio_padrao/uf_padrao (v1) são ignoradas: o município agora é fixo (Leme/SP).
    private val kLimiar = intPreferencesKey("limiar_amostra")
    private val kFormato = stringPreferencesKey("formato_exportacao")
    private val kLimiteRamal = intPreferencesKey("limite_ramal_metros")
    private val kUltimoBackup = longPreferencesKey("ultimo_backup_ms")

    private fun ler(p: Preferences) = Preferencias(
        limiarAmostra = (p[kLimiar] ?: RegrasNegocio.LIMIAR_AMOSTRA_PEQUENA_PADRAO).coerceIn(1, 1000),
        formatoExportacao = FormatoExportacao.entries.firstOrNull { it.name == p[kFormato] }
            ?.takeIf { it != FormatoExportacao.CSV_PARTICIPACOES } ?: FormatoExportacao.XLSX,
        limiteRamalMetros = (p[kLimiteRamal] ?: RegrasNegocio.LIMITE_RAMAL_METROS_PADRAO).coerceIn(1, 10_000),
        ultimoBackupMs = p[kUltimoBackup],
    )

    override val preferencias: Flow<Preferencias> = context.dataStore.data.map(::ler)

    override suspend fun salvar(transformar: (Preferencias) -> Preferencias) {
        context.dataStore.edit { p ->
            val novo = transformar(ler(p))
            p[kLimiar] = novo.limiarAmostra
            p[kFormato] = novo.formatoExportacao.name
            p[kLimiteRamal] = novo.limiteRamalMetros
            novo.ultimoBackupMs?.let { p[kUltimoBackup] = it }
        }
    }
}

/**
 * Relógio real (o "ClockProvider" injetável): parede (UTC) + monotônico (elapsedRealtime, conta
 * também o tempo com a tela apagada/aparelho em suspensão) + contador de boots (Settings.Global.BOOT_COUNT).
 * Nos testes é substituído por um relógio falso determinístico.
 */
class RelogioAndroid(private val context: Context) : Relogio {
    override fun agora(): Marcacao = Marcacao(
        wallClockMs = System.currentTimeMillis(),
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        bootCount = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        } catch (_: Exception) {
            null
        },
    )
}

/** Injeção de dependências manual (sem Hilt: poucas dependências, fácil de testar). */
class AppContainer(
    val context: Context,
    val database: AppDatabase,
    val relogio: Relogio,
    val preferencias: RepositorioPreferencias,
    /** Sugestões externas de logradouro (ViaCEP); substituível por fonte falsa em testes. */
    val fonteEnderecos: br.controleservicos.app.core.address.FonteEnderecos = br.controleservicos.app.core.address.FonteViaCep(),
) {
    val repositorio = ServicoRepository(database, relogio)

    /** Filtros atuais compartilhados entre Histórico, Estatísticas e Exportação. */
    val filtro = MutableStateFlow(FiltroServicos())

    /** Filtros da exportação em lote (mantidos ao voltar para ajustar); padrão: somente concluídos. */
    val filtroExportacao = MutableStateFlow(FiltroServicos(somenteFinalizados = true))

    companion object {
        fun producao(context: Context): AppContainer {
            val app = context.applicationContext
            return AppContainer(app, AppDatabase.construir(app), RelogioAndroid(app), PreferenciasDataStore(app))
        }
    }
}
