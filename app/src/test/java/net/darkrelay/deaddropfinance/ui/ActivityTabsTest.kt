package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Activity's tabs (1.2.0), with invented data: All, then one per account shown in Activity. All is view only; an
 * account's tab has its balance, Add, Scan and Import CSV, and only its own transactions. The compose rule needs the
 * debug build's test activity, so release unit tests skip these (see ui/PurgeWizardTest). The tab rules are in ActivityTabAccountsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h1400dp-xhdpi")
class ActivityTabsTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val day = LocalDate.of(2030, 8, 20)

    private fun acct(id: String, name: String, type: AccountType, start: Long, order: Int, show: Boolean = true) =
        AccountEntity(id = id, name = name, type = type, startingBalance = start, startDate = day.minusDays(30), sortOrder = order, showInActivity = show, createdAt = 1, updatedAt = 1)

    private fun tx(id: String, account: String, payee: String, amount: Long) =
        TransactionEntity(id = id, accountId = account, date = day, payee = payee, amount = amount, createdAt = 1, updatedAt = 1)

    private fun line(tx: String, amount: Long) = TransactionLineEntity(transactionId = tx, bucket = LineBucket.READY_TO_ASSIGN, amount = amount, createdAt = 1, updatedAt = 1)

    private val checking = acct("chk", "Sample Checking", AccountType.BANK, 500_00, 0)
    private val wallet = acct("wal", "Sample Wallet", AccountType.PAYMENT_APP, 25_00, 1)
    private val hidden = acct("hid", "Sample Hidden", AccountType.BANK, 10_00, 2, show = false)
    private val budget = Budget(
        listOf(checking, wallet, hidden), emptyList(),
        listOf(tx("t1", "chk", "SAMPLE PAYROLL", 100_00), tx("t2", "wal", "SAMPLE WALLET TOP UP", 5_00), tx("t3", "hid", "SAMPLE HIDDEN PAY", 1_00)),
        listOf(line("t1", 100_00), line("t2", 5_00), line("t3", 1_00)), emptyList(),
    )

    @Test fun uiAllHasNoAddOrScanAndShowsEveryAccountsTransactions() {
        compose.setContent { ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = activityTabAccounts(budget))) }
        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Sample Checking").assertExists()
        compose.onNodeWithText("Sample Wallet").assertExists()
        compose.onNodeWithText("Sample Hidden").assertDoesNotExist()
        compose.onNodeWithText("Add").assertDoesNotExist()
        compose.onNodeWithText("Scan").assertDoesNotExist()
        compose.onNodeWithText("SAMPLE HIDDEN PAY").assertExists()
    }

    /** Sample Checking stands for an account that was linked to bank sync before 1.3.0: now a manual account like any other. */
    @Test fun uiAFormerlyLinkedAccountTabShowsManualAccountItsBalanceItsOwnTransactionsAndActsOnIt() {
        val acted = mutableListOf<String>()
        compose.setContent {
            ActivityScreen(
                budget, {}, {}, { _, _, _ -> }, {},
                tabs = ActivityTabs(
                    accounts = activityTabAccounts(budget), selected = "chk",
                    onAdd = { acted += "add:$it" }, onScan = { acted += "scan:$it" }, onImport = { acted += "csv:$it" },
                ),
            )
        }
        compose.onNodeWithText("Current balance").assertExists()
        compose.onNodeWithText("$600.00").assertExists()
        compose.onNodeWithText("Manual account").assertExists()
        compose.onNodeWithText("Last sync", substring = true).assertDoesNotExist()
        compose.onNodeWithText("SAMPLE PAYROLL").assertExists()
        compose.onNodeWithText("SAMPLE WALLET TOP UP").assertDoesNotExist()
        compose.onNodeWithText("Starting balance").assertExists()
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Import CSV").performClick()
        assertEquals(listOf("add:chk", "scan:chk", "csv:chk"), acted)
    }

    @Test fun uiAManualAccountShowsItsStartingBalanceEntry() {
        compose.setContent { ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = activityTabAccounts(budget), selected = "wal")) }
        compose.onNodeWithText("Manual account").assertExists()
        compose.onNodeWithText("Starting balance").assertExists()
        compose.onNodeWithText("$25.00").assertExists()
        compose.onNodeWithText("$30.00").assertExists()
        compose.onNodeWithText("SAMPLE PAYROLL").assertDoesNotExist()
    }
}
