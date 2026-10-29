package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.budget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Payday and Fill envelopes boxes on New transaction (1.3.3). Invented data. Debug build only (the compose rule's
 * test activity), like the other screen tests; domain/PaydayCheckboxTest covers the saving in both builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h4000dp-xhdpi")
class PaydayBoxesTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private var saved: BudgetActions.TransactionDraft? = null
    private var payday: Pair<BudgetActions.TransactionDraft, Boolean>? = null

    private fun show() = compose.setContent {
        TransactionEditScreen(budget(), null, false, null, {}, { saved = it }, { _, _, _, _, _, _ -> }, onSavePayday = { d, f -> payday = d to f })
    }

    @Test fun bothAreGreyedForSpending() {
        show()
        compose.onNodeWithTag("payday").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("fillEnvelopes").assertIsNotEnabled().assertIsOff()
    }

    @Test fun fillIsGreyedUntilPaydayIsTickedAndUntickingPaydayUnticksFill() {
        show()
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithTag("payday").assertIsEnabled().assertIsOff()
        compose.onNodeWithTag("fillEnvelopes").assertIsNotEnabled()
        compose.onNodeWithTag("payday").performClick()
        compose.onNodeWithTag("fillEnvelopes").assertIsEnabled().assertIsOff()
        compose.onNodeWithTag("fillEnvelopes").performClick()
        compose.onNodeWithTag("fillEnvelopes").assertIsOn()
        compose.onNodeWithTag("payday").performClick()
        compose.onNodeWithTag("fillEnvelopes").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("payday").performClick()
        compose.onNodeWithTag("fillEnvelopes").assertIsOff()
    }

    @Test fun aPaydayWithFillIsSavedAsAPayday() {
        show()
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithText("From").performTextInput("Sample Payroll Co")
        compose.onNodeWithText("Amount").performTextInput("1500.00")
        compose.onNodeWithTag("payday").performClick()
        compose.onNodeWithTag("fillEnvelopes").performClick()
        compose.onNodeWithText("Save").performClick()
        assertNull(saved)
        val (d, fill) = payday!!
        assertEquals(true, fill)
        assertEquals(1500_00L, d.amount)
        assertEquals(listOf(true), d.lines.map { it.toReadyToAssign })
    }

    @Test fun moneyInWithoutPaydayIsPlainMoneyIn() {
        show()
        compose.onNodeWithText("Income").performClick()
        compose.onNodeWithText("Amount").performTextInput("25.00")
        compose.onNodeWithText("Save").performClick()
        assertNull(payday)
        assertEquals(25_00L, saved!!.amount)
    }
}
