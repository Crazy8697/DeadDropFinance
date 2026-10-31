package net.darkrelay.deaddropfinance

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.CategoryEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.Reports.Kind
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.ReportData
import net.darkrelay.deaddropfinance.ui.ReportScreen
import net.darkrelay.deaddropfinance.ui.ReportsScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.9.0: Reports on a real Android device (the emulator, through tools\emulator.ps1 -Instrument): the tiles and each
 * report draw on an invented budget in a plain activity, and each window is copied to a picture (PixelCopy) in the app's
 * own files folder (reports/) for the round's screenshots, which mustn't be blank. Compose's test rule isn't used: its
 * Espresso can't drive Android 17. The screens' contents are checked in ReportScreensTest. Never a real budget.
 */
@RunWith(AndroidJUnit4::class)
class ReportsOnDeviceTest {

    private val today = LocalDate.now()
    private val p1 = today.minusDays(14)
    private val p2 = today.minusDays(1)

    private fun tx(id: String, d: LocalDate, amount: Long, payee: String, payday: Boolean = false, memo: String = "") =
        TransactionEntity(id = id, accountId = "a", date = d, payee = payee, memo = memo, amount = amount, isPayday = payday, createdAt = 1, updatedAt = 1)

    private fun line(tx: String, amount: Long, bucket: LineBucket, env: String? = null, cat: String? = null) =
        TransactionLineEntity(transactionId = tx, bucket = bucket, envelopeId = env, amount = amount, categoryId = cat, createdAt = 1, updatedAt = 1)

    private fun move(to: String, amount: Long, d: LocalDate) =
        MoveEntity(fromEnvelopeId = null, toEnvelopeId = to, amount = amount, date = d, kind = MoveKind.ASSIGN, createdAt = 1, updatedAt = 1)

    private val budget = Budget(
        listOf(
            AccountEntity(id = "a", name = "Sample Checking", type = AccountType.BANK, startingBalance = 400_00, startDate = today.minusDays(90), createdAt = 1, updatedAt = 1),
            AccountEntity(id = "c", name = "Sample Card", type = AccountType.BANK, startingBalance = -120_00, startDate = today.minusDays(90), trackOnly = true, sortOrder = 1, createdAt = 1, updatedAt = 1),
        ),
        listOf(
            EnvelopeEntity(id = "e-food", name = "Groceries", mode = EnvelopeMode.FILL_TO_TARGET, target = 300_00, createdAt = 1, updatedAt = 1),
            EnvelopeEntity(id = "e-gas", name = "Gas", mode = EnvelopeMode.FILL_TO_TARGET, target = 120_00, sortOrder = 1, createdAt = 1, updatedAt = 1),
            EnvelopeEntity(id = "e-trip", name = "Trip", mode = EnvelopeMode.GOAL, goalAmount = 1000_00, goalDate = today.plusMonths(6), sortOrder = 2, createdAt = 1, updatedAt = 1),
            EnvelopeEntity(
                id = "e-loan", name = "Car loan", mode = EnvelopeMode.DEBT, perPayday = 150_00, debtOwed = 2400_00, debtOwedSince = today,
                requiredPayment = 300_00, dueDate = today.plusDays(5), interestRate = 699, sortOrder = 3, createdAt = 1, updatedAt = 1,
            ),
        ),
        listOf(
            tx("p1", p1, 1500_00, "Sample Payroll", payday = true), tx("p2", p2, 1500_00, "Sample Payroll", payday = true),
            tx("s1", p1.plusDays(2), -64_20, "Corner Market"), tx("s2", p1.plusDays(5), -38_00, "Fuel Stop"),
            tx("s3", p2, -41_10, "Corner Market"), tx("s4", p2, -4_29, "Corner Market", memo = "Eggs"),
        ),
        listOf(
            line("p1", 1500_00, LineBucket.READY_TO_ASSIGN), line("p2", 1500_00, LineBucket.READY_TO_ASSIGN),
            line("s1", -64_20, LineBucket.ENVELOPE, "e-food", "c-g"), line("s2", -38_00, LineBucket.ENVELOPE, "e-gas", "c-c"),
            line("s3", -41_10, LineBucket.ENVELOPE, "e-food", "c-g"), line("s4", -4_29, LineBucket.ENVELOPE, "e-food", "c-g"),
        ),
        listOf(move("e-food", 300_00, p1), move("e-gas", 120_00, p1), move("e-trip", 250_00, p1), move("e-food", 200_00, p2), move("e-loan", 150_00, p2)),
        bills = listOf(
            BillEntity(id = "b1", name = "Rent", envelopeId = null, amountDue = 900_00, dueDate = today.withDayOfMonth(1), createdAt = 1, updatedAt = 1),
            BillEntity(id = "b2", name = "Phone plan", envelopeId = null, amountDue = 45_00, dueDate = today.withDayOfMonth(15), autopay = true, createdAt = 1, updatedAt = 1),
        ),
        categories = listOf(
            CategoryEntity(id = "c-g", name = "Groceries", envelopeId = "e-food", createdAt = 1, updatedAt = 1),
            CategoryEntity(id = "c-c", name = "Car gas", envelopeId = "e-gas", sortOrder = 1, createdAt = 1, updatedAt = 1),
        ),
    )

