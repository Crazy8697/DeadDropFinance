package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.domain.ReceiptParser.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 1.9.4: one reader for many stores' layouts, on invented receipts written in the styles of several kinds of store
 * (a supercenter, a discount grocer, a department store, a supermarket with savings, a dollar store): tax letters with
 * and without marks, letters before the price, quantity and weight lines, savings and coupons, section headers, each
 * kind of tax block, and a receipt with tax but no letters.
 */
class ReceiptStoresTest {

    private fun parse(s: String) = ReceiptParser.parseLines(s.trimIndent().lines().map { Line(it, 95) })
    private fun work(p: ReceiptParser.Parsed, marks: List<Boolean?>? = null) =
        ReceiptTax.work(p.items.mapIndexed { i, it -> ReceiptTax.In(it.taxLetter, it.amount, marks?.get(i)) }, p.taxLines, p.tax)

    @Test fun aSupercenterWithCodesLettersAfterThePriceAndATaxRowWithItsRate() {
        val p = parse(
            """
            SAMPLE SUPERCENTER
            ST# 01234 OP# 001 TE# 01 TR# 0123
            SAMPLE CEREAL 001234567890 F 3.48 N
            SAMPLE SOAP 009876543210 2.97 X
            SAMPLE BATTERIES 004444444444 9.88 X
            SUBTOTAL 16.33
            TAX 1 7.000 % 0.90
            TOTAL 17.23
            DEBIT TEND 17.23
            CHANGE DUE 0.00
            08/14/30 10:22:33
            """,
        )
        assertEquals("SAMPLE SUPERCENTER", p.store)
        assertEquals(LocalDate.of(2030, 8, 14), p.date)
        assertEquals(listOf("SAMPLE CEREAL", "SAMPLE SOAP", "SAMPLE BATTERIES"), p.items.map { it.text })
        assertEquals(listOf("N", "X", "X"), p.items.map { it.taxLetter })
        assertEquals(90L, p.tax)
        assertEquals(17_23L, p.total)
        // no letter is named: the one set of letters whose items at 7% come to 0.90 is X
        val w = work(p)
        assertTrue(w.resolved)
        assertEquals(listOf(3_48L, 3_18L, 10_57L), w.withTax)
        assertEquals(p.total, w.withTax.sum())
        assertTrue(p.lowConfidence.isEmpty(), "${p.lowConfidence}")
    }

    @Test fun aDiscountGrocerWithATaxTablePerLetter() {
        val p = parse(
            """
            SAMPLE GROCER
            08/14/2030
            SAMPLE BREAD 2.29 A
            SAMPLE CHIPS 3.49 B
            SAMPLE SODA 4.00 B
            SUBTOTAL 9.78
            A 0.00% 2.29 0.00
            B 7.00% 7.49 0.52
            TOTAL 10.30
            VISA 10.30
            """,
        )
        assertEquals(listOf("A", "B", "B"), p.items.map { it.taxLetter })
        assertEquals(listOf(ReceiptParser.TaxLine(0, "A", 0, 2_29), ReceiptParser.TaxLine(52, "B", 7000, 7_49)), p.taxLines)
        assertEquals(52L, p.tax)
        val w = work(p)
        assertEquals(listOf(2_29L, 3_73L, 4_28L), w.withTax)
        assertEquals(p.total, w.withTax.sum())
    }

    @Test fun aDepartmentStoreWithSectionsLettersBeforeThePriceAndATaxableAmount() {
        val p = parse(
            """
            SAMPLE DEPARTMENT STORE
            08/14/2030
            GROCERY
            SAMPLE PASTA 012345678 N ${'$'}1.99
            SAMPLE SAUCE 012345679 N ${'$'}2.49
            HOME
            SAMPLE TOWELS 012345670 T ${'$'}8.00
            SUBTOTAL ${'$'}12.48
            T = SAMPLE STATE TAX 6.2500 on ${'$'}8.00 ${'$'}0.50
            TOTAL ${'$'}12.98
            """,
        )
        assertEquals(listOf("SAMPLE PASTA", "SAMPLE SAUCE", "SAMPLE TOWELS"), p.items.map { it.text })
        assertEquals(listOf("GROCERY", "GROCERY", "HOME"), p.items.map { it.section })
        // T before the price is a letter, as the tax row names it; N isn't (no tax row names it)
        assertEquals(listOf("", "", "T"), p.items.map { it.taxLetter })
        assertEquals(listOf(ReceiptParser.TaxLine(50, "T", null, 8_00)), p.taxLines)
        assertEquals(listOf(1_99L, 2_49L, 8_50L), work(p).withTax)
        assertFalse(p.items.any { it.text == "GROCERY" || it.text == "HOME" })
    }

