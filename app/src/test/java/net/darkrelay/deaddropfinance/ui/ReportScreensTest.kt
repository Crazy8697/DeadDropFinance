package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.Reports.Kind
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
 * Reports on the phone (1.9.0): More > Reports is the twelve tiles; each opens its report, with the phone's own Older
 * and Newer; a payee's page and an item's prices open from theirs; on a new budget each says it has nothing yet.
 * Invented data. The compose rule needs the debug build's test activity, so release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xhdpi")
class ReportScreensTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val today = LocalDate.of(2030, 8, 20)
    private val p1 = today.minusDays(14)
    private val p2 = today.minusDays(1)
    private val acct = AccountEntity(id = "a", name = "Sample", type = AccountType.BANK, startDate = today.minusDays(90), createdAt = 1, updatedAt = 1)
    private val food = EnvelopeEntity(id = "e-food", name = "Food", mode = EnvelopeMode.FILL_TO_TARGET, target = 300_00, createdAt = 1, updatedAt = 1)
    private val trip = EnvelopeEntity(id = "e-trip", name = "Trip", mode = EnvelopeMode.GOAL, goalAmount = 1000_00, goalDate = today.plusMonths(6), sortOrder = 1, createdAt = 1, updatedAt = 1)
    private val loan = EnvelopeEntity(
        id = "e-loan", name = "Sample Loan", mode = EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 900_00, debtOwedSince = today,
        requiredPayment = 150_00, dueDate = today.plusDays(5), interestRate = 1999, sortOrder = 2, createdAt = 1, updatedAt = 1,
    )
    private val groceries = CategoryEntity(id = "c-g", name = "Groceries", envelopeId = "e-food", createdAt = 1, updatedAt = 1)

    private fun tx(id: String, d: LocalDate, amount: Long, payee: String, payday: Boolean = false, memo: String = "") =
        TransactionEntity(id = id, accountId = "a", date = d, payee = payee, memo = memo, amount = amount, isPayday = payday, createdAt = 1, updatedAt = 1)
    private fun line(tx: String, amount: Long, bucket: LineBucket, env: String? = null, cat: String? = null) =
        TransactionLineEntity(transactionId = tx, bucket = bucket, envelopeId = env, amount = amount, categoryId = cat, createdAt = 1, updatedAt = 1)
    private fun move(to: String, amount: Long, d: LocalDate) = MoveEntity(fromEnvelopeId = null, toEnvelopeId = to, amount = amount, date = d, kind = MoveKind.ASSIGN, createdAt = 1, updatedAt = 1)

    private val budget = Budget(
        listOf(acct), listOf(food, trip, loan),
        listOf(
            tx("p1", p1, 900_00, "SAMPLE PAY", payday = true), tx("p2", p2, 900_00, "SAMPLE PAY", payday = true),
            tx("s1", p1.plusDays(2), -40_00, "Sample Market"), tx("s2", p2, -12_00, "Sample Market", memo = "Milk"),
        ),
        listOf(
            line("p1", 900_00, LineBucket.READY_TO_ASSIGN), line("p2", 900_00, LineBucket.READY_TO_ASSIGN),
            line("s1", -40_00, LineBucket.ENVELOPE, "e-food", "c-g"), line("s2", -12_00, LineBucket.ENVELOPE, "e-food", "c-g"),
        ),
        listOf(move("e-food", 300_00, p1), move("e-trip", 250_00, p1)),
        bills = listOf(BillEntity(id = "b1", name = "Sample Phone", envelopeId = null, amountDue = 45_00, dueDate = today.withDayOfMonth(25), createdAt = 1, updatedAt = 1)),
        categories = listOf(groceries),
    )
    private val receipts = listOf(ReceiptEntity(id = "r1", transactionId = "s1", store = "Sample Market", date = p1.plusDays(2), createdAt = 1, updatedAt = 1))
    private val items = listOf(ReceiptItemEntity(id = "i1", receiptId = "r1", name = "Milk", unitPrice = 3_50, amount = 3_50, createdAt = 1, updatedAt = 1))
    private val data = ReportData(budget, today, receipts, items, Forecast.build(budget, emptyList(), today, today.plusDays(13)))
    private val empty = Budget(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    @Test fun moreOpensReportsAndEveryTileIsThere() {
        var opened: Kind? = null
        var month = 0
        show { ReportsScreen(data, month, { month = it }, onBack = {}) { opened = it } }
        for (k in Kind.entries) compose.onNodeWithTag("report.${k.name}").performScrollTo().assertExists()
        compose.onNodeWithTag("report.MONEY_FLOW").performScrollTo().performClick()
        assertEquals(Kind.MONEY_FLOW, opened)
        compose.onNodeWithText("‹ Older").performClick()
        assertEquals(1, month)
    }

    // the rule takes one setContent a test: each screen after the first is swapped in through state
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var shown = false

    private fun show(content: @Composable () -> Unit) {
        screen = content
        if (!shown) { compose.setContent { screen?.invoke() }; shown = true }
        compose.waitForIdle()
    }

    private fun open(k: Kind, d: ReportData = data, month: Int = 0, onMerchant: (String) -> Unit = {}, onItem: (String) -> Unit = {}) {
        show { ReportScreen(k, d, month, {}, {}, onMerchant, onItem, {}, {}) }
        compose.onNodeWithTag("reportView.${k.name}").assertExists()
    }

    @Test fun spendingByEnvelopeAndCategoryShowEachShare() {
        open(Kind.SPENDING_BY_ENVELOPE)
        compose.onNodeWithTag("share.Food").assertExists()
        compose.onAllNodesWithText("$12.00", useUnmergedTree = true).assertCountEquals(2)
        open(Kind.SPENDING_BY_CATEGORY, month = 1)
        compose.onNodeWithTag("share.Groceries").assertExists()
        compose.onAllNodesWithText("$40.00", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test fun overTimeReportsDrawTheirCharts() {
        open(Kind.INCOME_VS_EXPENSE); compose.onNodeWithTag("chart.pairs").assertExists()
        open(Kind.TRENDS); compose.onNodeWithTag("chart.line").assertExists(); compose.onNodeWithTag("trend.Food").assertExists()
        open(Kind.MONEY_FLOW, month = 1); compose.onNodeWithTag("chart.flow").assertExists()
        open(Kind.NET_WORTH); compose.onNodeWithTag("chart.line").assertExists()
    }

    @Test fun billsDebtForecastAndGoals() {
        open(Kind.BILLS_CALENDAR); compose.onNodeWithTag("day.25").assertExists(); compose.onNodeWithText("Sample Phone").assertExists()
        open(Kind.DEBT_PAYDOWN); compose.onNodeWithTag("debt.payoff.Sample Loan").assertExists(); compose.onNodeWithText("19.99% a year").assertExists()
        open(Kind.PAYDAY_FORECAST); compose.onNodeWithText("Day before payday").assertExists()
        open(Kind.SAVINGS_GOALS); compose.onNodeWithText("Trip").assertExists(); compose.onNodeWithText("25%").assertExists()
    }

    @Test fun merchantsAndPricesOpenTheirPages() {
        var merchant: String? = null
        open(Kind.MERCHANTS, onMerchant = { merchant = it })
        compose.onNodeWithTag("merchants.allTime").performClick()
        compose.onNodeWithTag("merchant.Sample Market", useUnmergedTree = true).performClick()
        show { MerchantScreen(data, merchant!!, 0, {}) {} }
        compose.onNodeWithText("Most bought").assertExists()
        compose.onNodeWithText("Milk").assertExists()
        var item: String? = null
        open(Kind.PRICE_HISTORY, onItem = { item = it })
        compose.onNodeWithTag("item.milk", useUnmergedTree = true).performClick()
        assertEquals("milk", item)
        show { PriceItemScreen(data, "milk", {}) }
        compose.onNodeWithText("Lowest").assertExists()
    }

    @Test fun aNewBudgetsReportsSayTheyHaveNothingYet() {
        val d = ReportData(empty, today, emptyList(), emptyList(), null)
        open(Kind.SPENDING_BY_ENVELOPE, d); compose.onNodeWithText("No spending in this budget month.").assertExists()
        open(Kind.INCOME_VS_EXPENSE, d); compose.onNodeWithText("No income or spending yet.").assertExists()
        open(Kind.NET_WORTH, d); compose.onNodeWithText("Add an account to see your net worth.").assertExists()
        open(Kind.PAYDAY_FORECAST, d); compose.onNodeWithText("Mark a payday to see what the next one covers.").assertExists()
        for (k in Kind.entries) open(k, d)
    }
}
