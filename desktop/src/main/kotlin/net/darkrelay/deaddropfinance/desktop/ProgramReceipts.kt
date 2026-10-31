package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.scan.ImageCleanup
import net.darkrelay.deaddropfinance.scan.ReceiptPages
import net.darkrelay.deaddropfinance.scan.TextReader
import net.sourceforge.tess4j.ITessAPI
import net.sourceforge.tess4j.TessAPI1
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/**
 * Tesseract on the program (0.14.0): Tesseract OCR 5.5.3 through Tess4J (the DLLs in its jar and Lept4J's), with the
 * phone's own English model, read in the same way as the phone's TesseractReader (one block of text, LSTM only, a line
 * at a time with its confidence). The model is in the installed program's resources folder; nothing is downloaded, and
 * the engine is never given anything but the page's pixels. Tesseract's debug viewer (which could open a socket) only
 * runs when its debug settings are set, and none are.
 */
class TessReader(tessdata: File) : TextReader {
    private val api: ITessAPI.TessBaseAPI

    init {
        loadRuntime()
        api = TessAPI1.TessBaseAPICreate()
        if (TessAPI1.TessBaseAPIInit2(api, tessdata.path, "eng", ITessAPI.TessOcrEngineMode.OEM_LSTM_ONLY) != 0) {
            TessAPI1.TessBaseAPIDelete(api)
            throw IllegalStateException("The text recognition model couldn't be loaded.")
        }
        // one block of text: keeps an item and its price, far apart on the line, on the same line
        TessAPI1.TessBaseAPISetPageSegMode(api, ITessAPI.TessPageSegMode.PSM_SINGLE_BLOCK)
    }

    override fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line> {
        val px = ByteBuffer.allocateDirect(g.width * g.height)
        for (v in g.px) px.put(v.toByte())
        px.flip()
        val out = mutableListOf<ReceiptParser.Line>()
        try {
            TessAPI1.TessBaseAPISetImage(api, px, g.width, g.height, 1, g.width)
            if (TessAPI1.TessBaseAPIRecognize(api, null) != 0) return emptyList()
            val it = TessAPI1.TessBaseAPIGetIterator(api) ?: return emptyList()
            try {
                val page = TessAPI1.TessResultIteratorGetPageIterator(it)
                val line = ITessAPI.TessPageIteratorLevel.RIL_TEXTLINE
                do {
                    val p = TessAPI1.TessResultIteratorGetUTF8Text(it, line) ?: continue
                    val t = try { p.getString(0, "UTF-8") } finally { TessAPI1.TessDeleteText(p) }
                    if (t.isNotBlank()) out += ReceiptParser.Line(t.trim(), TessAPI1.TessResultIteratorConfidence(it, line).toInt())
                } while (TessAPI1.TessPageIteratorNext(page, line) == ITessAPI.TRUE)
            } finally {
                TessAPI1.TessResultIteratorDelete(it)
            }
        } finally {
            TessAPI1.TessBaseAPIClear(api)
        }
        return out
    }

    override fun close() {
        TessAPI1.TessBaseAPIEnd(api)
        TessAPI1.TessBaseAPIDelete(api)
    }

    companion object {
        /**
         * The model's folder: app\resources\tessdata in the installed program (Compose's resources folder), or the
         * phone's model in a build's tests (ddf.ocrResources).
         */
        fun tessdata(): File? = (System.getProperty("compose.application.resources.dir") ?: System.getProperty("ddf.ocrResources"))
            ?.let { File(it, "tessdata") }?.takeIf { File(it, "eng.traineddata").isFile }

        /**
         * The Visual C++ runtime the Tesseract DLL is built against, from the program's own Java runtime (it carries the
         * same DLLs), loaded first so Windows finds them whether or not the computer has them installed.
         */
        private fun loadRuntime() {
            val bin = File(System.getProperty("java.home"), "bin")
            for (n in listOf("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll")) {
                File(bin, n).takeIf { it.isFile }?.let { runCatching { System.load(it.path) } }
            }
        }
    }
}

