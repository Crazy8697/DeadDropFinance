package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
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
 * Edit a move on the phone (1.8.1), with invented data: a move made by hand on an envelope's screen opens Edit move;
 * Save hands over the new date, ends and amount; Delete asks "Delete this move?" first, as a transaction's delete does;
 * a payday's auto-fill row doesn't open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h1400dp-xhdpi")
class EditMoveScreenTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val day = LocalDate.of(2030, 5, 3)
    private val food = EnvelopeEntity(id = "food", name = "Sample Food", mode = EnvelopeMode.FILL_TO_TARGET, sortOrder = 1, createdAt = 1, updatedAt = 1)
    private val gas = EnvelopeEntity(id = "gas", name = "Sample Gas", mode = EnvelopeMode.FILL_TO_TARGET, sortOrder = 2, createdAt = 1, updatedAt = 1)
    private val byHand = MoveEntity(id = "m1", fromEnvelopeId = "food", toEnvelopeId = "gas", amount = 25_00, date = day, kind = MoveKind.MOVE, createdAt = 2, updatedAt = 2)
    private val fill = MoveEntity(id = "m2", fromEnvelopeId = null, toEnvelopeId = "food", amount = 100_00, date = day.minusDays(1), kind = MoveKind.AUTOFILL, paydayTransactionId = "pay", createdAt = 1, updatedAt = 1)
    private val budget = Budget(emptyList(), listOf(food, gas), emptyList(), emptyList(), listOf(byHand, fill))

    @Test fun uiAMoveByHandOpensAndTheAutoFillDoesNot() {
        val opened = mutableListOf<String>()
        compose.setContent { EnvelopeDetailScreen(food, budget, day, {}, {}, {}, {}, {}, {}, {}, onMoveRow = { opened += it }) }
        compose.onNodeWithText("Moved out to Sample Gas").performScrollTo().performClick()
        compose.onNodeWithText("Payday auto-fill").performScrollTo().performClick()
        assertEquals(listOf("m1"), opened)
    }

    @Test fun uiEditMoveSavesTheNewDateEndsAndAmount() {
        var saved: List<Any?>? = null
        compose.setContent {
            MoveMoneyScreen(budget, null, null, {}, editing = byHand, onSaveEdit = { f, t, a, d -> saved = listOf(f, t, a, d) }) { _, _, _ -> }
        }
        compose.onNodeWithText("Edit move").assertExists()
        compose.onAllNodesWithText("Amount")[0].performTextClearance()
        compose.onAllNodesWithText("Amount")[0].performTextInput("40.00")
        compose.onNodeWithText("Date (yyyy-mm-dd)").performTextClearance()
        compose.onNodeWithText("Date (yyyy-mm-dd)").performTextInput("2030-05-01")
        compose.onNodeWithText("Save").performClick()
        assertEquals(listOf("food", "gas", 40_00L, LocalDate.of(2030, 5, 1)), saved)
    }

    @Test fun uiDeleteAsksFirst() {
        var deleted = 0
        compose.setContent { MoveMoneyScreen(budget, null, null, {}, editing = byHand, onDelete = { deleted++ }) { _, _, _ -> } }
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete this move?").assertExists()
        compose.onNodeWithText("Keep").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete").performClick()
        // the question's own Delete (the button it replaced is gone)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, deleted)
    }
}
