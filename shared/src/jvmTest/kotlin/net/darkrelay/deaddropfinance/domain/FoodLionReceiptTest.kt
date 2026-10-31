package net.darkrelay.deaddropfinance.domain

import java.io.File
import net.darkrelay.deaddropfinance.domain.ReceiptParser.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * a real Food Lion receipt (1.9.4), the one 1.9.3 read badly: its photo and 1.9.3's form are kept beside this test
 * (resources/receipts/food-lion; approved by its owner for the public repository). Its text here is the receipt's own, line by line, as
 * text recognition gives it; a second copy has what the phone's reader made of the bottom in 1.9.3. Twenty-one item lines
 * (23 things: two of them are 2 each), the A items untaxed and the B items at 7%, proved by the taxable amounts on the
 * tax rows. The store and the date were outside the photo: they're left for the user.
 */
class FoodLionReceiptTest {

    private val receipt = """
        FROZEN/DAIRY
        FL 2 MILK GALLON      W      3.19 A *
        FL SALTED BUTTER QTR         2.99 A *
        FL HEAVY WHIP CREAM          5.49 A *
        GROCERY
        MODELA CHLD LM NA 6C
        2 @     11.49               22.98 B
        FL LEMON JUICE               1.99 A *
        FL RANCH DP DRESS PK         0.59 A *
        FL GRN TEA BAGS 40CT
        2 @     2.79                 5.58 A *
        FL CINNI MINI CRUNCH         2.49 A *
        FOOD LION LIME JUICE         1.99 A *
        KELLOGG FUN PAK              5.49 A *
        TWIN EARL GREY TEA           4.49 A *
        SIPPIE BOWLS                 4.49 B
        ATHLC LITE NA 12CN          17.99 B
        CELS SPRK WLD BRRY    M      1.49 A *
        You saved    1.50
        CELS SPRK PCH VIBES   M      1.49 A *
        You saved    1.50
        MEAT
        SLVRFLOSS KRAUT 32Z          3.99 A *
        LOF ITALIAN SUB KIT          7.99 A *
        CARPRD CELLO RED LNK         6.49 A *
        PRODUCE
        CELERY                W      2.19 A *
        BUNCH RADISHES        W      1.99 A *
        CHINESE CABBAGE       W
        1.26 lb @    2.89/lb         3.64 A *
        Tax Paid
        0.00 Tx 1     63.56     0.00
        7.00 Tx 2     45.46     3.18
        TOTAL TAX               3.18
        23 BALANCE DUE         112.20
        Debit Card            ${'$'}112.20
        RRN:                   515836
    """.trimIndent()

