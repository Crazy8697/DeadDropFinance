package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.budget
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.today
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

/**
 * Add activity on an envelope's screen (1.3.0): the button, and the Add transaction screen it opens with the envelope
 * chosen. Invented data. The compose rule needs the debug build's test activity, so release unit tests skip these
 * (see ui/PurgeWizardTest); domain/AddActivitySaveTest covers the save in both builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h4000dp-xhdpi")
class AddActivityTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private fun edit(presetEnvelope: String?, onBack: () -> Unit = {}, onSave: (BudgetActions.TransactionDraft) -> Unit) = compose.setContent {
        TransactionEditScreen(budget(), null, false, null, onBack, onSave, { _, _, _, _, _, _ -> }, presetEnvelope = presetEnvelope)
    }

    @Test fun theButtonIsOnEveryEnvelopeScreenWithTheOthers() {
        // one composition, the envelope swapped (a compose rule takes setContent once per test)
        val shown = mutableStateOf(EnvelopeActivityFixture.gaming)
        var added = 0
        compose.setContent { EnvelopeDetailScreen(shown.value, budget(), today, {}, {}, {}, {}, {}, {}, {}, onAddActivity = { added++ }) }
        for ((i, e) in listOf(EnvelopeActivityFixture.gaming, EnvelopeActivityFixture.rollover, EnvelopeActivityFixture.unplanned).withIndex()) {
            shown.value = e
            compose.waitForIdle()
            compose.onNodeWithText("Move money").assertExists()
            compose.onNodeWithText("Add activity").performClick()
            assertEquals(e.name, i + 1, added)
        }
    }

    @Test fun itOpensTheAddScreenWithTheEnvelopeChosenAndTheAccountPicker() {
        var got: BudgetActions.TransactionDraft? = null
        edit("g") { got = it }
        compose.onNodeWithText("New transaction").assertExists()
        compose.onNodeWithText("Account").assertExists()
        compose.onNodeWithText("Sample Checking").assertExists()
        compose.onNodeWithText("Gaming").assertExists()
        compose.onNodeWithText("Payee or description").performTextInput("SAMPLE GAS")
        compose.onNodeWithText("Amount").performTextInput("20.00")
        compose.onNodeWithText("Save").performClick()
        val d = got!!
        assertEquals("a", d.accountId)
        assertEquals(-20_00L, d.amount)
        assertEquals(listOf("g" to -20_00L), d.lines.map { it.envelopeId to it.amount })
    }

    @Test fun backSavesNothing() {
        var saved = 0
        var back = 0
        edit("g", onBack = { back++ }) { saved++ }
        compose.onNodeWithText("Amount").performTextInput("20.00")
        compose.onNodeWithText("Back").performClick()
        assertEquals(1, back)
        assertEquals(0, saved)
    }

    @Test fun addFromActivityStartsAsBefore() {
        var got: BudgetActions.TransactionDraft? = null
        edit(null) { got = it }
        compose.onNodeWithText("Account").assertExists()
        compose.onNodeWithText("Gaming").assertDoesNotExist()
        compose.onNodeWithText("Amount").performTextInput("20.00")
        compose.onNodeWithText("Save").performClick()
        // no envelope chosen, as before: whatever Save does, no envelope is sent
        assertTrue(got == null || got!!.lines.all { it.envelopeId == null })
    }
}
