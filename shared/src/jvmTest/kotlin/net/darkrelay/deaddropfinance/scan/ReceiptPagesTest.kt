package net.darkrelay.deaddropfinance.scan

import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.domain.ReceiptParser.Line
import net.darkrelay.deaddropfinance.domain.ReceiptTax
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.5: what the page reader does for a small, faint receipt picture: enlarges it before reading, and when the balance
 * due or the total tax came out with no amount, takes the amount from a second, scattered read: for the balance, the one
 * the items and tax add up to. Invented lines, in the shape the real Food Lion picture reads to.
 */
class ReceiptPagesTest {

    private val read = listOf(
        "SAMPLE BREAD 3.00 A *", "SAMPLE SOAP 10.00 B", "Tax Paid", "0.00 Tet 3.00 0.00", "9700 Tx 2 10.00 1.00", "TOTAL TAX onth", "23 BALANCE DUE ioe", "Debit Card $1",
    ).map { Line(it, 80) }

    private val scattered = listOf("TOTAL TAX", "0.70", "0.70", "23 BALANCE DUE", "13.79", "Debit Card", "$13.70").map { Line(it, 90) }

    @Test fun theTotalTaxAndTheBalanceAreTakenFromTheScatteredReadWhenTheyreMissing() {
        val lines = ReceiptPages.withAmountsFound(read) { scattered }
        // the total tax right after its words; the balance the items and tax come to (13.70), not the misread 13.79
        assertEquals("0.70", lines[lines.indexOfFirst { it.text.startsWith("TOTAL TAX") } + 1].text)
        assertEquals("13.70", lines[lines.indexOfFirst { it.text.contains("BALANCE DUE") } + 1].text)
        val p = ReceiptParser.parseLines(lines)
        assertEquals(70L, p.tax)
        assertEquals(13_70L, p.total)
        assertTrue("total" !in p.notes, "read from the receipt, not worked out: ${p.notes}")
        // the group row's misread tax (1.00) gives way to the receipt's total tax: B carries 0.70, and it adds up
        val w = ReceiptTax.work(p.items.map { ReceiptTax.In(it.taxLetter, it.amount) }, p.taxLines, p.tax)
        assertTrue(w.resolved)
        assertEquals(listOf(3_00L, 10_70L), w.withTax)
    }

    @Test fun nothingIsAskedOfTheScatteredReadWhenTheAmountsWereRead() {
        val clean = listOf("SAMPLE BREAD 3.00 A", "TOTAL TAX 0.00", "BALANCE DUE 3.00").map { Line(it, 90) }
        var asked = false
        assertEquals(clean, ReceiptPages.withAmountsFound(clean) { asked = true; emptyList() })
        assertTrue(!asked)
    }

    @Test fun aBalanceFoundThatDoesntAddUpIsMarkedDoubtful() {
        val lines = ReceiptPages.withAmountsFound(read) { listOf(Line("23 BALANCE DUE", 90), Line("19.99", 90)) }
        val p = ReceiptParser.parseLines(lines)
        assertEquals(19_99L, p.total)
        assertEquals("total may be misread", p.notes["total"])
    }

    @Test fun aSmallPageIsEnlargedByAWholeNumber() {
        val g = ImageCleanup.Gray(2, 2, intArrayOf(0, 255, 255, 0))
        val big = ImageCleanup.scaleUp(g, 3)
        assertEquals(6 to 6, big.width to big.height)
        assertEquals(0, big[0, 0]); assertEquals(255, big[5, 0]); assertEquals(0, big[5, 5])
        assertTrue(big[2, 0] in 1..254, "in between, blended")
    }
}
