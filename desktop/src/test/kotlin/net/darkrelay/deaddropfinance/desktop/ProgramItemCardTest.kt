package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.LocalProgramStyle
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import net.darkrelay.deaddropfinance.ui.ProgramType
import net.darkrelay.deaddropfinance.ui.ReceiptEditScreen
import net.darkrelay.deaddropfinance.ui.ReceiptForm
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.8: the program's item card as the approved mockup draws it: two rows, the measures (Qty 90, Price 120, With tax 120,
 * Item as wide as the three, Envelope 200, Checked and Remove 80, every box 26), the Taxed slider, plain labels with a
 * yellow border, and the reasons listed on the right of a flagged card only.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramItemCardTest {

    @After fun reset() { DdfColors.palette = PhonePalette }

    private val envelopes = listOf(net.darkrelay.deaddropfinance.data.EnvelopeEntity(id = "g", name = "Groceries", mode = net.darkrelay.deaddropfinance.data.EnvelopeMode.FILL_TO_TARGET, createdAt = 1, updatedAt = 1))

    /** Item 0 is doubted (low confidence, its price one digit from adding up); item 1 is clean. */
    private fun form(): ReceiptForm {
        val lines = listOf("SAMPLE MART" to 95, "08/14/2030" to 95, "SAMPLE CREAM 9.49" to 40, "SAMPLE BUTTER 2.99" to 95, "TOTAL 8.48" to 95)
        return ReceiptForm.from(ReceiptParser.parseLines(lines.map { (t, c) -> ReceiptParser.Line(t, c) })).also { it.guessEnvelopes(emptyList(), envelopes) }
    }

    private fun ComposeUiTest.show(f: ReceiptForm) {
        DdfColors.palette = ProgramPalette
        ProgramType.family = Segoe
        setContent {
            DdfTheme { CompositionLocalProvider(LocalProgramStyle provides true) { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = envelopes) } }
        }
        waitForIdle()
    }

    private fun ComposeUiTest.box(tag: String): Rect = onNodeWithTag(tag, useUnmergedTree = true).getBoundsInRoot().let { Rect(it.left.value, it.top.value, it.right.value, it.bottom.value) }
    private fun ComposeUiTest.has(tag: String) = onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.reasons(i: Int) = (0 until 8).filter { has("item.reason.$i.$it") }.map {
        onNodeWithTag("item.reason.$i.$it", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { t -> t.text }
    }

    @Test fun theCardIsTwoRowsWithTheMockupsMeasures() = runDesktopComposeUiTest(1400, 900) {
        show(form())
        for (i in 0..1) {
            val item = box("item.name.$i.box"); val env = box("item.envelope.$i")
            val qty = box("item.qty.$i.box"); val price = box("item.price.$i.box"); val tax = box("item.withTax.$i.box")
            val slider = box("item.taxed.$i"); val checked = box("item.checked.$i"); val remove = box("item.remove.$i")
            // row 1: Item then Envelope; row 2: Qty, Price, With tax, slider, Checked, Remove
            assertEquals(item.top, env.top, 0.6f); assertEquals(qty.top, price.top, 0.6f); assertEquals(price.top, tax.top, 0.6f)
            assertTrue(qty.top > item.bottom, "row 2 is under row 1")
            // widths as drawn, ten apart
            assertEquals(90f, qty.width, 0.6f); assertEquals(120f, price.width, 0.6f); assertEquals(120f, tax.width, 0.6f)
            assertEquals(10f, price.left - qty.right, 0.6f); assertEquals(10f, tax.left - price.right, 0.6f)
            assertEquals(296f, env.width, 0.6f); assertEquals(10f, env.left - item.right, 0.6f)
            // Envelope runs on to Remove's right edge, exactly
            assertEquals(remove.right, env.right, 0.6f)
            // Item's right edge is With tax's, exactly
            assertEquals(tax.right, item.right, 0.6f); assertEquals(qty.left, item.left, 0.6f)
            // every box is 26 tall
            for (b in listOf(item, env, qty, price, tax, checked, remove)) assertEquals(26f, b.height, 0.6f)
            // the slider, then Checked and Remove (80 each, 10 apart)
            assertTrue(slider.left > tax.right && slider.right <= checked.left)
            assertEquals(80f, checked.width, 0.6f); assertEquals(80f, remove.width, 0.6f); assertEquals(10f, remove.left - checked.right, 0.6f)
        }
    }

    @Test fun theTaxedSliderIsOneSwitch() = runDesktopComposeUiTest(1400, 900) {
        show(form())
        assertTrue(!has("item.untaxed.0"), "no Not taxed button any more")
        fun on(i: Int) = onNodeWithTag("item.taxed.$i", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Selected]
        assertEquals(false, on(1))
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(true, on(1))
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(false, on(1))
    }

    @Test fun aFlaggedCardListsWhatToCheckOnTheRightAndLabelsStayPlain() = runDesktopComposeUiTest(1400, 900) {
        val f = form()
        show(f)
        val lines = reasons(0)
        assertTrue(lines.isNotEmpty() && lines.all { it.startsWith("Check ") }, lines.toString())
        assertTrue("Check price, may be misread: \$5.49 would make it add up" in lines, lines.toString())
        assertTrue("Check with tax, it does not add up" in lines, lines.toString())
        // in the empty space to the right of the fields, one line each, in the card
        val card = box("item.card.0"); val right = box("item.reasons.0"); val remove = box("item.remove.0")
        assertTrue(right.left > remove.right && right.right <= card.right, "the list is right of the buttons: $right")
        for (n in lines.indices) assertTrue(box("item.reason.0.$n").height < 20f, "one line each")
        // the labels are plain words, and nothing sits under a field
        for (l in listOf("Item", "Qty", "Price", "With tax")) assertTrue(onAllNodesWithText(l, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), l)
        // a flagged field has the yellow border; a clean card's don't
        val img = onRoot().captureToImage().toAwtImage()
        fun border(tag: String): List<Int> { val b = box(tag); return (-1..1).map { img.getRGB((b.left + 100).toInt(), (b.top + it).toInt()) and 0xFFFFFF } }
        val yellow = (ProgramPalette.warning.value shr 32).toInt() and 0xFFFFFF
        assertTrue(yellow in border("item.price.0.box"), "flagged: yellow border")
        assertTrue(yellow !in border("item.price.1.box"), "clean: no yellow")
    }

    /** 0.15.9: the label follows the switch, and Checked and Remove stay where they are when it's clicked. */
    @Test fun theTaxedLabelFollowsTheSwitchAndTheButtonsHoldStill() = runDesktopComposeUiTest(1400, 900) {
        show(form())
        fun x(tag: String) = box(tag).left
        val before = x("item.checked.1") to x("item.remove.1")
        assertTrue(onAllNodesWithText("Not taxed", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "off reads Not taxed")
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(before, x("item.checked.1") to x("item.remove.1"))
        // item 1 is on: one switch now reads Taxed
        assertEquals(1, onAllNodesWithText("Taxed", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performClick(); waitForIdle()
        assertEquals(before, x("item.checked.1") to x("item.remove.1"))
    }

    /** 0.15.9: Store and Date as boxes in the card style, their reasons in the cards' own column. */
    @Test fun storeAndDateAreCardStyleBoxesWithTheirReasonsListedOnTheRight() = runDesktopComposeUiTest(1400, 900) {
        val lines = listOf("SAMPLE CREAM 9.49" to 40, "TOTAL 9.49" to 95)
        val f = ReceiptForm.from(ReceiptParser.parseLines(lines.map { (t, c) -> ReceiptParser.Line(t, c) }))
        show(f)
        val store = box("receipt.store.box"); val date = box("receipt.date.box"); val today = box("receipt.today"); val item = box("item.name.0.box")
        assertEquals(26f, store.height, 0.6f); assertEquals(26f, date.height, 0.6f); assertEquals(26f, today.height, 0.6f)
        assertEquals(350f, store.width, 0.6f); assertEquals(200f, date.width, 0.6f)
        // Store's edges are the Item boxes' below
        assertEquals(item.left, store.left, 0.6f); assertEquals(item.right, store.right, 0.6f); assertEquals(item.left, date.left, 0.6f)
        assertEquals(10f, today.left - date.right, 0.6f)
        // yellow border, plain labels, one line each in the column the cards use
        val img = onRoot().captureToImage().toAwtImage()
        val yellow = (ProgramPalette.warning.value shr 32).toInt() and 0xFFFFFF
        assertTrue((-1..1).any { img.getRGB((store.left + 100).toInt(), (store.top + it).toInt()) and 0xFFFFFF == yellow }, "Store is yellow")
        for (l in listOf("Store", "Date")) assertTrue(onAllNodesWithText(l, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), l)
        val sr = box("receipt.store.reason"); val dr = box("receipt.date.reason")
        assertEquals(listOf("Check the store, it wasn't on the photo"), listOf(textOf("receipt.store.reason")))
        assertEquals(listOf("Check the date, it wasn't on the photo"), listOf(textOf("receipt.date.reason")))
        assertTrue(sr.height < 20f && dr.height < 20f)
        assertTrue(has("item.reason.0.0")); assertEquals(box("item.reason.0.0").left, sr.left, 0.6f); assertEquals(sr.left, dr.left, 0.6f)
        assertTrue(sr.left > store.right && dr.left > today.right)
    }

    @Test fun aFilledStoreAndDateHaveNoList() = runDesktopComposeUiTest(1400, 900) {
        show(form())
        assertTrue(!has("receipt.store.reason") && !has("receipt.date.reason"))
        assertEquals(26f, box("receipt.store.box").height, 0.6f)
    }

    private fun ComposeUiTest.textOf(tag: String) = onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }

    @Test fun aCleanCardHasNoList() = runDesktopComposeUiTest(1400, 900) {
        val f = form()
        show(f)
        // item 1 (clean) shows no list; checking item 0 (a user's Checked) clears its reasons
        assertTrue(reasons(1).isEmpty() && !has("item.reason.1.0"))
        onNodeWithTag("item.checked.0", useUnmergedTree = true).performClick(); waitForIdle()
        assertTrue(reasons(0).isEmpty(), "Checked clears the list")
        assertTrue(has("item.checkedMark.0"))
    }

    /** The program's content at its 1100 wide window is about 820: simulated by a narrower window here. */
    @Test fun whenTheCardIsTooNarrowForASideListItGoesUnderTheFieldsAndNothingOverlaps() = runDesktopComposeUiTest(880, 700) {
        show(form())
        val card = box("item.card.0"); val reasons = box("item.reasons.0"); val remove = box("item.remove.0"); val tax = box("item.withTax.0.box")
        assertTrue(reasons.top >= tax.bottom, "under the fields: $reasons vs $tax")
        assertTrue(reasons.right <= card.right && remove.right <= card.right, "nothing clips at the card's right edge")
        assertTrue(reasons(0).isNotEmpty())
    }
}
