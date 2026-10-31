package net.darkrelay.deaddropfinance.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.ByteArrayOutputStream
import java.io.File
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser

/**
 * Tesseract on the phone (Tesseract4Android), English model from the app's assets. Nothing is sent anywhere: the
 * library has no network code and the app asks for no permission for it. The model is copied once to the app's
 * no-backup folder, which Erase everything clears.
 */
class TesseractReader(context: Context) : TextReader {
    private val api = TessBaseAPI()

    init {
        val base = ocrDir(context)
        val model = File(base, "tessdata/eng.traineddata")
        // copied again when the app version changes (a new model may come with it)
        val marker = File(base, "model-version")
        val version = net.darkrelay.deaddropfinance.BuildConfig.VERSION_CODE.toString()
        if (!model.exists() || runCatching { marker.readText() }.getOrNull() != version) {
            model.parentFile!!.mkdirs()
            context.assets.open("tessdata/eng.traineddata").use { input -> model.outputStream().use { input.copyTo(it) } }
            marker.writeText(version)
        }
        check(api.init(base.path, "eng", TessBaseAPI.OEM_LSTM_ONLY)) { "The text recognition model couldn't be loaded." }
        // one block of text: keeps an item and its price, far apart on the line, on the same line
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
    }

    override fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line> {
        val bmp = Bitmap.createBitmap(ImageCleanup.toArgb(g), g.width, g.height, Bitmap.Config.ARGB_8888)
        val out = mutableListOf<ReceiptParser.Line>()
        try {
            api.setImage(bmp)
            api.getUTF8Text()
            val it = api.resultIterator ?: return emptyList()
            try {
                it.begin()
                do {
                    val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)
                    if (!t.isNullOrBlank()) out += ReceiptParser.Line(t.trim(), it.confidence(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE).toInt())
                } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
            } finally {
                it.delete()
            }
        } finally {
            api.clear()
            bmp.recycle()
        }
        return out
    }

    override fun close() = api.recycle()

    companion object {
        fun ocrDir(context: Context) = File(context.noBackupFilesDir, "ocr")
    }
}

/**
 * Turns a photo or a PDF into receipt pages: decoded (turned upright from the camera's orientation tag), cleaned
 * (cropped, straightened, contrast), read, and stored encrypted under the receipt's id. Nothing is written in the
 * clear except the camera's own capture file, which the caller deletes right after.
 */
class ReceiptScanner(private val context: Context, private val images: ReceiptImages, private val reader: () -> TextReader) {

    /** Reads [uri] and stores its pages from [firstPage] on. Returns each page's lines. Call off the main thread. */
    fun scan(uri: Uri, receiptId: String, firstPage: Int): List<List<ReceiptParser.Line>> {
        val out = mutableListOf<List<ReceiptParser.Line>>()
        try {
            reader().use { r ->
                // one page at a time: a long PDF never has all its pages in memory at once
                // the page itself (clean, read, keep) is shared with the program: ReceiptPages
                val each: (Bitmap) -> Unit = { bmp ->
                    val g = toGray(bmp)
                    bmp.recycle()
                    val page = firstPage + out.size
                    out += ReceiptPages.read(g, r) { images.write(receiptId, page, jpeg(it)) }
                }
                if (context.contentResolver.getType(uri) == "application/pdf") pdfPages(uri, each) else photo(uri)?.let(each)
            }
        } catch (e: OutOfMemoryError) {
            throw IllegalArgumentException("That file is too big to read on this phone.")
        }
        if (out.isEmpty()) throw IllegalArgumentException("That file couldn't be read as an image or a PDF.")
        return out
    }

    /** The photo, upright (the platform decoder applies the camera's orientation tag), at most DECODE_MAX pixels. */
    private fun photo(uri: Uri): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val long = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (long / sample > DECODE_MAX) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (e: Exception) {
        null
    }

    private fun pdfPages(uri: Uri, each: (Bitmap) -> Unit) {
        val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
        fd.use {
            PdfRenderer(it).use { pdf ->
                for (n in 0 until minOf(pdf.pageCount, MAX_PDF_PAGES)) {
                    val bmp = pdf.openPage(n).use { page ->
                        // about 200 dpi, white behind the page
                        val (w, h) = ReceiptPages.pdfPixels(page.width.toFloat(), page.height.toFloat())
                        createBitmap(w, h).also { b ->
                            b.eraseColor(Color.WHITE)
                            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    each(bmp)
                }
            }
        }
    }

    private fun toGray(b: Bitmap): ImageCleanup.Gray {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return ImageCleanup.fromArgb(b.width, b.height, px)
    }

    private fun jpeg(g: ImageCleanup.Gray): ByteArray {
        val b = Bitmap.createBitmap(ImageCleanup.toArgb(g), g.width, g.height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out); b.recycle(); out.toByteArray() }
    }

    companion object {
        /** The sizes and limits are the program's too (ReceiptPages). */
        const val DECODE_MAX = ReceiptPages.DECODE_MAX
        const val JPEG_QUALITY = ReceiptPages.JPEG_QUALITY
        const val MAX_PDF_PAGES = ReceiptPages.MAX_PDF_PAGES
    }
}