    /** text, quantity (thousandths), unit price, line total, letter, section */
    private val expected = listOf(
        listOf("FL 2 MILK GALLON", 1000L, 3_19L, 3_19L, "A", "FROZEN/DAIRY"),
        listOf("FL SALTED BUTTER QTR", 1000L, 2_99L, 2_99L, "A", "FROZEN/DAIRY"),
        listOf("FL HEAVY WHIP CREAM", 1000L, 5_49L, 5_49L, "A", "FROZEN/DAIRY"),
        listOf("MODELA CHLD LM NA 6C", 2000L, 11_49L, 22_98L, "B", "GROCERY"),
        listOf("FL LEMON JUICE", 1000L, 1_99L, 1_99L, "A", "GROCERY"),
        listOf("FL RANCH DP DRESS PK", 1000L, 59L, 59L, "A", "GROCERY"),
        listOf("FL GRN TEA BAGS 40CT", 2000L, 2_79L, 5_58L, "A", "GROCERY"),
        listOf("FL CINNI MINI CRUNCH", 1000L, 2_49L, 2_49L, "A", "GROCERY"),
        listOf("FOOD LION LIME JUICE", 1000L, 1_99L, 1_99L, "A", "GROCERY"),
        listOf("KELLOGG FUN PAK", 1000L, 5_49L, 5_49L, "A", "GROCERY"),
        listOf("TWIN EARL GREY TEA", 1000L, 4_49L, 4_49L, "A", "GROCERY"),
        listOf("SIPPIE BOWLS", 1000L, 4_49L, 4_49L, "B", "GROCERY"),
        listOf("ATHLC LITE NA 12CN", 1000L, 17_99L, 17_99L, "B", "GROCERY"),
        listOf("CELS SPRK WLD BRRY", 1000L, 1_49L, 1_49L, "A", "GROCERY"),
        listOf("CELS SPRK PCH VIBES", 1000L, 1_49L, 1_49L, "A", "GROCERY"),
        listOf("SLVRFLOSS KRAUT 32Z", 1000L, 3_99L, 3_99L, "A", "MEAT"),
        listOf("LOF ITALIAN SUB KIT", 1000L, 7_99L, 7_99L, "A", "MEAT"),
        listOf("CARPRD CELLO RED LNK", 1000L, 6_49L, 6_49L, "A", "MEAT"),
        listOf("CELERY", 1000L, 2_19L, 2_19L, "A", "PRODUCE"),
        listOf("BUNCH RADISHES", 1000L, 1_99L, 1_99L, "A", "PRODUCE"),
        listOf("CHINESE CABBAGE", 1260L, 2_89L, 3_64L, "A", "PRODUCE"),
    )

    /** The A items stay at their shelf price; the B items' 3.18 is shared by price, to the cent. */
    private val withTax = expected.map { it[3] as Long }.toMutableList().also { w ->
        w[3] = 22_98L + 1_61; w[11] = 4_49L + 31; w[12] = 17_99L + 1_26
    }