    private val receipts = listOf(
        ReceiptEntity(id = "r1", transactionId = "s1", store = "Corner Market", date = p1.plusDays(2), createdAt = 1, updatedAt = 1),
        ReceiptEntity(id = "r2", transactionId = "s3", store = "Corner Market", date = p2, createdAt = 1, updatedAt = 1),
    )

    private val items = listOf(
        ReceiptItemEntity(id = "i1", receiptId = "r1", name = "Milk 2%", quantityMilli = 2000, unitPrice = 3_29, amount = 6_58, createdAt = 1, updatedAt = 1),
        ReceiptItemEntity(id = "i2", receiptId = "r2", name = "Milk 2%", quantityMilli = 2000, unitPrice = 3_49, amount = 6_98, createdAt = 1, updatedAt = 1),
        ReceiptItemEntity(id = "i3", receiptId = "r2", name = "Bread", amount = 2_99, createdAt = 1, updatedAt = 1),
    )

    // one activity: each screen is swapped in through state
    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "reports")

    private fun settle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(800)
    }

    private fun save(scenario: ActivityScenario<ComponentActivity>, name: String) {
        lateinit var bmp: Bitmap
        val done = CountDownLatch(1)
        var ok = false
        scenario.onActivity { a ->
            val v = a.window.decorView
            bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(a.window, bmp, { r -> ok = r == PixelCopy.SUCCESS; done.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue("$name: copied", done.await(10, TimeUnit.SECONDS) && ok)
        // not blank: more than one color on it
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        assertTrue("$name: drawn", px.distinct().size > 10)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everyReportDrawsOnThePhone() {
        dir.deleteRecursively(); dir.mkdirs()
        val data = ReportData(budget, today, receipts, items, Forecast.build(budget, emptyList(), today, today.plusDays(13)))
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            // the app's own background behind the screens, as AppRoot gives them
            scenario.onActivity { a ->
                a.setContent {
                    DdfTheme {
                        androidx.compose.material3.Surface(
                            androidx.compose.ui.Modifier.fillMaxSize(), color = net.darkrelay.deaddropfinance.ui.DdfColors.Background,
                        ) { screen?.invoke() }
                    }
                }
            }
            scenario.onActivity { screen = { ReportsScreen(data, 0, {}, onBack = {}) {} } }
            settle()
            save(scenario, "phone-reports")
            for (k in Kind.entries) {
                scenario.onActivity { screen = { ReportScreen(k, data, 0, {}, {}, {}, {}, {}, {}) } }
                settle()
                save(scenario, "phone-report-${(k.ordinal + 1).toString().padStart(2, '0')}-${k.name.lowercase().replace('_', '-')}")
            }
            // 1.9.1: an item's prices and a payee's page; Home as a phone that never customized it, and with two cards
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.PriceItemScreen(data, "milk 2%", {}) } }
            settle(); save(scenario, "phone-price-item")
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.MerchantScreen(data, net.darkrelay.deaddropfinance.domain.Payees.clean("Corner Market"), 0, {}) {} } }
            settle(); save(scenario, "phone-merchant-page")
            fun home(cards: List<Kind>) = scenario.onActivity {
                screen = {
                    net.darkrelay.deaddropfinance.ui.HomeScreen(
                        budget,
                        // 1.9.3: the top row: sync status, Bonus income, Sync Now
                        top = {
                            net.darkrelay.deaddropfinance.ui.SyncStatusLine(
                                net.darkrelay.deaddropfinance.serversync.SyncStatus(net.darkrelay.deaddropfinance.serversync.SyncStatus.State.SYNCED, lastSyncedAt = System.currentTimeMillis() - 120_000),
                                onSyncNow = {},
                                extra = { net.darkrelay.deaddropfinance.ui.TextButton(onClick = {}) { androidx.compose.material3.Text("Bonus income", color = net.darkrelay.deaddropfinance.ui.DdfColors.Cyan) } },
                            )
                        },
                        onAssign = {}, onToSort = {}, onEnvelope = {}, onAllEnvelopes = {}, onSafeToSpend = {}, onFixOverAssigned = {},
                        envelopes = budget.fillOrder, forecast = { net.darkrelay.deaddropfinance.ui.ForecastCard(data.forecast!!) {} },
                        dashboard = { net.darkrelay.deaddropfinance.ui.HomeReportCards(cards, data, 0, onOpen = {}) },
                    )
                }
            }
            home(emptyList()); settle(); save(scenario, "phone-home-default")
            home(listOf(Kind.SAVINGS_GOALS, Kind.SPENDING_BY_ENVELOPE)); settle(); save(scenario, "phone-home-customized")
            home(Kind.entries.toList()); settle(); save(scenario, "phone-home-all-12")
            var cards by mutableStateOf(listOf(Kind.SAVINGS_GOALS, Kind.SPENDING_BY_ENVELOPE))
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.HomeCustomizeScreen(cards, { cards = it }, {}) } }
            settle(); save(scenario, "phone-home-customize-screen")
            // 1.9.3: More with Customize Home, and the receipt form with tax letters (matched, and asking)
            scenario.onActivity {
                screen = {
                    net.darkrelay.deaddropfinance.ui.MoreScreen(
                        net.darkrelay.deaddropfinance.ui.MoreState(false, true, 5, false, true, false, null, "1.9.3"),
                        onAccounts = {}, onImport = {}, onLock = {}, onTimeout = {}, onHide = {}, onCheckAtStartup = {}, onCheckNow = {},
                        onReceipts = {}, onReports = {}, onCustomizeHome = {},
                    )
                }
            }
            settle(); save(scenario, "phone-more-customize-home")
            fun lines(t: List<String>) = t.map { net.darkrelay.deaddropfinance.domain.ReceiptParser.Line(it, 95) }
            val matched = net.darkrelay.deaddropfinance.ui.ReceiptForm.from(net.darkrelay.deaddropfinance.domain.ReceiptParser.parseLines(lines(listOf("SAMPLE MART", "08/14/2030", "LIME JUICE 1.99 A", "BREAD 2.50", "MILK 3.00 A", "SUBTOTAL 7.49", "TAX A 6.000% 0.47", "TOTAL 7.96"))))
            val asking = net.darkrelay.deaddropfinance.ui.ReceiptForm.from(net.darkrelay.deaddropfinance.domain.ReceiptParser.parseLines(lines(listOf("SAMPLE MART", "08/14/2030", "WIDGET 10.00 T", "APPLES 4.00 F", "SUBTOTAL 14.00", "TAX 0.60", "TOTAL 14.60"))))
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.ReceiptEditScreen(matched, emptyList(), true, false, null, {}, {}, {}, {}) } }
            settle(); save(scenario, "phone-receipt-tax-letters")
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.ReceiptEditScreen(asking, emptyList(), true, false, null, {}, {}, {}, {}) } }
            settle(); save(scenario, "phone-receipt-tax-ask")
            // 1.9.4: a real Food Lion receipt, read from its photo by the phone's own scan (cleaned up, then read) and
            // shown as the form shows it, each item with its envelope; Scan receipt on Activity and on the envelopes
            val ctx = InstrumentationRegistry.getInstrumentation().context
            val target = InstrumentationRegistry.getInstrumentation().targetContext
            val photo = File(target.cacheDir, "food-lion-receipt.webp").apply { ctx.assets.open("food-lion/food-lion-receipt.webp").use { i -> outputStream().use { i.copyTo(it) } } }
            val images = net.darkrelay.deaddropfinance.data.ReceiptImages(File(target.cacheDir, "fl-receipts"), { ByteArray(32) { it.toByte() } })
            val pages = net.darkrelay.deaddropfinance.scan.ReceiptScanner(target, images) { net.darkrelay.deaddropfinance.scan.TesseractReader(target) }
                .scan(android.net.Uri.fromFile(photo), "food-lion", 0)
            photo.delete(); File(target.cacheDir, "fl-receipts").deleteRecursively()
            val parsed = net.darkrelay.deaddropfinance.domain.ReceiptParser.parse(pages)
            assertTrue("every item line of the real receipt: ${parsed.items.size}", parsed.items.size == 21)
            val realForm = net.darkrelay.deaddropfinance.ui.ReceiptForm.from(parsed).also { it.guessEnvelopes(emptyList(), budget.envelopes) }
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.ReceiptEditScreen(realForm, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = budget.fillOrder) } }
            settle(); save(scenario, "phone-food-lion-receipt")
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.ReceiptEditScreen(matched.also { it.guessEnvelopes(emptyList(), budget.envelopes) }, emptyList(), true, false, null, {}, {}, {}, {}, envelopes = budget.fillOrder) } }
            settle(); save(scenario, "phone-receipt-envelope-dropdowns")
            scenario.onActivity {
                screen = {
                    net.darkrelay.deaddropfinance.ui.ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, sorting = net.darkrelay.deaddropfinance.ui.SortingUi(onScan = {}))
                }
            }
            settle(); save(scenario, "phone-activity-scan-receipt")
            scenario.onActivity { screen = { net.darkrelay.deaddropfinance.ui.EnvelopesScreen(budget, {}, {}, {}, {}, today = today, onScan = {}) } }
            settle(); save(scenario, "phone-envelopes-scan-receipt")
        }
    }
}
