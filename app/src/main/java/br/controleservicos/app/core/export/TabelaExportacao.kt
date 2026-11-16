package br.controleservicos.app.core.export

import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.EstatisticaEquipe
import br.controleservicos.app.core.domain.Estatisticas
import br.controleservicos.app.core.domain.EstatisticasEquipe
import br.controleservicos.app.core.domain.Numeros
import br.controleservicos.app.core.domain.Tempo
import br.controleservicos.app.core.model.CondicaoRegistro
import br.controleservicos.app.core.model.LigacaoTrocada
import br.controleservicos.app.core.model.LinhaServico
import br.controleservicos.app.core.model.ParticipanteServico
import java.time.ZoneId

/** Célula tipada: texto, número ou vazio (null ≠ 0). */
sealed interface Celula {
    data class Txt(val valor: String) : Celula
    data class Num(val valor: Number) : Celula
    data object Vazia : Celula
}

fun txt(v: String?): Celula = if (v == null) Celula.Vazia else Celula.Txt(v)
fun num(v: Number?): Celula = if (v == null) Celula.Vazia else Celula.Num(v)

data class Tabela(val cabecalho: List<String>, val linhas: List<List<Celula>>)

/**
 * Contrato de colunas da exportação (versão de esquema em RegrasNegocio.VERSAO_ESQUEMA_EXPORTACAO).
 * A ordem é estável; novas colunas só podem ser acrescentadas ao final com nova versão.
 */
object ContratoExportacao {
    val COLUNAS = listOf(
        "id_servico", "tipo_servico", "status", "municipio", "uf", "bairro", "logradouro", "numero", "complemento",
        "identificador_equipe", "inicio_local", "inicio_utc_iso", "rede_encontrada_local", "rede_encontrada_utc_iso",
        "fim_local", "fim_utc_iso", "duracao_total_segundos", "duracao_total_hhmmss", "tempo_ate_rede_segundos",
        "tempo_ate_rede_hhmmss", "tempo_apos_rede_segundos", "diametro_mm", "material_rede", "profundidade_m",
        "quantidade_ligacoes", "condicoes_observadas", "observacoes", "criado_em_utc", "atualizado_em_utc",
        // Acrescentadas ao final (esquema v1):
        "situacao_rede", "requer_revisao", "motivo_revisao",
        // Acrescentadas ao final (esquema v2): equipe, ramal trocado e base de tempo.
        "participantes", "funcoes_por_participante", "n_participantes", "chave_composicao", "perfil_funcoes",
        "ramal_agua_trocado", "lado_ramal_trocado", "comprimento_ramal_m", "base_tempo",
        // Acrescentadas ao final (esquema v3): ligações por imóvel, minutos e CEP.
        "quantidade_ligacoes_trocadas", "comprimento_total_ligacoes_m", "duracao_total_minutos", "tempo_ate_rede_minutos", "cep",
    )

    /** Separador interno de múltiplas condições numa célula. */
    const val SEP_CONDICOES = " | "

    /** Escapa `\` e `|` dentro de cada item para que ` | ` seja separador inequívoco. */
    fun escaparItemCondicao(s: String) = s.replace("\\", "\\\\").replace("|", "\\|")

    fun condicoesTexto(lista: List<CondicaoRegistro>?): String? {
        if (lista.isNullOrEmpty()) return null
        return lista.joinToString(SEP_CONDICOES) { c ->
            escaparItemCondicao(if (c.descricao.isNullOrBlank()) c.nome else "${c.nome}: ${c.descricao}")
        }
    }

    /** Nome legível (com apelido) ou pseudônimo estável quando nomes não devem sair do aparelho. */
    fun referencia(p: ParticipanteServico, incluirNomes: Boolean): String =
        if (incluirNomes) p.nomeAtual + (p.apelidoOuCodigo?.let { " ($it)" } ?: "") else Composicao.pseudonimo(p.funcionarioId)

