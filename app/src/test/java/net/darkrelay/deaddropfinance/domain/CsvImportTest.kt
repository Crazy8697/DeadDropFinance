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

    @Test fun aRowTheAccountHoldsIsSkippedAndARepeatInTheFileIsOffered() {
        val text = fixture("signed_iso.csv")
        val r = CsvImport.read(text, CsvImport.detect(text))
        val existing = listOf(F.tx("old", "acct", 1500_00, LocalDate.of(2030, 1, 2), payee = "SAMPLE PAYROLL CO."))
        val plan = CsvImport.plan(r, "acct", existing)
        // the payroll row is already here (normalized description); the water bill appears twice in the file
        assertEquals(1, plan.alreadyHere.size)
        assertEquals(6, plan.toImport.size)
        assertEquals(1, plan.possibleDuplicates.size)
        assertEquals(2, plan.possibleDuplicateRows)
        assertEquals(1, plan.bad.size)
        assertEquals(8, plan.total)
        // another account doesn't count as "already here"
        assertEquals(0, CsvImport.plan(r, "other", existing).alreadyHere.size)
    }

    // ---- count-aware duplicates -------------------------------------------------------------------------------

    private val day = LocalDate.of(2030, 2, 10)
    private fun file(n: Int): CsvImport.Read {
        val text = "Date,Description,Amount\n" + (1..n).joinToString("") { "2030-02-10,Sample Transfer Out,-25.00\n" }
        return CsvImport.read(text, CsvImport.detect(text))
    }
    private fun held(m: Int) = (1..m).map { F.tx("h$it", "acct", -25_00, day, payee = "Sample Transfer Out") }

    @Test fun identicalRowsInOneFileAreAllImportedByDefault() {
        val plan = CsvImport.plan(file(3), "acct", emptyList())
        assertEquals(3, plan.toImport.size)
        assertEquals(0, plan.alreadyHere.size)
        assertEquals(1, plan.possibleDuplicates.size)
        assertEquals(listOf(2, 3, 4), plan.possibleDuplicates.single().rows.map { it.rowNumber })
        assertEquals(3, plan.possibleDuplicateRows)
        assertEquals(3, plan.total)
    }

    @Test fun fileThreeAppZeroImportsThree() = counts(3, 0, imported = 3, already = 0, groupRows = 3)
    @Test fun fileThreeAppOneImportsTwo() = counts(3, 1, imported = 2, already = 1, groupRows = 2)
    @Test fun fileTwoAppTwoImportsNone() = counts(2, 2, imported = 0, already = 2, groupRows = 0)
    @Test fun fileOneAppThreeImportsNone() = counts(1, 3, imported = 0, already = 1, groupRows = 0)

    private fun counts(inFile: Int, inApp: Int, imported: Int, already: Int, groupRows: Int) {
        val plan = CsvImport.plan(file(inFile), "acct", held(inApp))
        assertEquals("to import", imported, plan.toImport.size)
        assertEquals("already here", already, plan.alreadyHere.size)
        assertEquals("possible duplicate rows", groupRows, plan.possibleDuplicateRows)
        assertEquals(inFile, plan.total)
    }

    @Test fun skippingOneRowOfAGroupUpdatesTheCounts() {
        val plan = CsvImport.plan(file(3), "acct", emptyList(), skippedRows = setOf(3))
        assertEquals(listOf(2, 4), plan.toImport.map { it.rowNumber })
        assertEquals(listOf(3), plan.skippedByUser.map { it.rowNumber })
        assertEquals(3, plan.possibleDuplicateRows)
        assertEquals(3, plan.total)
        // a skip number that isn't in a group does nothing
        assertEquals(3, CsvImport.plan(file(3), "acct", emptyList(), skippedRows = setOf(99)).toImport.size)
    }

    @Test fun differentDescriptionsOrAmountsAreNotGroupedAndOtherAccountsDontCount() {
        val text = "Date,Description,Amount\n2030-02-10,One,-5.00\n2030-02-10,Two,-5.00\n2030-02-10,One,-6.00\n2030-02-11,One,-5.00\n"
        val plan = CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), "acct", emptyList())
        assertEquals(4, plan.toImport.size)
        assertTrue(plan.possibleDuplicates.isEmpty())
        val other = listOf(F.tx("x", "elsewhere", -5_00, day, payee = "One"))
        assertEquals(0, CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), "acct", other).alreadyHere.size)
    }

    @Test fun importingTheSameFileAgainImportsNothingNew() {
        val read = file(3)
        val first = CsvImport.plan(read, "acct", emptyList())
        // what the app would hold after the import
        val after = first.toImport.mapIndexed { i, r ->
            F.tx("n$i", "acct", r.amount, r.date, payee = r.description).copy(importDescription = r.description)
        }
        val again = CsvImport.plan(read, "acct", after)
        assertEquals(0, again.toImport.size)
        assertEquals(3, again.alreadyHere.size)
        assertEquals(0, again.possibleDuplicateRows)
    }

    @Test fun aSkippedRowIsNotImportedSoReimportingOffersItAgain() {
        val read = file(3)
        val first = CsvImport.plan(read, "acct", emptyList(), skippedRows = setOf(4))
        val after = first.toImport.mapIndexed { i, r ->
            F.tx("n$i", "acct", r.amount, r.date, payee = r.description).copy(importDescription = r.description)
        }
        val again = CsvImport.plan(read, "acct", after)
        assertEquals(1, again.toImport.size)
        assertEquals(2, again.alreadyHere.size)
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
