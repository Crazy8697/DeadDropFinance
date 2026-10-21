package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.domain.CsvImport.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic bank exports only (app/src/test/resources/csv): invented names and amounts. */
class CsvImportTest {

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("csv/$name")!!.readText()

    @Test fun signedAmountsIsoDates() {
        val text = fixture("signed_iso.csv")
        val l = CsvImport.detect(text)
        assertEquals(',', l.delimiter)
        assertEquals(0, l.headerRow)
        assertEquals(listOf(Role.DATE, Role.DESCRIPTION, Role.AMOUNT, Role.BALANCE), l.roles)
        assertEquals("yyyy-MM-dd", l.dateFormat)
        assertFalse(l.decimalComma)
        val r = CsvImport.read(text, l)
        assertEquals(7, r.rows.size)
        assertEquals(listOf(7), r.bad.map { it.rowNumber })       // the 13th-month row, by its line in the file
        val shop = r.rows.first { it.description.startsWith("Corner") }
        assertEquals("Corner Grocer, Main St", shop.description) // quoted comma kept
        assertEquals(-82_15L, shop.amount)
        assertEquals(LocalDate.of(2030, 1, 3), shop.date)
    }

    @Test fun debitAndCreditColumnsWithLinesAboveTheHeader() {
        val text = fixture("debit_credit_us.csv")
        val l = CsvImport.detect(text)
        assertEquals(listOf(Role.DATE, Role.DESCRIPTION, Role.DEBIT, Role.CREDIT, Role.BALANCE, Role.MEMO), l.roles)
        assertEquals("MM/dd/yyyy", l.dateFormat)
        val r = CsvImport.read(text, l)
        assertEquals(listOf(150_000L, -8_215L, 5_000L, -1_299L), r.rows.map { it.amount })
        assertEquals("Bookshop \"The Page\"", r.rows[3].description)     // doubled quotes
        assertEquals("Water bill", r.rows[2].memo)                         // a note: shown as a hint, never assigns
        assertEquals(listOf(9), r.bad.map { it.rowNumber })                 // no amount at all
        assertTrue(r.bad.single().reason.contains("amount"))
    }

    @Test fun semicolonsDecimalCommaAndDayFirstDates() {
        val text = fixture("semicolon_eu.csv")
        val l = CsvImport.detect(text)
        assertEquals(';', l.delimiter)
        assertTrue(l.decimalComma)
        assertEquals("dd.MM.yyyy", l.dateFormat)
        val r = CsvImport.read(text, l)
        assertEquals(listOf(-123_456L, 200_000L, -1_250L), r.rows.map { it.amount })
        assertEquals(LocalDate.of(2030, 1, 3), r.rows[0].date)
        assertEquals(listOf(5), r.bad.map { it.rowNumber })
    }

    @Test fun ambiguousDatesAskForConfirmation() {
        val (fmt, ambiguous) = CsvImport.detectDateFormat(listOf("01/02/2030", "03/04/2030"))
        assertEquals("MM/dd/yyyy", fmt)
        assertTrue(ambiguous)
        val (fmt2, amb2) = CsvImport.detectDateFormat(listOf("01/02/2030", "12/25/2030"))
        assertEquals("MM/dd/yyyy", fmt2)
        assertFalse(amb2)
        assertEquals("dd/MM/yyyy", CsvImport.detectDateFormat(listOf("25/12/2030", "01/02/2030")).first)
    }

    @Test fun amountsParseToExactCents() {
        assertEquals(123_456L, CsvImport.parseCents("$1,234.56", false))
        assertEquals(-1_234L, CsvImport.parseCents("(12.34)", false))
        assertEquals(-1_234L, CsvImport.parseCents("12.34-", false))
        assertEquals(-1_234L, CsvImport.parseCents("12.34 DR", false))
        assertEquals(1_234L, CsvImport.parseCents("12.34 CR", false))
        assertEquals(-1_234L, CsvImport.parseCents("-$12.34", false))
        assertEquals(1_200L, CsvImport.parseCents("12", false))
        assertEquals(123_456L, CsvImport.parseCents("1.234,56", true))
        assertNull(CsvImport.parseCents("12.345", false))
        assertNull(CsvImport.parseCents("abc", false))
    }

    @Test fun duplicatesAgainstTheAccountAndWithinTheFile() {
        val text = fixture("signed_iso.csv")
        val r = CsvImport.read(text, CsvImport.detect(text))
        val existing = listOf(F.tx("old", "acct", 1500_00, LocalDate.of(2030, 1, 2), payee = "SAMPLE PAYROLL CO."))
        val plan = CsvImport.plan(r, "acct", existing)
        // the payroll row is already here (normalized description); the second water bill repeats in the file
        assertEquals(2, plan.alreadyHere.size)
        assertEquals(5, plan.toImport.size)
        assertEquals(1, plan.bad.size)
        assertEquals(8, plan.total)
        // another account doesn't count as "already here"
        assertEquals(1, CsvImport.plan(r, "other", existing).alreadyHere.size)
    }

    @Test fun aPendingEntryIsPostedInsteadOfDoubled() {
        val text = fixture("signed_iso.csv")
        val r = CsvImport.read(text, CsvImport.detect(text))
        val pending = F.tx("p", "acct", -35_20, LocalDate.of(2030, 1, 4), payee = "fuel").copy(pending = true)
        val plan = CsvImport.plan(r, "acct", listOf(pending))
        assertEquals(1, plan.matchedPending.size)
        assertEquals("p", plan.matchedPending.single().second.id)
        assertTrue(plan.toImport.none { it.amount == -35_20L })
    }

    @Test fun atmFeesAndRebatesAreTagged() {
        assertTrue(CsvImport.isAtmFee("ATM Fee Example Bank", -3_50))
        assertFalse(CsvImport.isAtmFee("Corner Grocer", -3_50))
        assertTrue(CsvImport.isFeeRebate("ATM Fee Rebate", 3_50))
        assertFalse(CsvImport.isFeeRebate("ATM Fee Rebate", -3_50))
    }

    @Test fun badRowsNeverAbortAndWrongWidthIsReported() {
        val text = "Date,Description,Amount\n2030-01-02,One,1.00\n2030-01-03,Two\n2030-01-04,Three,3.00\n"
        val r = CsvImport.read(text, CsvImport.detect(text))
        assertEquals(listOf("One", "Three"), r.rows.map { it.description })
        assertEquals(listOf(3), r.bad.map { it.rowNumber })
    }
}