    fun linha(
        s: LinhaServico,
        condicoes: List<CondicaoRegistro>?,
        zona: ZoneId,
        participantes: List<ParticipanteServico>? = null,
        incluirNomes: Boolean = false,
        ligacoes: List<LigacaoTrocada>? = null,
    ): List<Celula> {
        val ligs = ligacoes.orEmpty()
        // Colunas v2 de ramal único: preenchidas só quando há exatamente uma ligação (ou registro legado sem tabela).
        val unica = ligs.singleOrNull()
        val ladoUnico = if (!s.ramalAguaTrocado) null else unica?.lado ?: s.ladoRamalTrocado.takeIf { ligs.isEmpty() }
        val cmUnico = if (!s.ramalAguaTrocado) null else unica?.comprimentoCm ?: s.comprimentoRamalCm.takeIf { ligs.isEmpty() }
        val part = participantes.orEmpty()
        val ordenados = part.sortedBy { referencia(it, incluirNomes) }
        val total = Tempo.medirDuracaoTotal(s.inicioUtcEpochMs, s.fimUtcEpochMs)
        val ateRede = Tempo.medirTempoAteRede(s.inicioUtcEpochMs, s.redeEncontradaUtcEpochMs, s.fimUtcEpochMs)
        val aposRede = Tempo.medirTempoAposRede(s.inicioUtcEpochMs, s.redeEncontradaUtcEpochMs, s.fimUtcEpochMs)
        return listOf(
            txt(s.id), txt(s.tipoNome), txt(s.status.name), txt(s.municipioNome), txt(s.municipioUf), txt(s.bairroNome),
            txt(s.logradouro), txt(s.numero), txt(s.complemento), txt(s.equipeIdentificador),
            txt(Tempo.localParaExportacao(s.inicioUtcEpochMs, zona)), txt(Tempo.utcIso(s.inicioUtcEpochMs)),
            txt(Tempo.localParaExportacao(s.redeEncontradaUtcEpochMs, zona)), txt(Tempo.utcIso(s.redeEncontradaUtcEpochMs)),
            txt(Tempo.localParaExportacao(s.fimUtcEpochMs, zona)), txt(Tempo.utcIso(s.fimUtcEpochMs)),
            num(Tempo.emSegundos(total)), txt(total?.let(Tempo::formatarDuracao)),
            num(Tempo.emSegundos(ateRede)), txt(ateRede?.let(Tempo::formatarDuracao)),
            num(Tempo.emSegundos(aposRede)),
            num(s.diametroMm), txt(s.materialNome), num(s.profundidadeMetros), num(s.quantidadeLigacoes),
            txt(condicoesTexto(condicoes)), txt(s.observacoes),
            txt(Tempo.utcIso(s.createdAt)), txt(Tempo.utcIso(s.updatedAt)),
            txt(s.situacaoRede?.name), txt(if (s.requerRevisao) "SIM" else "NAO"), txt(s.motivoRevisao),
            txt(ordenados.takeIf { it.isNotEmpty() }?.joinToString(SEP_CONDICOES) { escaparItemCondicao(referencia(it, incluirNomes)) }),
            txt(ordenados.takeIf { it.isNotEmpty() }?.joinToString(SEP_CONDICOES) { escaparItemCondicao("${referencia(it, incluirNomes)}: ${it.funcaoTexto}") }),
            num(part.size),
            txt(Composicao.assinatura(Composicao.chaveDe(part))),
            txt(Composicao.perfilFuncoes(part)),
            txt(if (s.ramalAguaTrocado) "SIM" else "NAO"),
            // Não trocado ⇒ células vazias (nunca zero).
            txt(ladoUnico?.name),
            num(Numeros.centimetrosEmMetros(cmUnico)),
            txt(s.baseTempo?.name),
            num(if (s.ramalAguaTrocado) ligs.size else 0),
            num(if (s.ramalAguaTrocado && ligs.isNotEmpty()) Numeros.centimetrosEmMetros(ligs.sumOf { it.comprimentoCm }) else null),
            num(total?.let { java.math.BigDecimal(it).divide(java.math.BigDecimal(60_000), 2, java.math.RoundingMode.HALF_UP) }),
            num(ateRede?.let { java.math.BigDecimal(it).divide(java.math.BigDecimal(60_000), 2, java.math.RoundingMode.HALF_UP) }),
            txt(s.cep),
        )
    }

    fun registros(
        lista: List<LinhaServico>,
        condicoes: Map<String, List<CondicaoRegistro>>,
        zona: ZoneId,
        participantes: Map<String, List<ParticipanteServico>> = emptyMap(),
        incluirNomes: Boolean = false,
        ligacoes: Map<String, List<LigacaoTrocada>> = emptyMap(),
    ) = Tabela(COLUNAS, lista.map { linha(it, condicoes[it.id], zona, participantes[it.id], incluirNomes, ligacoes[it.id]) })

    val COLUNAS_LIGACOES = listOf(
        "id_servico", "ordem", "numero_imovel", "pendente_identificacao", "logradouro", "bairro", "endereco_proprio",
        "complemento", "identificador_ligacao", "lado", "comprimento_m", "observacoes", "legado", "tipo_servico", "inicio_local",
    )

