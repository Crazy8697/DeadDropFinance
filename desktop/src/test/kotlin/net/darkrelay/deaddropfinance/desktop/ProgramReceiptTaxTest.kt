package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals

/**
 * 0.15.2: the receipt form in the program shows the same tax letters as the app's (it is the app's own form): a saved
 * receipt's letters in their own field and the items with tax adding up to the total; a receipt whose letters can't be
 * matched asks which are taxed, and Save waits for the answer. Invented receipts. At 1400 x 900 and 1100 x 700.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramReceiptTaxTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.2" }?.let { File(it).also { d -> d.mkdirs() } }

    private val matchedText = "SAMPLE MART\n08/14/2030\nLIME JUICE 1.99 A\nBREAD 2.50\nMILK 3.00 A\nSUBTOTAL 7.49\nTAX A 6.000% 0.47\nTOTAL 7.96"
    private val unmatchedText = "SAMPLE MART\n08/14/2030\nWIDGET 10.00 T\nAPPLES 4.00 F\nSUBTOTAL 14.00\nTAX 0.60\nTOTAL 14.60"

    private fun model(): AppModel {
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        val dao = m.store()!!.dao
        runBlocking {
            // a receipt read with its letters matched (the prices with tax were saved)
            dao.upsertReceipt(ReceiptEntity(id = "tax1", store = "Sample Mart", date = java.time.LocalDate.of(2030, 8, 14), total = 7_96, tax = 47, rawText = matchedText, pages = 0, createdAt = 1, updatedAt = 1))
            listOf(
                Triple("LIME JUICE", 1_99L to "A", 2_18L), Triple("BREAD", 2_50L to "", 2_50L), Triple("MILK", 3_00L to "A", 3_28L),
            ).forEachIndexed { i, (t, p, w) ->
                dao.upsertReceiptItem(ReceiptItemEntity(id = "t1i$i", receiptId = "tax1", line = i, text = t, name = t.lowercase(), amount = p.first, taxLetter = p.second, priceWithTax = w, createdAt = 1, updatedAt = 1))
            }
            // one whose letters can't be matched to its tax line
            dao.upsertReceipt(ReceiptEntity(id = "tax2", store = "Sample Mart", date = java.time.LocalDate.of(2030, 8, 14), total = 14_60, tax = 60, rawText = unmatchedText, pages = 0, createdAt = 1, updatedAt = 1))
            listOf(Triple("WIDGET", 10_00L, "T"), Triple("APPLES", 4_00L, "F")).forEachIndexed { i, (t, p, l) ->
                dao.upsertReceiptItem(ReceiptItemEntity(id = "t2i$i", receiptId = "tax2", line = i, text = t, name = t.lowercase(), amount = p, taxLetter = l, createdAt = 1, updatedAt = 1))
            }
        }
        runBlocking { m.refresh() }
        return m
    }

    private fun run(w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val m = model()
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        m.go(Screen.RECEIPTS)
        m.openPhone(Nav.ReceiptEdit("tax1", isNew = false))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("tax.addsUp", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("receipt.save", useUnmergedTree = true).assertIsEnabled()
        assertEquals(0, onAllNodesWithTag("tax.ask", useUnmergedTree = true).fetchSemanticsNodes().size)
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "receipt-tax-matched-${w}x$h.png")) }
        m.go(Screen.RECEIPTS)
        m.openPhone(Nav.ReceiptEdit("tax2", isNew = false))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("tax.ask", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("receipt.save", useUnmergedTree = true).assertIsNotEnabled()
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "receipt-tax-ask-${w}x$h.png")) }
        // 0.15.4: no letters shown; each item's Taxed toggle is the answer
        onNodeWithTag("item.taxed.0", useUnmergedTree = true).performScrollTo().performClick()
        // 0.15.8: one slider per item; an unanswered item shows it off, so Not taxed is on then off
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performScrollTo().performClick()
        onNodeWithTag("item.taxed.1", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithTag("receipt.save", useUnmergedTree = true).assertIsEnabled()
        assertEquals(0, onAllNodesWithTag("tax.ask", useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test fun theProgramsReceiptFormShowsTaxLettersAtTheOpeningSize() = run(1400, 900)

    @Test fun theProgramsReceiptFormShowsTaxLettersAtTheMinimumSize() = run(1100, 700)
}
