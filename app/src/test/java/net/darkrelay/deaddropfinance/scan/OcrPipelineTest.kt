package net.darkrelay.deaddropfinance.scan

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The receipt pipeline end to end on generated images of invented receipts: draw the receipt (tilted, on a darker
 * table), clean it (crop, straighten, contrast), read it with Tesseract and the same English model the app ships
 * (assets/tessdata), and parse it. On the JVM the engine is the desktop Tesseract program; on the phone it's the same
 * engine through Tesseract4Android. Skipped where no desktop Tesseract is installed. Nothing touches the network.
 */
class OcrPipelineTest {

    private val tesseract = listOfNotNull(System.getenv("DDF_TESSERACT"), "C:/Program Files/Tesseract-OCR/tesseract.exe", "/usr/bin/tesseract")
        .map { File(it) }.firstOrNull { it.exists() }
    private val model = listOf(File("src/main/assets/tessdata"), File("app/src/main/assets/tessdata")).first { it.isDirectory }

    /** The desktop engine behind the app's TextReader interface: TSV output, words grouped into lines. */
    private inner class DesktopReader : TextReader {
        override fun read(g: ImageCleanup.Gray): List<ReceiptParser.Line> {
            val dir = Files.createTempDirectory("ddf-ocr").toFile()
            try {
                val png = File(dir, "page.png")
                val img = BufferedImage(g.width, g.height, BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, g.width, g.height, ImageCleanup.toArgb(g), 0, g.width)
                ImageIO.write(img, "png", png)
                val p = ProcessBuilder(tesseract!!.path, png.path, File(dir, "out").path, "--tessdata-dir", model.path, "--oem", "1", "--psm", "6", "-l", "eng", "-c", "tessedit_create_tsv=1", "-c", "tessedit_create_txt=0")
                    .redirectErrorStream(true).start()
                p.inputStream.readBytes()
                check(p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0) { "tesseract failed" }
                val rows = File(dir, "out.tsv").readLines().drop(1).map { it.split('\t') }.filter { it.size >= 12 && it[0] == "5" && it[11].isNotBlank() }
                return rows.groupBy { Triple(it[2], it[3], it[4]) }.values.map { ws ->
                    ReceiptParser.Line(ws.joinToString(" ") { it[11] }, ws.map { it[10].toDouble() }.average().toInt())
                }
            } finally {
                dir.deleteRecursively()
            }
        }
        override fun close() {}
    }

    /** An invented receipt as an image: white paper, black monospaced text, tilted, on a gray table. */
    private fun draw(lines: List<String>, tiltDegrees: Double): ImageCleanup.Gray {
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
        val photo = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        photo.createGraphics().apply {
            color = Color(70, 70, 75); fillRect(0, 0, w, h)
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val t = AffineTransform()
            t.rotate(Math.toRadians(tiltDegrees), w / 2.0, h / 2.0)
            t.translate(200.0, 200.0)
            drawImage(paper, t, null)
            dispose()
        }
        val px = IntArray(w * h)
        photo.getRGB(0, 0, w, h, px, 0, w)
        return ImageCleanup.fromArgb(w, h, px)
    }

    private fun read(lines: List<String>, tilt: Double): ReceiptParser.Parsed {
        val g = draw(lines, tilt)
        val r = ImageCleanup.clean(g)
        assertTrue("the table around the receipt is cropped off", r.cropped)
        assertTrue("straightened: found ${r.angleDegrees}", kotlin.math.abs(r.angleDegrees - tilt) <= 1.0)
        val ocr = DesktopReader().read(r.image)
        System.getenv("DDF_OCR_DEBUG")?.let { d ->
            val img = BufferedImage(r.image.width, r.image.height, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, r.image.width, r.image.height, ImageCleanup.toArgb(r.image), 0, r.image.width)
            ImageIO.write(img, "png", File(d, "cleaned-${tilt}.png"))
            File(d, "ocr-${tilt}.txt").writeText(ocr.joinToString("\n") { "${it.confidence}\t${it.text}" } + "\nangle ${r.angleDegrees}")
        }
        return ReceiptParser.parse(listOf(ocr))
    }

    @Test fun aTiltedGroceryReceiptIsReadIntoItsFields() {
        assumeTrue("desktop Tesseract not installed", tesseract != null)
        val p = read(
            listOf(
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
            ),
            tilt = 3.0,
        )
        assertEquals("SAMPLE FRESH MARKET", p.store)
        assertEquals(LocalDate.of(2030, 3, 14), p.date)
        assertEquals(12_49L, p.total)
        assertEquals(82L, p.tax)
        assertEquals(listOf(3_49L, 4_20L, 3_98L), p.items.map { it.amount })
        assertEquals(2000L, p.items[2].quantityMilli)
        assertTrue("clean text adds up: ${p.lowConfidence}", p.lowConfidence.isEmpty())
    }

    @Test fun aRestaurantReceiptTiltedTheOtherWay() {
        assumeTrue("desktop Tesseract not installed", tesseract != null)
        val p = read(
            listOf(
                "SAMPLE GRILL",
                "MAR 15, 2030",
                "SAMPLE BURGER        12.00",
                "SAMPLE TEA            2.50",
                "SUBTOTAL             14.50",
                "TAX                   1.16",
                "TOTAL                15.66",
            ),
            tilt = -4.0,
        )
        assertEquals("SAMPLE GRILL", p.store)
        assertEquals(LocalDate.of(2030, 3, 15), p.date)
        assertEquals(15_66L, p.total)
        assertEquals(2, p.items.size)
    }
}
