package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.domain.ReceiptParser.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receipt reading on invented receipt text: several layouts, long receipts, tax, discounts, quantities. */
class ReceiptParserTest {

    private fun lines(s: String, conf: Int = 95) = s.trimIndent().lines().map { Line(it, conf) }
    private fun parse(s: String) = ReceiptParser.parse(listOf(lines(s)))

    @Test fun groceryWithWeightQuantityCouponAndTaxFlags() {
        val r = parse(
            """
            SAMPLE FRESH MARKET
            123 EXAMPLE AVE
            ANYTOWN, ST 00000
            03/14/2030  14:22
            BANANAS 1.25 LB @ 0.69/LB   0.86 F
            SAMPLE BREAD 012345678   3.49 F
            SAMPLE SODA 2 @ 1.99     3.98 T
            COUPON SAMPLE SODA       0.50-
            SUBTOTAL                 7.83
            TAX                      0.32
            TOTAL                    8.15
            VISA ****1234            8.15
            CHANGE                   0.00
            """,
        )
        assertEquals("SAMPLE FRESH MARKET", r.store)
        assertEquals(LocalDate.of(2030, 3, 14), r.date)
        assertEquals(8_15L, r.total)
        assertEquals(7_83L, r.subtotal)
        assertEquals(32L, r.tax)
        assertEquals(ReceiptKind.PURCHASE, r.kind)
        assertEquals(listOf("BANANAS", "SAMPLE BREAD", "SAMPLE SODA", "COUPON SAMPLE SODA"), r.items.map { it.text })
        assertEquals(listOf(86L, 3_49L, 3_98L, -50L), r.items.map { it.amount })
        assertEquals(1250L, r.items[0].quantityMilli)
        assertEquals(69L, r.items[0].unitPrice)
        assertEquals(2000L, r.items[2].quantityMilli)
        assertEquals(1_99L, r.items[2].unitPrice)
        assertEquals("sample bread", r.items[1].name)
        assertTrue("it all adds up: nothing to check", r.lowConfidence.isEmpty())
    }

    @Test fun restaurantWithTipAndMonthNameDate() {
        val r = parse(
            """
            Sample Grill & Tavern
            Mar 14, 2030 7:45 PM
            Server: Sample
            1 x Sample Burger       12.00
            2 x Sample Iced Tea      5.00
            Subtotal                17.00
            Sales Tax 8%             1.36
            Tip                      3.40
            Total                   21.76
            """,
        )
        assertEquals("Sample Grill & Tavern", r.store)
        assertEquals(LocalDate.of(2030, 3, 14), r.date)
        assertEquals(21_76L, r.total)
        assertEquals(1_36L, r.tax)
        assertEquals(3_40L, r.tip)
        assertEquals(listOf("Sample Burger" to 1000L, "Sample Iced Tea" to 2000L), r.items.map { it.text to it.quantityMilli })
        assertEquals(2_50L, r.items[1].unitPrice)
        assertTrue(r.lowConfidence.isEmpty())
    }

    @Test fun descriptionThenQuantityLineTwoTaxLinesAndAmountDue() {
        val r = parse(
            """
            EXAMPLE HARDWARE CO
            2030-03-14
            SAMPLE HAMMER
              1 @ 14.99            14.99
            SAMPLE NAILS BOX
              3 @ 2.50              7.50
            SUB-TOTAL              22.49
            TAX 1                   1.12
            TAX 2                   0.22
            AMOUNT DUE             23.83
            """,
        )
        assertEquals(listOf("SAMPLE HAMMER", "SAMPLE NAILS BOX"), r.items.map { it.text })
        assertEquals(listOf(1000L, 3000L), r.items.map { it.quantityMilli })
        assertEquals(1_34L, r.tax)
        assertEquals(23_83L, r.total)
        assertTrue(r.lowConfidence.isEmpty())
    }

    @Test fun quantityLineUnderAnItemThatHasItsPrice() {
        val r = parse(
            """
            SAMPLE CORNER SHOP
            03/14/30
            SAMPLE JUICE            5.97
               3 @ 1.99
            SAMPLE GUM              1.25
            TOTAL                   7.22
            """,
        )
        assertEquals(3000L, r.items[0].quantityMilli)
        assertEquals(1_99L, r.items[0].unitPrice)
        assertEquals(LocalDate.of(2030, 3, 14), r.date)
        assertNull(r.tax)
        assertEquals(7_22L, r.total)
    }

    @Test fun aReturnReceiptIsMarkedAndPositive() {
        val r = parse(
            """
            SAMPLE OUTLET
            RETURN
            03/20/2030
            SAMPLE LAMP            -24.99
            TAX                     -2.00
            REFUND TOTAL           -26.99
            """,
        )
        assertEquals(ReceiptKind.RETURN, r.kind)
        assertEquals(26_99L, r.total)
        assertEquals(2_00L, r.tax)
        assertEquals(24_99L, r.items.single().amount)
    }

    @Test fun aLongReceiptInTwoOverlappingPhotos() {
        val items = (1..30).map { "SAMPLE ITEM ${('A' + it % 26)}${('A' + it / 26)}   ${it}.00" }
        val all = (1..30).sum() * 100L
        val page1 = listOf("SAMPLE MEGA STORE", "03/14/2030") + items.take(20)
        val page2 = items.subList(17, 30) + listOf("SUBTOTAL   ${all / 100}.00", "TAX   1.00", "TOTAL   ${all / 100 + 1}.00")
        val r = ReceiptParser.parse(listOf(page1.map { Line(it) }, page2.map { Line(it) }))
        assertEquals("the overlap is read once", 30, r.items.size)
        assertEquals(all, r.items.sumOf { it.amount })
        assertEquals(all + 1_00, r.total)
        assertTrue(r.lowConfidence.isEmpty())
    }

    @Test fun doubtfulLinesAndSumsThatDontAddUpAreMarked() {
        val r = ReceiptParser.parse(
            listOf(
                listOf(
                    Line("SAMPLE KIOSK", 40), Line("14-MAR-2030", 90), Line("SAMPLE PRETZEL   4.50", 90), Line("SAMPLE WATER   2.00", 90),
                    Line("TOTAL   7.90", 35),
                ),
            ),
        )
        assertEquals(LocalDate.of(2030, 3, 14), r.date)
        assertTrue(r.lowConfidence.containsAll(listOf("store", "total", "items")))
        assertFalse("date" in r.lowConfidence)
    }

    @Test fun noTotalLineIsWorkedOutAndMarked() {
        val r = parse(
            """
            SAMPLE STAND
            14/03/2030
            SAMPLE PIE   6.00
            SAMPLE TEA   2.50
            TAX   0.68
            """,
        )
        assertEquals("day first when the first number can't be a month", LocalDate.of(2030, 3, 14), r.date)
        assertEquals(9_18L, r.total)
        assertTrue("total" in r.lowConfidence)
    }

    @Test fun commonDigitMisreadsInPrices() {
        val r = parse(
            """
            SAMPLE DAIRY
            03/14/2030
            SAMPLE MILK   3.4O
            SAMPLE EGGS   l2.00
            TOTAL   15.40
            """,
        )
        assertEquals(listOf(3_40L, 12_00L), r.items.map { it.amount })
        assertEquals(15_40L, r.total)
    }

    @Test fun cleanNames() {
        assertEquals("sample soda pk", ReceiptParser.cleanName("SAMPLE SODA 12PK #44"))
        assertNull(ReceiptParser.findDate("NO DATE HERE 12.34"))
    }
}
