package net.darkrelay.deaddropfinance

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Bundle
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
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.PaySchedule
import net.darkrelay.deaddropfinance.domain.PaydayButton
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.ui.BillsScreen
import net.darkrelay.deaddropfinance.ui.ComingUpState
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.EnvelopeDetailScreen
import net.darkrelay.deaddropfinance.ui.EnvelopesScreen
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.9.9 on a real Android device (the emulator, through tools\emulator.ps1 -Instrument), an invented budget in memory:
 * Bills one budget month at a time with This month boxes (filled and empty), a later month, a custom range, an
 * envelope's target for the month; the Payday popup empty, less at the bank (blocked), the same, more, the "?" text and
 * the confirm with Clean up; Envelopes' month picker on this month, the next month with money assigned ahead, and the
 * unmarked payday banner. Copied to pictures for the round's screenshots. Compose's test rule isn't used: its Espresso
 * can't drive Android 17; the screen is driven through accessibility as a user's taps would be.
 */
@RunWith(AndroidJUnit4::class)
class MonthAndPaydayOnDeviceTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "month-screens")
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private val ui get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private lateinit var bills: BillActions
    private lateinit var acct: String
    private val env = mutableMapOf<String, String>()
    private var today = LocalDate.of(2026, 11, 27)
    private var tick = LocalDate.of(2026, 10, 1).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000

    private fun nodes(match: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val info = ui.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            ui.serviceInfo = info
            Thread.sleep(300)
        }
        fun walk(n: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            n ?: return emptyList()
            return (if (match(n)) listOf(n) else emptyList()) + (0 until n.childCount).flatMap { walk(n.getChild(it)) }
        }
        return (ui.windows.mapNotNull { it.root } + listOfNotNull(ui.rootInActiveWindow)).distinct().flatMap { walk(it) }
    }

    private fun all(text: String) = nodes { it.text?.toString() == text }
    private fun containing(text: String) = nodes { it.text?.toString()?.contains(text) == true }

    private fun waitFor(text: String, exact: Boolean = true) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        fun found() = if (exact) all(text).isNotEmpty() else containing(text).isNotEmpty()
        while (!found() && SystemClock.uptimeMillis() < deadline) Thread.sleep(200)
        assertTrue("shows: $text", found())
        Thread.sleep(600)
    }

    /** Taps the clickable thing that says [text] (itself or the nearest clickable parent); [lowest]: the one lowest on screen. */
    private fun tap(text: String, lowest: Boolean = false) {
        val list = all(text).mapNotNull { m -> var n: AccessibilityNodeInfo? = m; while (n != null && !n.isClickable) n = n.parent; n }
        assertTrue("to tap: $text", list.isNotEmpty())
        val n = if (lowest) list.maxBy { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.top } else list.first()
        assertTrue("clickable: $text", n.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        Thread.sleep(700)
    }

    private fun typeBalance(cents: Long) {
        val field = nodes { it.isEditable }.single()
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, Money.plain(cents)) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        Thread.sleep(800)
    }

    private fun shot(name: String) {
        val bmp = ui.takeScreenshot()
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("$name: drawn", px.distinct().size > 10)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Before fun setUp() = runBlocking<Unit> {
        dir.mkdirs()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { tick += 60_000; tick }
        a = BudgetActions(store)
        bills = BillActions(store, a) { today }
        store.ensureSystemEnvelopes(); store.ensureCategories()
        acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 9, 1))
        store.ensureBudgetStart(LocalDate.of(2026, 9, 1), setupDone = true)
        for ((name, target) in listOf("Phone" to 100_00L, "Power" to 150_00L, "Rent" to 900_00L, "Fun" to 60_00L)) {
            env[name] = a.saveEnvelope(null, EnvelopeDraft(name, EnvelopeMode.FILL_TO_TARGET, target = target), LocalDate.of(2026, 10, 1))
        }
        val pay = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 10, 28), "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true))))
        a.markPayday(pay, LocalDate.of(2026, 10, 28), fill = false)
        bills.savePaySchedule(PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2))
        store.write {
            put(null, BillEntity(id = "tel", name = "Sample Phone Co", envelopeId = env.getValue("Phone"), amountDue = 100_00, dueDate = LocalDate.of(2026, 12, 4), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "power", name = "Sample Electric", envelopeId = env.getValue("Power"), amountDue = 150_00, dueDate = LocalDate.of(2026, 12, 9), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "rent", name = "Sample Rent", envelopeId = env.getValue("Rent"), amountDue = 900_00, dueDate = LocalDate.of(2026, 12, 1), createdAt = now, updatedAt = now))
            put(null, BillEntity(id = "water", name = "Sample Water", envelopeId = env.getValue("Power"), amountDue = 35_00, dueDate = LocalDate.of(2026, 12, 18), createdAt = now, updatedAt = now))
        }
        val w = a.snapshot().budgetMonths(today).containing(today)
        bills.setMonthAmount("power", w.start, 182_37)
        bills.setMonthAmount("water", w.start, null)
        a.assign(env.getValue("Rent"), 900_00, null, LocalDate.of(2026, 10, 28))
        a.assign(env.getValue("Fun"), 50_00, null, LocalDate.of(2026, 10, 28))
        a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 11, 10), "Sample Store", "", -30_00, listOf(LineDraft(env.getValue("Phone"), -30_00))))
    }

    @After fun tearDown() { db.close() }

    private fun b(): Budget = runBlocking { a.snapshot() }

    private fun launch(block: (ActivityScenario<ComponentActivity>) -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { act ->
                act.setContent {
                    DdfTheme {
                        androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = DdfColors.Background) {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) { screen?.invoke() }
                        }
                    }
                }
            }
            block(scenario)
        }
    }

    @Test fun billsOneMonthAtATimeOnThePhone() {
        val budget = b()
        val p = Paydays.predict(budget.paydayDates, today, schedule = budget.paySchedule)
        val state = ComingUpState()
        launch { scenario ->
            scenario.onActivity { screen = { BillsScreen(budget, today, p, emptyList(), {}, {}, {}, {}, {}, comingUp = state) } }
            waitFor("4 unpaid this month", exact = false)
            assertTrue(containing("182.37").isNotEmpty())
            shot("phone-bills-this-month-boxes")
            scenario.onActivity { state.offset = 1 }
            waitFor("Jan 2027")
            shot("phone-bills-later-month")
            scenario.onActivity { state.offset = 0; state.custom = true; state.from = "2026-12-01"; state.to = "2026-12-10" }
            waitFor("unpaid in this range", exact = false)
            shot("phone-bills-custom-range")
            val power = budget.envelopes.first { it.id == env.getValue("Power") }
            scenario.onActivity { screen = { key("power") { EnvelopeDetailScreen(power, budget, today, {}, {}, {}, {}, {}, {}, {}) } } }
            waitFor("Target this month")
            assertTrue(all("$217.37").isNotEmpty())
            shot("phone-envelope-month-target")
        }
    }

    @Test fun thePaydayPopupOnThePhone() {
        // a week before payday (no banner): the Payday button beside Move money
        today = LocalDate.of(2026, 11, 20)
        val budget = b()
        val app = PaydayButton.appBalance(budget, acct)
        launch { scenario ->
            scenario.onActivity { screen = { EnvelopesScreen(budget, {}, {}, {}, {}, today, onPayday = { _, _, _ -> "" }) } }
            waitFor("Payday")
            tap("Payday")
            waitFor("App shows ${Money.format(app)}.")
            shot("phone-payday-empty")
            typeBalance(app - 25_00)
            waitFor("The app is $25.00 higher than the bank", exact = false)
            shot("phone-payday-negative-blocked")
            typeBalance(app)
            waitFor("App shows ${Money.format(app)}. Difference $0.00.")
            shot("phone-payday-zero")
            typeBalance(app + 2_000_00)
            waitFor("App shows ${Money.format(app)}. Difference $2,000.00.")
            shot("phone-payday-positive")
            tap("?")
            waitFor(net.darkrelay.deaddropfinance.ui.CLEAN_UP_HELP)
            shot("phone-payday-clean-up-help")
            val box = nodes { it.className?.toString() == "android.widget.CheckBox" }.single()
            assertTrue(box.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(600)
            tap("Payday", lowest = true)
            waitFor("Payday?")
            assertTrue(containing("Clean up: 2 leftovers back to Unplanned ($950.00), 1 overspent covered from Unplanned ($30.00).").isNotEmpty())
            shot("phone-payday-confirm-clean-up")
        }
    }

    @Test fun envelopesMonthPickerOnThePhone() {
        today = LocalDate.of(2026, 11, 20)
        var ahead by mutableStateOf(false)
        launch { scenario ->
            var budget = b()
            fun show() {
                val bb = budget
                screen = { EnvelopesScreen(bb, {}, {}, {}, {}, today, onPayday = { _, _, _ -> "" }, next = if (ahead) bb.nextMonth(today) else null, onNext = { ahead = it }) }
            }
            scenario.onActivity { show() }
            waitFor("This budget month, since Oct 28")
            shot("phone-envelopes-this-month")
            tap("▶")
            waitFor("Next budget month, Nov 27 to Dec 30")
            // assigned ahead: Phone 130.00 (to 100.00 from -30.00), Power 182.37 (its month amount + water 35.00 is the target: 217.37)
            runBlocking {
                val next = b().nextMonth(today)
                a.assign(env.getValue("Phone"), 130_00, next.start, today)
                a.addToEnvelope(env.getValue("Power"), today, next)
                a.addToEnvelope(env.getValue("Fun"), today, next)
            }
            budget = b()
            scenario.onActivity { show() }
            waitFor("${Money.format(budget.assignedAhead)} assigned ahead", exact = false)
            assertTrue(budget.assignedAhead == 130_00L + 217_37L + 10_00L)
            shot("phone-envelopes-next-month-assigned")
            // a week later, Nov 27 has come and isn't marked
            today = LocalDate.of(2026, 11, 28)
            ahead = false
            scenario.onActivity { show() }
            waitFor("Payday was due Nov 27. Mark it with Payday.")
            shot("phone-envelopes-payday-due-banner")
        }
    }
}
