package br.controleservicos.app.feature.comum

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import br.controleservicos.app.core.database.BairroEntity
import br.controleservicos.app.core.database.FuncionarioEntity
import br.controleservicos.app.core.database.MunicipioEntity
import br.controleservicos.app.core.database.TipoServicoEntity
import br.controleservicos.app.core.domain.Composicao
import br.controleservicos.app.core.domain.FiltroServicos
import br.controleservicos.app.core.domain.Marcacao
import br.controleservicos.app.core.domain.Ordenacao
import br.controleservicos.app.core.domain.Periodo
import br.controleservicos.app.core.model.StatusServico
import br.controleservicos.app.core.repository.Relogio
import kotlinx.coroutines.delay

/** Fábrica simples de ViewModel com construtor arbitrário. */
@Suppress("UNCHECKED_CAST")
fun <VM : ViewModel> fabrica(criar: () -> VM) = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = criar() as T
}

/**
 * Relógio de APRESENTAÇÃO (não grava nada e não é fonte de verdade): atualiza ~1 vez por segundo
 * somente enquanto a tela está visível (lifecycle STARTED). Com a tela apagada ou o app em segundo
 * plano o laço para — sem polling, wake lock ou serviço. Ao voltar, o primeiro valor é lido na hora
 * e a duração é recalculada a partir do marco de início persistido (nunca `contador++`).
 */
@Composable
fun rememberAgora(relogio: Relogio, ativo: Boolean = true): Marcacao {
    val ciclo = LocalLifecycleOwner.current.lifecycle
    val estado = produceState(initialValue = relogio.agora(), ativo, ciclo) {
        if (!ativo) return@produceState
        ciclo.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = relogio.agora()
                delay(1000 - (value.wallClockMs % 1000) + 5)
            }
        }
    }
    return estado.value
}

/** Chip com menu suspenso para escolher uma opção. */
@Composable
fun <T> ChipMenu(
    rotulo: String,
    selecionado: Boolean,
    opcoes: List<Pair<T, String>>,
    aoEscolher: (T) -> Unit,
) {
    var aberto by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selecionado,
            onClick = { aberto = true },
            label = { Text(rotulo, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = aberto, onDismissRequest = { aberto = false }) {
            opcoes.forEach { (valor, texto) ->
                DropdownMenuItem(text = { Text(texto) }, onClick = { aberto = false; aoEscolher(valor) })
            }
        }
    }
}

