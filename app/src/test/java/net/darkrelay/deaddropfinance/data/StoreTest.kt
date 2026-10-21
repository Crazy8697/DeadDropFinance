package net.darkrelay.deaddropfinance.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.CsvImport
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store and every write path, on an in-memory Room database (plain SQLite here: SQLCipher's native library
 * only runs on a phone, where the instrumented check covers encryption). Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoreTest {

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var a: BudgetActions
    private val d = LocalDate.of(2030, 1, 10)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        store = BudgetStore(db)
        a = BudgetActions(store)
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() = db.close()

    private fun changes() = runBlocking { store.dao.changes() }

    @Test fun rolloverIsSeededOnceAndNothingElse() = runBlocking<Unit> {
        store.ensureSystemEnvelopes()
        val envs = store.dao.envelopesNow()
        assertEquals(1, envs.size)
        assertEquals(EnvelopeMode.ROLLOVER, envs.single().mode)
        assertThrows(BudgetRuleException::class.java) { runBlocking { a.setEnvelopeArchived(envs.single().id, true) } }
    }

    @Test fun everyWritePathLogsItsChange() = runBlocking<Unit> {
        val before = changes().size
        val acct = a.saveAccount(null, "Test Bank", AccountType.BANK, 100_00, d)
        val cashAcct = a.saveAccount(null, "Wallet", AccountType.CASH, 0, d)
        val env = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 80_00))
        val env2 = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fuel", EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 20_00))
        a.reorderEnvelopes(listOf(store.dao.envelopesNow().first { it.mode == EnvelopeMode.ROLLOVER }.id, env2, env))
        a.assign(env, 30_00)
        a.move(env, env2, 5_00)
        val tx = a.saveTransaction(null, TransactionDraft(acct, d, "Corner Grocer", "", -25_00, listOf(LineDraft(env, -15_00), LineDraft(env2, -10_00))))
        val transfer = a.saveTransfer(null, acct, cashAcct, 40_00, d, "", fromEnvelopeId = env)
        a.setAccountArchived(cashAcct, true)
        a.deleteTransaction(tx)
        val log = changes().drop(before)
        val types = log.map { it.entityType }.toSet()
        assertTrue(types.containsAll(setOf("account", "envelope", "envelope_move", "transaction", "transaction_line")))
        // every change row names a real row, with this install's device id and an increasing sequence
        val device = store.deviceId()
        assertTrue(log.all { it.deviceId == device })
        assertEquals(log.map { it.seq }.sorted(), log.map { it.seq })
        assertEquals(log.size, log.map { it.seq }.toSet().size)
        // the ATM withdrawal made the Cash envelope and its move
        assertTrue(store.dao.envelopesNow().any { it.mode == EnvelopeMode.CASH })
        assertTrue(store.dao.movesLinkedTo(transfer).single().kind == MoveKind.ATM)
        // deleting logs DELETE for the transaction and its lines, and keeps them as tombstones
        assertEquals(3, log.count { it.operation == ChangeOperation.DELETE && it.entityType.startsWith("transaction") })
        assertTrue(store.dao.transaction(tx)!!.deleted)
        assertNotNull(store.dao.line(store.dao.changes().last { it.entityType == "transaction_line" }.entityId))
    }

    @Test fun changedFieldsAreJsonOfWhatChanged() = runBlocking<Unit> {
        val acct = a.saveAccount(null, "Test Bank", AccountType.BANK, 0, d)
        a.saveAccount(acct, "Renamed Bank", AccountType.BANK, 0, d)
        val last = changes().last()
        assertEquals(ChangeOperation.UPDATE, last.operation)
        val j = JSONObject(last.changedFields)
        assertEquals(listOf("name"), j.keys().asSequence().toList())
        assertEquals("Renamed Bank", j.getString("name"))
        // saving with nothing changed logs nothing
        val n = changes().size
        a.saveAccount(acct, "Renamed Bank", AccountType.BANK, 0, d)
        assertEquals(n, changes().size)
    }

    @Test fun aFailedWriteLeavesNoChangeRow() {
        val before = changes().size
        val accountsBefore = runBlocking { store.dao.accountsNow().size }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                store.write {
                    put(null, AccountEntity(name = "Half Written", type = AccountType.BANK, startDate = d, createdAt = now, updatedAt = now))
                    error("boom")
                }
            }
        }
        assertEquals(before, changes().size)
        assertEquals(accountsBefore, runBlocking { store.dao.accountsNow().size })
    }

    @Test fun ruleBreaksWriteNothing() {
        val before = changes().size
        assertThrows(BudgetRuleException::class.java) {
            runBlocking { a.saveTransaction(null, TransactionDraft("x", d, "p", "", -10_00, listOf(LineDraft(null, -9_00)))) }
        }
        assertEquals(before, changes().size)
    }

    @Test fun paydaySweepsFillsAndUnmarks() = runBlocking<Unit> {
        val acct = a.saveAccount(null, "Test Bank", AccountType.BANK, 0, d)
        val g = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 90_00))
        val s = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Savings", EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 25_00))
        val p1 = a.saveTransaction(null, TransactionDraft(acct, d, "Sample Payroll Co", "", 500_00, listOf(LineDraft(null, 500_00, toReadyToAssign = true))))
        val plan1 = a.markPayday(p1, d)
        assertTrue(plan1.sweeps.isEmpty())                                  // the first payday closes no month
        var b = a.snapshot()
        assertEquals(90_00L, b.envelopeBalance(g))
        assertEquals(25_00L, b.envelopeBalance(s))
        // spend some, then the next payday sweeps the leftover into Rollover and fills again
        a.saveTransaction(null, TransactionDraft(acct, d.plusDays(3), "Corner Grocer", "", -60_00, listOf(LineDraft(g, -60_00))))
        val p2 = a.saveTransaction(null, TransactionDraft(acct, d.plusDays(14), "Sample Payroll Co", "", 500_00, listOf(LineDraft(null, 500_00, toReadyToAssign = true))))
        val plan2 = a.markPayday(p2, d.plusDays(14))
        assertEquals(30_00L, plan2.sweeps.single().amount)
        b = a.snapshot()
        assertEquals(30_00L, b.envelopeBalance(b.rollover!!.id))
        assertEquals(90_00L, b.envelopeBalance(g))
        assertEquals(50_00L, b.envelopeBalance(s))
        assertEquals(0L, b.sortedCheck)
        a.unmarkPayday(p2)
        b = a.snapshot()
        assertEquals(0L, b.envelopeBalance(b.rollover!!.id))
        assertEquals(30_00L, b.envelopeBalance(g))
        assertFalse(store.dao.transaction(p2)!!.isPayday)
    }

    @Test fun washPairsCountAsNeither() = runBlocking<Unit> {
        val acct = a.saveAccount(null, "Test Bank", AccountType.BANK, 0, d)
        val inn = a.saveTransaction(null, TransactionDraft(acct, d, "From a friend", "", 50_00, listOf(LineDraft(null, 50_00, toReadyToAssign = true))))
        val out = a.saveTransaction(null, TransactionDraft(acct, d, "To someone", "", -50_00, listOf(LineDraft(null, -50_00))))
        assertEquals(listOf(out), a.washCandidates(inn).map { it.id })
        a.markWash(inn, out)
        val b = a.snapshot()
        assertEquals(0L, b.readyToAssign)
        assertTrue(b.toSort.isEmpty())
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun csvImportLandsInToSortAndPostsPending() = runBlocking<Unit> {
        val acct = a.saveAccount(null, "Test Bank", AccountType.BANK, 0, d)
        val pending = a.saveTransaction(null, TransactionDraft(acct, LocalDate.of(2030, 1, 5), "fuel", "", -35_20, listOf(LineDraft(null, -35_20)), pending = true))
        val text = javaClass.classLoader!!.getResource("csv/signed_iso.csv")!!.readText()
        val read = CsvImport.read(text, CsvImport.detect(text))
        val result = a.importCsv(acct, CsvImport.plan(read, acct, store.dao.transactionsNow()))
        assertEquals(1, result.posted)
        assertFalse(store.dao.transaction(pending)!!.pending)
        val b = a.snapshot()
        assertEquals(result.imported + 1, b.toSort.size)         // imported rows plus the (still unsorted) pending one
        assertTrue(store.dao.transactionsNow().any { it.fee })
        assertTrue(store.dao.transactionsNow().any { it.feeRebate })
        // importing the same file again adds nothing
        val again = a.importCsv(acct, CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), acct, store.dao.transactionsNow()))
        assertEquals(0, again.imported)
    }

    @Test fun deviceIdIsStableAndRandom() = runBlocking<Unit> {
        val id = store.deviceId()
        assertEquals(id, store.deviceId())
        assertEquals(36, id.length)
    }
}
