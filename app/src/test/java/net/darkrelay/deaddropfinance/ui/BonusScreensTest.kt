package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.BONUS_ACCOUNT_ID
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
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
 * Bonus income's screens (1.0.1): the form, its Home button's routing and its Activity row. Invented data only. The
 * compose rule needs the debug build's test activity, so release unit tests skip it (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BonusScreensTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val day = LocalDate.of(2030, 8, 20)

    private val bonus = TransactionEntity(
        id = "b1", accountId = BONUS_ACCOUNT_ID, kind = TransactionKind.BONUS, date = day, payee = "Cash", memo = "Sample side work",
        amount = 40_00, createdAt = 1, updatedAt = 1,
    )

    @Test fun theActivityRowIsLabeledBonusIncomeWithItsNoteAndReceivedAs() {
        val line = TransactionLineEntity(transactionId = "b1", bucket = LineBucket.READY_TO_ASSIGN, amount = 40_00, createdAt = 1, updatedAt = 1)
        val budget = Budget(emptyList(), emptyList(), listOf(bonus), listOf(line), emptyList())
        compose.setContent { TransactionRow(bonus, budget, onClick = {}) }
        compose.onNodeWithText("Bonus income").assertIsDisplayed()
        compose.onNodeWithText("Cash").assertIsDisplayed()
        compose.onNodeWithText("Sample side work", substring = true).assertIsDisplayed()
    }

    @Test fun theFormSavesTheAmountTodaysDateTheLabelAndTheNote() {
        var saved: BonusForm? = null
        compose.setContent { BonusIncomeScreen(null, null, onBack = {}, onSave = { saved = it }, today = day) }
        compose.onNodeWithText("Amount").performTextInput("12.50")
        compose.onNodeWithText("Note (where it came from)").performTextInput("Sample")
        compose.onNodeWithText("Save").performClick()
        assertEquals(BonusForm(12_50, day, "Cash", "Sample"), saved)
    }

    @Test fun anExistingOneAsksBeforeItDeletes() {
        var deleted = false
        compose.setContent { BonusIncomeScreen(bonus, null, onBack = {}, onSave = {}, onDelete = { deleted = true }, today = day) }
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onNodeWithText("The amount comes back off Ready to Assign.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(false, deleted)
        compose.onNodeWithText("Delete").performScrollTo().performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        assertEquals(true, deleted)
    }
}