/** Barra de filtros compartilhada (Histórico/Estatísticas). */
@Composable
fun BarraFiltros(
    filtro: FiltroServicos,
    tipos: List<TipoServicoEntity>,
    municipios: List<MunicipioEntity>,
    bairros: List<BairroEntity>,
    aoMudar: (FiltroServicos) -> Unit,
    mostrarStatus: Boolean = true,
    mostrarOrdenacao: Boolean = true,
    mostrarExcluidos: Boolean = true,
    funcionarios: List<FuncionarioEntity> = emptyList(),
) {
    val nomeMunicipio = municipios.associate { it.id to (it.nome + (if (it.uf.isNotEmpty()) "/${it.uf}" else "")) }
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChipMenu(
            rotulo = tipos.firstOrNull { it.id == filtro.tipoServicoId }?.nome ?: "Tipo: todos",
            selecionado = filtro.tipoServicoId != null,
            opcoes = listOf<Pair<String?, String>>(null to "Todos os tipos") + tipos.map { it.id to it.nome },
            aoEscolher = { aoMudar(filtro.copy(tipoServicoId = it)) },
        )
        if (municipios.size > 1) {
            ChipMenu(
                rotulo = filtro.municipioId?.let { nomeMunicipio[it] } ?: "Município: todos",
                selecionado = filtro.municipioId != null,
                opcoes = listOf<Pair<String?, String>>(null to "Todos os municípios") + municipios.map { it.id to nomeMunicipio.getValue(it.id) },
                aoEscolher = { aoMudar(filtro.copy(municipioId = it, bairroId = null)) },
            )
        }
        val bairrosVisiveis = bairros.filter { filtro.municipioId == null || it.municipioId == filtro.municipioId }
        ChipMenu(
            rotulo = bairros.firstOrNull { it.id == filtro.bairroId }?.nomeOriginal ?: "Bairro: todos",
            selecionado = filtro.bairroId != null,
            opcoes = listOf<Pair<String?, String>>(null to "Todos os bairros") + bairrosVisiveis.map {
                it.id to if (municipios.size > 1) "${it.nomeOriginal} (${nomeMunicipio[it.municipioId] ?: ""})" else it.nomeOriginal
            },
            aoEscolher = { id -> aoMudar(filtro.copy(bairroId = id, municipioId = bairros.firstOrNull { it.id == id }?.municipioId ?: filtro.municipioId)) },
        )
        if (funcionarios.isNotEmpty()) {
            ChipMenu(
                rotulo = funcionarios.firstOrNull { it.id == filtro.funcionarioId }?.let { "Participante: ${it.nomeExibicao}" } ?: "Participante: todos",
                selecionado = filtro.funcionarioId != null,
                opcoes = listOf<Pair<String?, String>>(null to "Todos os participantes") +
                    funcionarios.sortedBy { it.nomeNormalizado }.map { f ->
                        f.id to (f.nomeExibicao + (f.apelidoOuCodigo?.let { " ($it)" } ?: "") + if (!f.ativo) " — inativo" else "")
                    },
                aoEscolher = { aoMudar(filtro.copy(funcionarioId = it)) },
            )
        }
        ChipMenu(
            rotulo = filtro.periodo.rotulo,
            selecionado = filtro.periodo != Periodo.TODOS,
            opcoes = Periodo.entries.map { it to it.rotulo },
            aoEscolher = { aoMudar(filtro.copy(periodo = it)) },
        )
        if (mostrarStatus) {
            ChipMenu(
                rotulo = filtro.status?.rotulo ?: "Estado: todos",
                selecionado = filtro.status != null,
                opcoes = listOf<Pair<StatusServico?, String>>(null to "Todos os estados") + StatusServico.entries.map { it to it.rotulo },
                aoEscolher = { aoMudar(filtro.copy(status = it)) },
            )
        }
        if (mostrarOrdenacao) {
            ChipMenu(
                rotulo = filtro.ordenacao.rotulo,
                selecionado = false,
                opcoes = Ordenacao.entries.map { it to it.rotulo },
                aoEscolher = { aoMudar(filtro.copy(ordenacao = it)) },
            )
        }
        if (mostrarExcluidos) {
            FilterChip(
                selected = filtro.incluirExcluidos,
                onClick = { aoMudar(filtro.copy(incluirExcluidos = !filtro.incluirExcluidos)) },
                label = { Text("Mostrar excluídos") },
            )
        }
    }
}

/** Descrição legível dos filtros (para Exportar e metadados). */
fun descreverFiltro(
    f: FiltroServicos,
    tipos: List<TipoServicoEntity>,
    municipios: List<MunicipioEntity>,
    bairros: List<BairroEntity>,
    funcionarios: List<FuncionarioEntity> = emptyList(),
): String {
    if (!f.ativo) return "Sem filtros (todos os registros não excluídos)"
    val partes = mutableListOf<String>()
    f.tipoServicoId?.let { id -> partes += "Tipo: " + (tipos.firstOrNull { it.id == id }?.nome ?: id) }
    f.municipioId?.let { id -> partes += "Município: " + (municipios.firstOrNull { it.id == id }?.nome ?: id) }
    f.bairroId?.let { id -> partes += "Bairro: " + (bairros.firstOrNull { it.id == id }?.nomeOriginal ?: id) }
    if (f.periodo != Periodo.TODOS) partes += "Período: ${f.periodo.rotulo}"
    f.status?.let { partes += "Estado: ${it.rotulo}" }
    f.equipe?.takeIf { it.isNotBlank() }?.let { partes += "Equipe: $it" }
    f.chaveComposicao?.let { partes += "Equipe: composição exata selecionada (${Composicao.assinatura(it)})" }
    f.funcionarioId?.let { id -> partes += "Participante: " + (funcionarios.firstOrNull { it.id == id }?.nomeExibicao ?: "selecionado") }
    if (f.texto.isNotBlank()) partes += "Busca: \"${f.texto}\""
    if (f.incluirExcluidos) partes += "Incluindo excluídos"
    return partes.joinToString("; ")
}

@Composable
fun EfeitoUnico(chave: Any?, bloco: suspend () -> Unit) {
    LaunchedEffect(chave) { bloco() }
}
