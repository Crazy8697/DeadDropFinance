package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Activity rows (1.2.1), with invented data: three lines each (name, info, chips), the name on one line cut with an
 * ellipsis, and the amount in a fixed column that never wraps or moves, at the normal and a larger system font size.
 * The compose rule needs the debug build's test activity, so release unit tests skip these (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w360dp-h1400dp-xhdpi")
class ActivityRowTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val day = LocalDate.of(2030, 8, 20)
    private val acct = AccountEntity(id = "a", name = "Sample Checking", type = AccountType.BANK, startDate = day.minusDays(30), createdAt = 1, updatedAt = 1)
    private val cat = CategoryEntity(id = "c", name = "Groceries", createdAt = 1, updatedAt = 1)
    private val names = listOf(
        "POS DEBIT SAMPLE MERCHANT #0042 1234 SAMPLE AVENUE SAMPLETOWN XX 00000 CARD 9999",
        "Sample Coffee Roasters And Bakery Market",
        "SAMPLE SHOP",
        "SAMPLE PAY",
    )
    private val amounts = listOf(-12_34L, -45_67L, -98_765_43L, 12_345_67L)

    private fun budget(): Budget {
        val txs = names.indices.map { i -> TransactionEntity(id = "t$i", accountId = "a", date = day, payee = names[i], amount = amounts[i], isPayday = i == 3, createdAt = 1, updatedAt = 1) }
        val lines = txs.map { t ->
            TransactionLineEntity(transactionId = t.id, bucket = if (t.amount < 0) LineBucket.UNSORTED else LineBucket.READY_TO_ASSIGN, amount = t.amount, categoryId = if (t.amount < 0) "c" else null, createdAt = 1, updatedAt = 1)
        }
        return Budget(listOf(acct), emptyList(), txs, lines, emptyList(), categories = listOf(cat))
    }

    private fun layoutOf(n: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        n.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    private fun check() {
        val b = budget()
        compose.setContent { Column { b.transactions.forEach { t -> TransactionRow(t, b) {} } } }
        val rows = compose.onAllNodesWithTag("activityRow", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(4, rows.size)
        val heights = rows.map { it.boundsInRoot.height }
        assertTrue("equal row heights: $heights", heights.all { kotlin.math.abs(it - heights[0]) < 0.5f })
        val nameNodes = compose.onAllNodesWithTag("rowName", useUnmergedTree = true).fetchSemanticsNodes()
        for (n in nameNodes) {
            val l = layoutOf(n)
            assertEquals("one line for ${l.layoutInput.text}", 1, l.lineCount)
        }
        assertTrue("the long name is cut", layoutOf(nameNodes[0]).isLineEllipsized(0))
        val amountCols = compose.onAllNodesWithTag("rowAmount", useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("amount columns line up", amountCols.all { kotlin.math.abs(it.left - amountCols[0].left) < 0.5f && kotlin.math.abs(it.right - amountCols[0].right) < 0.5f })
        val amountTexts = compose.onAllNodesWithTag("rowAmountText", useUnmergedTree = true).fetchSemanticsNodes()
        for ((i, n) in amountTexts.withIndex()) {
            val l = layoutOf(n)
            assertEquals("amount on one line", 1, l.lineCount)
            val textWidth = l.multiParagraph.maxIntrinsicWidth
            assertTrue("amount fits its column (${l.layoutInput.text}: $textWidth of ${amountCols[i].width})", textWidth <= amountCols[i].width + 0.5f)
            assertTrue("amount not cut", !l.isLineEllipsized(0))
        }
    }

    @Test fun threeLinesOneLineNamesAndAlignedAmounts() = check()

    @Test @Config(fontScale = 1.5f) fun theSameAtALargerSystemFontSize() = check()
}
