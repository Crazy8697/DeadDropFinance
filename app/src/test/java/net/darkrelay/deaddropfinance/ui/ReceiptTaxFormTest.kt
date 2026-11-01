package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
 * The receipt form (1.9.3 tax letters; 1.9.5 cards, totals, toggles and yellow), on invented receipt text: each item in
 * its own card with a Taxed or Not taxed toggle (a letter is never shown or typed), its price and its price with tax; the
 * totals as the receipt shows them (untaxed items, taxed items, tax, total paid); fields the reader wasn't sure of in
 * yellow until edited or Checked; the price a misread digit would explain; Clear after asking. The compose rule needs the
 * debug build, so the release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h4000dp-xhdpi")
class ReceiptTaxFormTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private fun form(text: String): ReceiptForm = ReceiptForm.from(ReceiptParser.parseLines(text.trimIndent().lines().map { ReceiptParser.Line(it, 95) }))

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var shown = false
    private fun show(f: ReceiptForm, onClear: (() -> Unit)? = null) {
        screen = { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}, onClear = onClear) }
        if (!shown) { compose.setContent { DdfTheme { screen?.invoke() } }; shown = true }
        compose.waitForIdle()
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

    private fun field(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    @Test fun lettersAreNeverShownTheToggleIsAndEachItemShowsItsPriceWithTax() {
        val f = form(matched)
        assertEquals(listOf("A", "", "A"), f.items.map { it.letter })
        show(f)
        assertEquals(0, compose.onAllNodesWithText("Tax letter").fetchSemanticsNodes().size)
        val work = f.taxWork()
        assertEquals(listOf(true, false, true), f.items.indices.map { f.taxedOf(it, work) })
        field("item.withTax.0").performScrollTo().assertTextContains("2.18")
        field("item.withTax.1").assertTextContains("2.50")
        field("item.withTax.2").assertTextContains("3.28")
        // the totals, each saying what it is
        field("breakdown.untaxed").performScrollTo().assertTextContains("2.50")
        field("breakdown.taxed").assertTextContains("4.99")
        field("breakdown.tax").assertTextContains("0.47")
        field("breakdown.total").assertTextContains("7.96")
        compose.onNodeWithText("Total paid").assertExists()
        assertEquals(0, compose.onAllNodes(hasText("Total", substring = false)).fetchSemanticsNodes().size)
        compose.onNodeWithTag("tax.addsUp").performScrollTo().assertExists()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
        // the letters are still kept
        assertEquals(listOf("A", "", "A"), f.draft(1).items.map { it.taxLetter })
        assertEquals(listOf(2_18L, 2_50L, 3_28L), f.draft(1).items.map { it.priceWithTax })
    }

    @Test fun theTaxedToggleDecidesWhenItsChanged() {
        val f = form(matched)
        show(f)
        field("item.taxed.1").performScrollTo().performClick()
        compose.waitForIdle()
        // the receipt's 0.47 is now shared between all three, by price
        assertEquals(listOf(2_11L, 2_66L, 3_19L), f.draft(1).items.map { it.priceWithTax })
        field("breakdown.taxed").performScrollTo().assertTextContains("7.49")
        field("breakdown.untaxed").assertTextContains("0.00")
    }

    @Test fun whenTheItemsDontAddUpTheTotalPaidSaysByHowMuch() {
        val f = form(matched)
        f.total = "8.50"
        show(f)
        compose.onNodeWithText("the items come to \$0.54 less than the total paid (a tip, or a missed line?)").performScrollTo().assertExists()
        assertEquals(0, compose.onAllNodes(hasTestTag("tax.addsUp")).fetchSemanticsNodes().size)
    }

    /** 0.15.5: only a price the reader already doubted is pointed at; a clean line never is, even one digit away. */
    @Test fun aDoubtedPriceOneDigitFromAddingUpIsPointedAtAndNeverChanged() {
        val lines = listOf("SAMPLE MART" to 95, "08/14/2030" to 95, "SAMPLE CREAM 9.49" to 40, "SAMPLE BUTTER 2.99" to 95, "TOTAL 8.48" to 95)
        val f = ReceiptForm.from(ReceiptParser.parseLines(lines.map { (t, c) -> ReceiptParser.Line(t, c) }))
        show(f)
        assertEquals(mapOf(0 to 5_49L), f.misread(f.totals(f.taxWork())))
        compose.onNodeWithText("Check price, may be misread: \$5.49 would make it add up").performScrollTo().assertExists()
        compose.onNodeWithText("the items come to \$4.00 more than the total paid").assertExists()
        assertEquals("9.49", f.items[0].amount)
        // the user fixes it: it adds up and the yellow goes
        field("item.price.0").performScrollTo().performTextReplacement("5.49")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Check price, may be misread: \$5.49 would make it add up").fetchSemanticsNodes().size)
        compose.onNodeWithTag("tax.addsUp").performScrollTo().assertExists()
    }

    @Test fun aCleanPriceIsNeverPointedAtTheGapStillShows() {
        val f = form(
            """
            SAMPLE MART
            08/14/2030
            SAMPLE CREAM 9.49
            SAMPLE BUTTER 2.99
            TOTAL 8.48
            """,
        )
        show(f)
        assertEquals(emptyMap<Int, Long>(), f.misread(f.totals(f.taxWork())))
        compose.onNodeWithText("the items come to \$4.00 more than the total paid").performScrollTo().assertExists()
        assertEquals(0, compose.onAllNodesWithText("would make it add up", substring = true).fetchSemanticsNodes().size)
    }

    /** 0.15.5: an envelope guessed from the built-in section map is never yellow. */
    @Test fun anEnvelopeFromTheSectionMapIsNeverYellow() {
        val f = form("SAMPLE MART\n08/14/2030\nHBA\nSAMPLE SHAMPOO 4.00\nTOTAL 4.00")
        val household = net.darkrelay.deaddropfinance.data.EnvelopeEntity(id = "h", name = "Household", mode = net.darkrelay.deaddropfinance.data.EnvelopeMode.FILL_TO_TARGET, createdAt = 1, updatedAt = 1)
        f.guessEnvelopes(emptyList(), listOf(household))
        assertEquals("h", f.items.single().envelopeId)
        screen = { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = listOf(household)) }
        if (!shown) { compose.setContent { DdfTheme { screen?.invoke() } }; shown = true }
        compose.waitForIdle()
        assertTrue(f.items.none { "envelope" in it.unsure })
        assertEquals(0, compose.onAllNodes(androidx.compose.ui.test.hasTestTag("warn") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("item.envelope.0")), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun unmatchedTaxAsksThroughTheTogglesAndSaveWaits() {
        val f = form(unmatched)
        show(f)
        compose.onNodeWithTag("tax.ask").performScrollTo().assertExists()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsNotEnabled()
        assertNull(f.taxedOf(0, f.taxWork()))
        compose.onAllNodesWithText("Check taxed, not sure whether it's taxed").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        field("item.taxed.0").performScrollTo().performClick()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsNotEnabled()
        // item 1: the slider is off while it is unanswered; on and off again answers Not taxed
        field("item.taxed.1").performScrollTo().performClick()
        field("item.taxed.1").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
        assertEquals(listOf(10_60L, 4_00L), f.draft(1).items.map { it.priceWithTax })
        compose.onNodeWithTag("tax.addsUp").performScrollTo().assertExists()
    }

    @Test fun taxButNoLettersAsksAndAllOfThemCanBeAnsweredAtOnce() {
        val f = form("SAMPLE MART\n08/14/2030\nWIDGET 10.00\nSUBTOTAL 10.00\nTAX 0.60\nTOTAL 10.60")
        show(f)
        compose.onNodeWithText("Which items are taxed?").performScrollTo().assertExists()
        compose.onNodeWithTag("tax.all.taxed").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
        assertEquals(10_60L, f.draft(1).items.single().priceWithTax)
    }

    @Test fun unsureFieldsAreYellowWithWhyUntilEditedOrChecked() {
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
        // what wasn't on the photo, and what may be misread, each says so
        compose.onNodeWithText("Check the store, it wasn't on the photo").performScrollTo().assertExists()
        compose.onNodeWithText("Check the date, it wasn't on the photo").assertExists()
        compose.onNodeWithText("Check quantity, read from a smudged line").performScrollTo().assertExists()
        compose.onNodeWithText("may be misread", substring = true).performScrollTo().assertExists()
        assertTrue(compose.onAllNodesWithText("(check this)", substring = true).fetchSemanticsNodes().isEmpty())
        // editing the store clears its yellow
        field("receipt.store").performScrollTo().performTextReplacement("Sample Mart")
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Check the store, it wasn't on the photo").fetchSemanticsNodes().size)
        // Checked: a mark, the card dimmed, its yellow quiet; saving isn't blocked and nothing changes
        field("item.checked.1").performScrollTo().performClick()
        compose.waitForIdle()
        field("item.checkedMark.1").assertExists()
        assertEquals(0, compose.onAllNodesWithText("may be misread", substring = true).fetchSemanticsNodes().size)
        assertTrue(f.items[1].checked)
        assertEquals("2.19", f.items[1].amount)
        compose.onNodeWithTag("receipt.save").performScrollTo().assertIsEnabled()
    }

    @Test fun quantitiesShowTheirUnitPriceAndLineTotalAsTheReceiptDoes() {
        val f = form(
            """
            SAMPLE MART
            08/14/2030
            SAMPLE TEA
            2 @ 2.79 5.58 A
            SAMPLE CABBAGE
            1.26 lb @ 2.89/lb 3.64 A
            SAMPLE GUM 1.00 A
            TOTAL 10.22
            """,
        )
        show(f)
        field("item.qtyLine.0").performScrollTo().assertTextContains("2 @ \$2.79 = \$5.58")
        field("item.qtyLine.1").performScrollTo().assertTextContains("1.26 lb @ \$2.89/lb = \$3.64")
        assertEquals(0, compose.onAllNodes(hasTestTag("item.qtyLine.2")).fetchSemanticsNodes().size)
    }

    @Test fun clearAsksFirstAndCancelIsTheDefault() {
        val f = form(matched)
        var cleared = 0
        show(f, onClear = { cleared++ })
        compose.onNodeWithTag("receipt.clear").performClick()
        compose.onNodeWithText("Clear this receipt?").assertExists()
        compose.onNodeWithText("Nothing has been saved. The photos, what was read and your changes are thrown out.").assertExists()
        compose.onNodeWithTag("clear.cancel").performClick()
        compose.waitForIdle()
        assertEquals(0, cleared)
        compose.onNodeWithTag("receipt.clear").performClick()
        compose.onNodeWithTag("clear.confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test fun theFormIsKeptExactlyAsLeft() {
        val f = form(unmatched)
        f.store = "Sample Mart edited"; f.edited("store")
        f.editItem(0, "taxed") { it.copy(taxed = true) }
        f.items[1] = f.items[1].copy(checked = true, envelopeId = "e1")
        val back = ScanDraft.fromJson(ScanDraft("scan-1", null, listOf(listOf(ReceiptParser.Line("WIDGET 10.00 T", 88))), f).toJson())!!
        assertEquals("scan-1", back.scanId)
        assertEquals("WIDGET 10.00 T", back.pages.single().single().text)
        assertEquals(f.store, back.form.store)
        assertEquals(f.items.toList(), back.form.items.toList())
        assertEquals(f.notes, back.form.notes)
        assertEquals(f.taxLines, back.form.taxLines)
        assertFalse(back.form.items[0].checked)
    }
}
