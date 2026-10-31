package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Reports
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * Reports in the program (0.15.0): the Reports screen is the twelve tiles; each opens its report, the app's own
 * screens on the top bar's month, and Back returns to the tiles. At the size the window opens (1400 x 900) and its
 * minimum (1100 x 700). On an empty budget each says it has nothing yet. Invented data only.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramReportsTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() } }

    /** The sample budget, with what the reports need on top: an older budget month, a Debt envelope, bills, receipts. */
    private fun reportsBudget(): AppModel = runBlocking {
        val made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        val m = made.model.also { models += it }
        val a = m.actions!!
        val store = (m.engine!!.opened as DesktopDatabase.Opened.Ok).store
        val today = SampleBudget.today
        val ids = made.ids
        val cats = a.snapshot().categories.associate { it.name to it.id }
        // the budget month before: its payday and a market trip
        val pay = a.saveTransaction(null, TransactionDraft(ids["Checking"]!!, today.minusDays(17), "Sample Payroll Co", "Income", 1850_00, listOf(LineDraft(null, 1850_00, toReadyToAssign = true))))
        a.markPayday(pay, today.minusDays(17), fill = false)
        a.move(null, ids["Groceries"], 400_00, date = today.minusDays(17))
        val trip1 = a.saveTransaction(null, TransactionDraft(ids["Checking"]!!, today.minusDays(12), "Corner Market", "", -38_40, listOf(LineDraft(ids["Groceries"], -38_40, categoryId = cats["Groceries"]))))
        // a Debt envelope with a rate
        a.saveEnvelope(null, EnvelopeDraft("Car loan", EnvelopeMode.DEBT, perPayday = 150_00, debtOwed = 4200_00, requiredPayment = 300_00, dueDate = today.plusDays(5), interestRate = 699), today)
        val bills = BillActions(store, a) { today }
        bills.saveBill(null, BillActions.BillDraft("Rent", 1200_00, today.withDayOfMonth(1), envelopeId = ids["Rent"]))
        bills.saveBill(null, BillActions.BillDraft("Phone plan", 45_00, today.withDayOfMonth(15), autopay = true))
        // receipts for both market trips
        val trip2 = a.snapshot().transactions.first { it.payee == "Corner Market" && it.date == today }
        store.dao.upsertReceipt(ReceiptEntity(id = "r1", transactionId = trip1, store = "Corner Market", date = today.minusDays(12), total = 38_40, createdAt = 1, updatedAt = 1))
        store.dao.upsertReceipt(ReceiptEntity(id = "r2", transactionId = trip2.id, store = "Corner Market", date = today, total = 43_12, createdAt = 1, updatedAt = 1))
        listOf(
            ReceiptItemEntity(id = "i1", receiptId = "r1", name = "Milk 2%", quantityMilli = 2000, unitPrice = 3_29, amount = 6_58, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i2", receiptId = "r1", name = "Bread", amount = 2_99, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i3", receiptId = "r2", name = "Milk 2%", quantityMilli = 2000, unitPrice = 3_49, amount = 6_98, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i4", receiptId = "r2", name = "Eggs", amount = 4_29, createdAt = 1, updatedAt = 1),
        ).forEach { store.dao.upsertReceiptItem(it) }
        m.refresh()
        m
    }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.0" }?.let { File(it).also { d -> d.mkdirs() } }

    private fun every(w: Int, h: Int, m: AppModel, suffix: String = "", check: (Reports.Kind) -> String?) = runDesktopComposeUiTest(w, h) {
        m.go(Screen.REPORTS)
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        onNodeWithTag("reports", useUnmergedTree = true).assertExists()
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "reports-${w}x$h$suffix.png")) }
        for (k in Reports.Kind.entries) {
            onNodeWithTag("report.${k.name}").performClick()
            waitForIdle()
            onNodeWithTag("reportView.${k.name}", useUnmergedTree = true).assertExists()
            check(k)?.let { onNodeWithTag(it, useUnmergedTree = true).assertExists() }
            shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "report-${(k.ordinal + 1).toString().padStart(2, '0')}-${k.name.lowercase().replace('_', '-')}-${w}x$h$suffix.png")) }
            onNodeWithTag("back", useUnmergedTree = true).let { runCatching { it.performClick() }.getOrElse { onNodeWithTag("nav.REPORTS").performClick() } }
            waitForIdle()
            onNodeWithTag("reports", useUnmergedTree = true).assertExists()
        }
    }

    private val filledChecks: (Reports.Kind) -> String? = { k ->
        when (k) {
            Reports.Kind.SPENDING_BY_ENVELOPE -> "share.Groceries"
            Reports.Kind.INCOME_VS_EXPENSE -> "chart.pairs"
            Reports.Kind.TRENDS -> "chart.line"
            Reports.Kind.MONEY_FLOW -> "chart.flow"
            Reports.Kind.BILLS_CALENDAR -> "day.15"
            Reports.Kind.DEBT_PAYDOWN -> "debt.payoff.Car loan"
            Reports.Kind.MERCHANTS -> "merchant.Corner Market"
            Reports.Kind.PRICE_HISTORY -> "item.milk 2%"
            else -> null
        }
    }

    @Test fun everyReportOpensFromItsTileAtTheOpeningSize() = every(1400, 900, reportsBudget(), check = filledChecks)

    @Test fun everyReportOpensFromItsTileAtTheMinimumSize() = every(1100, 700, reportsBudget(), check = filledChecks)

    @Test fun onAnEmptyBudgetEachReportSaysItHasNothingYet() {
        val m = SampleBudget.empty(tmp.newFolder("DeadDropFinance")).also { models += it }
        every(1100, 700, m, "-empty") { null }
    }

    @Test fun reportsFollowTheTopBarsMonth() = runDesktopComposeUiTest(1400, 900) {
        val m = reportsBudget()
        m.go(Screen.REPORTS)
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        onNodeWithTag("report.SPENDING_BY_ENVELOPE").performClick()
        waitForIdle()
        onNodeWithTag("share.Groceries", useUnmergedTree = true).assertExists()
        // the month before (the top bar's Older): Groceries is still there, Gas isn't (it was bought this month)
        onNodeWithTag("month.older").performClick()
        waitForIdle()
        assertEquals(1, m.monthIndex)
        onNodeWithTag("share.Groceries", useUnmergedTree = true).assertExists()
        onNodeWithTag("share.Gas", useUnmergedTree = true).assertDoesNotExist()
    }
}
