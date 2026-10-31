package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.domain.Receipts
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ReceiptForm
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 0.15.3 (phone 1.9.4) in the program: Scan receipt on Activity and on Budget's envelope overview opens the app's scan;
 * the receipt form has an envelope dropdown on every item; a real Food Lion receipt, read from its photo by the
 * program's own text recognition, opens with every item, the tax and no question. At 1400 x 900 and 1100 x 700.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramScanAndEnvelopesTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.3" }?.let { File(it).also { d -> d.mkdirs() } }

    private fun model() = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }

    /** The real receipt's photo, read the way a scan in the program reads it, saved as the form would save it. */
    private fun foodLion(m: AppModel): String {
        val src = listOf(File("../shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp"), File("shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp")).first { it.isFile }
        val jpg = File(tmp.root, "food-lion.jpg").apply {
            writeBytes(org.jetbrains.skia.Image.makeFromEncoded(src.readBytes()).encodeToData(org.jetbrains.skia.EncodedImageFormat.JPEG, 95)!!.bytes)
        }
        val pages = ProgramReceiptScanner(ReceiptImages(tmp.newFolder("receipts"), { ByteArray(32) })) { TessReader(TessReader.tessdata()!!) }.scan(jpg, "fl", 0)
        val form = ReceiptForm.from(ReceiptParser.parse(pages))
        val store = m.store()!!
        return runBlocking {
            form.guessEnvelopes(store.dao.receiptItemsNow(), store.dao.envelopesNow())
            Receipts(store, m.actions!!).save("food-lion", form.draft(0)).also { m.refresh() }
        }
    }

    private fun run(w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val m = model()
        val id = foodLion(m)
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        // Budget: Scan receipt by the envelope overview's buttons
        m.go(Screen.BUDGET)
        waitForIdle()
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "program-budget-scan-receipt-${w}x$h.png")) }
        onNodeWithTag("scanReceipt").performClick()
        waitForIdle()
        assertIs<Nav.Scan>(m.phoneNav)
        // Activity: Scan receipt by Add and Transfer
        m.phoneNav = null
        m.go(Screen.ACTIVITY)
        waitForIdle()
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "program-activity-scan-receipt-${w}x$h.png")) }
        onNodeWithTag("scanReceipt").performClick()
        waitForIdle()
        assertIs<Nav.Scan>(m.phoneNav)
        // the real receipt in the form: 21 items, each with its envelope dropdown, the tax read, nothing asked
        m.go(Screen.RECEIPTS)
        m.openPhone(Nav.ReceiptEdit(id, isNew = false))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("tax.addsUp", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(21, (0 until 30).count { onAllNodesWithTag("item.envelope.$it", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        assertEquals(0, onAllNodesWithTag("tax.ask", useUnmergedTree = true).fetchSemanticsNodes().size)
        onNodeWithTag("receipt.save", useUnmergedTree = true).assertIsEnabled()
        shots?.let { javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(it, "program-food-lion-receipt-${w}x$h.png")) }
        val items = runBlocking { m.store()!!.dao.itemsOf(id) }
        val groceries = runBlocking { m.store()!!.dao.envelopesNow() }.first { it.name == "Groceries" }.id
        assertTrue(items.all { it.envelopeId == groceries }, "every item is under a food section: Groceries")
        assertEquals(112_20L, items.sumOf { it.paid })
    }

    @Test fun atTheOpeningSize() = run(1400, 900)

    @Test fun atTheMinimumSize() = run(1100, 700)
}
