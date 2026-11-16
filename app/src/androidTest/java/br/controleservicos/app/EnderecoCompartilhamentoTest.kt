package br.controleservicos.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.controleservicos.app.core.AppContainer
import br.controleservicos.app.core.database.AppDatabase
import br.controleservicos.app.core.domain.EnderecoExterno
import br.controleservicos.app.core.model.FuncaoEquipe
import br.controleservicos.app.core.model.LadoRamal
import br.controleservicos.app.core.model.OrigemEndereco
import br.controleservicos.app.core.repository.DadosFuncionario
import br.controleservicos.app.core.repository.ParticipanteNovo
import br.controleservicos.app.core.share.CompartilharServico
import br.controleservicos.app.core.share.FormatoRelatorio
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream

/** Catálogo de endereços (Room real), relatório individual PDF/PNG/DOCX e compartilhamento. Dados SINTÉTICOS. */
@RunWith(AndroidJUnit4::class)
class EnderecoCompartilhamentoTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var container: AppContainer
    private val relogio = RelogioFalso()
    private val repo get() = container.repositorio

    @Before fun setup() { container = containerDeTeste(ctx, relogio) }
    @After fun fim() = container.database.close()

    private fun ext(rua: String, bairro: String?, cep: String?) = EnderecoExterno(cep, rua, "Rua", bairro, "Leme", "SP")

    @Test fun bairroManualDeduplicaEPersisteOrigem() = runBlocking {
        val a = repo.adicionarBairroManual("Jardim  São Pedro")
        val b = repo.adicionarBairroManual("jardim sao pedro")
        assertEquals(a.id, b.id)
        assertEquals("Jardim São Pedro", a.nomeOriginal)
        assertEquals(OrigemEndereco.MANUAL, a.origem)
        assertEquals(1, repo.observarBairrosLeme().first().size)
    }

    @Test fun resultadosExternosViramCatalogoSemInventarRelacao() = runBlocking {
        repo.registrarResultadosExternos(listOf(ext("Rua São Caetano", "Taquari", "13615-752"), ext("Rua São Caetano", "Jardim Águia", "13615-753"), ext("Rua Sem Bairro", null, null)))
        val bairros = repo.observarBairrosLeme().first()
        assertEquals(setOf("Taquari", "Jardim Águia"), bairros.map { it.nomeOriginal }.toSet())
        assertTrue(bairros.all { it.origem == OrigemEndereco.VIACEP && it.verificadoEm != null })
        val ruas = repo.observarLogradouros().first()
        assertEquals(3, ruas.size) // mesma rua com CEPs/trechos distintos fica separada
        val vinc = repo.observarVinculosLogradouro().first()
        val semBairro = ruas.single { it.nomeExibicao == "Rua Sem Bairro" }
        assertTrue(vinc.none { it.logradouroId == semBairro.id }) // sem bairro na fonte ⇒ nenhum vínculo inventado
        // Repetir a consulta não duplica.
        repo.registrarResultadosExternos(listOf(ext("Rua São Caetano", "Taquari", "13615-752")))
        assertEquals(3, repo.observarLogradouros().first().size)
    }

    @Test fun ruaDeOutroBairroConfirmadaGanhaVinculoManualEServicoGuardaSnapshot() = runBlocking {
        val centro = repo.adicionarBairroManual("Centro")
        val l = repo.usarResultadoExterno(ext("Rua São Caetano", "Taquari", "13615-752"), centro.id)
        val v = repo.observarVinculosLogradouro().first().filter { it.logradouroId == l.id }
        assertEquals(setOf(OrigemEndereco.VIACEP, OrigemEndereco.MANUAL), v.map { it.origem }.toSet())
        val id = repo.iniciarServico(novo(bairro = "Centro").copy(logradouro = l.nomeExibicao, logradouroId = l.id, cep = l.cep), relogio.agora())
        val s = repo.observarLinha(id).first()!!
        assertEquals(l.id, s.logradouroId)
        assertEquals("13615-752", s.cep)
        assertEquals("Rua São Caetano", s.logradouro)
    }

    @Test fun importacaoDeListaComPreviaSemDuplicar() = runBlocking {
        repo.adicionarBairroManual("Centro")
        val linhas = listOf("bairro;logradouro;cep", "Centro;Rua Um;13610-001", "centro;R. Um;", "Taquari;Avenida Dois;13615000", ";sem bairro;", "")
        val p = repo.importarEnderecos(linhas, aplicar = false)
        assertEquals(3, p.linhasValidas); assertEquals(1, p.linhasInvalidas)
        assertEquals(1, p.bairrosNovos); assertEquals(2, p.logradourosNovos)
        assertEquals(0, repo.observarLogradouros().first().size) // prévia não grava
        repo.importarEnderecos(linhas, aplicar = true)
        assertEquals(2, repo.observarLogradouros().first().size) // "Rua Um" e "R. Um" = mesmo logradouro
        assertEquals(2, repo.observarBairrosLeme().first().size)
    }

    private suspend fun servicoCompleto(nLigacoes: Int): String {
        val f = repo.adicionarFuncionario(DadosFuncionario("José da Silva", funcaoHabitual = FuncaoEquipe.ENCANADOR))
        val id = repo.iniciarServico(novo(bairro = "Centro", participantes = listOf(ParticipanteNovo(f, FuncaoEquipe.ENCANADOR))), relogio.agora())
        relogio.avancar(17 * 60_000L); repo.marcarRede(id, relogio.agora())
        relogio.avancar(50 * 60_000L); repo.finalizar(id, relogio.agora())
        repo.salvarComplementos(id, complementosComLigacoes(*(1..nLigacoes).map { lig("${100 + it}A", if (it % 2 == 0) LadoRamal.CURTO else LadoRamal.LONGO, 200 + it) }.toTypedArray(), semEquipe = false))
        return id
    }

    @Test fun pdfValidoAbreNoLeitorDoAndroidComTodasAsSecoes() = runBlocking {
        val id = servicoCompleto(3)
        val a = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PDF, ocultarNomes = false)
        val f = a.arquivos.single()
        assertTrue(f.name.matches(Regex("servico_LEME_\\d{8}_[0-9a-f]{8}\\.pdf")))
        assertEquals("%PDF", f.inputStream().use { s -> ByteArray(4).also { b -> s.read(b) }.toString(Charsets.US_ASCII) })
        PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
            assertTrue(r.pageCount >= 1)
            r.openPage(0).use { pg -> val bmp = android.graphics.Bitmap.createBitmap(pg.width, pg.height, android.graphics.Bitmap.Config.ARGB_8888); pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
        listOf("Relatório de serviço — Leme/SP", "Serviço", "Endereço", "Horários e durações", "Equipe", "Rede", "Ligações trocadas (3)", "José da Silva", "101A", "103A")
            .forEach { t -> assertTrue("faltou '$t'", a.textos.any { it.contains(t) }) }
        assertTrue(a.textos.any { it == "00:17:00" }) // tempo até a rede separado da duração total
        assertTrue(a.textos.any { it == "01:07:00" })
    }

    /** Relatório curto não ganha página só com a nota de rodapé (nota vai no pé da última página). */
    @Test fun pdfCurtoCabeEmUmaPaginaComNotaDeRodape() = runBlocking {
        val id = repo.iniciarServico(novo(bairro = "Taquari"), relogio.agora())
        relogio.avancar(60_000); repo.finalizar(id, relogio.agora())
        repo.salvarComplementos(id, complementosComLigacoes(lig("45A")))
        val a = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PDF, ocultarNomes = false)
        val paginas = PdfRenderer(ParcelFileDescriptor.open(a.arquivos.single(), ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
        assertEquals(1, paginas)
        assertTrue(a.textos.any { it.startsWith("Gerado em ") })
        val png = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PNG, ocultarNomes = false)
        assertEquals(1, png.arquivos.size)
        assertTrue(png.textos.any { it.startsWith("Gerado em ") })
    }

    @Test fun pdfLongoQuebraPaginasSemPerderLigacoes() = runBlocking {
        val id = servicoCompleto(60)
        val a = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PDF, ocultarNomes = false)
        val paginas = PdfRenderer(ParcelFileDescriptor.open(a.arquivos.single(), ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
        assertTrue("páginas = $paginas", paginas >= 2)
        (1..60).forEach { n -> assertTrue("ligação ${100 + n}A ausente", a.textos.any { it.contains("nº ${100 + n}A ") }) }
    }

    @Test fun pngDecodificavelSemCortesEOcultaNomes() = runBlocking {
        val id = servicoCompleto(2)
        val a = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PNG, ocultarNomes = true)
        assertEquals(1, a.arquivos.size) // relatório curto cabe em uma imagem
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(a.arquivos.single().path, opts)
        assertEquals(1080, opts.outWidth); assertTrue(opts.outHeight in 400..6000)
        assertFalse(a.textos.any { it.contains("José") })
        assertTrue(a.textos.any { it.contains("Participante 1") })
        // Muito longo ⇒ várias imagens, todas decodificáveis, nenhuma ligação perdida.
        val id2 = repo.iniciarServico(novo(bairro = "Taquari"), relogio.agora())
        relogio.avancar(60_000); repo.finalizar(id2, relogio.agora())
        repo.salvarComplementos(id2, complementosComLigacoes(*(1..300).map { lig("${it}B", cm = 100 + it) }.toTypedArray()))
        val b = CompartilharServico.gerar(ctx, container, id2, FormatoRelatorio.PNG, ocultarNomes = false)
        assertTrue(b.arquivos.size > 1)
        b.arquivos.forEach { assertNotNull(BitmapFactory.decodeFile(it.path)) }
        (1..300).forEach { n -> assertTrue(b.textos.any { it.contains("nº ${n}B ") }) }
    }

    @Test fun docxRealEIntentDeCompartilhamentoSeguro() = runBlocking {
        val id = servicoCompleto(1)
        val a = CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.DOCX, ocultarNomes = false)
        val nomes = ZipInputStream(a.arquivos.single().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue(nomes.containsAll(listOf("[Content_Types].xml", "word/document.xml", "word/styles.xml")))
        val uri = a.uris.single()
        assertEquals("content", uri.scheme)
        assertEquals(FormatoRelatorio.DOCX.mime, ctx.contentResolver.getType(uri))
        val chooser = CompartilharServico.intentCompartilhar(a, "Serviço")
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val envio = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, envio.action)
        assertEquals(FormatoRelatorio.DOCX.mime, envio.type)
        assertTrue(envio.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION") assertEquals(uri, envio.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        // Vários PNG ⇒ ACTION_SEND_MULTIPLE com todas as URIs.
        val varios = a.copy(uris = listOf(uri, uri), formato = FormatoRelatorio.PNG)
        @Suppress("DEPRECATION") val multi = CompartilharServico.intentCompartilhar(varios, "x").getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multi.action)
    }

    @Test fun compartilharServicoNaoFinalizadoFalhaSemAlterarRegistro() = runBlocking {
        val id = repo.iniciarServico(novo(), relogio.agora())
        try { CompartilharServico.gerar(ctx, container, id, FormatoRelatorio.PDF, false); fail() } catch (_: Exception) {}
        assertEquals(br.controleservicos.app.core.model.StatusServico.EM_ANDAMENTO, repo.observarLinha(id).first()!!.status)
    }

    @Suppress("unused") private val usarDb = AppDatabase.VERSAO
}
