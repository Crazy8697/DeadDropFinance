package net.darkrelay.deaddropfinance.data

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.sync.SyncSettings
import net.darkrelay.deaddropfinance.ui.rowChips
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bank notification reader removed (1.5.0): the upgrade cleanup clears its settings, empties its picked apps and
 * deletes its notification channels, every other setting stays, and a second run finds nothing to do. Transactions it
 * logged before the upgrade stay, and count and show like any other. Invented values only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReaderRemovalTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val day = LocalDate.of(2030, 6, 12)
    private val now = 20_000_000L
    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        a = BudgetActions(store)
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { db.close() }

    /** A picked app as the reader saved it (up to 1.4.x), with a template and its last read amount. */
    private fun oldPickedApp(pkg: String) = db.openHelper.writableDatabase.execSQL(
        "INSERT INTO notif_app (package_name, label, account_id, enabled, templates, last_amount, last_payee, last_deposit, last_at) " +
            "VALUES ('$pkg', 'Sample Bank', 'a1', 1, '0\u001Fof\u001Fat\u001Fwith card\u001F', 4218, 'SAMPLE MARKET', 0, 1)",
    )

    @Test fun theReadersSettingsAppsAndChannelsGoAndEverythingElseStays() = runBlocking<Unit> {
        oldPickedApp("com.example.samplebank")
        oldPickedApp("com.example.samplewallet")
        val prefs = ctx.getSharedPreferences("reader-removal-test", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            clear()
            putBoolean("notif_reading", true); putStringSet("notif_packages", setOf("com.example.samplebank"))
            putBoolean("alert_spending", false); putBoolean("alert_low", false); putBoolean("alert_safe_to_spend", false)
            putStringSet("alert_ids", setOf("t1"))
            // kept: bill reminders, autopay warnings, the lock screen switch they use, and the rest
            putBoolean("alert_bills", false); putBoolean("alert_autopay", false); putBoolean("alert_lock_screen_amounts", true)
            putBoolean("auto_approve", true); putBoolean("setup_done", true); putBoolean("alerts_asked", true)
            putStringSet("reminded", setOf("autopay:b1:2030-06-15"))
        }
        val settings = SyncSettings(prefs)
        val deleted = mutableListOf<String>()

        assertEquals(2, ReaderRemoval(store.dao, settings) { deleted += it }.run())

        assertEquals(0, store.dao.notifAppCount())
        for (k in ReaderRemoval.OLD_SETTINGS) assertFalse(k, prefs.contains(k))
        assertEquals(listOf("spending", "warnings", "reading"), deleted)
        assertFalse(settings.alertBills)
        assertFalse(settings.alertAutopay)
        assertTrue(settings.alertAmountsOnLockScreen)
        assertTrue(settings.autoApprove)
        assertTrue(settings.setupDone)
        assertTrue(settings.alertsAsked)
        assertEquals(setOf("autopay:b1:2030-06-15"), settings.reminded)
        val kept = prefs.all.toMap()

        // a second start: nothing left to remove, nothing else changes, and deleting a channel that's gone is fine
        assertEquals(0, ReaderRemoval(store.dao, settings) { deleted += it; if (deleted.size > 3) error("already gone") }.run())
        assertEquals(0, store.dao.notifAppCount())
        assertEquals(kept, prefs.all.toMap())
    }

    @Test fun transactionsTheReaderMadeStayOrdinaryTransactions() = runBlocking<Unit> {
        val chk = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day.minusDays(30))
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
        a.move(null, food, 100_00, MoveKind.ASSIGN, day)
        // as the reader logged them up to 1.4.x: pending, source NOTIFICATION; one still in To sort, one sorted into Food
        suspend fun logged(payee: String, amount: Long, sorted: Boolean) = store.write {
            val t = put(null, TransactionEntity(accountId = chk, date = day, payee = payee, amount = amount, pending = true, importDescription = payee, source = TransactionSource.NOTIFICATION, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = if (sorted) LineBucket.ENVELOPE else LineBucket.UNSORTED, envelopeId = if (sorted) food else null, amount = amount, createdAt = now, updatedAt = now))
            t.id
        }
        val unsorted = logged("SAMPLE KIOSK", -9_99, sorted = false)
        val sorted = logged("SAMPLE MARKET", -42_18, sorted = true)
        oldPickedApp("com.example.samplebank")
        val before = a.snapshot()

        ReaderRemoval(store.dao, SyncSettings(ctx.getSharedPreferences("reader-removal-tx", Context.MODE_PRIVATE))).run()

        val b = a.snapshot()
        assertEquals("nothing deleted or changed", before.transactions, b.transactions)
        assertEquals(before.lines, b.lines)
        assertEquals(500_00L - 9_99 - 42_18, b.accountBalance(chk))
        assertEquals(before.accountBalance(chk), b.accountBalance(chk))
        assertEquals(100_00L - 42_18, b.envelopeBalance(food))
        assertEquals(before.readyToAssign, b.readyToAssign)
        assertEquals(before.toSortTotal, b.toSortTotal)
        assertEquals("the unsorted one waits in To sort like any other", listOf(unsorted), b.toSort.map { it.id })
        // in Activity its row reads like a hand-entered pending purchase's: no sign of where it came from
        val reader = b.transactions.single { it.id == sorted }
        assertEquals(listOf("Uncategorized", "pending"), rowChips(reader, b))
        assertEquals(rowChips(reader.copy(source = TransactionSource.MANUAL), b), rowChips(reader, b))

        // CSV import is unchanged: the bank's row of a pending one posts it instead of adding a second
        val csv = "Date,Description,Amount\n${day.plusDays(1)},POS DEBIT SAMPLE MARKET 0042,-42.18\n"
        val res = a.importCsv(chk, CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), chk, a.snapshot().transactions))
        assertTrue(res.newIds.isEmpty())
        val after = a.snapshot()
        assertEquals(2, after.transactions.size)
        assertFalse(after.transactions.single { it.id == sorted }.pending)
        assertEquals(b.accountBalance(chk), after.accountBalance(chk))
    }
}
