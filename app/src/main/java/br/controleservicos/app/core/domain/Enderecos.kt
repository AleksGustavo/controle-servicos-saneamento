package br.controleservicos.app.core.domain

import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.LocalOperacional

/** Endereço retornado por uma fonte externa (ex.: ViaCEP), já filtrado para Leme/SP. */
data class EnderecoExterno(
    val cep: String?,
    /** Nome completo como retornado, ex. "Avenida Joaquim Lopes Aguila". */
    val logradouro: String,
    val tipo: String?,
    val bairro: String?,
    val localidade: String,
    val uf: String,
)

object Enderecos {
    private val TIPOS = listOf(
        "Rua", "Avenida", "Travessa", "Alameda", "Praça", "Rodovia", "Estrada", "Viela", "Largo",
        "Viaduto", "Via", "Vila", "Passagem", "Rotatória", "Servidão", "Caminho", "Acesso", "Ladeira",
    )

    /** Abreviações comuns → forma por extenso, só para comparação (a grafia exibida é preservada). */
    private val ABREVIACOES = mapOf(
        "av" to "avenida", "av." to "avenida", "r" to "rua", "r." to "rua", "tv" to "travessa", "tv." to "travessa",
        "trav" to "travessa", "trav." to "travessa", "al" to "alameda", "al." to "alameda", "pc" to "praca", "pca" to "praca",
        "pc." to "praca", "rod" to "rodovia", "rod." to "rodovia", "est" to "estrada", "est." to "estrada",
    )

    /** Tipo do logradouro pela primeira palavra ("Av." → "Avenida"); null se não reconhecido. */
    fun tipoDe(nome: String): String? {
        val primeira = Texto.normalizarNome(nome).substringBefore(' ')
        val expandida = ABREVIACOES[primeira] ?: primeira
        return TIPOS.firstOrNull { Texto.normalizarNome(it) == expandida }
    }

    /**
     * Normalização para busca e deduplicação de logradouros: sem acentos/caixa/espaços extras e com
     * abreviação do tipo expandida ("Av. Brasil" == "Avenida  BRASIL"). Não remove outras palavras.
     */
    fun normalizarLogradouro(nome: String): String {
        val partes = Texto.normalizarNome(nome).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (partes.isNotEmpty()) partes[0] = ABREVIACOES[partes[0]] ?: partes[0]
        return partes.joinToString(" ")
    }

    fun ehLeme(localidade: String?, uf: String?, ibge: String?): Boolean =
        Texto.normalizarNome(localidade ?: "") == Texto.normalizarNome(LocalOperacional.MUNICIPIO) &&
            (uf ?: "").trim().uppercase() == LocalOperacional.UF &&
            (ibge.isNullOrBlank() || ibge.trim() == LocalOperacional.CODIGO_IBGE)

    /** CEP só com dígitos, formatado 00000-000; null se inválido. */
    fun normalizarCep(cep: String?): String? {
        val d = cep?.filter(Char::isDigit) ?: return null
        return if (d.length == 8) "${d.substring(0, 5)}-${d.substring(5)}" else null
    }

    /**
     * Interpreta a resposta de pesquisa por endereço do ViaCEP (array JSON de objetos planos).
     * Descarta itens de outras cidades (o ViaCEP devolve, por ex., "Clementina" para buscas em Leme),
     * itens sem logradouro e duplicados (mesmo logradouro normalizado + CEP + bairro).
     */
    fun interpretarViaCep(json: String): List<EnderecoExterno> {
        val itens = JsonLeitor.lerArrayDeObjetos(json) ?: return emptyList()
        val vistos = HashSet<String>()
        return itens.mapNotNull { o ->
            if (!ehLeme(o["localidade"], o["uf"], o["ibge"])) return@mapNotNull null
            val log = Texto.limparNome(o["logradouro"] ?: "")
            if (log.isEmpty()) return@mapNotNull null
            val bairro = o["bairro"]?.let(Texto::limparNome)?.ifBlank { null }
            val cep = normalizarCep(o["cep"])
            val chave = "${normalizarLogradouro(log)}|$cep|${Texto.normalizarNome(bairro ?: "")}"
            if (!vistos.add(chave)) return@mapNotNull null
            EnderecoExterno(cep, log, tipoDe(log), bairro, LocalOperacional.MUNICIPIO, LocalOperacional.UF)
        }
    }
}

