package net.darkrelay.deaddropfinance

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.scan.ReceiptScanner
import net.darkrelay.deaddropfinance.scan.TesseractReader
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.ReceiptEditScreen
import net.darkrelay.deaddropfinance.ui.ReceiptForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.9.6 on a real Android device (the emulator, through tools\emulator.ps1 -Instrument): the committed Food Lion
 * fixture (the file picked on 0.15.3, a small screenshot of the photo) through the phone's own scan path (ReceiptScanner,
 * Tesseract4Android: cleaned up, read as one block, then the balance and total tax it drops read again as scattered text),
 * which must give the receipt the program gives; and the phone's check screen, copied to pictures for the round's
 * screenshots. Compose's test rule isn't used: its Espresso can't drive Android 17. Never a real budget.
 */
@RunWith(AndroidJUnit4::class)
class PhoneReceiptCheckOnDeviceTest {

    private val names = listOf(
        "FL 2 MILK GALLON", "FL SALTED BUTTER QTR", "FL HEAVY WHIP CREAM", "MODELA CHLD LM NA 6C", "FL LEMON JUICE", "FL RANCH DP DRESS PK",
        "FL GRN TEA BAGS 40CT", "FL CINNI MINI CRUNCH", "FOOD LION LIME JUICE", "KELLOGG FUN PAK", "TWIN EARL GREY TEA", "SIPPIE BOWLS",
        "ATHLC LITE NA 12CN", "CELS SPRK WLD BRRY", "CELS SPRK PCH VIBES", "SLVRFLOSS KRAUT 32Z", "LOF ITALIAN SUB KIT", "CARPRD CELLO RED LNK",
        "CELERY", "BUNCH RADISHES", "CHINESE CABBAGE",
    )
    private val prices = listOf("3.19", "2.99", "5.49", "22.98", "1.99", "0.59", "5.58", "2.49", "1.99", "5.49", "4.49", "4.49", "17.99", "1.49", "1.49", "3.99", "7.99", "6.49", "2.19", "1.99", "3.64")
    private val qtys = List(21) { "1" }.toMutableList().also { it[3] = "2"; it[6] = "2"; it[20] = "1.26" }

    /** The program's own allowance: on some sizes one letter of two names comes out wrong ("32Z" as "322"). */
    private fun sameName(read: String, want: String) = read == want ||
        want in setOf("SLVRFLOSS KRAUT 32Z", "LOF ITALIAN SUB KIT") && read.length == want.length && read.zip(want).count { (a, b) -> a != b } == 1

