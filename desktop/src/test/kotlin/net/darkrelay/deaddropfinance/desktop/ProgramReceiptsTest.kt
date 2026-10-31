package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 0.13.0: receipts in the program (the phone's screens): one added to a transaction from an image file (kept
 * encrypted; this one has no text to read, 0.14.0, so it's typed in, as the phone marks), the Receipts list and a receipt's screen, editing,
 * matching a waiting one to its charge, splitting by items, and deleting after the phone's question.
 */
class ProgramReceiptsTest : ProgramScreensBase() {

    private fun image(): File = tmp.newFile("sample-receipt.png").also { f ->
        val img = BufferedImage(40, 60, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 40) for (y in 0 until 60) img.setRGB(x, y, if ((x + y) % 7 == 0) 0x000000 else 0xFFFFFF)
        javax.imageio.ImageIO.write(img, "png", f)
    }

    private fun last(text: String) {
        val all = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    private fun receipts() = runBlocking { store().dao.receiptsNow() }.filter { !it.deleted }

    /** Fills the receipt form: store, total, and one item per (name, price). Nothing was read, so they're marked to check. */
    private fun fill(store: String, total: String, items: List<Pair<String, String>>) {
        typeMarked("Store", store)
        typeMarked("Total", total)
        items.forEachIndexed { i, (name, price) ->
            clickText("Add an item", exact = true)
            val names = compose.onAllNodes(fieldMatcher("Item"), useUnmergedTree = false)
            names[i].performTextInputCleared(name)
            val prices = compose.onAllNodes(fieldMatcher("Price"), useUnmergedTree = false)
            prices[i].performTextInputCleared(price)
        }
    }

    @Test fun aReceiptIsAddedToATransactionFromAnImageTypedInSavedAndDeleted() {
        show()
        picked = image()
        click("nav.ACTIVITY")
        click("act.Corner Market")
        click("dialog.details")
        clickText("Add receipt", exact = true)
        until("It's read on this computer; nothing is sent anywhere.")
        assertFalse(shows("Take a photo"), "no camera on the program")
        clickText("Choose an image or PDF", exact = true)
        until("Save receipt")
        fill("Corner Market", "42.00", listOf("Sample Apples" to "30.00", "Sample Soap" to "12.00"))
        clickText("Save receipt", exact = true)
        until("Saved to this transaction.")
        val r = receipts().single()
        assertEquals(b().transactions.first { it.payee == "Corner Market" }.id, r.transactionId)
        assertEquals(42_00L, r.total)
        assertEquals(1, r.pages)
        // the image is kept encrypted in the data folder, never as the plain file
        val kept = File(dataDir(), "receipts").listFiles()!!.single()
        assertFalse(kept.readBytes().contentEquals(picked!!.readBytes()))
        assertEquals(2, runBlocking { store().dao.receiptItemsNow() }.count { it.receiptId == r.id })
        // split by items: Soap goes to Gifts, the rest stays in Groceries
        until("Split by items")
        clickText("Split by items", exact = true)
        until("shared out by what each envelope's items cost")
        val soap = node("Sample Soap", exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val pickers = compose.onAllNodes(hasText("Groceries"), useUnmergedTree = true)
        val ps = pickers.fetchSemanticsNodes()
        pickers[ps.indices.filter { ps[it].boundsInRoot.center.y > soap }.minBy { ps[it].boundsInRoot.center.y }].performClick()
        compose.waitForIdle()
        last("Gifts")
        clickText("Apply split", exact = true)
        compose.waitUntil(5_000) { refresh(); b().lines.count { it.transactionId == r.transactionId } == 2 }
        assertEquals(setOf(id("Groceries"), id("Gifts")), b().lines.filter { it.transactionId == r.transactionId }.map { it.envelopeId }.toSet())
        // the Receipts list (its own sidebar item, 0.14.0) and deleting it after the phone's question
        click("nav.RECEIPTS")
        until("Corner Market")
        clickText("Corner Market")
        until("Edit")
        clickText("Delete receipt")
        until("Delete this receipt?")
        last("Delete")
        compose.waitUntil(5_000) { receipts().isEmpty() }
    }

    @Test fun aReceiptWithTwoPossibleChargesAsksWhichOneAndOneChargeMatchesByItself() {
        // two purchases the receipt could be: the phone asks which
        show {
            saveTransaction(null, net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft(ids.getValue("Cash"), today.minusDays(1), "Corner Market", "", -42_00,
                listOf(net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft(ids.getValue("Groceries"), -42_00, categoryId = category("Groceries")))))
        }
        picked = image()
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        clickText("Choose an image or PDF", exact = true)
        until("Save receipt")
        typeMarked("Store", "Corner Market")
        typeMarked("Total", "42.00")
        typeMarked("Date", today.minusDays(1).toString(), " (yyyy-mm-dd)")
        clickText("Save receipt", exact = true)
        compose.waitUntil(5_000) { receipts().size == 1 }
        until("Corner Market, $42.00")
        last("Corner Market")
        compose.waitUntil(5_000) { receipts().single().transactionId != null }
        assertNotNull(b().transactions.firstOrNull { it.id == receipts().single().transactionId && it.payee == "Corner Market" })
        // one possible charge left (the other has its receipt): a new receipt matches it by itself
        click("nav.BUDGET")
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        clickText("Choose an image or PDF", exact = true)
        until("Save receipt")
        typeMarked("Store", "Corner Market")
        typeMarked("Total", "42.00")
        typeMarked("Date", today.minusDays(1).toString(), " (yyyy-mm-dd)")
        clickText("Save receipt", exact = true)
        until("Matched to Corner Market on")
        compose.waitUntil(5_000) { receipts().size == 2 && receipts().all { it.transactionId != null } }
    }

    @Test fun aFileThatIsNotAnImageIsRefusedPlainly() {
        show()
        picked = tmp.newFile("not-an-image.png").also { it.writeText("plain text") }
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        clickText("Choose an image or PDF", exact = true)
        until("That file couldn't be read as a receipt image or PDF. Try another.")
        assertTrue(receipts().isEmpty())
    }

    /** 0.14.0: a receipt photo is read on this computer into the phone's form, as the phone reads one. */
    @Test fun aReceiptPhotoIsReadIntoTheFormAndSavedAsRead() {
        show()
        picked = tmp.newFile("sample-grocery.jpg").also { javax.imageio.ImageIO.write(ProgramReceiptReadingTest.photo(ProgramReceiptReadingTest.grocery, 2.0), "jpg", it) }
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        until("It's read on this computer; nothing is sent anywhere.")
        clickText("Choose an image or PDF", exact = true)
        until("Check the receipt", ms = 60_000)
        until("SAMPLE FRESH MARKET")
        assertTrue(shows("12.49"), dump())
        assertTrue(shows("SAMPLE BREAD"), dump())
        assertFalse(shows("Store (check this)"), "read clearly: ${dump()}")
        clickText("Save receipt", exact = true)
        compose.waitUntil(10_000) { receipts().size == 1 }
        val r = receipts().single()
        assertEquals("SAMPLE FRESH MARKET", r.store)
        assertEquals(12_49L, r.total)
        assertEquals(3, runBlocking { store().dao.receiptItemsNow() }.count { it.receiptId == r.id })
    }

    /** A field the reading wasn't sure of (here: nothing could be read) is labeled to check, as on the phone. */
    private fun typeMarked(label: String, text: String, suffix: String = "") {
        val marked = "$label (check this)$suffix"
        typeInto(if (shows(marked)) marked else "$label$suffix", text)
    }

    private fun fieldMatcher(label: String) = androidx.compose.ui.test.SemanticsMatcher("field $label") { n ->
        n.config.contains(androidx.compose.ui.semantics.SemanticsProperties.EditableText) &&
            (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.any { it.text == label } == true ||
                n.children.any { c -> c.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.any { it.text == label } == true })
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.performTextInputCleared(text: String) {
        performTextClearance()
        performTextInput(text)
        compose.waitForIdle()
    }
}
