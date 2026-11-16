package br.controleservicos.app.core.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import java.io.File
import java.io.OutputStream

/** Recebe páginas para desenhar. */
interface ProvedorPaginas {
    fun nova(numero: Int): Canvas
    fun finalizar(numero: Int, canvas: Canvas)
}

/**
 * Diagramação do relatório em um Canvas (pontos tipográficos × [escala]).
 * Quebra páginas sem truncar: linhas de tabela que não cabem vão para a próxima página
 * (com o cabeçalho repetido). Registra os textos desenhados para testes de completude.
 */
class DiagramadorRelatorio(
    private val largura: Float,
    private val alturaPagina: Float,
    private val escala: Float,
    private val provedor: ProvedorPaginas,
) {
    private val azul = Color.rgb(0x15, 0x65, 0xC0)
    private val azulClaro = Color.rgb(0xE3, 0xF0, 0xFD)
    private val cinza = Color.rgb(0x4A, 0x55, 0x68)
    private val texto = Color.rgb(0x14, 0x23, 0x3C)
    private val margem = 36f * escala
    private val util = largura - 2 * margem
    /** Área reservada no pé de cada página (nº da página e, na última, a nota de geração). */
    private val rodapeAltura = 30f * escala
    private var notaRodape: String? = null

    val textosDesenhados = mutableListOf<String>()
    var paginas = 0
        private set
    private var canvas: Canvas? = null
    private var y = 0f

    /** Posição vertical ao terminar (altura usada na última página). */
    val alturaFinal: Float get() = y

    private fun paint(tamanho: Float, cor: Int = texto, negrito: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = tamanho * escala
        color = cor
        typeface = if (negrito) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun layout(t: String, p: TextPaint, w: Float): StaticLayout =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w.toInt().coerceAtLeast(1)).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f).setIncludePad(false).build()

    private fun novaPagina() {
        canvas?.let { fecharPagina(it) }
        paginas++
        canvas = provedor.nova(paginas).also { it.drawColor(Color.WHITE) }
        y = margem
    }

    private fun fecharPagina(c: Canvas) {
        val p = paint(8f, cinza)
        val base = alturaPagina - margem / 2
        val numero = "Página $paginas"
        val wNumero = p.measureText(numero) + 12f * escala
        c.drawText(numero, largura - margem - p.measureText(numero), base, p)
        notaRodape?.let { nota ->
            // Nota de geração no pé da última página, à esquerda do número (quebra em linhas se preciso).
            val l = layout(nota, paint(7.5f, cinza), util - wNumero)
            desenhar(l, margem, base - l.height + 2f * escala, nota)
            notaRodape = null
        }
        provedor.finalizar(paginas, c)
    }

    private fun garantir(altura: Float) {
        if (canvas == null || y + altura > alturaPagina - margem - rodapeAltura) novaPagina()
    }

    private fun desenhar(l: StaticLayout, x: Float, yTopo: Float, original: String) {
        val c = canvas!!
        c.withTranslation(x, yTopo) { l.draw(this) }
        textosDesenhados += original
    }

    private fun bloco(t: String, p: TextPaint, w: Float = util, x: Float = margem, espacoDepois: Float = 4f) {
        val l = layout(t, p, w)
        garantir(l.height.toFloat())
        desenhar(l, x, y, t)
        y += l.height + espacoDepois * escala
    }

    fun desenhar(r: RelatorioServico) {
        novaPagina()
        val barra = Paint().apply { color = azul }
        canvas!!.drawRect(0f, 0f, largura, 8f * escala, barra)
        bloco(r.titulo, paint(18f, azul, true), espacoDepois = 2f)
        bloco(r.subtitulo, paint(10f, cinza), espacoDepois = 8f)
        r.secoes.forEach { s ->
            val tituloP = paint(12.5f, azul, true)
            val lt = layout(s.titulo, tituloP, util - 12f * escala)
            garantir(lt.height + 30f * escala) // título nunca fica sozinho no fim da página
            canvas!!.drawRect(margem, y, largura - margem, y + lt.height + 8f * escala, Paint().apply { color = azulClaro })
            desenhar(lt, margem + 6f * escala, y + 4f * escala, s.titulo)
            y += lt.height + 14f * escala
            s.pares.forEach { (k, v) ->
                val wk = util * 0.38f
                val lk = layout(k, paint(10f, cinza, true), wk - 6f * escala)
                val lv = layout(v, paint(10.5f), util - wk)
                val h = maxOf(lk.height, lv.height).toFloat()
                garantir(h)
                desenhar(lk, margem, y, k); desenhar(lv, margem + wk, y, v)
                y += h + 5f * escala
            }
            s.tabela?.let { tabela(it) }
            s.paragrafos.forEach { bloco(it, paint(10f, cinza)) }
            y += 8f * escala
        }
        notaRodape = r.rodape
        canvas?.let { fecharPagina(it) }
        canvas = null
    }

    private fun tabela(t: TabelaRelatorio) {
        val n = t.cabecalho.size
        // Larguras: coluna "Imóvel"/"Participante" maior.
        val pesos = t.cabecalho.map { h -> when { h.startsWith("Imóvel") || h == "Participante" || h == "Observações" -> 3f; h == "Nº" -> 0.6f; else -> 1.4f } }
        val soma = pesos.sum()
        val ws = pesos.map { util * it / soma }
        val pad = 4f * escala
        val linhaP = Paint().apply { color = azul; strokeWidth = 0.6f * escala; style = Paint.Style.STROKE }
        fun linha(celulas: List<String>, cab: Boolean) {
            val ls = celulas.mapIndexed { i, c -> layout(c, paint(9.5f, if (cab) azul else texto, cab), ws[i] - 2 * pad) }
            val h = (ls.maxOf { it.height } + 2 * pad)
            var x = margem
            val c = canvas!!
            if (cab) c.drawRect(margem, y, largura - margem, y + h, Paint().apply { color = azulClaro })
            ls.forEachIndexed { i, l -> desenhar(l, x + pad, y + pad, celulas[i]); c.drawRect(x, y, x + ws[i], y + h, linhaP); x += ws[i] }
            y += h
        }
        fun altura(celulas: List<String>, cab: Boolean) = celulas.mapIndexed { i, c -> layout(c, paint(9.5f, texto, cab), ws[i] - 2 * pad).height }.max() + 2 * pad
        garantir(altura(t.cabecalho, true) + altura(t.linhas.firstOrNull() ?: t.cabecalho, false))
        linha(t.cabecalho, true)
        t.linhas.forEach { l ->
            val h = altura(l, false)
            if (y + h > alturaPagina - margem - rodapeAltura) { novaPagina(); linha(t.cabecalho, true) }
            linha(l.take(n), false)
        }
        y += 6f * escala
    }
}

