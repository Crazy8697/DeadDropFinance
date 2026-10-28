package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.BillActions.BillDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Bills against a real (in-memory) database: payments, partial payments, checklist, autopay, reminders, fill. Invented data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BillsTest {

    private val d = LocalDate.of(2030, 4, 1)
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private class W(val store: BudgetStore, val a: BudgetActions, val bills: BillActions) {
        lateinit var chk: String
        lateinit var phone: String
        lateinit var home: String
        suspend fun bank(desc: String, amount: Long, date: LocalDate, account: String = chk): String = store.write {
            val t = put(null, TransactionEntity(accountId = account, date = date, payee = desc, amount = amount, importDescription = desc, source = TransactionSource.SYNC, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
            t.id
        }
        suspend fun b() = a.snapshot()
        suspend fun cycle(id: String, due: LocalDate) = Bills.cycles(store.dao.bill(id)!!, store.dao.billPaymentsNow(), due).last { it.due == due }
    }

    private fun world(today: LocalDate = d, block: suspend W.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        var tick = 1_000L
        try {
            val store = BudgetStore(db) { ++tick }
            val actions = BudgetActions(store)
            val w = W(store, actions, BillActions(store, actions) { today })
            runBlocking {
                store.ensureSystemEnvelopes()
                w.chk = actions.saveAccount(null, "Checking", AccountType.BANK, 2000_00, d.minusDays(60))
                w.phone = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Phone", EnvelopeMode.FILL_TO_TARGET, target = 60_00))
                w.home = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Home", EnvelopeMode.FILL_TO_TARGET))
                w.block()
            }
        } finally {
            db.close()
        }
    }

    @Test fun oneBillPaidBySeveralPaymentsByDifferentMethods() = world {
        val rent = bills.saveBill(null, BillDraft("Sample Rent", 1000_00, d.plusDays(5), envelopeId = home, matchText = "SAMPLE LANDLORD", anyAmount = true))
        val t1 = bank("TRANSFER TO SAMPLE LANDLORD", -600_00, d.plusDays(1))
        assertEquals(1, bills.matchPayments(listOf(t1)))
        var c = cycle(rent, d.plusDays(5))
        assertTrue(c.partial)
        assertEquals(400_00L, c.remaining)
        assertFalse(c.isPaid)
        val t2 = bank("SAMPLE PAY APP TRANSFER", -150_00, d.plusDays(2))
        assertEquals("a payee the bill doesn't know isn't counted", 0, bills.matchPayments(listOf(t2)))
        val t3 = bank("TRANSFER TO SAMPLE LANDLORD", -250_00, d.plusDays(2))
        bills.matchPayments(listOf(t3, t3))
        bills.markPaid(rent, d.plusDays(5))                                      // the last 150 by hand
        c = cycle(rent, d.plusDays(5))
        assertTrue(c.isPaid)
        assertEquals(listOf(PaymentMethod.BANK, PaymentMethod.BANK, PaymentMethod.HAND), c.payments.sortedBy { it.createdAt }.map { it.method })
        assertEquals("the next month is open", d.plusDays(5).plusMonths(1), Bills.openCycle(store.dao.bill(rent)!!, store.dao.billPaymentsNow(), d)!!.due)
    }

    @Test fun aPartPaymentLeavesTheRestAndOneTapMovesItToDebt() = world {
        val electric = bills.saveBill(null, BillDraft("Sample Power", 200_00, d.plusDays(3), envelopeId = home, matchText = "sample power co"))
        bills.matchPayments(listOf(bank("SAMPLE POWER CO WEB PMT", -120_00, d.plusDays(3))))
        assertEquals(80_00L, cycle(electric, d.plusDays(3)).remaining)
        val debt = bills.moveRestToDebt(electric, d.plusDays(3))
        val e = store.dao.envelope(debt)!!
        assertEquals("Sample Power catch-up", e.name)
        assertEquals(EnvelopeMode.DEBT, e.mode)
        assertEquals(80_00L, e.debtOwed)
        assertTrue("settled", cycle(electric, d.plusDays(3)).isPaid)
        // next month's rest goes to the same catch-up envelope
        bills.matchPayments(listOf(bank("SAMPLE POWER CO WEB PMT", -150_00, d.plusDays(3).plusMonths(1))))
        bills.moveRestToDebt(electric, d.plusDays(3).plusMonths(1))
        assertEquals(1, store.dao.envelopesNow().count { it.mode == EnvelopeMode.DEBT })
        assertEquals(130_00L, store.dao.envelope(debt)!!.debtOwed)
        assertEquals("a rest moved to debt isn't what the bill costs", 0, b().billPayments.count { it.method == PaymentMethod.DEBT && it.amount == 0L })
    }

    @Test fun severalBillsFeedOneEnvelopeAndThePaydayChecklistTicksAndCollapses() = world(d.plusDays(1)) {
        val lines = listOf("Sample Line One" to 3L, "Sample Line Two" to 6L, "Sample Line Three" to 9L).map { (n, day) ->
            bills.saveBill(null, BillDraft(n, 20_00, d.plusDays(day), envelopeId = phone, matchText = n))
        }
        // the payday deposit lands: sweep, fill, then the checklist
        val pay = bank("SAMPLE EMPLOYER PAYROLL", 1500_00, d)
        a.markPayday(pay, d)
        val b0 = b()
        val pred = Paydays.predict(b0.paydayDates, d.plusDays(1))
        assertTrue(pred.guessed)
        assertEquals(d.plusDays(14), pred.next)
        val list = Bills.checklist(b0.bills, b0.billPayments, b0.paydayDates.last(), pred.next!!)
        assertEquals(3, list.size)
        assertTrue(list.none { it.isPaid })
        bills.markPaid(lines[0], d.plusDays(3))
        bills.markPaid(lines[1], d.plusDays(6), cashAccountId = a.saveAccount(null, "Wallet", AccountType.CASH, 50_00, d))
        // the third one is paid at the bank and sync ticks it
        bills.matchPayments(listOf(bank("SAMPLE LINE THREE", -20_00, d.plusDays(8))))
        val b1 = b()
        val after = Bills.checklist(b1.bills, b1.billPayments, b1.paydayDates.last(), pred.next!!)
        assertTrue("all paid: the card collapses", after.all { it.isPaid })
        assertEquals("cash came out of the Phone envelope", 1, b1.lines.count { it.envelopeId == phone && it.amount == -20_00L })
        // unticking a hand tick
        bills.unmarkPaid(lines[0], d.plusDays(3))
        assertFalse(cycle(lines[0], d.plusDays(3)).isPaid)
    }

    @Test fun aYearlyBillFillsALittleEachPayday() = world {
        val reg = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Registration", EnvelopeMode.FILL_TO_TARGET))
        bills.saveBill(null, BillDraft("Sample Registration", 260_00, d.plusDays(130), BillRecurrence.YEARLY, envelopeId = reg))
        val b = b()
        val e = b.envelopes.first { it.id == reg }
        // 130 days at 14 days a payday: 10 paydays
        assertEquals(26_00L, Payday.amountFor(e, 0, b, d))
        assertEquals((260_00L - 52_00) / 10, Payday.amountFor(e, 52_00, b, d))
        // an irregular one-off does the same, and an envelope with a target keeps its target
        assertEquals(60_00L, Payday.amountFor(b.envelopes.first { it.id == phone }, 0, b, d))
    }

    @Test fun csvMarksBillsPaidAndAHandTickTakesTheBankRecord() = world {
        val stream = bills.saveBill(null, BillDraft("Sample Stream", 12_99, d.plusDays(2), envelopeId = home, matchText = "sample stream"))
        // CSV import
        val csv = "Date,Description,Amount\n${d.plusDays(2)},SAMPLE STREAM SVC,-12.99\n"
        val plan = CsvImport.plan(CsvImport.read(csv, CsvImport.detect(csv)), chk, b().transactions)
        val res = a.importCsv(chk, plan)
        assertEquals(1, bills.matchPayments(res.newIds))
        assertTrue(cycle(stream, d.plusDays(2)).isPaid)
        // next month: ticked by hand first, then the bank's record arrives in the next CSV
        bills.markPaid(stream, d.plusDays(2).plusMonths(1))
        val csv2 = "Date,Description,Amount\n${d.plusDays(2).plusMonths(1)},SAMPLE STREAM SVC,-12.99\n"
        val out = a.importCsv(chk, CsvImport.plan(CsvImport.read(csv2, CsvImport.detect(csv2)), chk, b().transactions))
        bills.matchPayments(out.newIds)
        val c = cycle(stream, d.plusDays(2).plusMonths(1))
        assertEquals("the hand tick took the bank record: no double payment", 1, c.payments.size)
        assertEquals(PaymentMethod.BANK, c.payments.single().method)
        assertEquals(out.newIds.single(), c.payments.single().transactionId)
        // and the To sort suggestion for a bill payment is the bill's envelope
        val t = b().transactions.first { it.id == out.newIds.single() }
        assertEquals(Suggest.Basis.BILL, Suggest.forTx(b(), store.dao.rulesNow(), t)!!.basis)
    }

    @Test fun anAutopayDueBeforePaydayWithAShortEnvelopeWarns() = world {
        val ins = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Insurance", EnvelopeMode.FILL_TO_TARGET, target = 90_00))
        bills.saveBill(null, BillDraft("Sample Insurance", 90_00, d.plusDays(3), envelopeId = ins, autopay = true))
        a.assign(ins, 50_00)
        val w = Reminders.autopayWarnings(b(), d, d.plusDays(10)).single()
        assertEquals(40_00L, w.short)
        assertEquals(50_00L, w.envelopeHas)
        a.assign(ins, 40_00)
        assertTrue(Reminders.autopayWarnings(b(), d, d.plusDays(10)).isEmpty())
        assertTrue("due after payday: no warning", Reminders.autopayWarnings(b(), d, d.plusDays(2)).isEmpty())
    }

    @Test fun remindersAreOffByDefaultAndTurnedOnPerBill() = world {
        val quiet = bills.saveBill(null, BillDraft("Sample Water", 30_00, d.plusDays(2), envelopeId = home))
        assertFalse(store.dao.bill(quiet)!!.reminder)
        assertTrue(Reminders.due(b(), d).isEmpty())
        val loud = bills.saveBill(null, BillDraft("Sample Gas Co", 45_00, d.plusDays(4), envelopeId = home, reminder = true, reminderDays = 3))
        assertTrue("too early", Reminders.due(b(), d).isEmpty())
        val r = Reminders.due(b(), d.plusDays(1)).single()
        assertEquals(loud, r.id)
        assertEquals(45_00L, r.amount)
        bills.markPaid(loud, d.plusDays(4))
        assertTrue("paid: no reminder", Reminders.due(b(), d.plusDays(2)).isEmpty())
        // a Debt envelope with a required payment and due date reminds the same way
        val debt = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, requiredPayment = 75_00, dueDate = d.plusDays(5)))
        assertTrue(Reminders.due(b(), d.plusDays(4)).none { it.id == debt })
        store.write { val e = store.dao.envelope(debt)!!; put(e, e.copy(reminder = true, updatedAt = now)) }
        assertEquals(Reminders.Kind.DEBT, Reminders.due(b(), d.plusDays(4)).single { it.id == debt }.kind)
        assertNull(Reminders.due(b(), d.plusDays(6)).firstOrNull { it.id == debt && it.due == d.plusDays(5) })
    }
}
