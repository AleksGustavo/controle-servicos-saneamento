package br.controleservicos.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.export.ArquivosAndroid
import br.controleservicos.app.core.export.FormatoExportacao
import br.controleservicos.app.core.export.GeradorExportacao
import br.controleservicos.app.core.export.PedidoExportacao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CompartilhamentoTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun arquivoNoCacheViaFileProviderComPermissaoDeLeitura() {
        val pedido = PedidoExportacao(FormatoExportacao.XLSX, emptyList(), emptyMap(), "t", "1", 5, System.currentTimeMillis(), ZoneId.systemDefault())
        val uri = ArquivosAndroid.gerarNoCache(ctx, "teste.xlsx") { GeradorExportacao.gerar(pedido, it) }
        assertEquals("content", uri.scheme)
        assertEquals("${ctx.packageName}.fileprovider", uri.authority)
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals(FormatoExportacao.XLSX.mime, ctx.contentResolver.getType(uri))

        val chooser = ArquivosAndroid.intentCompartilhar(uri, FormatoExportacao.XLSX.mime, "teste.xlsx")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val envio = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, envio.action)
        assertEquals(FormatoExportacao.XLSX.mime, envio.type)
        assertTrue(envio.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        assertEquals(uri, envio.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    }

    @Test fun csvMimeEGravacaoEmUri() {
        val pedido = PedidoExportacao(FormatoExportacao.CSV, emptyList(), emptyMap(), "t", "1", 5, System.currentTimeMillis(), ZoneId.systemDefault())
        val uri = ArquivosAndroid.gerarNoCache(ctx, "teste.csv") { GeradorExportacao.gerar(pedido, it) }
        // O FileProvider usa o MimeTypeMap do Android, que pode devolver o alias "text/comma-separated-values".
        assertTrue(ctx.contentResolver.getType(uri) in setOf("text/csv", "text/comma-separated-values"))
        val destino = File(ctx.cacheDir, "destino.csv")
        ArquivosAndroid.gravarEmUri(ctx, Uri.fromFile(destino)) { GeradorExportacao.gerar(pedido, it) }
        assertTrue(destino.readText(Charsets.UTF_8).contains("id_servico;tipo_servico"))
    }

    @Test fun limpezaRemoveSomenteArquivosAntigos() {
        val pasta = ArquivosAndroid.pastaExportacao(ctx)
        val velho = File(pasta, "velho.csv").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 48 * 3600_000L) }
        val novo = File(pasta, "novo.csv").apply { writeText("x") }
        ArquivosAndroid.limparAntigos(ctx)
        assertTrue(!velho.exists() && novo.exists())
    }
}
