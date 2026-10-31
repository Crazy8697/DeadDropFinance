package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The receipt form with tax letters (1.9.3), on invented receipt text: each item shows its letter in its own field and
 * its price with tax; the form says whether the items with tax add up to the total; and when the letters can't be
 * matched to the tax lines it asks, and Save waits for the answer. The compose rule needs the debug build, so the
 * release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xhdpi")
class ReceiptTaxFormTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private fun form(text: String): ReceiptForm {
        val p = ReceiptParser.parseLines(text.trimIndent().lines().map { ReceiptParser.Line(it, 95) })
        return ReceiptForm.from(p)
    }

    private fun show(f: ReceiptForm) = compose.setContent {
        DdfTheme { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}) }
    }

    private val matched = """
        SAMPLE MART
        08/14/2030
        LIME JUICE 1.99 A
        BREAD 2.50
        MILK 3.00 A
        SUBTOTAL 7.49
        TAX A 6.000% 0.47
        TOTAL 7.96
    """

    private val unmatched = """
        SAMPLE MART
        08/14/2030
        WIDGET 10.00 T
        APPLES 4.00 F
        SUBTOTAL 14.00
        TAX 0.60
        TOTAL 14.60
    """

    @Test fun lettersAreTheirOwnFieldAndEachItemShowsItsPriceWithTax() {
        val f = form(matched)
        assertEquals(listOf("LIME JUICE", "BREAD", "MILK"), f.items.map { it.text })
        assertEquals(listOf("A", "", "A"), f.items.map { it.letter })
        show(f)
        compose.onNodeWithText("LIME JUICE: \$1.99 + tax A = \$2.18").assertExists()
        compose.onNodeWithText("MILK: \$3.00 + tax A = \$3.28").assertExists()
        compose.onAllNodesWithText("BREAD: ", substring = true).let { assertEquals(0, it.fetchSemanticsNodes().size) }
        compose.onNodeWithTag("tax.addsUp").performScrollTo().assertExists()
        compose.onNodeWithText("Items with tax \$7.96 = the total.").assertExists()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
        val d = f.draft(1)
        assertEquals(listOf(2_18L, 2_50L, 3_28L), d.items.map { it.priceWithTax })
        assertEquals("shelf prices are kept", listOf(1_99L, 2_50L, 3_00L), d.items.map { it.amount })
        assertEquals(listOf("A", "", "A"), d.items.map { it.taxLetter })
        assertTrue("no letter in a name", d.items.none { it.text.endsWith(" A") })
    }

    @Test fun whenTheItemsWithTaxDontAddUpTheFormSaysSo() {
        val f = form(matched)
        f.total = "8.50"
        show(f)
        compose.onNodeWithText("Items with tax \$7.96; the total says \$8.50", substring = true).assertExists()
    }

    @Test fun unmatchedLettersAskWhichAreTaxedAndSaveWaitsForTheAnswer() {
        val f = form(unmatched)
        show(f)
        compose.onNodeWithTag("tax.ask").performScrollTo().assertExists()
        compose.onNodeWithText("Which items are taxed?").assertExists()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsNotEnabled()
        // nothing was guessed: the prices with tax are the shelf prices until it's answered
        assertEquals(listOf(10_00L, 4_00L), f.draft(1).items.map { it.priceWithTax })
        compose.onNodeWithTag("tax.T.taxed").performScrollTo().performClick()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("tax.F.untaxed").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
        assertEquals(0, compose.onAllNodesWithText("Which items are taxed?").fetchSemanticsNodes().size)
        val d = f.draft(1)
        assertEquals(listOf(10_60L, 4_00L), d.items.map { it.priceWithTax })
        compose.onNodeWithText("Items with tax \$14.60 = the total.").assertExists()
    }

    @Test fun anItemCanBeMarkedTaxedOnItsOwn() {
        val f = form(unmatched)
        show(f)
        compose.onNodeWithTag("tax.item.0").performScrollTo().performClick()
        compose.onNodeWithTag("tax.item.1").performScrollTo().performClick()
        compose.waitForIdle()
        // both marked taxed: the 0.60 is shared by price, 10.00 to 4.00
        assertEquals(listOf(10_43L, 4_17L), f.draft(1).items.map { it.priceWithTax })
    }

    @Test fun aReceiptWithoutLettersIsAsItWas() {
        val f = form(
            """
            SAMPLE MART
            08/14/2030
            WIDGET 10.00
            SUBTOTAL 10.00
            TAX 0.60
            TOTAL 10.60
            """,
        )
        show(f)
        assertEquals(0, compose.onAllNodesWithText("Which items are taxed?").fetchSemanticsNodes().size)
        compose.onNodeWithText("Items \$10.00 + tax \$0.60 = \$10.60, the total.").performScrollTo().assertExists()
        assertNull(f.draft(1).items.single().priceWithTax)
        assertFalse(f.taxWork().hasLetters)
    }
}
