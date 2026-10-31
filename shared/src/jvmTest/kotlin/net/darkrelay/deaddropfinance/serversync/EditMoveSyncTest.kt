package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.MoveRules
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edit a move (1.8.1, program 0.14.0), the shared rule both use: a move made by hand changes its date, ends and
 * amount, both envelopes' balances follow, it can go below what the source holds (as Move money can), and it's synced
 * as an edit of that move, a delete as a tombstone; a payday's and an ATM's moves can't be edited. FakeServer, no
 * network, invented budgets.
 */
class EditMoveSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<BudgetStore>()

    @After fun done() { scope.cancel(); opened.forEach { it.db.close() } }

    private inner class Device(name: String) {
        val store: BudgetStore = runBlocking {
            val o = kotlin.test.assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(name)))
            o.store.ensureSystemEnvelopes(); o.store.ensureCategories(); opened += o.store; o.store
        }
        val actions = BudgetActions(store)
        val keys = net.darkrelay.deaddropfinance.backup.RecoveryKeys(net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore())
        val sync = ServerSync(store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { server.transport() }, sendDelayMs = 60_000, recovery = keys)
    }

    private suspend fun Device.budget(): Triple<String, String, String> {
        val acct = actions.saveAccount(null, "Sample Checking", AccountType.BANK, 0, day)
        actions.saveTransaction(null, TransactionDraft(acct, day, "Sample Payroll", "", 500_00, listOf(LineDraft(null, 500_00, toReadyToAssign = true))))
        val food = actions.saveEnvelope(null, EnvelopeDraft("Sample Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
        val gas = actions.saveEnvelope(null, EnvelopeDraft("Sample Gas", EnvelopeMode.FILL_TO_TARGET, target = 100_00), day)
        actions.move(null, food, 200_00, MoveKind.ASSIGN, day)
        actions.move(food, gas, 50_00, date = day)
        store.setBudgetStart(day)
        return Triple(food, gas, actions.snapshot().moves.single { it.fromEnvelopeId == food }.id)
    }

    @Test fun anEditMovesTheMoneyAndBothBalancesFollow() = runBlocking<Unit> {
        val d = Device("A")
        val (food, gas, move) = d.budget()
        d.actions.editMove(move, food, gas, 80_00, day.plusDays(2))
        val b = d.actions.snapshot()
        assertEquals(120_00, b.envelopeBalance(food))
        assertEquals(80_00, b.envelopeBalance(gas))
        assertEquals(300_00, b.readyToAssign)
        val m = b.moves.single { it.id == move }
        assertEquals(day.plusDays(2), m.date)
        // turned around: back into Food from Gas
        d.actions.editMove(move, gas, food, 10_00, day)
        assertEquals(210_00, d.actions.snapshot().envelopeBalance(food))
        assertEquals(-10_00, d.actions.snapshot().envelopeBalance(gas))
        // out of Ready to assign it's an Assign
        d.actions.editMove(move, null, gas, 30_00, day)
        assertEquals(MoveKind.ASSIGN, d.actions.snapshot().moves.single { it.id == move }.kind)
        assertFailsWith<BudgetRuleException> { d.actions.editMove(move, gas, gas, 1_00, day) }
        assertFailsWith<BudgetRuleException> { d.actions.editMove(move, food, gas, 0, day) }
    }

    @Test fun anEditBeyondWhatTheSourceHoldsGoesNegativeAsMoveMoneyDoes() = runBlocking<Unit> {
        val d = Device("A")
        val (food, gas, move) = d.budget()
        // Food holds 150.00 after the move: as Move money, an edit isn't stopped at what's there
        d.actions.editMove(move, food, gas, 260_00, day)
        val b = d.actions.snapshot()
        assertEquals(-60_00, b.envelopeBalance(food))
        assertEquals(260_00, b.envelopeBalance(gas))
        // and an assign beyond Ready to assign leaves it below zero: shown as over-assigned
        d.actions.editMove(move, null, gas, 400_00, day)
        assertTrue(d.actions.snapshot().readyToAssign < 0)
    }

    @Test fun aDeleteIsATombstoneAndThePaydayAndAtmMovesStayAsTheyAre() = runBlocking<Unit> {
        val d = Device("A")
        val (food, _, move) = d.budget()
        d.actions.deleteMove(move)
        assertEquals(200_00, d.actions.snapshot().envelopeBalance(food))
        assertTrue(d.store.dao.move(move)!!.deleted)
        // a payday's auto-fill
        val acct = d.actions.snapshot().accounts.single().id
        val pay = d.actions.saveTransaction(null, TransactionDraft(acct, day.plusDays(14), "Sample Payroll", "", 400_00, listOf(LineDraft(null, 400_00, toReadyToAssign = true))))
        d.actions.markPayday(pay, day.plusDays(14), fill = true)
        val made = d.actions.snapshot().moves.filter { it.paydayTransactionId == pay }
        assertTrue(made.isNotEmpty())
        for (m in made) {
            assertFalse(MoveRules.editable(m))
            assertFailsWith<BudgetRuleException> { d.actions.editMove(m.id, m.fromEnvelopeId, m.toEnvelopeId, 1_00, day) }
            assertFailsWith<BudgetRuleException> { d.actions.deleteMove(m.id) }
        }
        assertTrue(d.actions.snapshot().envelopeActivity(food).filter { it.label == "Payday auto-fill" }.all { it.moveId == null })
    }

    @Test fun anEditAndADeleteReachTheOtherDeviceAsChangesToThatMove() = runBlocking<Unit> {
        val a = Device("A"); val (food, gas, move) = a.budget()
        runBlocking { a.sync.createAccount("server.example", "test-code", "tester", "test-pass-for-unit", "Test program") }
        val b = Device("B")
        if (b.sync.needsRecoveryKeyFirst()) b.keys.ensure()
        b.sync.login("server.example", "tester", "test-pass-for-unit", "Test phone") { "Downloads/test-backup.ddfbak" }
        a.sync.foreground()
        a.actions.editMove(move, food, gas, 75_00, day.plusDays(1))
        // one change: the move itself, edited (no new move, no delete)
        assertEquals(1, a.store.dao.changes().count { it.entityType == "envelope_move" && it.entityId == move && it.operation == net.darkrelay.deaddropfinance.data.ChangeOperation.UPDATE })
        a.sync.syncNow(); b.sync.syncNow()
        val bm = b.actions.snapshot()
        assertEquals(75_00, bm.envelopeBalance(gas))
        assertEquals(125_00, bm.envelopeBalance(food))
        assertEquals(day.plusDays(1), bm.moves.single { it.id == move }.date)
        // then the other way: the phone edits and deletes, the program takes it
        a.sync.background(); b.sync.foreground()
        b.actions.editMove(move, food, gas, 20_00, day)
        b.sync.syncNow(); a.sync.syncNow()
        assertEquals(20_00, a.actions.snapshot().envelopeBalance(gas))
        b.actions.deleteMove(move)
        b.sync.syncNow(); a.sync.syncNow()
        assertTrue(a.actions.snapshot().moves.none { it.id == move })
        assertEquals(0L, a.actions.snapshot().envelopeBalance(gas))
    }
}