    /** Uma linha por ligação trocada (imóvel atendido), relacionável pelo id_servico. */
    fun ligacoes(lista: List<LinhaServico>, ligacoes: Map<String, List<LigacaoTrocada>>, zona: ZoneId): Tabela = Tabela(
        COLUNAS_LIGACOES,
        lista.filter { it.ramalAguaTrocado }.flatMap { s ->
            ligacoes[s.id].orEmpty().sortedBy { it.ordem }.map { l ->
                val proprio = l.logradouroTexto != null || l.bairroTexto != null
                listOf(
                    txt(s.id), num(l.ordem), txt(l.numeroTexto), txt(if (l.pendenteIdentificacao) "SIM" else "NAO"),
                    txt(l.logradouroTexto ?: s.logradouro), txt(l.bairroTexto ?: s.bairroNome), txt(if (proprio) "SIM" else "NAO"),
                    txt(l.complemento), txt(l.identificadorLigacao), txt(l.lado.name), num(Numeros.centimetrosEmMetros(l.comprimentoCm)),
                    txt(l.observacoes), txt(if (l.legado) "SIM" else "NAO"), txt(s.tipoNome), txt(Tempo.localParaExportacao(s.inicioUtcEpochMs, zona)),
                )
            }
        },
    )

    val COLUNAS_INDICADORES = listOf(
        "bairro", "tipo_servico", "n", "media_segundos", "media_minutos", "mediana_segundos", "minimo_segundos", "maximo_segundos",
        "p75_segundos", "p90_segundos", "n_ate_rede", "media_ate_rede_segundos", "mediana_ate_rede_segundos",
        "servicos_com_troca", "ligacoes_trocadas", "amostra",
    )

    /**
     * Indicadores por bairro (agrupamento principal) e tipo de serviço, calculados a partir das
     * durações elegíveis de cada serviço (nunca média de médias). `n` conta serviços; ligações
     * trocadas são uma coluna à parte e nunca entram no `n`. Ordenado por nome de bairro.
     */
    fun indicadoresPorBairro(lista: List<LinhaServico>, ligacoes: Map<String, List<LigacaoTrocada>>, limiar: Int): Tabela {
        val validos = lista.filter(Estatisticas::ehValido)
        val seg: (Double?) -> Celula = { v -> num(v?.let { Math.round(it / 10.0) / 100.0 }) }
        val linhas = validos.groupBy { it.bairroId to it.tipoServicoId }.values.map { itens ->
            val f = itens.first()
            val t = Estatisticas.resumir(itens.mapNotNull(Estatisticas::duracaoValida))
            val r = Estatisticas.resumir(itens.mapNotNull(Estatisticas::ateRedeValida))
            listOf(
                txt(f.bairroNome), txt(f.tipoNome), num(t.n), seg(t.mediaMs), num(t.mediaMs?.let { Math.round(it / 600.0) / 100.0 }), seg(t.medianaMs),
                num(Tempo.emSegundos(t.minimoMs)), num(Tempo.emSegundos(t.maximoMs)), seg(t.p75Ms), seg(t.p90Ms),
                num(r.n), seg(r.mediaMs), seg(r.medianaMs),
                num(itens.count { it.ramalAguaTrocado }), num(itens.sumOf { ligacoes[it.id]?.size ?: 0 }),
                txt(Estatisticas.qualidade(t.n, limiar).name),
            ) to (br.controleservicos.app.core.domain.Texto.normalizarNome(f.bairroNome) + "|" + f.tipoNome)
        }.sortedBy { it.second }.map { it.first }
        return Tabela(COLUNAS_INDICADORES, linhas)
    }

    val COLUNAS_PARTICIPACOES = listOf(
        "id_servico", "referencia_funcionario", "nome_no_servico", "nome_atual", "funcao_no_servico", "funcao_descricao", "nota",
        "chave_composicao", "tipo_servico", "bairro", "inicio_local",
    )

    /**
     * Uma linha por participante (estrutura relacional). Sem nomes: referência = pseudônimo
     * e colunas de nome vazias. Use a aba Registros (uma linha por serviço) para médias.
     */
    fun participacoes(
        lista: List<LinhaServico>,
        participantes: Map<String, List<ParticipanteServico>>,
        zona: ZoneId,
        incluirNomes: Boolean,
    ): Tabela = Tabela(
        COLUNAS_PARTICIPACOES,
        lista.flatMap { s ->
            val part = participantes[s.id].orEmpty()
            val chave = Composicao.assinatura(Composicao.chaveDe(part))
            part.sortedBy { referencia(it, incluirNomes) }.map { p ->
                listOf(
                    txt(s.id),
                    txt(if (incluirNomes) p.funcionarioId else Composicao.pseudonimo(p.funcionarioId)),
                    txt(if (incluirNomes) p.nomeSnapshot else null),
                    txt(if (incluirNomes) p.nomeAtual else null),
                    txt(p.funcaoNoServico.name), txt(p.funcaoDescricao), txt(if (incluirNomes) p.nota else null),
                    txt(chave), txt(s.tipoNome), txt(s.bairroNome), txt(Tempo.localParaExportacao(s.inicioUtcEpochMs, zona)),
                )
            }
        },
    )