    @Test fun aSupermarketWithAWeightSavingsAndACoupon() {
        val p = parse(
            """
            SAMPLE FOODS
            08/14/2030
            PRODUCE
            BANANAS
            2.10 lb @ 0.59 /lb 1.24 F
            SAMPLE CHEESE 4.99 F
            SC SAMPLE SAVINGS 1.00-
            SAMPLE PAPER TOWELS 6.99 T
            COUPON SAMPLE PAPER 0.50-
            YOU SAVED 1.50
            T TAX 7.000% 0.45
            BALANCE DUE 12.17
            """,
        )
        assertEquals(listOf("BANANAS", "SAMPLE CHEESE", "SAMPLE PAPER TOWELS"), p.items.map { it.text })
        assertEquals(2100L, p.items[0].quantityMilli)
        assertEquals(59L, p.items[0].unitPrice)
        // the savings and the coupon come off their item: it carries what was paid
        assertEquals(listOf(1_24L, 3_99L, 6_49L), p.items.map { it.amount })
        assertEquals(listOf("F", "F", "T"), p.items.map { it.taxLetter })
        assertEquals(12_17L, p.total)
        val w = work(p)
        assertEquals(listOf(1_24L, 3_99L, 6_94L), w.withTax)
        assertEquals(p.total, w.withTax.sum())
    }

    @Test fun aDollarStoreWithQuantitiesAndTaxButNoLettersAsks() {
        val p = parse(
            """
            SAMPLE DOLLAR STORE
            08/14/2030 12:01
            SAMPLE GUM
            QTY 2 @ 1.25 2.50
            SAMPLE CANDY 2 x 1.00 2.00
            SAMPLE CLEANER 3.50
            SUBTOTAL 8.00
            SALES TAX 6.5% 0.52
            TOTAL 8.52
            CASH 10.00
            CHANGE 1.48
            """,
        )
        assertEquals(listOf("SAMPLE GUM", "SAMPLE CANDY", "SAMPLE CLEANER"), p.items.map { it.text })
        assertEquals(listOf(2000L, 2000L, 1000L), p.items.map { it.quantityMilli })
        assertEquals(listOf(1_25L, 1_00L, 3_50L), p.items.map { it.unitPrice })
        assertEquals(52L, p.tax)
        // tax and no letters: which items are taxed is asked, never guessed
        val asked = work(p)
        assertFalse(asked.resolved)
        assertEquals(setOf(""), asked.needsChoice)
        val answered = work(p, listOf(false, false, true))
        assertTrue(answered.resolved && answered.perItem)
        assertEquals(listOf(2_50L, 2_00L, 4_02L), answered.withTax)
        assertEquals(p.total, answered.withTax.sum())
        // no tax at all: nothing to ask
        val none = ReceiptTax.work(p.items.map { ReceiptTax.In("", it.amount) }, emptyList(), 0)
        assertTrue(none.resolved)
    }

    @Test fun flagsThatArentTaxLettersAndMarksAfterTheLetter() {
        val p = parse(
            """
            SAMPLE MARKET
            08/14/2030
            SAMPLE APPLES W 2.19 A *
            SAMPLE SODA M 1.49 A*
            SAMPLE PEARS 1.99 Ax
            SAMPLE RICE 3.00 EA
            SAMPLE JAM 4.00 B #
            TAX A 0% 0.00
            TAX B 5% 0.20
            TOTAL 12.87
            """,
        )
        assertEquals(listOf("SAMPLE APPLES", "SAMPLE SODA", "SAMPLE PEARS", "SAMPLE RICE", "SAMPLE JAM"), p.items.map { it.text })
        assertEquals(listOf("A", "A", "A", "", "B"), p.items.map { it.taxLetter })
        assertEquals(20L, p.tax)
        assertEquals(listOf(2_19L, 1_49L, 1_99L, 3_00L, 4_20L), work(p).withTax)
    }

    @Test fun aGlobbedOrMisreadAtSignIsAQuantityOnlyWhenTheTotalChecks() {
        val p = parse(
            """
            SAMPLE MARKET
            08/14/2030
            SAMPLE YOGURT
            20 11.49 22.98 B
            SAMPLE TEA
            28 2.79 5.58 A x
            SAMPLE CHIPS
            3 e 1.25 3.75 A
            TOTAL 32.31
            """,
        )
        assertEquals(listOf(2000L, 2000L, 3000L), p.items.map { it.quantityMilli })
        assertEquals(listOf(11_49L, 2_79L, 1_25L), p.items.map { it.unitPrice })
        assertEquals(listOf("B", "A", "A"), p.items.map { it.taxLetter })
    }
}
