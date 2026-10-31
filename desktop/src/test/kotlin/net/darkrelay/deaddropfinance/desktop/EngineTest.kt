package net.darkrelay.deaddropfinance.desktop

import androidx.room.useReaderConnection
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.Payday
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The phone app's logic (the shared module) on the desktop, on a real budget database file in a temp folder (bundled
 * SQLite, as the program uses it). Invented data only: round numbers, made-up payees, dates in 2030.
 */
class EngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 3, 2)
    private val now = day.atTime(12, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val engines = mutableListOf<Engine>()

    private fun start(dir: File = tmp.root): Engine = runBlocking { Engine.start(dir) { now } }.also { engines += it }

    private fun actions(e: Engine) = assertIs<BudgetActions>(e.actions).also { it.requireCategories = false }

    @After fun closeAll() = engines.forEach { it.close() }

    @Test fun theProgramsBudgetIsTheSameDatabaseAsThePhones() = runBlocking<Unit> {
        val e = start()
        val ok = assertIs<DesktopDatabase.Opened.Ok>(e.opened)
        assertEquals(File(tmp.root, "budget.db"), ok.file)
        val (version, hash) = ok.store.db.useReaderConnection { c ->
            val v = c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) }
            val h = c.usePrepared("SELECT identity_hash FROM room_master_table") { it.step(); it.getText(0) }
            v to h
        }
        // the phone's schema 13 (0.15.0), as committed in app/schemas: same tables, columns, indexes (Room's identity hash)
        val phone = listOf(File("../app/schemas"), File("app/schemas")).first { it.isDirectory }
            .resolve("net.darkrelay.deaddropfinance.data.AppDatabase/13.json").readText()
        assertEquals(13L, version)
        assertTrue(phone.contains("\"identityHash\": \"$hash\""), "identity hash $hash is the phone's")
        val d = e.describe()
        assertEquals("ok", d["db"])
        assertTrue(d.getValue("envelopes").toInt() >= 1, "the system envelopes are made at startup, as on the phone")
        assertTrue(d.getValue("categories").toInt() > 0, "the starter categories too")
    }

    @Test fun dataStaysBetweenStartsAndStartingAgainAddsNothing() = runBlocking<Unit> {
        val first = start()
        val a = actions(first)
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day)
        val before = first.describe()
        first.close()
        engines.clear()
        val second = start()
        assertEquals(before, second.describe())
        assertEquals(listOf("Sample Checking"), actions(second).snapshot().accounts.map { it.name })
    }

    @Test fun envelopesAssignMoveAndReadyToAssign() = runBlocking<Unit> {
        val a = actions(start())
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 1000_00, day)
        val food = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true), day)
        val fun_ = a.saveEnvelope(null, EnvelopeDraft("Fun", EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 50_00), day)
        var b = a.snapshot()
        assertEquals(1000_00L, b.readyToAssign)
        a.move(null, food, 300_00, date = day)
        a.move(null, fun_, 100_00, date = day)
        a.move(food, fun_, 25_00, date = day)
        b = a.snapshot()
        assertEquals(275_00L, b.envelopeBalance(food))
        assertEquals(125_00L, b.envelopeBalance(fun_))
        assertEquals(600_00L, b.readyToAssign)
        assertEquals(275_00L, b.safeToSpend, "Safe to spend is the Everyday envelopes")
        assertFailsWith<BudgetRuleException> { a.saveEnvelope(null, EnvelopeDraft(" ", EnvelopeMode.FILL_TO_TARGET), day) }
    }

    @Test fun transactionsSplitIntoEnvelopesAndMoveTheBalances() = runBlocking<Unit> {
        val a = actions(start())
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 1000_00, day)
        val food = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
        val gas = a.saveEnvelope(null, EnvelopeDraft("Gas", EnvelopeMode.FILL_TO_TARGET, target = 100_00), day)
        a.move(null, food, 300_00, date = day)
        a.move(null, gas, 100_00, date = day)
        val tx = a.saveTransaction(
            null,
            TransactionDraft(acct, day, "Sample Market", "", -120_00, listOf(LineDraft(food, -80_00), LineDraft(gas, -40_00))),
        )
        var b = a.snapshot()
        assertEquals(880_00L, b.accountBalance(acct))
        assertEquals(220_00L, b.envelopeBalance(food))
        assertEquals(60_00L, b.envelopeBalance(gas))
        assertEquals(600_00L, b.readyToAssign)
        assertTrue(b.toSort.isEmpty())
        // the split must add up to the amount: refused, and nothing is half saved
        assertFailsWith<BudgetRuleException> {
            a.saveTransaction(null, TransactionDraft(acct, day, "Sample Shop", "", -10_00, listOf(LineDraft(food, -9_00))))
        }
        a.deleteTransaction(tx)
        b = a.snapshot()
        assertEquals(1000_00L, b.accountBalance(acct))
        assertEquals(300_00L, b.envelopeBalance(food))
        assertTrue(b.transactions.isEmpty())
    }

    @Test fun aWriteThatFailsKeepsNothing() = runBlocking<Unit> {
        val e = start()
        val ok = assertIs<DesktopDatabase.Opened.Ok>(e.opened)
        val acct = actions(e).saveAccount(null, "Sample Checking", AccountType.BANK, 0, day)
        val changesBefore = ok.store.dao.changes().size
        assertFailsWith<IllegalStateException> {
            ok.store.write {
                put(null, TransactionEntity(accountId = acct, date = day, payee = "Sample Half", amount = -5_00, createdAt = now, updatedAt = now))
                error("fails halfway")
            }
        }
        assertTrue(ok.store.dao.transactionsNow().isEmpty(), "the transaction was rolled back")
        assertEquals(changesBefore, ok.store.dao.changes().size, "and so was its change log row")
    }

    @Test fun csvImportLandsInToSortAndSkipsWhatIsAlreadyThere() = runBlocking<Unit> {
        val a = actions(start())
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 0, LocalDate.of(2030, 1, 1))
        val csv = """
            Date,Description,Amount,Balance
            2030-01-02,Sample Payroll Co,1500.00,1500.00
            2030-01-03,"Corner Grocer, Main St",-82.15,1417.85
            2030-01-05,ATM Fee Example Bank,-3.50,1414.35
            2030-13-05,Broken Date Row,-1.00,1413.35
            2030-01-07,ATM Fee Rebate,3.50,1416.85
        """.trimIndent()
        val layout = CsvImport.detect(csv)
        val read = CsvImport.read(csv, layout)
        assertEquals(4, read.rows.size)
        assertEquals(1, read.bad.size)
        val r = a.importCsv(acct, CsvImport.plan(read, acct, a.snapshot().transactions))
        assertEquals(4, r.imported)
        assertEquals(1, r.bad)
        var b = a.snapshot()
        assertEquals(1417_85L, b.accountBalance(acct))
        assertEquals(4, b.toSort.size)
        val shop = b.transactions.single { it.payee == "Corner Grocer, Main St" }
        assertEquals(-82_15L, shop.amount)
        assertTrue(b.transactions.single { it.payee == "ATM Fee Example Bank" }.fee)
        assertTrue(b.transactions.single { it.payee == "ATM Fee Rebate" }.feeRebate)
        assertTrue(store(a).dao.linesNow().all { it.bucket == LineBucket.UNSORTED })
        // the same file again: everything is already here
        val again = a.importCsv(acct, CsvImport.plan(read, acct, a.snapshot().transactions))
        assertEquals(0, again.imported)
        assertEquals(4, again.skipped)
        b = a.snapshot()
        assertEquals(4, b.transactions.size)
        // sorting one: it leaves To sort and lands in the envelope
        val food = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
        a.sortInto(shop.id, food)
        b = a.snapshot()
        assertEquals(3, b.toSort.size)
        assertEquals(-82_15L, b.envelopeBalance(food))
    }

    @Test fun paydayFillsEnvelopesInOrderAndReportsShortfalls() = runBlocking<Unit> {
        val a = actions(start())
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 0, day)
        val x = a.saveEnvelope(null, EnvelopeDraft("Rent", EnvelopeMode.FILL_TO_TARGET, target = 70_00), day)
        val y = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 50_00), day)
        val z = a.saveEnvelope(null, EnvelopeDraft("Gas", EnvelopeMode.FILL_TO_TARGET, target = 10_00), day)
        val pay = a.saveTransaction(null, TransactionDraft(acct, day, "Sample Payroll Co", "", 100_00, listOf(LineDraft(null, 100_00, toReadyToAssign = true))))
        val plan = Payday.plan(a.snapshot(), day, closesMonth = false)
        assertEquals(listOf(Payday.PlannedMove(null, x, 70_00), Payday.PlannedMove(null, y, 30_00)), plan.fills)
        assertEquals(listOf(Payday.Shortfall(y, 50_00, 30_00), Payday.Shortfall(z, 10_00, 0)), plan.shortfalls)
        assertEquals(0L, plan.leftInReadyToAssign)
        // marking the deposit as payday carries the plan out
        a.markPayday(pay, day, fill = true)
        val b = a.snapshot()
        assertEquals(70_00L, b.envelopeBalance(x))
        assertEquals(30_00L, b.envelopeBalance(y))
        assertEquals(0L, b.envelopeBalance(z))
        assertEquals(0L, b.readyToAssign)
    }

    @Test fun aBudgetThatCantBeOpenedIsReportedNeverReplaced() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val junk = "not a database, invented bytes ".repeat(200).toByteArray()
        File(dir, "budget.db").writeBytes(junk)
        val e = start(dir)
        assertIs<DesktopDatabase.Opened.Unavailable>(e.opened)
        assertEquals(null, e.actions)
        assertEquals("unavailable", e.describe()["db"])
        assertTrue(File(dir, "budget.db").readBytes().contentEquals(junk), "the file is left exactly as it was")
    }

    private fun store(a: BudgetActions) = (engines.last().opened as DesktopDatabase.Opened.Ok).store
}
