package net.darkrelay.deaddropfinance

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.ui.ActivityScreen
import net.darkrelay.deaddropfinance.ui.ActivityTabs
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.ImportScreen
import net.darkrelay.deaddropfinance.ui.ImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.9.7 on a real Android device (the emulator, through tools\emulator.ps1 -Instrument): the phone's import preview
 * (an invented bank file in a USAA-like layout with new, already in, unsure and pending rows against invented hand
 * entries) and Undo last import's confirm, copied to pictures for the round's screenshots. Compose's test rule isn't
 * used: its Espresso can't drive Android 17. Never a real budget.
 */
@RunWith(AndroidJUnit4::class)
class ImportPreviewOnDeviceTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "import-screens")
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun settle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); Thread.sleep(900) }

    private fun shot(name: String): Bitmap {
        val bmp = ui.takeScreenshot()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("$name: drawn", px.distinct().size > 10)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    /** Every window's nodes (a dialog is a window of its own) that show [text]. */
    private fun all(text: String): List<AccessibilityNodeInfo> {
        val info = ui.serviceInfo
        if (info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            Thread.sleep(300)
        }
        fun walk(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            n ?: return emptyList()
            return (if (n.text?.toString() == text) listOf(n) else emptyList()) + (0 until n.childCount).flatMap { walk(n.getChild(it)) }
        }
        return (ui.windows.map { it.root } + ui.rootInActiveWindow).distinct().flatMap { walk(it) }
    }

    private fun tap(text: String) {
        val r = Rect().also { (all(text).firstOrNull() ?: error("nothing on screen says '$text'")).getBoundsInScreen(it) }
        val t = SystemClock.uptimeMillis()
        for ((action, at) in listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 80)) {
            val e = MotionEvent.obtain(t, at, action, r.exactCenterX(), r.exactCenterY(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            ui.injectInputEvent(e, true); e.recycle()
        }
        settle()
    }

    private val day = LocalDate.of(2030, 10, 29)
    private fun hand(id: String, d: LocalDate, amount: Long, payee: String) =
        TransactionEntity(id = id, accountId = "chk", date = d, payee = payee, amount = amount, createdAt = 1, updatedAt = 1)
    private fun line(tx: String, amount: Long) = TransactionLineEntity(transactionId = tx, bucket = LineBucket.UNSORTED, amount = amount, createdAt = 1, updatedAt = 1)

    private val file = """
        Date,Description,Original Description,Category,Amount,Status
        2030-10-26,Sample Payroll,SAMPLE PAYROLL CO DIR DEP,Income,1850.00,Posted
        2030-10-23,Power Co,POWER COMPANY AUTOPAY 0099,Utilities,-58.00,Posted
        2030-10-29,Fuel Stop,FUEL STOP 12 SAMPLE CITY,Gas,-40.00,Posted
        2030-10-27,Sample Bookshop,SAMPLE BOOKSHOP 9,Shopping,-12.50,Posted
        2030-10-28,Sample Pharmacy,SAMPLE PHARMACY 22,Health,-8.20,Posted
        2030-10-29,Sample Diner,SAMPLE DINER,Restaurants,-18.40,Pending
    """.trimIndent() + "\n"

    @Test fun thePreviewAndTheUndoConfirmDrawOnThePhone() {
        dir.deleteRecursively(); dir.mkdirs()
        val account = AccountEntity(id = "chk", name = "Sample Checking", type = AccountType.BANK, startingBalance = 400_00, startDate = day.minusDays(60), createdAt = 1, updatedAt = 1)
        val handTx = listOf(hand("h1", day.minusDays(3), 1850_00, "My payroll"), hand("h2", day.minusDays(6), -58_00, "Electric"), hand("h3", day.minusDays(1), -40_00, "Fuel Stop 12"))
        val budget = Budget(listOf(account), emptyList(), handTx, handTx.map { line(it.id, it.amount) }, emptyList())
        val layout = CsvImport.detect(file)
        val plan = CsvImport.plan(CsvImport.read(file, layout), "chk", budget.transactions)
        assertEquals(listOf(2, 2, 1, 1), listOf(plan.new.size, plan.alreadyCount, plan.unsure.size, plan.pending.size))
        // after an import made at 08:00: two of its transactions on the account
        val at = java.time.LocalDateTime.of(2030, 10, 29, 8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val imported = listOf(
            TransactionEntity(id = "i1", accountId = "chk", date = day.minusDays(2), payee = "Sample Bookshop", amount = -12_50, source = TransactionSource.CSV, importId = "imp", createdAt = at, updatedAt = at),
            TransactionEntity(id = "i2", accountId = "chk", date = day.minusDays(1), payee = "Sample Pharmacy", amount = -8_20, source = TransactionSource.CSV, importId = "imp", createdAt = at, updatedAt = at),
        )
        val after = Budget(listOf(account), emptyList(), handTx + imported, (handTx + imported).map { line(it.id, it.amount) }, emptyList())
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.setContent {
                    DdfTheme {
                        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = DdfColors.Background) {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { screen?.invoke() }
                        }
                    }
                }
            }
            scenario.onActivity { screen = { ImportScreen(budget, ImportState("sample-usaa.csv", file, layout, "chk", previewing = true), {}, {}, {}, {}, {}) } }
            settle()
            // the dialog is a window of its own: wait for it, then the picture
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (all("2 new, 2 already in, 1 unsure, 1 pending").isEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
            assertTrue("the counts show", all("2 new, 2 already in, 1 unsure, 1 pending").isNotEmpty())
            Thread.sleep(600)
            shot("phone-import-preview")
            tap("2 already in (show)")
            Thread.sleep(600)
            shot("phone-import-preview-already-open")
            scenario.onActivity { screen = { ActivityScreen(after, {}, {}, { _, _, _ -> }, {}, tabs = ActivityTabs(accounts = listOf(account), selected = "chk", onUndoImport = {})) } }
            settle()
            tap("Undo last import")
            val bmp = shot("phone-undo-import-confirm")
            assertTrue(all("This removes the 2 transactions it added. Nothing else changes.").isNotEmpty())
            val go = Rect().also { all("Undo import").last().getBoundsInScreen(it) }
            val cancel = Rect().also { all("Cancel").single().getBoundsInScreen(it) }
            assertTrue("Cancel on the left", cancel.right <= go.left)
            assertEquals("the action is filled red", 0xFFE2574C.toInt(), bmp.getPixel(go.left + 10, go.centerY()))
        }
    }
}
