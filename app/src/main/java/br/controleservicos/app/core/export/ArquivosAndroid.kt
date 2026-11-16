package br.controleservicos.app.core.export

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Integração com o Android: cache controlado, FileProvider e chooser nativo. */
object ArquivosAndroid {
    private const val PASTA = "exports"
    private const val VALIDADE_MS = 24 * 60 * 60 * 1000L

    fun pastaExportacao(context: Context): File = File(context.cacheDir, PASTA).apply { mkdirs() }

    /**
     * Remove arquivos temporários de exportação com mais de 24 h. Não apaga os recentes para
     * não revogar acesso de um app receptor que ainda esteja lendo.
     */
    fun limparAntigos(context: Context, agoraMs: Long = System.currentTimeMillis()) {
        pastaExportacao(context).listFiles()?.forEach { f ->
            if (agoraMs - f.lastModified() > VALIDADE_MS) f.delete()
        }
    }

    /** Gera o arquivo no cache e devolve a URI content:// do FileProvider. */
    @Throws(IOException::class)
    fun gerarNoCache(context: Context, nome: String, escrever: (java.io.OutputStream) -> Unit): Uri {
        val arquivo = File(pastaExportacao(context), nome)
        val tmp = File(arquivo.parentFile, "$nome.tmp")
        tmp.outputStream().use { escrever(it) }
        if (arquivo.exists()) arquivo.delete()
        if (!tmp.renameTo(arquivo)) throw IOException("Falha ao finalizar o arquivo.")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", arquivo)
    }

    fun intentCompartilhar(uri: Uri, mime: String, assunto: String): Intent {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, assunto)
            clipData = ClipData.newRawUri(assunto, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(envio, "Compartilhar exportação").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Abre o chooser. Retorna false se nenhum app puder receber o arquivo. */
    fun compartilhar(context: Context, uri: Uri, mime: String, assunto: String): Boolean = try {
        val intent = intentCompartilhar(uri, mime, assunto)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Grava no destino escolhido pelo usuário via SAF. */
    @Throws(IOException::class)
    fun gravarEmUri(context: Context, destino: Uri, escrever: (java.io.OutputStream) -> Unit) {
        val out = context.contentResolver.openOutputStream(destino, "wt")
            ?: throw IOException("Destino indisponível.")
        out.use { escrever(it) }
    }
}
