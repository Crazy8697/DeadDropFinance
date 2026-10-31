package net.darkrelay.deaddropfinance.desktop

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import javax.imageio.ImageIO
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.scan.ImageCleanup
import net.darkrelay.deaddropfinance.scan.TextReader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Receipt text recognition and PDF receipts on the program (0.14.0), on invented receipts drawn here: a tilted photo on
 * a darker table, a photo stored on its side with an orientation tag, a PDF with text and a scanned PDF (an image in a
 * PDF), each read by the program's Tesseract with the phone's model and parsed by the phone's parser. Pages are kept
 * encrypted; a file that's neither is refused; when the reader can't start the page is kept to be typed in.
 */
class ProgramReceiptReadingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7).toByte() }
    private fun images(dir: File = tmp.newFolder("receipts")) = ReceiptImages(dir, { key })
    private fun scanner(images: ReceiptImages) = ProgramReceiptScanner(images) { TessReader(assertNotNull(TessReader.tessdata(), "the model")) }

    /** A JPEG of [img]; with [orientation], an EXIF orientation tag (6: shown turned a quarter clockwise). */
    private fun jpeg(img: BufferedImage, name: String, orientation: Int? = null): File {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        val out = if (orientation == null) bytes else {
            // APP1 Exif: a big-endian TIFF header and one IFD entry, Orientation (0x0112), SHORT
            val exif = byteArrayOf(
                'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0,
                'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
                0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
                0, 0, 0, 0,
            )
            val len = exif.size + 2
            bytes.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + exif + bytes.copyOfRange(2, bytes.size)
        }
        return tmp.newFile(name).also { it.writeBytes(out) }
    }

    /** [img] turned a quarter counterclockwise: what a camera stores for a photo tagged 6. */
    private fun onItsSide(img: BufferedImage): BufferedImage {
        val out = BufferedImage(img.height, img.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) out.setRGB(y, img.width - 1 - x, img.getRGB(x, y))
        return out
    }

    /** A PDF receipt with real text, one page per list of lines, in a receipt printer's narrow page. */
    private fun textPdf(pages: List<List<String>>, name: String): File = tmp.newFile(name).also { f ->
        PDDocument().use { doc ->
            for (lines in pages) {
                val page = PDPage(PDRectangle(260f, 30f + 16f * lines.size + 30f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.COURIER_BOLD), 11f)
                    cs.newLineAtOffset(12f, page.mediaBox.height - 30f)
                    for (l in lines) { cs.showText(l); cs.newLineAtOffset(0f, -16f) }
                    cs.endText()
                }
            }
            doc.save(f)
        }
    }

    /** A scanned receipt: the photo as the PDF's one page. */
    private fun scannedPdf(img: BufferedImage, name: String): File = tmp.newFile(name).also { f ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(img.width * 72f / 200f, img.height * 72f / 200f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { it.drawImage(LosslessFactory.createFromImage(doc, img), 0f, 0f, page.mediaBox.width, page.mediaBox.height) }
            doc.save(f)
        }
    }

    private fun assertGrocery(p: ReceiptParser.Parsed) {
        assertEquals("SAMPLE FRESH MARKET", p.store)
        assertEquals(LocalDate.of(2030, 3, 14), p.date)
        assertEquals(12_49L, p.total)
        assertEquals(82L, p.tax)
        assertEquals(listOf(3_49L, 4_20L, 3_98L), p.items.map { it.amount })
    }

    @Test fun theModelIsThePhonesOwnFile() {
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        val here = File(assertNotNull(TessReader.tessdata()), "eng.traineddata")
        val phone = listOf(File("../app/src/main/assets/tessdata/eng.traineddata"), File("app/src/main/assets/tessdata/eng.traineddata")).first { it.isFile }
        assertEquals(sha(phone), sha(here))
    }

    @Test fun aTiltedReceiptPhotoIsReadIntoItsFieldsAndKeptEncrypted() {
        val img = images()
        val file = jpeg(photo(grocery, 3.0), "sample-receipt.jpg")
        val pages = scanner(img).scan(file, "r1", 0)
        assertEquals(1, pages.size)
        assertGrocery(ReceiptParser.parse(pages))
        // kept as one encrypted page, which opens as a JPEG
        assertEquals(listOf("r1" to 0), img.stored())
        val kept = img.read("r1", 0)!!
        assertTrue(kept[0] == 0xFF.toByte() && kept[1] == 0xD8.toByte(), "a JPEG inside")
        val onDisk = tmp.root.resolve("receipts").listFiles()!!.single().readBytes()
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("JFIF"), "never in the clear")
    }

    @Test fun aPhotoStoredOnItsSideIsTurnedUprightByItsOrientationTag() {
        val file = jpeg(onItsSide(photo(grocery, 0.0)), "sideways.jpg", orientation = 6)
        assertGrocery(ReceiptParser.parse(scanner(images()).scan(file, "r2", 0)))
    }

    @Test fun aPdfReceiptIsReadPageByPageAndEachPageIsKept() {
        val img = images()
        val file = textPdf(listOf(grocery.take(6), grocery.drop(6)), "sample-receipt.pdf")
        val pages = scanner(img).scan(file, "r3", 0)
        assertEquals(2, pages.size)
        assertGrocery(ReceiptParser.parse(pages))
        assertEquals(listOf("r3" to 0, "r3" to 1), img.stored())
    }

    @Test fun aScannedPdfIsReadLikeAPhotoAndAddsToTheReceiptsPages() {
        val img = images()
        val pages = scanner(img).scan(scannedPdf(photo(grocery, -2.0), "scan.pdf"), "r4", 2)
        assertGrocery(ReceiptParser.parse(pages))
        assertEquals(listOf("r4" to 2), img.stored())
    }

    @Test fun aPdfLongerThanTenPagesIsReadToItsTenth() {
        val pages = scanner(images()).scan(textPdf(List(12) { listOf("SAMPLE PAGE ${it + 1}") }, "long.pdf"), "r5", 0)
        assertEquals(10, pages.size)
    }

    @Test fun whenTheReaderCantStartThePageIsStillKeptToBeTypedIn() {
        val img = images()
        val broken = ProgramReceiptScanner(img) { throw IllegalStateException("no engine") }
        assertEquals(listOf(emptyList()), broken.scan(jpeg(photo(grocery, 0.0), "r.jpg"), "r6", 0))
        assertEquals(listOf("r6" to 0), img.stored())
        // and a page the engine fails on is kept with no lines too
        val failing = ProgramReceiptScanner(img) {
            object : TextReader {
                override fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line> = error("read failed")
                override fun close() {}
            }
        }
        assertEquals(listOf(emptyList()), failing.scan(textPdf(listOf(grocery), "p.pdf"), "r7", 0))
    }

    @Test fun aFileThatIsNeitherAnImageNorAPdfIsRefusedAndNothingIsKept() {
        val img = images()
        val notes = tmp.newFile("notes.jpg").also { it.writeText("SAMPLE not an image") }
        assertFailsWith<IllegalArgumentException> { scanner(img).scan(notes, "r8", 0) }
        assertTrue(img.stored().isEmpty())
        assertFalse(ProgramReceiptScanner.isPdf("SAMPLE".toByteArray()))
    }

    companion object {
        val grocery = listOf(
            "SAMPLE FRESH MARKET",
            "123 EXAMPLE AVE",
            "03/14/2030 14:22",
            "SAMPLE BREAD          3.49",
            "SAMPLE APPLES         4.20",
            "SAMPLE SODA 2 @ 1.99  3.98",
            "SUBTOTAL             11.67",
            "TAX                   0.82",
            "TOTAL                12.49",
            "VISA                 12.49",
        )

        /** An invented receipt as a photo: white paper, black monospaced text, tilted, on a gray table (the phone's test's). */
        fun photo(lines: List<String>, tiltDegrees: Double): BufferedImage {
            val font = Font(Font.MONOSPACED, Font.BOLD, 30)
            val lineH = 42
            val paperW = 760; val paperH = lineH * lines.size + 80
            val paper = BufferedImage(paperW, paperH, BufferedImage.TYPE_INT_RGB)
            paper.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                color = Color.WHITE; fillRect(0, 0, paperW, paperH)
                color = Color.BLACK; this.font = font
                lines.forEachIndexed { i, l -> drawString(l, 30, 60 + i * lineH) }
                dispose()
            }
            val w = paperW + 400; val h = paperH + 400
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            out.createGraphics().apply {
                color = Color(70, 70, 75); fillRect(0, 0, w, h)
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                val t = AffineTransform()
                t.rotate(Math.toRadians(tiltDegrees), w / 2.0, h / 2.0)
                t.translate(200.0, 200.0)
                drawImage(paper, t, null)
                dispose()
            }
            return out
        }
    }
}
