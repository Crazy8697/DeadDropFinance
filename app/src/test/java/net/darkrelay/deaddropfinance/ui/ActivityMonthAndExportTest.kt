package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Activity's budget month and Export CSV (1.8.0), with invented data: Older and Newer step through the budget months
 * as in Categories, each tab lists that month's transactions, and Export CSV hands over what's shown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h1400dp-xhdpi")
class ActivityMonthAndExportTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    // the screen dates its budget months by the real clock, as Activity does
    private val today = LocalDate.now()
    private val checking = AccountEntity(id = "chk", name = "Sample Checking", type = AccountType.BANK, startingBalance = 500_00, startDate = today.minusDays(90), createdAt = 1, updatedAt = 1)

    private fun tx(id: String, days: Long, payee: String, amount: Long, payday: Boolean = false) =
        TransactionEntity(id = id, accountId = "chk", date = today.minusDays(days), payee = payee, amount = amount, isPayday = payday, createdAt = 1, updatedAt = 1)

    private fun line(tx: String, amount: Long) = TransactionLineEntity(transactionId = tx, bucket = LineBucket.READY_TO_ASSIGN, amount = amount, createdAt = 1, updatedAt = 1)

    private val budget = Budget(
        listOf(checking), emptyList(),
        listOf(tx("p1", 40, "SAMPLE PAYROLL ONE", 900_00, payday = true), tx("p2", 10, "SAMPLE PAYROLL TWO", 900_00, payday = true),
            tx("old", 20, "SAMPLE OLDER REFUND", 5_00), tx("new", 5, "SAMPLE NEWER REFUND", 7_00)),
        listOf(line("p1", 900_00), line("p2", 900_00), line("old", 5_00), line("new", 7_00)), emptyList(),
    )

    @Test fun uiOlderAndNewerStepThroughTheBudgetMonthsOnEachTab() {
        compose.setContent {
            var month by remember { mutableIntStateOf(0) }
            ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = activityTabAccounts(budget), monthIndex = month, onMonth = { month = it }))
        }
        compose.onNodeWithText("SAMPLE NEWER REFUND").assertExists()
        compose.onNodeWithText("SAMPLE OLDER REFUND").assertDoesNotExist()
        compose.onNodeWithText("‹ Older").performClick()
        compose.onNodeWithText("SAMPLE OLDER REFUND").assertExists()
        compose.onNodeWithText("SAMPLE NEWER REFUND").assertDoesNotExist()
        compose.onNodeWithText("Newer ›").performClick()
        compose.onNodeWithText("SAMPLE NEWER REFUND").assertExists()
    }

    @Test fun uiAnAccountsTabShowsItsMonthAndExportCsvHandsItOver() {
        var exports = 0
        compose.setContent {
            ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = activityTabAccounts(budget), selected = "chk", onExport = { exports++ }, note = "Saved sample.csv."))
        }
        compose.onNodeWithText("SAMPLE NEWER REFUND").assertExists()
        compose.onNodeWithText("SAMPLE OLDER REFUND").assertDoesNotExist()
        compose.onNodeWithText("Export CSV").performClick()
        assertEquals(1, exports)
        compose.onNodeWithText("Saved sample.csv.").assertExists()
    }

    @Test fun uiWithoutAnExportItHasNoButton() {
        compose.setContent { ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = activityTabAccounts(budget))) }
        compose.onNodeWithText("Export CSV").assertDoesNotExist()
    }
}
