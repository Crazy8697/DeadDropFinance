package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
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
 * The Categories tab, a category's purchases, category management and the assigned line on Envelopes (1.1.0), with
 * invented data. The compose rule needs the debug build's test activity, so release unit tests skip these (see
 * ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h1400dp-xhdpi")
class CategoryScreensTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val today = LocalDate.of(2030, 8, 20)
    private val acct = AccountEntity(id = "a", name = "Sample", type = AccountType.BANK, startDate = today.minusDays(90), createdAt = 1, updatedAt = 1)
    private val rent = CategoryEntity(id = "c-rent", name = "Rent", createdAt = 1, updatedAt = 1)
    private val snacks = CategoryEntity(id = "c-snacks", name = "Snacks", sortOrder = 1, createdAt = 1, updatedAt = 1)

    private fun payday(id: String, d: LocalDate) = TransactionEntity(id = id, accountId = "a", date = d, payee = "SAMPLE PAY", amount = 500_00, isPayday = true, createdAt = 1, updatedAt = 1)
    private fun spend(id: String, d: LocalDate, amount: Long, payee: String) = TransactionEntity(id = id, accountId = "a", date = d, payee = payee, amount = -amount, createdAt = 1, updatedAt = 1)
    private fun line(tx: String, amount: Long, bucket: LineBucket, cat: String? = null) =
        TransactionLineEntity(transactionId = tx, bucket = bucket, envelopeId = null, amount = amount, categoryId = cat, createdAt = 1, updatedAt = 1)

    private val p1 = today.minusDays(14)
    private val p2 = today.minusDays(1)
    private val budget = Budget(
        listOf(acct), emptyList(),
        listOf(payday("p1", p1), payday("p2", p2), spend("s1", p1.plusDays(3), 40_00, "SAMPLE LANDLORD"), spend("s2", p2, 7_00, "SAMPLE VENDING"), spend("s3", p2, 9_00, "OLD SHOP")),
        listOf(
            line("p1", 500_00, LineBucket.READY_TO_ASSIGN), line("p2", 500_00, LineBucket.READY_TO_ASSIGN),
            line("s1", -40_00, LineBucket.UNSORTED, "c-rent"), line("s2", -7_00, LineBucket.UNSORTED, "c-snacks"), line("s3", -9_00, LineBucket.UNSORTED),
        ),
        listOf(MoveEntity(fromEnvelopeId = null, toEnvelopeId = "e1", amount = 120_00, date = today, kind = MoveKind.ASSIGN, createdAt = 1, updatedAt = 1)),
        categories = listOf(rent, snacks),
    )

    @Test fun theTabShowsTheTotalAndEachCategoryAndStepsToOlderMonths() {
        var month = 0
        compose.setContent { CategoriesScreen(budget, today, month, { month = it }, onCategory = {}, onManage = {}) }
        compose.onNodeWithText("Total spent").assertExists()
        compose.onNodeWithText("$16.00").assertExists()
        compose.onNodeWithText("Snacks").assertExists()
        compose.onNodeWithText("Uncategorized").assertExists()
        compose.onNodeWithText("‹ Older").performClick()
        assertEquals(1, month)
    }

    @Test fun anOlderMonthShowsItsOwnTotalsAndNewerStepsBack() {
        var month = 1
        compose.setContent { CategoriesScreen(budget, today, month, { month = it }, onCategory = {}, onManage = {}) }
        compose.onAllNodesWithText("$40.00").assertCountEquals(2)      // the total and the one category
        compose.onNodeWithText("Rent").assertExists()
        compose.onNodeWithText("Newer ›").performClick()
        assertEquals(0, month)
    }

    @Test fun aCategoryListsItsPurchasesForTheMonth() {
        compose.setContent { CategoryPurchasesScreen(budget, today, 1, "c-rent", onBack = {}, onTransaction = {}) }
        compose.onNodeWithText("SAMPLE LANDLORD").assertExists()
        compose.onNodeWithText("Spent").assertExists()
    }

    @Test fun deletingACategoryThatIsUsedAsksWhereToMoveItsPurchases() {
        var deleted: Pair<String, String?>? = null
        compose.setContent {
            ManageCategoriesScreen(listOf(rent, snacks), usage = { if (it == "c-rent") 3 else 0 }, error = null, onBack = {}, onAdd = {}, onRename = { _, _ -> }, onDelete = { id, to -> deleted = id to to })
        }
        compose.onNodeWithText("Delete", useUnmergedTree = true).let { }
        compose.onAllNodesWithText("Delete")[0].performClick()
        compose.onNodeWithText("Choose the category to move them to", substring = true).assertExists()
        assertEquals(null, deleted)
    }

    @Test fun theEnvelopesScreenShowsTheAssignedLine() {
        compose.setContent { EnvelopesScreen(budget, {}, {}, {}, {}, today) }
        compose.onNodeWithText("$120.00 of $500.00 assigned").assertExists()
        assertEquals("$5.00 of $50.00 assigned", assignedLine(5_00, 50_00))
    }
}