/** Geração local (nada é enviado a servidores) de PDF A4 e de imagens PNG do relatório. */
object GeradorRelatorioAndroid {
    private const val A4_W = 595f
    private const val A4_H = 842f
    private const val PNG_W = 1080
    /** Altura máxima de uma imagem única; acima disso o relatório é paginado em várias imagens. */
    private const val PNG_MAX_H = 6000

    /** Escreve o PDF e devolve (nº de páginas, textos desenhados). */
    fun pdf(r: RelatorioServico, saida: OutputStream): Pair<Int, List<String>> {
        val doc = PdfDocument()
        try {
            var pagina: PdfDocument.Page? = null
            val d = DiagramadorRelatorio(A4_W, A4_H, 1f, object : ProvedorPaginas {
                override fun nova(numero: Int): Canvas {
                    pagina = doc.startPage(PdfDocument.PageInfo.Builder(A4_W.toInt(), A4_H.toInt(), numero).create())
                    return pagina!!.canvas
                }
                override fun finalizar(numero: Int, canvas: Canvas) { doc.finishPage(pagina) }
            })
            d.desenhar(r)
            doc.writeTo(saida)
            return d.paginas to d.textosDesenhados
        } finally {
            doc.close()
        }
    }

    /** Altura total do conteúdo em px para largura [PNG_W] (medição sem desenhar de verdade). */
    private fun medirAltura(r: RelatorioServico): Int {
        val escala = PNG_W / A4_W
        val grande = 200_000f
        val pic = Picture()
        val d = DiagramadorRelatorio(PNG_W.toFloat(), grande, escala, object : ProvedorPaginas {
            override fun nova(numero: Int): Canvas = pic.beginRecording(PNG_W, grande.toInt())
            override fun finalizar(numero: Int, canvas: Canvas) { pic.endRecording() }
        })
        d.desenhar(r)
        return d.alturaFinal.toInt()
    }

    /**
     * Gera uma ou mais imagens PNG em [pasta]. Uma imagem única se couber em [PNG_MAX_H];
     * senão, páginas com proporção A4. Nenhum conteúdo é cortado.
     */
    fun png(r: RelatorioServico, pasta: File, nomeBase: (Int?) -> String): Pair<List<File>, List<String>> {
        val escala = PNG_W / A4_W
        // Conteúdo + margem inferior + área do rodapé (36 + 30 pt) + folga: cabe sem gerar segunda página.
        val alturaTotal = medirAltura(r) + (100 * escala).toInt()
        val umaSo = alturaTotal <= PNG_MAX_H
        val altura = if (umaSo) alturaTotal.coerceAtLeast(400) else (PNG_W * A4_H / A4_W).toInt()
        val arquivos = mutableListOf<File>()
        var bmp: Bitmap? = null
        val d = DiagramadorRelatorio(PNG_W.toFloat(), altura.toFloat(), escala, object : ProvedorPaginas {
            override fun nova(numero: Int): Canvas {
                bmp = createBitmap(PNG_W, altura)
                return Canvas(bmp!!)
            }
            override fun finalizar(numero: Int, canvas: Canvas) {
                val f = File(pasta, nomeBase(if (umaSo) null else numero))
                f.outputStream().use { bmp!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp!!.recycle()
                arquivos += f
            }
        })
        d.desenhar(r)
        return arquivos to d.textosDesenhados
    }
}