    val COLUNAS_RESUMO_EQUIPES = listOf(
        "visao", "tipo_servico", "bairro", "grupo", "chave_composicao", "n", "media_segundos", "mediana_segundos",
        "minimo_segundos", "maximo_segundos", "n_ate_rede", "media_ate_rede_segundos", "mediana_ate_rede_segundos", "amostra",
    )

    /**
     * Resumo por composição exata, por bairro + composição e por perfil de funções.
     * Cada serviço conta uma vez por grupo. Sem ranking: ordem alfabética.
     */
    fun resumoEquipes(
        lista: List<LinhaServico>,
        participantes: Map<String, List<ParticipanteServico>>,
        limiar: Int,
        incluirNomes: Boolean,
    ): Tabela {
        val seg: (Double?) -> Celula = { v -> num(v?.let { Math.round(it / 10.0) / 100.0 }) }
        // Rótulos sem nomes: pseudônimos dos integrantes.
        val anon = if (incluirNomes) participantes else participantes.mapValues { (_, l) ->
            l.map { it.copy(nomeAtual = Composicao.pseudonimo(it.funcionarioId), apelidoOuCodigo = null) }
        }
        fun linhas(visao: String, grupos: List<EstatisticaEquipe>, chaveExportada: (EstatisticaEquipe) -> String?) = grupos.map { g ->
            listOf(
                txt(visao), txt(g.tipoNome), txt(g.bairroNome), txt(g.rotulo), txt(chaveExportada(g)), num(g.total.n),
                seg(g.total.mediaMs), seg(g.total.medianaMs), num(Tempo.emSegundos(g.total.minimoMs)), num(Tempo.emSegundos(g.total.maximoMs)),
                num(g.ateRede.n), seg(g.ateRede.mediaMs), seg(g.ateRede.medianaMs), txt(g.qualidade.name),
            )
        }
        val chaveComp: (EstatisticaEquipe) -> String? = { g -> Composicao.assinatura(g.chave.substringAfterLast('#')) }
        return Tabela(
            COLUNAS_RESUMO_EQUIPES,
            linhas("composicao_exata", EstatisticasEquipe.porComposicao(lista, anon, limiar), chaveComp) +
                linhas("bairro_e_composicao", EstatisticasEquipe.porBairroEComposicao(lista, anon, limiar), chaveComp) +
                linhas("perfil_de_funcoes", EstatisticasEquipe.porPerfilFuncoes(lista, anon, limiar)) { null },
        )
    }

    val COLUNAS_RESUMO = listOf(
        "municipio", "uf", "bairro", "tipo_servico", "n", "media_segundos", "media_hhmmss", "mediana_segundos",
        "minimo_segundos", "maximo_segundos", "n_ate_rede", "media_ate_rede_segundos", "media_ate_rede_hhmmss", "amostra",
    )

    /** Agregações por município, bairro e tipo (somente registros válidos para métricas). */
    fun resumo(lista: List<LinhaServico>, limiar: Int): Tabela {
        val validos = lista.filter(Estatisticas::ehValido)
        val grupos = validos.groupBy { Triple(it.municipioId, it.bairroId, it.tipoServicoId) }
        val linhas = grupos.values.map { itens ->
            val f = itens.first()
            val t = Estatisticas.resumir(itens.mapNotNull(Estatisticas::duracaoValida))
            val r = Estatisticas.resumir(itens.mapNotNull(Estatisticas::ateRedeValida))
            val seg: (Double?) -> Celula = { v -> num(v?.let { Math.round(it / 10.0) / 100.0 }) }
            listOf(
                txt(f.municipioNome), txt(f.municipioUf), txt(f.bairroNome), txt(f.tipoNome), num(t.n),
                seg(t.mediaMs), txt(t.mediaMs?.let { Tempo.formatarDuracao(it.toLong()) }), seg(t.medianaMs),
                num(Tempo.emSegundos(t.minimoMs)), num(Tempo.emSegundos(t.maximoMs)), num(r.n),
                seg(r.mediaMs), txt(r.mediaMs?.let { Tempo.formatarDuracao(it.toLong()) }),
                txt(Estatisticas.qualidade(t.n, limiar).name),
            ) to "${f.municipioNome}|${f.bairroNome}|${f.tipoNome}"
        }.sortedBy { it.second }.map { it.first }
        return Tabela(COLUNAS_RESUMO, linhas)
    }

    fun formatarNumero(v: Number): String = when (v) {
        is java.math.BigDecimal -> v.stripTrailingZeros().toPlainString()
        is Double -> Numeros.formatarDecimalInvariavel(v) ?: ""
        is Float -> Numeros.formatarDecimalInvariavel(v.toDouble()) ?: ""
        else -> v.toString()
    }
}
