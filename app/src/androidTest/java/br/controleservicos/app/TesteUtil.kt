package br.controleservicos.app

import android.content.Context
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.Preferencias
import br.controleservicos.app.core.RelogioAndroid
import br.controleservicos.app.core.RepositorioPreferencias
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.model.CodigosCatalogo
import br.controleservicos.app.core.model.SituacaoRede
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.domain.LigacaoValidada
import br.controleservicos.app.core.address.FonteEnderecos
import br.controleservicos.app.core.address.ResultadoBusca
import br.controleservicos.app.core.repository.Complementos
import br.controleservicos.app.core.repository.NovoServico
import br.controleservicos.app.core.repository.ParticipanteNovo
import br.controleservicos.app.core.repository.Relogio
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class PreferenciasMemoria(inicial: Preferencias = Preferencias()) : RepositorioPreferencias {
    override val preferencias = MutableStateFlow(inicial)
    override suspend fun salvar(transformar: (Preferencias) -> Preferencias) = preferencias.update(transformar)
}

/** Relógio controlável para testes determinísticos. */
class RelogioFalso(var wall: Long = 1_763_000_000_000L, var elapsed: Long = 5_000_000L, var boot: Int = 3) : Relogio {
    override fun agora() = Marcacao(wall, elapsed, boot)
    fun avancar(ms: Long) { wall += ms; elapsed += ms }
}

/** Fonte de endereços falsa: testes nunca acessam a rede real. Padrão = sem conexão. */
class FonteFalsa(var resposta: (String) -> ResultadoBusca = { ResultadoBusca.SemConexao }) : FonteEnderecos {
    val consultas = mutableListOf<String>()
    override suspend fun buscarLogradouros(termo: String): ResultadoBusca { consultas += termo; return resposta(termo) }
}

fun containerDeTeste(ctx: Context, relogio: Relogio = RelogioAndroid(ctx), fonte: FonteEnderecos = FonteFalsa()) =
    AppContainer(ctx, AppDatabase.emMemoria(ctx), relogio, PreferenciasMemoria(), fonte)

/** Ligação trocada SINTÉTICA para testes. */
fun lig(numero: String?, lado: LadoRamal = LadoRamal.LONGO, cm: Int = 350, rua: String? = null, bairro: String? = null, identificador: String? = null, legado: Boolean = false) =
    LigacaoValidada(numero, lado, cm, bairro, rua, null, identificador, null, numero == null, legado)

fun complementosComLigacoes(vararg l: LigacaoValidada, semEquipe: Boolean = true) =
    Complementos(SituacaoRede.NAO_APLICAVEL, null, null, null, null, emptyMap(), null, ramalAguaTrocado = true, ligacoes = l.toList(), permitirSemParticipantes = semEquipe)

/** Dados SINTÉTICOS de teste (nunca usados no banco de produção). */
fun novo(
    tipo: String = CodigosCatalogo.TIPO_PRP_NAO_VISIVEL,
    bairro: String = "Jardim Exemplo",
    participantes: List<ParticipanteNovo> = emptyList(),
) = NovoServico(tipo, bairro, "Rua Exemplo", "100", null, "America/Sao_Paulo", participantes)

/** Complementos mínimos; por padrão permite salvar sem equipe (testes focados em tempo). */
fun complementosBasicos(semEquipe: Boolean = true) =
    Complementos(SituacaoRede.NAO_APLICAVEL, null, null, null, null, emptyMap(), null, permitirSemParticipantes = semEquipe)
