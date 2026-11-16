package br.controleservicos.app.core.address

import br.controleservicos.app.core.domain.EnderecoExterno
import br.controleservicos.app.core.domain.Enderecos
import br.controleservicos.app.core.domain.Texto
import br.controleservicos.app.core.model.LocalOperacional
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

/** Resultado de uma consulta externa. Falhas nunca bloqueiam o registro do serviço. */
sealed interface ResultadoBusca {
    data class Sucesso(val itens: List<EnderecoExterno>) : ResultadoBusca
    data object SemConexao : ResultadoBusca
    data object LimiteExcedido : ResultadoBusca
    data class Erro(val mensagem: String) : ResultadoBusca
}

/** Fonte externa de sugestões de logradouro (interface para testes com fonte falsa). */
fun interface FonteEnderecos {
    suspend fun buscarLogradouros(termo: String): ResultadoBusca
}

/** Requisição HTTP mínima: (url) → (código, corpo). Substituível em testes. */
fun interface ClienteHttp {
    @Throws(IOException::class)
    fun get(url: String): Pair<Int, String>
}

object ClienteHttpPadrao : ClienteHttp {
    private const val LIMITE_BYTES = 512 * 1024

    override fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "ControleDeServicos-Android")
            val codigo = c.responseCode
            val stream = if (codigo in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(8192)
                while (true) {
                    val n = s.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    if (buf.size() > LIMITE_BYTES) throw IOException("Resposta grande demais")
                }
                buf.toByteArray()
            } ?: ByteArray(0)
            return codigo to String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * ViaCEP — pesquisa por logradouro em `https://viacep.com.br/ws/SP/Leme/{termo}/json/`.
 * Verificado em 13/11/2026: termo < 3 caracteres → HTTP 400; no máximo 50 resultados; a busca
 * em "Leme" também devolve outras cidades (ex.: Clementina) — por isso o filtro estrito por
 * localidade, UF e código IBGE. NÃO é catálogo completo e não lista "todas as ruas do bairro".
 * Limites locais: cache de consultas, no máximo [maxPorMinuto] requisições/min e pausa após HTTP 429.
 */
class FonteViaCep(
    private val http: ClienteHttp = ClienteHttpPadrao,
    private val agora: () -> Long = System::currentTimeMillis,
    private val maxPorMinuto: Int = 12,
    private val pausaApos429Ms: Long = 120_000,
) : FonteEnderecos {
    private val cache = object : LinkedHashMap<String, List<EnderecoExterno>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EnderecoExterno>>?) = size > 100
    }
    private val chamadas = ArrayDeque<Long>()
    private var bloqueadoAte = 0L
    var requisicoesFeitas = 0
        private set

    fun url(termo: String): String {
        val seg = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
        return "https://viacep.com.br/ws/${LocalOperacional.UF}/${seg(LocalOperacional.MUNICIPIO)}/${seg(termo)}/json/"
    }

    override suspend fun buscarLogradouros(termo: String): ResultadoBusca {
        val t = Texto.limparNome(termo)
        if (t.length < 3) return ResultadoBusca.Sucesso(emptyList())
        val chave = Texto.normalizarNome(t)
        synchronized(cache) { cache[chave] }?.let { return ResultadoBusca.Sucesso(it) }
        val now = agora()
        synchronized(chamadas) {
            if (now < bloqueadoAte) return ResultadoBusca.LimiteExcedido
            while (chamadas.isNotEmpty() && now - chamadas.first() > 60_000) chamadas.removeFirst()
            if (chamadas.size >= maxPorMinuto) return ResultadoBusca.LimiteExcedido
            chamadas.addLast(now)
            requisicoesFeitas++
        }
        return withContext(Dispatchers.IO) {
            try {
                val (codigo, corpo) = http.get(url(t))
                when {
                    codigo == 429 -> { bloqueadoAte = agora() + pausaApos429Ms; ResultadoBusca.LimiteExcedido }
                    codigo !in 200..299 -> ResultadoBusca.Erro("HTTP $codigo")
                    else -> {
                        val itens = Enderecos.interpretarViaCep(corpo)
                        synchronized(cache) { cache[chave] = itens }
                        ResultadoBusca.Sucesso(itens)
                    }
                }
            } catch (_: UnknownHostException) {
                ResultadoBusca.SemConexao
            } catch (_: SocketTimeoutException) {
                ResultadoBusca.SemConexao
            } catch (_: java.net.ConnectException) {
                ResultadoBusca.SemConexao
            } catch (e: IOException) {
                ResultadoBusca.Erro(e.message ?: "Falha de rede")
            } catch (e: SecurityException) {
                ResultadoBusca.SemConexao
            }
        }
    }
}

/** Estado da busca externa exibido sob a lista local. */
data class EstadoBuscaExterna(
    val termo: String = "",
    val carregando: Boolean = false,
    val itens: List<EnderecoExterno> = emptyList(),
    val aviso: String? = null,
)

/**
 * Busca externa com debounce (500 ms), mínimo de 3 caracteres e cancelamento da consulta anterior
 * (`mapLatest`): uma resposta atrasada de "Rua A" nunca sobrescreve a de "Rua B".
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class BuscadorExterno(
    private val fonte: FonteEnderecos,
    escopo: CoroutineScope,
    debounceMs: Long = 500,
    private val aoReceber: suspend (List<EnderecoExterno>) -> Unit = {},
) {
    private val termo = MutableStateFlow("")
    private val _estado = MutableStateFlow(EstadoBuscaExterna())
    val estado: StateFlow<EstadoBuscaExterna> = _estado

    init {
        termo.debounce(debounceMs).distinctUntilChanged().mapLatest { t ->
            val limpo = Texto.limparNome(t)
            if (limpo.length < 3) return@mapLatest EstadoBuscaExterna(t)
            _estado.value = EstadoBuscaExterna(t, carregando = true)
            when (val r = fonte.buscarLogradouros(limpo)) {
                is ResultadoBusca.Sucesso -> { aoReceber(r.itens); EstadoBuscaExterna(t, itens = r.itens, aviso = if (r.itens.isEmpty()) "Nenhuma sugestão online para \"$limpo\"." else null) }
                ResultadoBusca.SemConexao -> EstadoBuscaExterna(t, aviso = AVISO_FALHA)
                ResultadoBusca.LimiteExcedido -> EstadoBuscaExterna(t, aviso = "Muitas consultas seguidas; aguarde um pouco. $AVISO_FALHA")
                is ResultadoBusca.Erro -> EstadoBuscaExterna(t, aviso = AVISO_FALHA)
            }
        }.onEach { _estado.value = it }.launchIn(escopo)
    }

    fun pesquisar(texto: String) { termo.value = texto }

    companion object {
        const val AVISO_FALHA = "Não foi possível atualizar endereços. Você pode continuar usando os dados salvos ou cadastrar manualmente."
    }
}
