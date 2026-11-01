package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.ReceiptParser
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
 * 1.9.6: the phone's check screen takes the program's pieces that suit a phone: the Taxed slider (one switch, "Not taxed"
 * or "Taxed", its label's room reserved), a flagged field with a plain yellow label and no sentence, and the reasons
 * stacked in a list under the fields (the program's narrow layout), Store and Date included; and the shared confirm
 * dialog (its red fill is checked on the device, PhoneReceiptCheckOnDeviceTest). The compose rule needs the debug build, so the release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h4000dp-xhdpi")
class PhoneReceiptCardTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var shown = false
    private fun show(f: ReceiptForm, content: (@Composable () -> Unit)? = null) {
        screen = content ?: { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}, onClear = {}) }
        if (!shown) { compose.setContent { DdfTheme { screen?.invoke() } }; shown = true }
        compose.waitForIdle()
    }

    private fun form(text: String) = ReceiptForm.from(ReceiptParser.parseLines(text.trimIndent().lines().map { ReceiptParser.Line(it, 95) }))
    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun textOf(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().let { n ->
        fun all(x: androidx.compose.ui.semantics.SemanticsNode): List<String> = x.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + x.children.flatMap { all(it) }
        all(n).joinToString()
    }
    private fun switchText(tag: String): String = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().children
        .flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString { it.text }
    private fun left(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().left.value

    private val clean = """
        SAMPLE MART
        08/14/2030
        LIME JUICE 1.99 A
        BREAD 2.50
        MILK 3.00 A
        SUBTOTAL 7.49
        TAX A 6.000% 0.47
        TOTAL 7.96
    """

    @Test fun oneSliderReplacesTheTaxedAndNotTaxedButtonsAndTheLabelFollowsIt() {
        val f = form(clean)
        show(f)
        compose.onNodeWithTag("item.card.0").performScrollTo()
        assertTrue("no Not taxed button", !has("item.untaxed.0") && !has("item.taxToggle.0"))
        assertTrue(has("item.taxed.0"))
        // item 0 (LIME JUICE, letter A) is taxed, item 1 (BREAD) is not
        assertEquals("Taxed", switchText("item.taxed.0"))
        assertEquals("Not taxed", switchText("item.taxed.1"))
        compose.onNodeWithTag("item.taxed.1").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals("Taxed", switchText("item.taxed.1"))
        assertEquals(true, f.taxedOf(1, f.taxWork()))
        compose.onNodeWithTag("item.taxed.1").performClick(); compose.waitForIdle()
        assertEquals("Not taxed", switchText("item.taxed.1"))
    }

    @Test fun checkedAndRemoveHoldStillWhenTheSliderIsTapped() {
        val f = form(clean)
        show(f)
        compose.onNodeWithTag("item.taxed.1").performScrollTo()
        val before = left("item.checked.1") to left("item.remove.1")
        val slot = left("item.taxed.1")
        compose.onNodeWithTag("item.taxed.1").performClick(); compose.waitForIdle()
        assertEquals(before, left("item.checked.1") to left("item.remove.1"))
        assertEquals(slot, left("item.taxed.1"), 0.5f)
    }

    @Test fun anUnansweredItemIsOffAndYellowAndCheckedClearsIt() {
        val f = form("SAMPLE MART\n08/14/2030\nWIDGET 10.00\nSUBTOTAL 10.00\nTAX 0.60\nTOTAL 10.60")
        show(f)
        assertEquals(null, f.taxedOf(0, f.taxWork()))
        compose.onNodeWithTag("item.taxed.0").performScrollTo()
        assertEquals("Not taxed", switchText("item.taxed.0"))
        assertTrue(has("item.reason.0.0"))
        assertEquals("Check taxed, not sure whether it's taxed", textOf("item.reason.0.0"))
        compose.onNodeWithTag("item.checked.0").performScrollTo().performClick(); compose.waitForIdle()
        assertTrue("Checked clears the yellow", !has("item.reason.0.0"))
    }

    @Test fun aFlaggedCardShowsPlainLabelsAndTheStackedListAndACleanOneShowsNone() {
        val f = form(
            """
            FROZEN/DAIRY
            SAMPLE CHEESE
            20 2.79 5.58 A x
            SAMPLE APPLES W 219 Ax
            TAX A 0.00% 7.77 0.00
            BALANCE DUE 7.77
            """,
        )
        show(f)
        // Store and Date: plain labels, one listed line each, nothing under the fields as a sentence
        assertEquals("Check the store, it wasn't on the photo", textOf("receipt.store.reason"))
        assertEquals("Check the date, it wasn't on the photo", textOf("receipt.date.reason"))
        assertTrue("no sentence attached to a field", compose.onAllNodes(hasTestTag("warn"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Store").assertExists()
        compose.onNodeWithText("Date (yyyy-mm-dd)").assertExists()
        // the list is stacked under the item's fields: below the Price box, one line each, starting with Check
        val flagged = (0 until f.items.size).first { has("item.reason.$it.0") }
        compose.onNodeWithTag("item.reasons.$flagged").performScrollTo()
        var n = 0
        while (has("item.reason.$flagged.$n")) { assertTrue(textOf("item.reason.$flagged.$n"), textOf("item.reason.$flagged.$n").startsWith("Check ")); n++ }
        assertTrue(n >= 1)
        val price = compose.onNodeWithTag("item.price.$flagged", useUnmergedTree = true).getBoundsInRoot()
        val list = compose.onNodeWithTag("item.reasons.$flagged", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("stacked under the fields: list top ${list.top} price bottom ${price.bottom}", list.top >= price.bottom)
        // clean card, clean Store and Date: no list at all
        val g = form(clean)
        screen = { ReceiptEditScreen(g, emptyList(), true, false, null, {}, {}, {}, {}, onClear = {}) }
        compose.waitForIdle()
        assertTrue(!has("item.reasons.0") && !has("item.reason.0.0") && !has("receipt.store.reason") && !has("receipt.date.reason"))
    }

    @Test fun theClearConfirmIsTheSharedOneCancelOnTheLeftAndTheActionOnTheRight() {
        val f = form(clean)
        show(f)
        compose.onNodeWithTag("receipt.clear", useUnmergedTree = true).performScrollTo().performClick(); compose.waitForIdle()
        compose.onNodeWithText("Clear this receipt?").assertExists()
        val cancel = compose.onNodeWithTag("clear.cancel", useUnmergedTree = true).getBoundsInRoot()
        val action = compose.onNodeWithTag("clear.confirm", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("Cancel on the left", cancel.right <= action.left)
        assertEquals("Cancel", textOf("clear.cancel"))
        assertEquals("Clear", textOf("clear.confirm"))
    }
}
