package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Export CSV from Activity (1.8.0): the columns, a row per line, transfers, moves, quoting, the name, and importing it back. */
class ActivityExportTest {

    @get:Rule val tmp = TemporaryFolder()
    private val d = LocalDate.of(2030, 3, 10)

    private fun budget(): Pair<BudgetActions, Map<String, String>> = runBlocking {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder("b"), clock = { 1_000L }))
        o.store.ensureSystemEnvelopes(); o.store.ensureCategories()
        val a = BudgetActions(o.store)
        val ids = mutableMapOf<String, String>()
        ids["chk"] = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, d.minusDays(20))
        ids["sav"] = a.saveAccount(null, "Sample Savings", AccountType.BANK, 0, d.minusDays(20))
        ids["food"] = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
        ids["home"] = a.saveEnvelope(null, EnvelopeDraft("Home", EnvelopeMode.FILL_TO_TARGET, target = 100_00))
        val cat = o.store.dao.categoriesNow().first { it.name == "Groceries" }.id
        a.saveTransaction(null, TransactionDraft(ids["chk"]!!, d, "Sample Market, Inc", "weekly \"big\" run", -60_00,
            listOf(LineDraft(ids["food"], -45_00, categoryId = cat), LineDraft(ids["home"], -15_00, categoryId = cat))))
        a.saveTransaction(null, TransactionDraft(ids["chk"]!!, d.plusDays(1), "Sample Payroll", "", 800_00, listOf(LineDraft(null, 800_00, toReadyToAssign = true)), pending = true))
        a.saveTransfer(null, ids["chk"]!!, ids["sav"]!!, 100_00, d.plusDays(2), "", null)
        a.move(null, ids["food"], 20_00, date = d.plusDays(3))
        a to ids
    }

    @Test fun eachLineIsARowWithTheColumnsActivityShows() = runBlocking {
        val (a, ids) = budget()
        val b = a.snapshot()
        val all = Budget.Month(null, null)
        val rows = ActivityExport.rows(b, ids["chk"], all, moves = false)
        // the split purchase is two rows, then the deposit, then the transfer out
        assertEquals(4, rows.size)
        assertEquals(listOf(d.toString(), "Sample Market, Inc", "-45.00", "Food", "Groceries", "Sample Checking", "weekly \"big\" run", "no", "Spending"), rows[0])
        assertEquals("-15.00", rows[1][2])
        assertEquals("Home", rows[1][3])
        assertEquals(listOf("800.00", "Ready to assign", "yes", "Money in"), listOf(rows[2][2], rows[2][3], rows[2][7], rows[2][8]))
        assertEquals("Transfer", rows[3][8])
        // the savings tab shows the same transfer from its side: money in
        val sav = ActivityExport.rows(b, ids["sav"], all, moves = false)
        assertEquals(1, sav.size)
        assertEquals("100.00", sav[0][2])
        assertEquals("Sample Savings", sav[0][5])
        // All with the moves (the program's All tab shows them)
        val withMoves = ActivityExport.rows(b, null, all, moves = true)
        assertTrue(withMoves.any { it[8] == "Move" && it[1] == "Moved from Ready to assign to Food" && it[2] == "20.00" })
        assertTrue(ActivityExport.rows(b, null, all, moves = false).none { it[8] == "Move" })
    }

    @Test fun theCsvHasAHeaderQuotesWhatNeedsItAndAPlainName() = runBlocking {
        val (a, ids) = budget()
        val text = ActivityExport.csv(a.snapshot(), ids["chk"], Budget.Month(null, null), moves = false)
        val lines = text.split("\r\n")
        assertEquals("Date,Description,Amount,Envelope,Category,Account,Memo,Pending,Kind", lines[0])
        assertEquals("$d,\"Sample Market, Inc\",-45.00,Food,Groceries,Sample Checking,\"weekly \"\"big\"\" run\",no,Spending", lines[1])
        assertEquals("deaddrop-activity-sample-checking-2030-03-01-to-2030-03-30.csv",
            ActivityExport.fileName("Sample Checking", Budget.Month(LocalDate.of(2030, 3, 1), LocalDate.of(2030, 3, 31)), d))
        assertEquals("deaddrop-activity-all-start-to-$d.csv", ActivityExport.fileName(null, Budget.Month(null, null), d))
    }

    @Test fun onlyTheChosenBudgetMonthIsExported() = runBlocking {
        val (a, ids) = budget()
        val month = Budget.Month(d.plusDays(1), d.plusDays(2))
        val rows = ActivityExport.rows(a.snapshot(), ids["chk"], month, moves = false)
        assertEquals(listOf("Sample Payroll"), rows.map { it[1] })
    }

    @Test fun anExportedFileImportsBackThroughTheCsvImportWithTheSameRows() = runBlocking {
        val (a, ids) = budget()
        val exported = ActivityExport.rows(a.snapshot(), ids["chk"], Budget.Month(null, null), moves = false)
        val text = ActivityExport.csv(exported)
        // into a new, empty budget's account, through the phone's own import
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder("other"), clock = { 2_000L }))
        o.store.ensureSystemEnvelopes()
        val other = BudgetActions(o.store)
        val acct = other.saveAccount(null, "Sample Imported", AccountType.BANK, 0, d.minusDays(20))
        val layout = CsvImport.detect(text)
        val read = CsvImport.read(text, layout)
        val result = other.importCsv(acct, CsvImport.plan(read, acct, other.snapshot().transactions))
        assertEquals(exported.size, result.imported, "every row went in (the identical-looking ones too)")
        val got = other.snapshot().transactions.filter { it.accountId == acct }.map { Triple(it.date.toString(), it.payee, ActivityExport.plain(it.amount)) }.sortedBy { it.toString() }
        assertEquals(exported.map { Triple(it[0], it[1], it[2]) }.sortedBy { it.toString() }, got)
    }
}
