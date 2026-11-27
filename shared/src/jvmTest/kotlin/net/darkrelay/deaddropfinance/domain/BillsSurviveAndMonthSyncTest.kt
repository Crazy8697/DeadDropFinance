package net.darkrelay.deaddropfinance.domain

import androidx.sqlite.execSQL
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.DesktopKey
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.desktopdb.CipherDriver
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.serversync.ServerChange
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.ServerSyncTest
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The bills that went missing from Bills after 0.15.11 (they were never deleted), recreated on an invented budget: bills
 * added on schema 15, the update (schema 16), the pay schedule saved, an older phone syncing. They survive every step
 * and the budget month that holds today has them. And a bill's This month amount (1.9.9) syncing, with an older
 * device's changes leaving it alone.
 */
class BillsSurviveAndMonthSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val today = LocalDate.of(2026, 11, 27)
    private var tick = today.minusDays(40).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val firstMinus2 = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)

    private fun open(dir: File) = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(dir, clock = { tick += 60_000; tick })).store

    private suspend fun setUp(store: BudgetStore): Pair<String, String> {
        store.ensureSystemEnvelopes(); store.ensureCategories()
        val a = BudgetActions(store)
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2026, 10, 1))
        store.ensureBudgetStart(LocalDate.of(2026, 10, 1), setupDone = true)
        val rent = a.saveEnvelope(null, EnvelopeDraft("Rent", EnvelopeMode.FILL_TO_TARGET, target = 900_00), LocalDate.of(2026, 10, 1))
        val pay = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2026, 10, 28), "Sample Payroll Co", "", 2_000_00, listOf(LineDraft(null, 2_000_00, toReadyToAssign = true))))
        a.markPayday(pay, LocalDate.of(2026, 10, 28), fill = false)
        return acct to rent
    }

    private fun older(store: BudgetStore, block: suspend (ServerSync) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try { block(ServerSync(store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { error("no network here") })) } finally { scope.cancel() }
    }

    @Test fun billsAddedBeforeTheUpdateSurviveTheUpdateTheScheduleAndAnOlderPhone() = runBlocking<Unit> {
        val dir = tmp.newFolder()
        var store = open(dir)
        val (_, rent) = setUp(store)
        repeat(6) { n -> store.write { put(null, BillEntity(name = "Sample Bill $n", envelopeId = rent, amountDue = 100_00, dueDate = LocalDate.of(2026, 12, 1), createdAt = now, updatedAt = now)) } }
        // as 0.15.10 and 0.15.11 left it: schema 15, no bill_month table
        store.db.close()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).load()).key
        CipherDriver { key.copyOf() }.open(File(dir, "budget.db").absolutePath).use { c ->
            c.execSQL("DROP TABLE `bill_month`")
            c.execSQL("ALTER TABLE `envelope_move` DROP COLUMN `for_month`")
            c.execSQL("PRAGMA user_version = 15")
        }
        store = open(dir)
        assertEquals(6, store.dao.billsNow().size, "the update keeps them")
        BillActions(store) { today }.savePaySchedule(firstMinus2)
        assertEquals(6, store.dao.billsNow().size, "the schedule keeps them")
        older(store) { sync ->
            val one = store.dao.billsNow().first()
            sync.applyRemote(listOf(
                ServerChange(1, "c-1", "bill", one.id, "UPDATE", JSONObject(one.fields().mapValues { it.value ?: JSONObject.NULL }).put("amount_due", 101_00), tick + 1, "phone-old"),
                ServerChange(2, "c-2", "meta", BudgetStore.META_BUDGET_START, "UPDATE", JSONObject().put("value", "2026-10-01"), tick + 1, "phone-old"),
            ))
        }
        assertEquals(6, store.dao.billsNow().size, "the older phone keeps them")
        // Nov 27 is a scheduled payday not marked yet: the budget month that holds today is Nov 27 to Dec 30, and has them
        val b = BudgetActions(store).snapshot()
        val w = b.budgetMonths(today).containing(today)
        assertEquals(today to LocalDate.of(2026, 12, 30), w.start to w.end)
        assertTrue(b.bills.all { bill -> Bills.cycles(bill, b.billPayments, w.end.minusDays(1)).any { w.contains(it.due) } })
    }

    @Test fun aMonthAmountSyncsAndAnOlderDevicesChangesLeaveItAlone() = runBlocking<Unit> {
        val store = open(tmp.newFolder())
        val (_, rent) = setUp(store)
        val bill = store.write { put(null, BillEntity(name = "Sample Power", envelopeId = rent, amountDue = 150_00, dueDate = LocalDate.of(2026, 12, 1), createdAt = now, updatedAt = now)) }
        older(store) { sync ->
            sync.applyRemote(listOf(ServerChange(1, "c-1", "bill_month", "bm-1", "INSERT",
                JSONObject().put("bill_id", bill.id).put("month_start", today.toString()).put("amount", 140_00).put("created_at", tick).put("updated_at", tick).put("deleted", false), tick + 1, "phone")))
            val b = BudgetActions(store).snapshot()
            assertEquals(140_00L, BillMonths.amountFor(b.billMonths, bill.id, b.budgetMonths(today).containing(today)))
            // an older device's change to the bill knows nothing of month amounts: the month amount stays
            sync.applyRemote(listOf(ServerChange(2, "c-2", "bill", bill.id, "UPDATE", JSONObject(bill.fields().mapValues { it.value ?: JSONObject.NULL }).put("name", "Sample Power Co"), tick + 2, "phone-old")))
            assertEquals(1, store.dao.billMonthsNow().size)
            // a change of a kind this version doesn't know is skipped, the rest go in
            sync.applyRemote(listOf(ServerChange(3, "c-3", "some_future_table", "x", "INSERT", JSONObject().put("a", 1), tick + 3, "phone-new")))
        }
        BillActions(store) { today }.setMonthAmount(bill.id, today, 141_00)
        assertTrue(store.dao.changes().any { it.entityType == "bill_month" && it.changedFields.contains("\"amount\":14100") }, "saved here, logged to sync")
    }

    /**
     * 1.9.9: money assigned ahead to the next budget month syncs (its for_month), and an older device, which doesn't
     * know the column, changing the move leaves it assigned ahead; Ready to assign is the same on both.
     */
    @Test fun anAssignedAheadMoveSyncsAndAnOlderDevicesEditKeepsIt() = runBlocking<Unit> {
        val store = open(tmp.newFolder())
        val (_, rent) = setUp(store)
        BillActions(store) { today }.savePaySchedule(firstMinus2)
        val next = BudgetActions(store).snapshot().nextMonth(LocalDate.of(2026, 11, 20))
        assertEquals(today, next.start)
        val rta = BudgetActions(store).snapshot().readyToAssign
        older(store) { sync ->
            sync.applyRemote(listOf(ServerChange(1, "c-1", "envelope_move", "mv-1", "INSERT",
                JSONObject().put("from_envelope_id", JSONObject.NULL).put("to_envelope_id", rent).put("amount", 300_00).put("date", "2026-11-20").put("kind", "ASSIGN")
                    .put("for_month", next.start.toString()).put("created_at", tick).put("updated_at", tick).put("deleted", false), tick + 1, "phone")))
            var b = BudgetActions(store).snapshot()
            assertTrue(b.isAhead(b.moves.single { it.id == "mv-1" }))
            assertEquals(rta - 300_00, b.readyToAssign)
            assertEquals(300_00L, b.nextBalance(rent) - b.envelopeBalance(rent))
            // an older device edits its amount: its change has no for_month, so it stays assigned ahead
            sync.applyRemote(listOf(ServerChange(2, "c-2", "envelope_move", "mv-1", "UPDATE",
                JSONObject().put("from_envelope_id", JSONObject.NULL).put("to_envelope_id", rent).put("amount", 250_00).put("date", "2026-11-20").put("kind", "ASSIGN")
                    .put("payday_transaction_id", JSONObject.NULL).put("linked_transaction_id", JSONObject.NULL).put("deleted", false), tick + 2, "phone-old")))
            b = BudgetActions(store).snapshot()
            val mv = b.moves.single { it.id == "mv-1" }
            assertEquals(250_00L, mv.amount)
            assertEquals(next.start, mv.forMonth)
            assertTrue(b.isAhead(mv))
            assertEquals(rta - 250_00, b.readyToAssign)
        }
        // saved here: logged with its for_month, so it syncs
        BudgetActions(store).assign(rent, 10_00, next.start, today)
        assertTrue(store.dao.changes().any { it.entityType == "envelope_move" && it.changedFields.contains("\"for_month\":\"${next.start}\"") })
    }
}
