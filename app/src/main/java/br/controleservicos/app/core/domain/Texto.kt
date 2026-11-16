package br.controleservicos.app.core.domain

import java.text.Normalizer
import java.util.Locale

object Texto {
    private val ESPACOS = Regex("\\s+")
    private val MARCAS = Regex("\\p{Mn}+")

    /** Remove espaços extras preservando acentos e caixa (nome para exibição). */
    fun limparNome(nome: String): String = nome.trim().replace(ESPACOS, " ")

    /**
     * Forma normalizada para comparação de bairros/municípios: sem acentos, minúsculas,
     * espaços colapsados. "São  João " e "sao joao" → "sao joao".
     * Não remove palavras, números ou pontuação, para não fundir nomes distintos por engano.
     */
    fun normalizarNome(nome: String): String {
        val semAcento = Normalizer.normalize(limparNome(nome), Normalizer.Form.NFD).replace(MARCAS, "")
        return semAcento.lowercase(Locale.ROOT)
    }

    /**
     * Busca sem distinção de caixa/acentos: cada palavra da consulta deve aparecer em algum campo.
     * "joao sil" encontra "João da Silva". Consulta vazia corresponde a tudo.
     */
    fun correspondeBusca(consulta: String, vararg campos: String?): Boolean {
        val termos = normalizarNome(consulta).split(' ').filter { it.isNotEmpty() }
        if (termos.isEmpty()) return true
        val alvo = campos.filterNotNull().joinToString(" ") { normalizarNome(it) }
        return termos.all { alvo.contains(it) }
    }

    /** Nome seguro para arquivo: ASCII, minúsculas, `_`. */
    fun paraNomeArquivo(texto: String): String =
        normalizarNome(texto).replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
}

/** Resultado de conversão de entrada numérica do usuário. */
sealed interface EntradaNumerica<out T> {
    data object Vazia : EntradaNumerica<Nothing>
    data class Valida<T>(val valor: T) : EntradaNumerica<T>
    data class Invalida(val mensagem: String) : EntradaNumerica<Nothing>
}

object Numeros {
    /**
     * Converte decimal digitado em pt-BR ("1,25", "1.25", "0,8"). Vazio → [EntradaNumerica.Vazia]
     * (não informado; nunca zero). Exige valor > 0 quando [positivo].
     */
    fun decimal(texto: String, positivo: Boolean = true, maximo: Double = Double.MAX_VALUE): EntradaNumerica<Double> {
        val t = texto.trim()
        if (t.isEmpty()) return EntradaNumerica.Vazia
        if (!Regex("^-?\\d{1,7}([.,]\\d{1,4})?$").matches(t)) return EntradaNumerica.Invalida("Número inválido. Use vírgula para decimais (ex.: 1,20).")
        val v = t.replace(',', '.').toDoubleOrNull() ?: return EntradaNumerica.Invalida("Número inválido.")
        if (positivo && v <= 0.0) return EntradaNumerica.Invalida("Informe um valor maior que zero.")
        if (v > maximo) return EntradaNumerica.Invalida("Valor acima do limite ($maximo).")
        return EntradaNumerica.Valida(v)
    }

    /** Inteiro; [minimo] inclusivo. Vazio → não informado. */
    fun inteiro(texto: String, minimo: Int = 0, maximo: Int = 1_000_000): EntradaNumerica<Int> {
        val t = texto.trim()
        if (t.isEmpty()) return EntradaNumerica.Vazia
        val v = t.toIntOrNull() ?: return EntradaNumerica.Invalida("Informe um número inteiro.")
        if (v < minimo) return EntradaNumerica.Invalida("O valor mínimo é $minimo.")
        if (v > maximo) return EntradaNumerica.Invalida("O valor máximo é $maximo.")
        return EntradaNumerica.Valida(v)
    }

    /** Formata decimal com vírgula para exibição (pt-BR), sem zeros desnecessários. */
    fun formatarDecimalBr(v: Double?): String {
        if (v == null) return ""
        val s = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
        return s.replace('.', ',')
    }

