package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.domain.ReceiptParser.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tax letters (1.9.3): the letter at the end of an item line is its own field, never part of the name or the price; the
 * tax lines at the bottom are matched to the letters; each item's price with tax adds up to the receipt's total. All on
 * invented receipt text.
 */
class ReceiptTaxLettersTest {

    private fun parse(s: String) = ReceiptParser.parseLines(s.trimIndent().lines().map { Line(it, 95) })

    private val oneRate = """
        SAMPLE MART
        08/14/2030
        LIME JUICE 1.99 A
        BREAD 2.50
        MILK 3.00 A
        SUBTOTAL 7.49
        TAX A 6.000% 0.47
        TOTAL 7.96
    """

    @Test fun theLetterIsItsOwnFieldNotPartOfTheNameOrThePrice() {
        val p = parse(oneRate)
        assertEquals(listOf("LIME JUICE", "BREAD", "MILK"), p.items.map { it.text })
        assertEquals(listOf("lime juice", "bread", "milk"), p.items.map { it.name })
        assertEquals(listOf(1_99L, 2_50L, 3_00L), p.items.map { it.amount })
        assertEquals(listOf("A", "", "A"), p.items.map { it.taxLetter })
    }

    @Test fun aLetterBeforeThePriceIsTakenWhenATaxLineNamesIt() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            MILK A 3.00
            VITAMIN C 4.00
            SUBTOTAL 7.00
            TAX A 6% 0.18
            TOTAL 7.18
            """,
        )
        assertEquals(listOf("MILK", "VITAMIN"), p.items.map { it.text }.map { it.removeSuffix(" C") }, "a stray capital never stays in a name")
        assertEquals("A", p.items[0].taxLetter)
        assertEquals("", p.items[1].taxLetter, "C is a word of the name here: no tax line names it")
    }

    @Test fun theTaxLinesAreReadWithTheirLetterAndRate() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00 A
            GADGET 5.00 B
            SUBTOTAL 15.00
            A TAX 6.000% 0.60
            TAX B 10% 0.50
            TOTAL 16.10
            """,
        )
        assertEquals(
            listOf(ReceiptParser.TaxLine(60, "A", 6000), ReceiptParser.TaxLine(50, "B", 10_000)), p.taxLines,
        )
        assertEquals(1_10L, p.tax)
    }

    @Test fun oneRateGivesTheTaxedItemsTheirShareAndTheRestStaysAtShelfPrice() {
        val p = parse(oneRate)
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved)
        assertEquals(2_50L, w.withTax[1], "no letter: shelf price")
        // 0.47 over 1.99 and 3.00, by price, to the cent
        assertEquals(listOf(1_99L + 19, 2_50L, 3_00L + 28), w.withTax)
        assertEquals(p.total, w.withTax.sum(), "the items with tax add up to the receipt's total")
        assertEquals(0L, w.unassigned)
    }

    @Test fun twoRatesEachLetterGetsItsOwnLine() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00 A
            GADGET 5.00 B
            GIZMO 5.00 B
            PLAIN 2.00
            SUBTOTAL 22.00
            TAX A 6% 0.60
            TAX B 10% 1.00
            TOTAL 23.60
            """,
        )
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertEquals(listOf(10_60L, 5_50L, 5_50L, 2_00L), w.withTax)
        assertEquals(p.total, w.withTax.sum())
    }

    @Test fun oneTaxLineWithoutALetterGoesToTheOnlyLetter() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00 T
            GADGET 5.00 T
            SUBTOTAL 15.00
            TAX 0.90
            TOTAL 15.90
            """,
        )
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertEquals(listOf(10_60L, 5_30L), w.withTax)
    }

    @Test fun aLineWithoutALetterIsMatchedByItsRateWhenOnlyOneSetOfLettersFits() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00 T
            APPLES 4.00 F
            SUBTOTAL 14.00
            TAX 6% 0.60
            TOTAL 14.60
            """,
        )
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved)
        assertEquals(listOf(10_60L, 4_00L), w.withTax, "6% of the T items is the tax: F is untaxed")
    }

    @Test fun lettersThatCantBeMatchedAreAQuestionNotAGuess() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00 T
            APPLES 4.00 F
            SUBTOTAL 14.00
            TAX 0.60
            TOTAL 14.60
            """,
        )
        val ins = p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }
        val w = ReceiptTax.work(ins, p.taxLines, p.tax)
        assertFalse(w.resolved)
        assertEquals(setOf("F", "T"), w.needsChoice)
        assertEquals(listOf(10_00L, 4_00L), w.withTax, "shelf prices until the user answers")
        // the user marks the widget taxed and the apples not: the tax is on the widget
        val answered = ReceiptTax.work(listOf(ins[0].copy(taxed = true), ins[1].copy(taxed = false)), p.taxLines, p.tax)
        assertTrue(answered.resolved)
        assertEquals(listOf(10_60L, 4_00L), answered.withTax)
        assertEquals(p.total, answered.withTax.sum())
        // an answer for only one of them is still a question
        assertFalse(ReceiptTax.work(listOf(ins[0].copy(taxed = true), ins[1]), p.taxLines, p.tax).resolved)
    }

    /** 1.9.4 (the owner's answer): tax above zero and no letters asks which items are taxed; zero tax asks nothing. */
    @Test fun aReceiptWithTaxButNoLettersAsksWhichItemsAreTaxed() {
        val p = parse(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00
            SUBTOTAL 10.00
            TAX 0.60
            TOTAL 10.60
            """,
        )
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertFalse(w.resolved)
        assertEquals(setOf(""), w.needsChoice)
        assertFalse(w.hasLetters)
        assertEquals(listOf(10_00L), w.withTax, "shelf prices until it's answered")
        val answered = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount, taxed = true) }, p.taxLines, p.tax)
        assertTrue(answered.resolved && answered.perItem)
        assertEquals(listOf(10_60L), answered.withTax)
        assertTrue(ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, 0).resolved, "no tax: nothing asked")
    }

    @Test fun theSharesAlwaysAddUpToTheTaxToTheCent() {
        for (tax in listOf(1L, 7L, 33L, 101L)) for (ws in listOf(listOf(1_99L, 3_00L, 7L), listOf(100L, 100L, 100L), listOf(1L, 1L))) {
            assertEquals(tax, ReceiptTax.largestRemainder(tax, ws).sum())
        }
        assertEquals(listOf(1L, 1L, 0L), ReceiptTax.largestRemainder(2, listOf(0, 0, 0)))
    }
}
