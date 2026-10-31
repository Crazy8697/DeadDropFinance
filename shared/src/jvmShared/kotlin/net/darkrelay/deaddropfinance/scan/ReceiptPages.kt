package net.darkrelay.deaddropfinance.scan

import net.darkrelay.deaddropfinance.domain.ReceiptParser

/**
 * Text recognition behind one interface: Tesseract4Android on the phone, Tesseract through Tess4J on the program
 * (0.14.0), and the desktop Tesseract program in the phone's pipeline test.
 */
interface TextReader : AutoCloseable {
    fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line>

    /**
     * 0.15.5: the page read as scattered text, each piece of text found where it lies (the program's Tesseract), for what
     * a read as one block can drop, as an amount set apart on its own at the bottom right. None by default.
     *
     * 0.15.6: this is the platform's whole part of the second read: the raw pass, nothing more. When to ask for it, the
     * page it gets (uncleaned, enlarged) and what's taken from it are the shared reader's ([ReceiptPages.read],
     * [ReceiptPages.withAmountsFound]). The program supplies it (Tess4J); the phone doesn't, and gets the total worked out.
     */
    fun readSparse(g: ImageCleanup.Gray): List<ReceiptParser.Line> = emptyList()
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

    /** A page smaller than this (long side) is enlarged before it's read: small text reads badly. */
    const val READ_MIN = 1000

    /**
     * Cleans and reads [page] with [reader]; [store] gets the cleaned page to keep. Returns the page's lines.
     * 0.15.5: a small page (a screenshot of a photo, a small image: text a dozen pixels high) is enlarged first, by a whole
     * number, so its text is the size the reader reads well. And when the lines read have a balance due (or a total tax)
     * with no amount the reader could read, the page is read again as scattered text, without the cleanup (whose
     * contrast can wipe out a faint amount), and the amount found after the same words there is put back after them.
     */
    fun read(page: ImageCleanup.Gray, reader: TextReader, store: (ImageCleanup.Gray) -> Unit): List<ReceiptParser.Line> {
        val long = maxOf(page.width, page.height)
        val sized = if (long in 1 until READ_MIN) ImageCleanup.scaleUp(page, maxOf(2, 1600 / long + 1).coerceAtMost(4)) else page
        val cleaned = ImageCleanup.clean(sized).image
        val lines = reader.read(ImageCleanup.scaleDown(cleaned, OCR_MAX))
        store(ImageCleanup.scaleDown(cleaned, STORE_MAX))
        return withAmountsFound(lines) {
            val raw = if (long in 1 until OCR_MAX) ImageCleanup.scaleUp(page, (OCR_MAX + long - 1) / long) else page
            reader.readSparse(ImageCleanup.scaleDown(raw, OCR_MAX))
        }
    }

    private val dueWords = Regex("""\b(BALANCE DUE|BAL DUE|AMOUNT DUE|TOTAL DUE|GRAND TOTAL|TOTAL)\b""", RegexOption.IGNORE_CASE)
    private val notDue = Regex("""\b(TAX|TAXES|SUBTOTAL|SUB TOTAL|SAVINGS|SAVED)\b""", RegexOption.IGNORE_CASE)
    private val taxTotalWords = Regex("""\bTOTAL\s+(SALES\s+)?TAX(ES)?\b|\bTAX\s+TOTAL\b""", RegexOption.IGNORE_CASE)
    private val amount = Regex("""(?<![\d.,])(\d{1,5}[.,]\d{2})(?![\d%])""")

    private fun hasAmount(lines: List<ReceiptParser.Line>, at: Int) =
        amount.containsMatchIn(lines[at].text) || lines.getOrNull(at + 1)?.let { amount.containsMatchIn(it.text) && it.text.count { c -> c.isLetter() } < 2 } == true

    /** The amounts on and after the last line matching [words] in [other], within a few lines. */
    private fun amountsAfter(other: List<ReceiptParser.Line>, words: (String) -> Boolean): List<String> {
        val at = other.indexOfLast { words(it.text) }
        if (at < 0) return emptyList()
        val out = mutableListOf<String>()
        // the words' own line and a few after it: the amount, and the payment line that repeats it
        for (l in other.subList(at, minOf(other.size, at + 6))) out += amount.findAll(l.text).map { it.groupValues[1].replace(',', '.') }
        return out
    }

    /**
     * The balance due and the total tax, when the lines read have the words but no amount the reader could read: the
     * scattered read is looked at once, and the amount after the same words there is put on a line of its own right after
     * them. For the balance due, of the amounts found after it, the one the items and tax add up to (a receipt prints the
     * same amount as the balance and the payment, and one may be misread); the one found otherwise, marked doubtful.
     */
    fun withAmountsFound(lines: List<ReceiptParser.Line>, sparse: () -> List<ReceiptParser.Line>): List<ReceiptParser.Line> {
        val due = lines.indexOfLast { dueWords.containsMatchIn(it.text) && !notDue.containsMatchIn(it.text) }
        val taxAt = lines.indexOfLast { taxTotalWords.containsMatchIn(it.text) }
        val needDue = due >= 0 && !hasAmount(lines, due)
        val needTax = taxAt >= 0 && !hasAmount(lines, taxAt)
        if (!needDue && !needTax) return lines
        val other = runCatching(sparse).getOrDefault(emptyList())
        val inserts = sortedMapOf<Int, ReceiptParser.Line>()
        if (needTax) amountsAfter(other) { taxTotalWords.containsMatchIn(it) }.firstOrNull()?.let { inserts[taxAt] = ReceiptParser.Line(it, lines[taxAt].confidence) }
        var out = lines.toMutableList()
        inserts.entries.reversed().forEach { (i, l) -> out.add(i + 1, l) }
        if (needDue) {
            val d = out.indexOfLast { dueWords.containsMatchIn(it.text) && !notDue.containsMatchIn(it.text) }
            val found = amountsAfter(other) { dueWords.containsMatchIn(it) && !notDue.containsMatchIn(it) }
            if (found.isNotEmpty()) {
                // what the items and tax come to, the total worked out from the lines read
                val worked = ReceiptParser.parseLines(out).total
                val agree = found.firstOrNull { c -> worked != null && c.replace(".", "").toLong() == worked }
                out.add(d + 1, ReceiptParser.Line(agree ?: found.first(), if (agree != null) out[d].confidence.coerceAtLeast(ReceiptParser.LOW_CONFIDENCE) else ReceiptParser.LOW_CONFIDENCE - 1))
            }
        }
        return out
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