    /**
     * Comprimento em metros digitado em pt-BR ("3", "3,5", "3.5", "12,25") → centímetros inteiros,
     * sem ponto flutuante (BigDecimal). Aceita no máximo 2 casas decimais: "1.234" é rejeitado em vez
     * de virar 1234 m ou 1,234 m silenciosamente. Exige > 0 e <= [limiteMetros].
     */
    fun metrosParaCentimetros(texto: String, limiteMetros: Int): EntradaNumerica<Int> {
        val t = texto.trim().replace(" ", "")
        if (t.isEmpty()) return EntradaNumerica.Vazia
        if (t.startsWith("-")) return EntradaNumerica.Invalida("Informe um comprimento maior que zero.")
        if (!Regex("^\\d{1,5}([.,]\\d{1,2})?$").matches(t)) {
            return EntradaNumerica.Invalida("Comprimento inválido. Use até 2 casas decimais (ex.: 3,5).")
        }
        val cm = try {
            java.math.BigDecimal(t.replace(',', '.')).movePointRight(2).intValueExact()
        } catch (_: ArithmeticException) {
            return EntradaNumerica.Invalida("Comprimento inválido.")
        }
        if (cm <= 0) return EntradaNumerica.Invalida("Informe um comprimento maior que zero.")
        if (cm > limiteMetros * 100) return EntradaNumerica.Invalida("Acima do limite de $limiteMetros m. Confira a digitação (limite ajustável em Configurações).")
        return EntradaNumerica.Valida(cm)
    }

    /** Centímetros → metros para exibição pt-BR ("3,5"), sem zeros desnecessários. */
    fun formatarMetrosBr(cm: Int?): String =
        cm?.let { java.math.BigDecimal.valueOf(it.toLong(), 2).stripTrailingZeros().toPlainString().replace('.', ',') } ?: ""

    /** Centímetros → metros como número exato para planilhas (3,5 m → 3.5). */
    fun centimetrosEmMetros(cm: Int?): java.math.BigDecimal? =
        cm?.let { java.math.BigDecimal.valueOf(it.toLong(), 2).stripTrailingZeros() }

    /** Formata decimal invariável com ponto (exportação). */
    fun formatarDecimalInvariavel(v: Double?): String? =
        v?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }
}

/** Validação das condições observadas. */
object Condicoes {
    /**
     * Aplica a exclusividade de "Nenhuma": marcar Nenhuma limpa as demais;
     * marcar outra condição remove Nenhuma.
     */
    fun alternar(selecionadas: Set<String>, codigo: String, codigoExclusivo: String): Set<String> {
        return if (codigo in selecionadas) {
            selecionadas - codigo
        } else if (codigo == codigoExclusivo) {
            setOf(codigoExclusivo)
        } else {
            (selecionadas - codigoExclusivo) + codigo
        }
    }

    fun validar(
        selecionadas: Set<String>,
        codigoExclusivo: String,
        codigoOutras: String,
        descricaoOutras: String,
        codigoMaisDeUmaLigacao: String,
        quantidadeLigacoes: Int?,
    ): List<String> {
        val erros = mutableListOf<String>()
        if (codigoExclusivo in selecionadas && selecionadas.size > 1) {
            erros += "\"Nenhuma das condições\" não pode ser combinada com outras."
        }
        if (codigoOutras in selecionadas && descricaoOutras.isBlank()) {
            erros += "Descreva a condição em \"Outras\"."
        }
        if (codigoMaisDeUmaLigacao in selecionadas && quantidadeLigacoes != null && quantidadeLigacoes < 2) {
            erros += "Com \"Mais de uma ligação\" marcada, a quantidade deve ser 2 ou mais."
        }
        return erros
    }
}

/** JSON mínimo (somente escrita) para a trilha de auditoria, sem dependências de Android. */
object JsonSimples {
    fun objeto(campos: Map<String, Any?>): String = campos.entries.joinToString(",", "{", "}") { (k, v) ->
        "${string(k)}:${valor(v)}"
    }

    private fun valor(v: Any?): String = when (v) {
        null -> "null"
        is Number, is Boolean -> v.toString()
        is Collection<*> -> v.joinToString(",", "[", "]") { valor(it) }
        else -> string(v.toString())
    }

    fun string(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
