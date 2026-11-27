package net.darkrelay.deaddropfinance

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.ui.BillsScreen
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.9.8 on a real Android device (the emulator, through tools\emulator.ps1 -Instrument): the Payday schedule popup on
 * Bills, monthly on the 1st, 2 days early (Nov 27, Dec 30, Jan 29 from Nov 27 2026) and every two weeks, and the Bills
 * line once a schedule is saved, copied to pictures for the round's screenshots. An invented budget, never a real one.
 * Compose's test rule isn't used: its Espresso can't drive Android 17.
 */
@RunWith(AndroidJUnit4::class)
class PayScheduleOnDeviceTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "payday-screens")
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun all(text: String): List<AccessibilityNodeInfo> {
        val info = ui.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            Thread.sleep(300)
        }
        fun walk(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            n ?: return emptyList()
            return (if (n.text?.toString() == text) listOf(n) else emptyList()) + (0 until n.childCount).flatMap { walk(n.getChild(it)) }
        }
        return (ui.windows.map { it.root } + ui.rootInActiveWindow).distinct().flatMap { walk(it) }
    }

    private fun waitFor(text: String) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (all(text).isEmpty() && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        assertTrue("shows: $text", all(text).isNotEmpty())
        Thread.sleep(600)
    }

    private fun shot(name: String) {
        val bmp = ui.takeScreenshot()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("$name: drawn", px.distinct().size > 10)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val today = LocalDate.of(2026, 11, 27)

    @Test fun thePopupAndTheBillsLineDrawOnThePhone() {
        dir.mkdirs()
        val account = AccountEntity(id = "chk", name = "Sample Checking", type = AccountType.BANK, startingBalance = 500_00, startDate = today.minusDays(60), createdAt = 1, updatedAt = 1)
        val pay = TransactionEntity(id = "p1", accountId = "chk", date = LocalDate.of(2026, 10, 30), payee = "Sample Payroll Co", amount = 2000_00, isPayday = true, createdAt = 1, updatedAt = 1)
        val budget = Budget(listOf(account), emptyList(), listOf(pay), listOf(TransactionLineEntity(transactionId = "p1", bucket = LineBucket.READY_TO_ASSIGN, amount = 2000_00, createdAt = 1, updatedAt = 1)), emptyList())
        val monthly = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)
        val biweekly = PaySchedule(PaySchedule.Kind.BIWEEKLY, anchor = LocalDate.of(2026, 11, 13))
        fun bills(s: PaySchedule, editing: Boolean, k: String) {
            val p = Paydays.predict(budget.paydayDates, today, schedule = s)
            screen = { key(k) { BillsScreen(budget, today, p, emptyList(), {}, {}, {}, {}, {}, editingPayday = editing) } }
        }
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
            scenario.onActivity { bills(monthly, true, "monthly") }
            waitFor("Next three paydays: Nov 27, Dec 30, Jan 29")
            assertTrue(all("If your bank pays before the official date").isNotEmpty())
            shot("phone-payday-schedule-monthly")
            scenario.onActivity { bills(biweekly, true, "biweekly") }
            waitFor("Next three paydays: Nov 27, Dec 11, Dec 24")
            shot("phone-payday-schedule-every-two-weeks")
            scenario.onActivity { bills(monthly, false, "card") }
            waitFor("Monthly, the 1st, 2 days early")
            assertTrue(all("Guessed from one payday (two weeks). Correct it if it's different.").isEmpty())
            shot("phone-bills-after-saving")
        }
    }

    /** 1.9.8: Bills, Coming up, this budget month only (Nov 27 to Dec 30 2026), overdue ones still listed. Invented bills. */
    @Test fun comingUpShowsThisBudgetMonthOnThePhone() {
        dir.mkdirs()
        val account = AccountEntity(id = "chk", name = "Sample Checking", type = AccountType.BANK, startingBalance = 500_00, startDate = today.minusDays(60), createdAt = 1, updatedAt = 1)
        val pays = listOf(LocalDate.of(2026, 10, 30), today).mapIndexed { i, d -> TransactionEntity(id = "p$i", accountId = "chk", date = d, payee = "Sample Payroll Co", amount = 2000_00, isPayday = true, createdAt = 1, updatedAt = 1) }
        fun bill(id: String, name: String, amount: Long, due: LocalDate) = net.darkrelay.deaddropfinance.data.BillEntity(id = id, name = name, envelopeId = null, amountDue = amount, dueDate = due, createdAt = 1, updatedAt = 1)
        val bills = listOf(bill("phone", "Sample Phone", 100_00, today), bill("rent", "Sample Rent", 900_00, LocalDate.of(2026, 12, 1)),
            bill("water", "Sample Water", 35_00, LocalDate.of(2026, 11, 20)), bill("gym", "Sample Gym", 30_00, LocalDate.of(2026, 12, 30)))
        val paid = listOf(net.darkrelay.deaddropfinance.data.BillPaymentEntity(billId = "phone", transactionId = null, amount = 100_00, date = today, periodDue = today, method = net.darkrelay.deaddropfinance.data.PaymentMethod.CASH, createdAt = 1, updatedAt = 1))
        val budget = Budget(listOf(account), emptyList(), pays, pays.map { TransactionLineEntity(transactionId = it.id, bucket = LineBucket.READY_TO_ASSIGN, amount = it.amount, createdAt = 1, updatedAt = 1) }, emptyList(), bills = bills, billPayments = paid)
        val p = Paydays.predict(budget.paydayDates, today, schedule = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2))
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
            scenario.onActivity { screen = { BillsScreen(budget, today, p, emptyList(), {}, {}, {}, {}, {}) } }
            waitFor("Next payday: Dec 30")
            assertTrue(all("was due Nov 20, overdue").isNotEmpty())
            assertTrue(all("Sample Gym").isEmpty())
            assertTrue(all("Sample Phone").size == 1)
            shot("phone-bills-coming-up")
        }
    }
}