    private fun check(p: ReceiptParser.Parsed) {
        assertEquals<List<Any?>>(expected.map { it[0] }, p.items.map { it.text }, "every item, named as printed")
        assertEquals<List<Any?>>(expected.map { it[1] }, p.items.map { it.quantityMilli })
        assertEquals<List<Any?>>(expected.map { it[2] }, p.items.map { it.unitPrice })
        assertEquals<List<Any?>>(expected.map { it[3] }, p.items.map { it.amount })
        assertEquals<List<Any?>>(expected.map { it[4] }, p.items.map { it.taxLetter }, "the letters, W and M never one")
        assertEquals<List<Any?>>(expected.map { it[5] }, p.items.map { it.section })
        assertEquals(23_000L, p.items.sumOf { it.quantityMilli } - 1260 + 1000, "23 things, as the receipt counts them")
        assertEquals(3_18L, p.tax, "the Tax box")
        assertEquals("", p.store, "not on the photo: the user fills it in")
        assertNull(p.date)
        assertTrue("store" in p.lowConfidence && "date" in p.lowConfidence)
        // none of the savings, tax, total or card lines is an item
        assertTrue(p.items.none { it.text.contains("saved", true) || it.text.contains("Tx", true) || it.text.contains("TAX") || it.text.contains("BALANCE") })
        // the tax rows' taxable amounts say which letter is which: A (63.56) untaxed, B (45.46) at 7%, no question
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved, "nothing to ask")
        assertEquals(withTax, w.withTax)
        assertEquals(112_20L, p.total)
        assertEquals(p.total, w.withTax.sum(), "the items with tax add up to the total")
    }

    @Test fun theRealReceiptIsReadItemByItem() = check(ReceiptParser.parseLines(receipt.lines().map { Line(it, 90) }))

    @Test fun asThePhonesReaderReadItsBottomIn193() {
        // 1.9.3's form shows these: a bar and a comma in the first tax row, the TOTAL TAX row's ".18" and the tax rows'
        // last column on lines of their own
        val bottom = receipt.substringBefore("Tax Paid") + listOf(
            "Tax Paid", "| 0,00 Tx 1 63.56", "0.00", "7.00 Tx 2 45.46", "3.18", "TOTAL TAX .18 3.18", "23 BALANCE DUE 112.20", "Debit Card \$112.20",
        ).joinToString("\n")
        check(ReceiptParser.parseLines(bottom.lines().map { Line(it, 90) }))
    }

    /**
     * The photo as the phone's own scan reads it (cleaned up, then its text recognition, on an Android 17 emulator): the
     * marks come out as "Ww", "Ax", "20 811.49", "Txt" and "7:00", and one price is misread (5.49 as 9.49). Every line is
     * still an item, the tax is still 3.18 and settled, and the misread price is what the form flags.
     */
    @Test fun asThePhonesTextRecognitionReadsThePhoto() {
        val read = listOf(
            "EROZEN/DAIRY \"",
            "FL 2 MILK GALLON Ww 3.19 A *",
            "FL SALTED BUTTER QTR 2.99 A *",
            "FL HEAVY WHIP CREAM 9.49 A",
            "GROCERY",
            "MODELA CHLD LM NA 6C",
            "20 811.49 22.98 B",
            "FL LEMON JUICE 1.99 A *",
            "FL RANCH DP DRESS PK 0.59 A *",
            "FL GRN TEA BAGS 40CT",
            "28 2.79 5.58 A x",
            "FL CINNI MINI CRUNCH 2.49 A x",
            "© FOOD LION LIME JUICE 1.99 A x",
            "KELLOGG FUN PAK 5.49 A x",
            "TWIN EARL GREY TEA 4.49 Ax",
            "SIPPIE BOWLS 4.49 B",
            "ATHLC LITE NA 12CN 17.99 B",
            "CELS SPRK WLD BRRY M 1.49 Ax",
            "You saved 1.50",
            "CELS SPRK PCH VIBES M 1.49 Ax",
            "You saved 1.50",
            "MEAT",
            "SLVRFLOSS KRAUT 322 3.99 Ax",
            "LOF ITALIAN SUB KIT 7.99 Ax",
            "CARPRD CELLO RED LNK 6.49 A x",
            "PRODUCE",
            "CELERY W 2.19 Ax",
            "BUNCH RADISHES W 1.99 A x",
            "CHINESE CABBAGE W",
            "1.26 lb @ 2.89/1b 3.64 Ax",
            "Tax Paid",
            "0.00 Txt 63.51",
            "7:00 Tx 2 45.46 a!",
            "TOTAL T. 7",
            "a 3.18",
            "23 BALANCE DUE",
            "; 112.20",
            "ecard \$112.29",
            "ne",
            "BRN :",
        )
        val p = ReceiptParser.parseLines(read.map { Line(it, 90) })
        assertEquals(21, p.items.size)
        assertEquals<List<Any?>>(expected.map { it[0] }.toMutableList().also { it[15] = "SLVRFLOSS KRAUT 322" }, p.items.map { it.text })
        assertEquals<List<Any?>>(expected.map { it[1] }, p.items.map { it.quantityMilli })
        assertEquals<List<Any?>>(expected.map { it[4] }, p.items.map { it.taxLetter })
        assertEquals<List<Any?>>(expected.map { it[3] }.toMutableList().also { it[2] = 9_49L }, p.items.map { it.amount })
        assertEquals(3_18L, p.tax)
        assertEquals(112_20L, p.total, "BALANCE DUE with its amount on the next line")
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved)
        assertTrue("items" in p.lowConfidence && "total" in p.lowConfidence, "the misread price doesn't add up: the form says so")
    }

    @Test fun thePhotoAndThe193FormAreKeptBesideTheTest() {
        val dir = listOf(File("src/jvmTest/resources/receipts/food-lion"), File("shared/src/jvmTest/resources/receipts/food-lion")).first { it.isDirectory }
        assertTrue(File(dir, "food-lion-receipt.webp").length() > 10_000)
        assertTrue(File(dir, "food-lion-scan-1.9.3.png").length() > 10_000)
    }
}
