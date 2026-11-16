package br.controleservicos.app.core.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.export.ArquivosAndroid
import br.controleservicos.app.core.repository.OperacaoInvalidaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Arquivos gerados localmente no cache privado para um serviço. */
data class ArquivosRelatorio(val arquivos: List<File>, val uris: List<Uri>, val formato: FormatoRelatorio, val textos: List<String>)

/**
 * Gera o relatório individual de um serviço já salvo (pelo ID persistido) em PDF, PNG ou DOCX,
 * no diretório privado de cache compartilhável por FileProvider. Não altera o registro:
 * falhas aqui nunca desfazem o serviço salvo.
 */
object CompartilharServico {

    suspend fun gerar(context: Context, container: AppContainer, servicoId: String, formato: FormatoRelatorio, ocultarNomes: Boolean): ArquivosRelatorio =
        withContext(Dispatchers.IO) {
            val repo = container.repositorio
            val s = container.database.servicoDao().linha(servicoId)
                ?: throw OperacaoInvalidaException("Serviço não encontrado.")
            if (s.fimUtcEpochMs == null) throw OperacaoInvalidaException("Finalize o serviço antes de compartilhar.")
            val r = MontadorRelatorio.montar(
                s, repo.condicoesDe(servicoId), repo.participantesDe(servicoId), repo.ligacoesDe(servicoId),
                ocultarNomes, container.relogio.agora().wallClockMs,
            )
            val pasta = ArquivosAndroid.pastaExportacao(context)
            val autoridade = "${context.packageName}.fileprovider"
            when (formato) {
                FormatoRelatorio.PDF -> {
                    val f = File(pasta, MontadorRelatorio.nomeArquivo(s, formato))
                    val (_, textos) = f.outputStream().use { GeradorRelatorioAndroid.pdf(r, it) }
                    ArquivosRelatorio(listOf(f), listOf(FileProvider.getUriForFile(context, autoridade, f)), formato, textos)
                }
                FormatoRelatorio.PNG -> {
                    val (fs, textos) = GeradorRelatorioAndroid.png(r, pasta) { p -> MontadorRelatorio.nomeArquivo(s, formato, p) }
                    ArquivosRelatorio(fs, fs.map { FileProvider.getUriForFile(context, autoridade, it) }, formato, textos)
                }
                FormatoRelatorio.DOCX -> {
                    val f = File(pasta, MontadorRelatorio.nomeArquivo(s, formato))
                    f.outputStream().use { GeradorDocx.escrever(r, it) }
                    ArquivosRelatorio(listOf(f), listOf(FileProvider.getUriForFile(context, autoridade, f)), formato, r.textos())
                }
            }
        }

    /** Intent do seletor nativo (WhatsApp, Gmail…): `content://`, MIME correto, leitura temporária. */
    fun intentCompartilhar(a: ArquivosRelatorio, assunto: String): Intent {
        val envio = if (a.uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, a.uris.first()); clipData = ClipData.newRawUri(assunto, a.uris.first()) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(a.uris))
                clipData = ClipData.newRawUri(assunto, a.uris.first()).also { c -> a.uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            }
        }
        envio.type = a.formato.mime
        envio.putExtra(Intent.EXTRA_SUBJECT, assunto)
        envio.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(envio, "Compartilhar serviço").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
