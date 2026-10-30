package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.domain.ActivityExport
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.CsvImport
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Export CSV (0.13.0): the program's Activity saves what its tab shows for the budget month (with the moves on All)
 * where the Save As dialog says, named for the tab and the dates; and the phone's Activity, when the program shows it.
 */
class ProgramExportTest : ProgramScreensBase() {

    @Test fun anAccountTabIsSavedAsTheChosenFileAndImportsBackWithTheSameRows() {
        show()
        saveTo = tmp.newFile("export.csv")
        click("nav.ACTIVITY")
        click("tab.Checking")
        click("exportCsv")
        val text = saveTo!!.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("Date,Description,Amount,Envelope,Category,Account,Memo,Pending,Kind\r\n"))
        assertEquals("Saved export.csv.", model.message?.first)
        val rows = text.trim().split("\r\n").drop(1)
        // what the Checking tab shows this budget month: the payday and Corner Market (the starting balance is no transaction)
        assertEquals(listOf("Sample Payroll Co", "Corner Market"), rows.map { it.split(",")[1] })
        // into another account, through the import: the same rows
        val back = runBlocking {
            val acct = BudgetActions(store()).saveAccount(null, "Sample Copy", AccountType.BANK, 0, today.minusDays(60))
            val read = CsvImport.read(text, CsvImport.detect(text))
            BudgetActions(store()).importCsv(acct, CsvImport.plan(read, acct, emptyList()))
            BudgetActions(store()).snapshot().transactions.filter { it.accountId == acct }
        }
        assertEquals(setOf("Sample Payroll Co" to "1600.00", "Corner Market" to "-42.00"), back.map { it.payee to ActivityExport.plain(it.amount) }.toSet())
    }

    @Test fun theAllTabIncludesTheMovesItShowsAndTheNameSaysAllAndTheDates() {
        show { move(ids.getValue("Groceries"), ids.getValue("Gifts"), 5_00, date = today) }
        var offered: String? = null
        shell = Shell(run = { b -> runBlocking { b() } }, settings = settings, pickSave = { n -> offered = n; tmp.newFile(n) })
        compose.setContent { DesktopApp(model, shell) }
        compose.waitForIdle()
        click("nav.ACTIVITY")
        click("tab.All")
        click("exportCsv")
        assertEquals(ActivityExport.fileName(null, model.month, model.today()), offered)
        assertTrue(offered!!.startsWith("deaddrop-activity-all-${today.minusDays(3)}-to-"), offered)
        val text = java.io.File(tmp.root, offered!!).readText()
        assertTrue(text.contains("Moved from Groceries to Gifts,5.00"), text)
    }

    @Test fun thePhonesActivityInTheProgramExportsThroughTheSaveAsDialogToo() {
        show()
        saveTo = tmp.newFile("phone-export.csv")
        click("nav.ACTIVITY")
        click("toSort")
        until("Export CSV")
        clickText("Export CSV", exact = true)
        until("Saved ")
        assertTrue(saveTo!!.readText().contains("Corner Market"))
    }
}