/**
 * Turns an image or a PDF into receipt pages on the program (0.14.0), as the phone's ReceiptScanner does: each page
 * decoded (turned upright from a photo's orientation tag), then cleaned, read and kept (ReceiptPages, shared with the
 * phone), stored encrypted under the receipt's id as a JPEG. A PDF's first ten pages, drawn at about 200 dpi on white.
 * If the reader can't start or can't read a page, the page is still kept and its lines are empty: the form opens to be
 * typed, as before the program read receipts. Nothing is written in the clear.
 */
class ProgramReceiptScanner(private val images: ReceiptImages, private val reader: () -> TextReader) {

    /** Reads [file] and stores its pages from [firstPage] on. Returns each page's lines. Call off the UI thread. */
    fun scan(file: File, receiptId: String, firstPage: Int): List<List<ReceiptParser.Line>> {
        val out = mutableListOf<List<ReceiptParser.Line>>()
        val bytes = file.readBytes()
        val r = runCatching { reader() }.getOrElse { NO_TEXT }
        try {
            val each: (ImageCleanup.Gray) -> Unit = { g ->
                val page = firstPage + out.size
                out += ReceiptPages.read(g, safe(r)) { images.write(receiptId, page, jpeg(it)) }
            }
            if (isPdf(bytes)) pdfPages(bytes, each) else photo(bytes)?.let(each)
        } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("That file is too big to read on this computer.")
        } finally {
            runCatching { r.close() }
        }
        if (out.isEmpty()) throw IllegalArgumentException("That file couldn't be read as an image or a PDF.")
        return out
    }

    /** A page the engine fails on is kept with no lines. */
    private fun safe(r: TextReader) = object : TextReader {
        override fun read(g: ImageCleanup.Gray) = runCatching { r.read(g) }.getOrDefault(emptyList())
        override fun close() {}
    }

    /** The photo, upright (Skia applies a photo's orientation tag), at most DECODE_MAX pixels on its long side. */
    private fun photo(bytes: ByteArray): ImageCleanup.Gray? = try {
        val img = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().toAwtImage()
        ImageCleanup.scaleDown(gray(img), ReceiptPages.DECODE_MAX)
    } catch (e: OutOfMemoryError) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun pdfPages(bytes: ByteArray, each: (ImageCleanup.Gray) -> Unit) {
        Loader.loadPDF(bytes).use { doc ->
            val renderer = PDFRenderer(doc)
            for (n in 0 until minOf(doc.numberOfPages, ReceiptPages.MAX_PDF_PAGES)) {
                val box = doc.getPage(n).cropBox
                val (w, _) = ReceiptPages.pdfPixels(box.width, box.height)
                // about 200 dpi, white behind the page
                each(gray(renderer.renderImage(n, w / box.width, ImageType.RGB)))
            }
        }
    }

    private fun gray(img: BufferedImage): ImageCleanup.Gray {
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return ImageCleanup.fromArgb(img.width, img.height, px)
    }

    private fun jpeg(g: ImageCleanup.Gray): ByteArray {
        val img = BufferedImage(g.width, g.height, BufferedImage.TYPE_BYTE_GRAY)
        img.raster.setPixels(0, 0, g.width, g.height, g.px)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        return ByteArrayOutputStream().use { out ->
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                val p = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = ReceiptPages.JPEG_QUALITY / 100f }
                writer.write(null, IIOImage(img, null, null), p)
                writer.dispose()
            }
            out.toByteArray()
        }
    }

    companion object {
        private val NO_TEXT = object : TextReader {
            override fun read(g: ImageCleanup.Gray) = emptyList<ReceiptParser.Line>()
            override fun close() {}
        }

        /** A PDF by its first bytes, whatever its name. */
        fun isPdf(bytes: ByteArray) = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1).contains("%PDF-")
    }
}
