package net.darkrelay.deaddropfinance.desktop

import java.io.File
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.domain.ReceiptTax
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1.9.4 (0.15.3): a real Food Lion receipt photo (shared/src/jvmTest/resources/receipts/food-lion), read the way
 * the program reads a scan (cleaned up, then its text recognition), then read into a receipt: every item line, its
 * quantity, price, tax letter and section; the tax; the prices with tax adding up. The recognition misreads a few marks
 * on this photo ("Ax" for "A *", "20" for "2 @", "EROZEN"), which the reader takes in its stride.
 */
class FoodLionPhotoTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun theRealPhotoIsReadIntoEveryItem() {
        val src = listOf(File("../shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp"), File("shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp")).first { it.isFile }
        val jpg = File(tmp.root, "food-lion.jpg").apply {
            writeBytes(org.jetbrains.skia.Image.makeFromEncoded(src.readBytes()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 95)!!.bytes)
        }
        val pages = ProgramReceiptScanner(ReceiptImages(tmp.newFolder("receipts"), { ByteArray(32) { it.toByte() } })) {
            TessReader(TessReader.tessdata()!!)
        }.scan(jpg, "food-lion", 0)
        val p = ReceiptParser.parse(pages)
        assertEquals(21, p.items.size, p.items.joinToString("\n"))
        assertEquals(
            listOf(3_19L, 2_99L, 5_49L, 22_98L, 1_99L, 59L, 5_58L, 2_49L, 1_99L, 5_49L, 4_49L, 4_49L, 17_99L, 1_49L, 1_49L, 3_99L, 7_99L, 6_49L, 2_19L, 1_99L, 3_64L),
            p.items.map { it.amount },
        )
        assertEquals("AAABAAAAAAABBAAAAAAAA".map { it.toString() }, p.items.map { it.taxLetter })
        assertEquals(listOf(2000L, 2000L, 1260L), listOf(p.items[3], p.items[6], p.items[20]).map { it.quantityMilli })
        assertEquals(listOf(11_49L, 2_79L, 2_89L), listOf(p.items[3], p.items[6], p.items[20]).map { it.unitPrice })
        assertEquals(listOf("FROZEN/DAIRY", "GROCERY", "MEAT", "PRODUCE"), p.items.map { it.section }.distinct())
        assertTrue(p.items.count { it.text in setOf("FL 2 MILK GALLON", "MODELA CHLD LM NA 6C", "FOOD LION LIME JUICE", "CELS SPRK WLD BRRY", "CHINESE CABBAGE") } == 5)
        assertEquals("", p.store)
        assertEquals(3_18L, p.tax)
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved, "the taxable amounts settle the letters (one digit misread, 63.51 for 63.56)")
        assertEquals(112_20L, w.withTax.sum())
    }
}
