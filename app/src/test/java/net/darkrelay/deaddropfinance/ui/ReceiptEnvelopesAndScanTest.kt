package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onLast
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.ReceiptParser
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
 * 1.9.4 on the phone: every item on the receipt form has its own envelope dropdown, guessed from the last pick for the
 * same item name, then from the receipt's section; Scan receipt on Activity and on the envelope overview (beside Move
 * money and Add) opens the same scan. Invented data. The compose rule needs the debug build, so release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xhdpi")
class ReceiptEnvelopesAndScanTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var shown = false
    private fun show(content: @Composable () -> Unit) {
        screen = content
        if (!shown) { compose.setContent { DdfTheme { screen?.invoke() } }; shown = true }
        compose.waitForIdle()
    }

    private val today = LocalDate.of(2030, 8, 20)
    private val groceries = EnvelopeEntity(id = "g", name = "Groceries", mode = EnvelopeMode.FILL_TO_TARGET, sortOrder = 1, createdAt = 1, updatedAt = 1)
    private val household = EnvelopeEntity(id = "h", name = "Household", mode = EnvelopeMode.FILL_TO_TARGET, sortOrder = 2, createdAt = 1, updatedAt = 1)
    private val budget = Budget(
        listOf(AccountEntity(id = "a", name = "Sample", type = AccountType.BANK, startDate = today.minusDays(30), createdAt = 1, updatedAt = 1)),
        listOf(groceries, household), emptyList(), emptyList(), emptyList(),
    )

    private val text = """
        SAMPLE MARKET
        08/14/2030
        GROCERY
        SAMPLE BREAD 2.50 A
        SAMPLE PAPER TOWELS 6.00 B
        HOME
        SAMPLE CANDLE 4.00 B
        TAX B 5% 0.50
        TOTAL 13.00
    """.trimIndent()

    @Test fun eachItemHasItsOwnEnvelopeGuessedFromThePastThenTheSection() {
        val form = ReceiptForm.from(ReceiptParser.parseLines(text.lines().map { ReceiptParser.Line(it, 95) }))
        // the towels went to Household last time; bread is under GROCERY; the candle (HOME) has no guess
        val past = listOf(ReceiptItemEntity(receiptId = "old", text = "SAMPLE PAPER TOWELS", name = "sample paper towels", amount = 6_00, envelopeId = "h", createdAt = 1, updatedAt = 1))
        form.guessEnvelopes(past, listOf(groceries, household))
        assertEquals(listOf("g", "h", null), form.items.map { it.envelopeId })
        show { ReceiptEditScreen(form, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = listOf(groceries, household)) }
        compose.onNodeWithTag("item.envelope.0").performScrollTo().assertExists()
        compose.onNodeWithTag("item.envelope.2").performScrollTo()
        compose.onAllNodesWithText("No envelope").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        // the user picks one for the candle
        compose.onNodeWithText("No envelope").performClick()
        compose.onAllNodesWithText("Household").onLast().performClick()
        compose.waitForIdle()
        assertEquals(listOf("g", "h", "h"), form.draft(1).items.map { it.envelopeId })
    }

    @Test fun scanReceiptIsOnTheEnvelopeOverviewBesideMoveMoneyAndAdd() {
        var scans = 0
        show { EnvelopesScreen(budget, onEnvelope = {}, onAdd = {}, onMove = {}, onReorder = {}, today = today, onScan = { scans++ }) }
        compose.onNodeWithText("Move money").assertExists()
        compose.onNodeWithText("Add").assertExists()
        compose.onNodeWithTag("envelopes.scan").performClick()
        assertEquals(1, scans)
    }

    @Test fun scanReceiptIsOnActivity() {
        var scans = 0
        show { ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, sorting = SortingUi(onScan = { scans++ })) }
        compose.onNodeWithTag("activity.scan").performClick()
        assertEquals(1, scans)
        compose.onNodeWithText("Scan receipt").assertExists()
    }
}