/** Leitor JSON mínimo (somente arrays de objetos com valores escalares), sem dependências. */
object JsonLeitor {
    fun lerArrayDeObjetos(json: String): List<Map<String, String?>>? {
        val p = Parser(json)
        return try {
            p.espacos()
            if (p.atual() != '[') return null
            val v = p.valor()
            @Suppress("UNCHECKED_CAST")
            (v as List<Any?>).filterIsInstance<Map<String, String?>>()
        } catch (_: Exception) {
            null
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun atual() = if (i < s.length) s[i] else '\u0000'
        fun espacos() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            espacos()
            return when (atual()) {
                '[' -> { i++; val l = mutableListOf<Any?>(); espacos(); if (atual() == ']') { i++; return l }
                    while (true) { l += valor(); espacos(); if (atual() == ',') { i++; continue }; require(atual() == ']'); i++; return l } }
                '{' -> { i++; val m = LinkedHashMap<String, String?>(); espacos(); if (atual() == '}') { i++; return m }
                    while (true) { espacos(); val k = texto(); espacos(); require(atual() == ':'); i++
                        val v = valor(); m[k] = v?.toString(); espacos()
                        if (atual() == ',') { i++; continue }; require(atual() == '}'); i++; return m } }
                '"' -> texto()
                else -> { val ini = i; while (i < s.length && s[i] !in ",]} \n\r\t") i++
                    s.substring(ini, i).let { if (it == "null") null else it } }
            }
        }
        fun texto(): String {
            require(atual() == '"'); i++
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
    }
}

/** Formulário de uma ligação trocada (um imóvel atendido). */
data class LigacaoForm(
    val chave: String,
    val numero: String = "",
    val lado: LadoRamal? = null,
    val comprimento: String = "",
    val enderecoDiferente: Boolean = false,
    val bairro: String = "",
    val rua: String = "",
    val complemento: String = "",
    val identificador: String = "",
    val observacoes: String = "",
    /** Migrado da v2: número do imóvel pode ficar pendente. */
    val legado: Boolean = false,
)

/** Ligação validada pronta para gravar. */
data class LigacaoValidada(
    val numero: String?,
    val lado: LadoRamal,
    val comprimentoCm: Int,
    val bairro: String?,
    val rua: String?,
    val complemento: String?,
    val identificador: String?,
    val observacoes: String?,
    val pendenteIdentificacao: Boolean,
    val legado: Boolean,
)

object Ligacoes {
    /**
     * Valida as ligações. Erros por índice e campo ("0:numero", "1:lado", "2:comprimento", "1:duplicada").
     * Número obrigatório para toda troca (inclusive quando só há uma); exceção apenas para item legado.
     * Mesmo número + rua + bairro no mesmo serviço só é aceito com identificadores distintos.
     */
    fun validar(
        lista: List<LigacaoForm>,
        ruaPrincipal: String,
        bairroPrincipal: String,
        limiteMetros: Int,
    ): Pair<List<LigacaoValidada>, Map<String, String>> {
        val erros = LinkedHashMap<String, String>()
        val ok = mutableListOf<LigacaoValidada>()
        lista.forEachIndexed { i, f ->
            val numero = Texto.limparNome(f.numero).uppercase().let { if (it == "SN" || it == "S/N") "S/N" else it }.take(20)
            if (numero.isEmpty() && !f.legado) erros["$i:numero"] = "Informe o número do imóvel atendido."
            if (f.lado == null) erros["$i:lado"] = "Escolha o lado."
            val cm = when (val r = Numeros.metrosParaCentimetros(f.comprimento, limiteMetros)) {
                EntradaNumerica.Vazia -> { erros["$i:comprimento"] = "Informe os metros utilizados."; null }
                is EntradaNumerica.Invalida -> { erros["$i:comprimento"] = r.mensagem; null }
                is EntradaNumerica.Valida -> r.valor
            }
            val rua = if (f.enderecoDiferente) Texto.limparNome(f.rua).take(150).ifBlank { null } else null
            val bairro = if (f.enderecoDiferente) Texto.limparNome(f.bairro).take(150).ifBlank { null } else null
            if (f.enderecoDiferente && rua == null && bairro == null) erros["$i:endereco"] = "Informe a rua e/ou o bairro diferentes."
            if (f.lado != null && cm != null && (numero.isNotEmpty() || f.legado)) {
                ok += LigacaoValidada(
                    numero.ifEmpty { null }, f.lado, cm, bairro, rua,
                    Texto.limparNome(f.complemento).take(60).ifBlank { null },
                    Texto.limparNome(f.identificador).take(40).ifBlank { null },
                    f.observacoes.trim().take(300).ifBlank { null },
                    pendenteIdentificacao = numero.isEmpty(), legado = f.legado,
                )
            }
        }
        // Duplicidade: mesmo imóvel (número + complemento + rua + bairro efetivos) sem identificadores distintos.
        val chaves = HashMap<String, MutableList<Int>>()
        lista.forEachIndexed { i, f ->
            if (f.numero.isBlank()) return@forEachIndexed
            val rua = if (f.enderecoDiferente && f.rua.isNotBlank()) f.rua else ruaPrincipal
            val bairro = if (f.enderecoDiferente && f.bairro.isNotBlank()) f.bairro else bairroPrincipal
            val k = listOf(f.numero, f.complemento).joinToString("|") { Texto.normalizarNome(it) } + "|" +
                Enderecos.normalizarLogradouro(rua) + "|" + Texto.normalizarNome(bairro)
            chaves.getOrPut(k) { mutableListOf() } += i
        }
        chaves.values.filter { it.size > 1 }.forEach { idx ->
            val ids = idx.map { Texto.normalizarNome(lista[it].identificador) }
            if (ids.any { it.isEmpty() } || ids.toSet().size != ids.size) {
                idx.drop(1).forEach { erros["$it:duplicada"] = "Mesmo imóvel de outra ligação. Se forem ligações distintas, preencha \"Identificador da ligação\" em cada uma." }
            }
        }
        return ok to erros
    }
}
