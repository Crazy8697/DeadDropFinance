package net.darkrelay.deaddropfinance.scan

import net.darkrelay.deaddropfinance.domain.ReceiptParser

/**
 * Text recognition behind one interface: Tesseract4Android on the phone, Tesseract through Tess4J on the program
 * (0.14.0), and the desktop Tesseract program in the phone's pipeline test.
 */
interface TextReader : AutoCloseable {
    fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line>
}

/**
 * One receipt page, the same on the phone and the program: the decoded page is cleaned (cropped, straightened,
 * contrast), read at [OCR_MAX], and the cleaned page handed back at [STORE_MAX] to be stored encrypted. How a page is
 * decoded (a photo, a PDF page) and stored (a JPEG at [JPEG_QUALITY]) is each platform's.
 */
object ReceiptPages {
    /** Photos are decoded at most this big (long side), read at OCR_MAX, and stored at STORE_MAX. */
    const val DECODE_MAX = 3000
    const val OCR_MAX = 2400
    const val STORE_MAX = 1600
    const val JPEG_QUALITY = 70
    /** A PDF's first pages only, rendered at about [PDF_DPI] on white. */
    const val MAX_PDF_PAGES = 10
    const val PDF_DPI = 200f

    /** Cleans and reads [page] with [reader]; [store] gets the cleaned page to keep. Returns the page's lines. */
    fun read(page: ImageCleanup.Gray, reader: TextReader, store: (ImageCleanup.Gray) -> Unit): List<ReceiptParser.Line> {
        val cleaned = ImageCleanup.clean(page).image
        val lines = reader.read(ImageCleanup.scaleDown(cleaned, OCR_MAX))
        store(ImageCleanup.scaleDown(cleaned, STORE_MAX))
        return lines
    }

    /** A PDF page's size in pixels at [PDF_DPI], from its size in points, kept within [DECODE_MAX]. */
    fun pdfPixels(widthPoints: Float, heightPoints: Float): Pair<Int, Int> {
        val scale = PDF_DPI / 72f
        var w = (widthPoints * scale).toInt(); var h = (heightPoints * scale).toInt()
        val long = maxOf(w, h)
        if (long > DECODE_MAX) { w = w * DECODE_MAX / long; h = h * DECODE_MAX / long }
        return w.coerceAtLeast(1) to h.coerceAtLeast(1)
    }
}