    @Test fun theFoodLionFileFrom0153ReadsOnThePhoneAsItDoesInTheProgram() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val photo = File(target.cacheDir, "food-lion-scanned-in-0.15.3.png").apply {
            ctx.assets.open("food-lion/food-lion-scanned-in-0.15.3.png").use { i -> outputStream().use { i.copyTo(it) } }
        }
        val images = ReceiptImages(File(target.cacheDir, "fl-scanned"), { ByteArray(32) { it.toByte() } })
        var sparse = 0
        // the phone's own reader, counting the scattered passes it is asked for ([off]: none is given, as before 1.9.6)
        fun reader(off: Boolean) = object : net.darkrelay.deaddropfinance.scan.TextReader {
            val inner = TesseractReader(target)
            override fun read(g: net.darkrelay.deaddropfinance.scan.ImageCleanup.Gray) = inner.read(g)
            override fun readSparse(g: net.darkrelay.deaddropfinance.scan.ImageCleanup.Gray) =
                if (off) emptyList() else { sparse++; inner.readSparse(g) }
            override fun close() = inner.close()
        }
        val control = try {
            ReceiptScanner(target, images) { reader(true) }.scan(android.net.Uri.fromFile(photo), "food-lion-153-off", 0)
        } catch (e: Exception) { emptyList() }
        val pages = try {
            ReceiptScanner(target, images) { reader(false) }.scan(android.net.Uri.fromFile(photo), "food-lion-153", 0)
        } finally {
            photo.delete(); File(target.cacheDir, "fl-scanned").deleteRecursively()
        }
        assertTrue("the scattered pass was asked for and run", sparse >= 1)
        val without = ReceiptForm.from(ReceiptParser.parse(control))
        println("SCAN-0.15.3 without the scattered pass: total '${without.total}' tax '${without.tax}' notes ${without.notes}")
        File(target.getExternalFilesDir(null), "scan-0.15.3-without-sparse.txt").writeText("total '${without.total}' tax '${without.tax}' notes ${without.notes}")
        val parsed = ReceiptParser.parse(pages)
        val form = ReceiptForm.from(parsed)
        val work = form.taxWork()
        val totals = form.totals(work)
        val report = "items ${form.items.size} store '${form.store}' date '${form.date}' untaxed ${totals.untaxed} taxed ${totals.taxed} tax ${totals.tax} total ${totals.total} gap ${totals.gap} notes ${form.notes} misread ${form.misread(totals)}"
        File(target.getExternalFilesDir(null), "scan-0.15.3.txt").writeText(report + "\n" + pages.flatten().joinToString("\n") { "${it.confidence}\t${it.text}" })
        println("SCAN-0.15.3: $report")
        assertEquals(report, 21, form.items.size)
        assertEquals("store empty", "", form.store)
        assertEquals("date empty", "", form.date)
        assertTrue("store and date flagged: ${form.notes}", "store" in form.notes && "date" in form.notes)
        names.forEachIndexed { i, n -> assertTrue("item $i: '${form.items[i].text}'", sameName(form.items[i].text, n)) }
        assertEquals(prices, form.items.map { it.amount })
        assertEquals(qtys, form.items.map { it.qty })
        assertEquals(report, 63_56L, totals.untaxed)
        assertEquals(report, 45_46L, totals.taxed)
        assertEquals(report, 3_18L, totals.tax)
        assertEquals(report, 112_20L, totals.total)
        assertTrue("total read from the receipt: ${form.notes}", "total" !in form.notes)
        assertEquals("the add-up line shows", 0L, totals.gap)
        assertTrue("no price pointed at: ${form.misread(totals)}", form.misread(totals).isEmpty())
    }

    // ---- the check screen, for the screenshots ----------------------------------------------------------------------

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens")
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(900)
    }

    private fun shot(name: String) {
        val bmp = ui.takeScreenshot()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("$name: drawn", px.distinct().size > 10)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun allWithText(text: String, root: AccessibilityNodeInfo? = ui.rootInActiveWindow): List<AccessibilityNodeInfo> {
        root ?: return emptyList()
        return (if (root.text?.toString() == text) listOf(root) else emptyList()) + (0 until root.childCount).flatMap { allWithText(text, root.getChild(it)) }
    }

    private fun nodeWithText(text: String, root: AccessibilityNodeInfo? = ui.rootInActiveWindow): AccessibilityNodeInfo? {
        root ?: return null
        if (root.text?.toString() == text) return root
        for (i in 0 until root.childCount) nodeWithText(text, root.getChild(i))?.let { return it }
        return null
    }

    /** A tap on the node showing [text], through the accessibility tree and an injected touch (no test rule). */
    private fun tap(text: String) {
        val n = nodeWithText(text) ?: error("nothing on screen says '$text'")
        val r = Rect().also { n.getBoundsInScreen(it) }
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 80)) {
            val e = MotionEvent.obtain(t, at, action, r.exactCenterX(), r.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            ui.injectInputEvent(e, true); e.recycle()
        }
        settle()
    }

    private fun lines(vararg t: String) = ReceiptForm.from(ReceiptParser.parseLines(t.map { ReceiptParser.Line(it, 95) }))

    @Test fun thePhonesCheckScreenDrawsWithThePlainLabelsTheStackedListTheSliderAndTheSharedConfirm() {
        dir.deleteRecursively(); dir.mkdirs()
        val envelopes = listOf(
            EnvelopeEntity(id = "e-food", name = "Groceries", mode = EnvelopeMode.FILL_TO_TARGET, target = 300_00, createdAt = 1, updatedAt = 1),
            EnvelopeEntity(id = "e-home", name = "Household", mode = EnvelopeMode.FILL_TO_TARGET, target = 100_00, createdAt = 1, updatedAt = 1),
        )
        // clean: everything read, nothing to check
        val clean = lines("SAMPLE MART", "08/14/2030", "LIME JUICE 1.99 A", "BREAD 2.50", "MILK 3.00 A", "SUBTOTAL 7.49", "TAX A 6.000% 0.47", "TOTAL 7.96")
        // flagged: no store or date on the photo, a smudged quantity, a price that may be misread, a weighed item
        val flagged = lines("FROZEN/DAIRY", "SAMPLE CHEESE", "20 2.79 5.58 A x", "SAMPLE APPLES W 219 Ax", "TAX A 0.00% 7.77 0.00", "BALANCE DUE 7.77")
            .also { it.guessEnvelopes(emptyList(), envelopes) }
        // one card with several things to check, for the full-height shot of a card and its list
        val several = lines("SAMPLE MART", "08/14/2030", "SAMPLE CHEESE 5.58 A", "SAMPLE APPLES 2.19 A", "TAX A 6.000% 0.47", "TOTAL 8.24").also { it.guessEnvelopes(emptyList(), envelopes) }
        several.items[0] = several.items[0].copy(
            envelopeId = null,
            unsure = mapOf("name" to "name read at low confidence", "qty" to "quantity read from a smudged line", "price" to "price read at low confidence", "envelope" to "envelope guessed from the item name"),
        )
        // unanswered tax: every item asks
        val asking = lines("SAMPLE MART", "08/14/2030", "WIDGET 10.00", "APPLES 4.00", "SUBTOTAL 14.00", "TAX 0.60", "TOTAL 14.60")
        val scroll = ScrollState(0)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.setContent {
                    DdfTheme {
                        // the app's own background behind the screen, and clear of the status and navigation bars as AppRoot leaves it
                        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = DdfColors.Background) {
                            androidx.compose.foundation.layout.Box(
                                androidx.compose.ui.Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                            ) { screen?.invoke() }
                        }
                    }
                }
            }
            fun show(f: ReceiptForm) = scenario.onActivity { screen = { ReceiptEditScreen(f, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = envelopes, onClear = {}, scroll = scroll) } }
            fun scrollTo(px: Int) { scenario.onActivity { scroll.dispatchRawDelta((px - scroll.value).toFloat()) }; settle() }

            show(clean); settle()
            scrollTo(0); shot("phone-check-top-clean")
            scrollTo(540); shot("phone-check-clean-item-card")
            show(flagged); settle()
            scrollTo(0); shot("phone-check-store-date-flagged")
            show(several); settle()
            for (y in listOf(380, 480, 580)) { scrollTo(y); shot("phone-check-flagged-card-$y") }
            show(asking); settle()
            scrollTo(540); shot("phone-check-slider-off")
            tap("Not taxed"); shot("phone-check-slider-on")
            show(clean); settle()
            scrollTo(0)
            tap("Clear"); shot("phone-check-clear-confirm")
            // the shared confirm: Cancel on the left, the action on the right and filled red (the program's red)
            val cancel = Rect().also { allWithText("Cancel").single().getBoundsInScreen(it) }
            val action = Rect().also { allWithText("Clear").last().getBoundsInScreen(it) }
            assertTrue("Cancel is left of Clear", cancel.right <= action.left)
            val bmp = ui.takeScreenshot()
            assertEquals("the action is filled red", 0xFFE2574C.toInt(), bmp.getPixel(action.left + 10, action.centerY()))
            assertTrue("Cancel is not filled red", bmp.getPixel(cancel.left + 10, cancel.centerY()) != 0xFFE2574C.toInt())
        }
    }
}
