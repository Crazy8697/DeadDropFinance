package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.BalanceCheck
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
 * The balance check's wrong-sign warning (1.2.2): Adjust, its confirmation (account, starting balance now and new, the
 * balance it gives), Cancel changing nothing; other differences keep Add an adjustment. Invented numbers. The compose
 * rule needs the debug build's test activity, so release unit tests skip these (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h1400dp-xhdpi")
class BalanceCheckViewTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val day = LocalDate.of(2030, 8, 20)
    private val flipped = BalanceCheck.Result.FlippedSign(bank = -23_14, app = 23_14, asOf = day)

    @Test fun theWrongSignWarningHasAdjustAndCancelChangesNothing() {
        var fixed = 0
        compose.setContent { BalanceCheckView(flipped, "Sample Checking", onAdjust = { _, _ -> }, startingBalance = 50_00, onFixStart = { fixed++ }) }
        compose.onNodeWithText("Balance check: the bank's balance looks to have the wrong sign.").assertExists()
        compose.onNodeWithText("nothing needs fixing", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Adjust").performClick()
        compose.onNodeWithText("Adjust the starting balance?").assertExists()
        compose.onNodeWithText("Sample Checking").assertExists()
        compose.onNodeWithText("Starting balance now: $50.00").assertExists()
        compose.onNodeWithText("New starting balance: $3.72").assertExists()
        compose.onNodeWithText("becomes -$23.14", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals("Cancel changes nothing", 0, fixed)
        compose.onNodeWithText("Adjust").performClick()
        compose.onAllNodesWithText("Adjust").onLast().performClick()
        assertEquals(1, fixed)
    }

    @Test fun otherDifferencesKeepAddAnAdjustment() {
        compose.setContent {
            BalanceCheckView(BalanceCheck.Result.Mismatch(bank = 10_00, app = 4_00, asOf = day), "Sample Checking", onAdjust = { _, _ -> }, startingBalance = 50_00, onFixStart = {})
        }
        compose.onNodeWithText("Add an adjustment").assertExists()
        compose.onNodeWithText("Adjust").assertDoesNotExist()
    }

    @Test fun withoutTheFixTheWarningReadsAsBefore() {
        compose.setContent { BalanceCheckView(flipped, "Sample Checking", onAdjust = null) }
        compose.onNodeWithText("nothing needs fixing", substring = true).assertExists()
        compose.onNodeWithText("Adjust").assertDoesNotExist()
    }
}
