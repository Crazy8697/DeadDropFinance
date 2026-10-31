package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The twelve reports' numbers (stage 27: phone 1.9.0, program 0.15.0) on synthetic budgets: a new, empty one, and one
 * with two paydays, so the budget month edges (payday to the day before the next) are tested. Invented data only.
 */
class ReportsTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Paydays on the 1st and the 15th of March 2030; today is the 20th. */
    private val p1 = LocalDate.of(2030, 3, 1)
    private val p2 = LocalDate.of(2030, 3, 15)
    private val today = LocalDate.of(2030, 3, 20)

    private class Made(val a: BudgetActions, val store: BudgetStore, val ids: Map<String, String>)

    private fun open(): Pair<BudgetActions, BudgetStore> = runBlocking {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        o.store.ensureSystemEnvelopes(); o.store.ensureCategories()
        BudgetActions(o.store) to o.store
    }

    private fun filled(): Made = runBlocking {
        val (a, store) = open()
        val ids = mutableMapOf<String, String>()
        ids["chk"] = a.saveAccount(null, "Sample Checking", AccountType.BANK, 100_00, p1.minusDays(10))
        ids["card"] = a.saveAccount(null, "Sample Card", AccountType.PAYMENT_APP, -50_00, p1.minusDays(10), trackOnly = true)
        ids["food"] = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
        ids["fun"] = a.saveEnvelope(null, EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET, target = 50_00))
        ids["trip"] = a.saveEnvelope(null, EnvelopeDraft("Trip", EnvelopeMode.GOAL, goalAmount = 1000_00, goalDate = today.plusMonths(10)), today)
        ids["loan"] = a.saveEnvelope(null, EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00, requiredPayment = 150_00, dueDate = LocalDate.of(2030, 3, 31)), today)
        val groceries = store.dao.categoriesNow().first { it.name == "Groceries" }.id
        // two paydays: each starts a budget month
        val (pay1, _) = a.savePayday(TransactionDraft(ids["chk"]!!, p1, "Sample Payroll", "", 1000_00, listOf(LineDraft(null, 1000_00, toReadyToAssign = true))), fill = false, today = p1)
        a.savePayday(TransactionDraft(ids["chk"]!!, p2, "Sample Payroll", "", 800_00, listOf(LineDraft(null, 800_00, toReadyToAssign = true))), fill = false, today = p2)
        ids["pay1"] = pay1
        a.move(null, ids["food"], 300_00, date = p1)
        a.move(null, ids["fun"], 50_00, date = p1)
        a.move(null, ids["trip"], 250_00, date = p1)
        a.move(null, ids["food"], 200_00, date = p2)
        // the first month: a split purchase, the last day of the month (the day before payday), and a return
        ids["shop1"] = a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p1.plusDays(2), "Sample Market", "", -60_00,
            listOf(LineDraft(ids["food"], -45_00, categoryId = groceries), LineDraft(ids["fun"], -15_00, categoryId = groceries))))
        a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p2.minusDays(1), "SAMPLE MARKET", "Milk", -4_00, listOf(LineDraft(ids["food"], -4_00, categoryId = groceries))))
        // the second month: on payday itself (it belongs to the new month), and one still in To sort
        ids["shop2"] = a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p2, "Sample Market", "", -30_00, listOf(LineDraft(ids["food"], -30_00, categoryId = groceries))))
        a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p2.plusDays(2), "Sample Cafe", "", -8_00, listOf(LineDraft(null, -8_00))))
        // a payment to the loan, and spending on the track only card (counts nowhere in the budget)
        a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p2.plusDays(1), "Sample Lender", "", -100_00, listOf(LineDraft(ids["loan"], -100_00))))
        a.saveTransaction(null, TransactionDraft(ids["card"]!!, p2.plusDays(1), "Sample Market", "", -20_00, listOf(LineDraft(null, -20_00))))
        Made(a, store, ids)
    }

    private fun Made.b() = runBlocking { a.snapshot() }
    private fun Budget.month(i: Int) = months(today)[i]

    @Test fun anEmptyBudgetHasEmptyReports() = runBlocking {
        val (a, _) = open()
        val b = a.snapshot()
        val m = b.months(today).single()
        assertEquals(emptyList(), Reports.spendingByEnvelope(b, m))
        assertEquals(emptyList(), Reports.spendingByCategory(b, m))
        val ie = Reports.incomeVsExpense(b, today)
        assertEquals(1, ie.size)
        assertEquals(0L, ie.single().income); assertEquals(0L, ie.single().spent); assertNull(ie.single().savingsRate)
        assertEquals(emptyList(), Reports.topEnvelopes(b, listOf(m)))
        assertTrue(Reports.moneyFlow(b, m).isEmpty)
        assertEquals(emptyList(), Reports.billsCalendar(b, YearMonth.from(today)))
        assertEquals(0L, Reports.netWorth(b, today, listOf(m)).single().total)
        assertEquals(emptyList(), Reports.debtPlans(b, today))
        assertEquals(emptyList(), Reports.savingsGoals(b, today))
        assertEquals(emptyList(), Reports.merchants(b, emptyList(), emptyList(), m))
        assertEquals(emptyList(), Reports.priceItems(b, emptyList(), emptyList()))
    }

    @Test fun spendingByEnvelopeIsPerBudgetMonthWithItsEdges() {
        val made = filled(); val b = made.b(); val ids = made.ids
        val now = b.month(0)
        val before = b.month(1)
        assertEquals(p2, now.start); assertEquals(p1, before.start); assertEquals(p2, before.end)
        // the first month: the split (45 + 15) and the day before payday (4), not the payday's own purchase
        val first = Reports.spendingByEnvelope(b, before)
        assertEquals(listOf("Food" to 49_00L, "Fun" to 15_00L), first.map { it.name to it.amount })
        assertEquals(10000L, first.sumOf { it.share })
        assertEquals(7656L, first[0].share, "49 of 64, in hundredths of a percent")
        // this month: payday's purchase, the loan payment, To sort; never the track only card
        val second = Reports.spendingByEnvelope(b, now).associate { it.name to it.amount }
        assertEquals(mapOf("Sample Loan" to 100_00L, "Food" to 30_00L, Reports.TO_SORT to 8_00L), second)
        assertEquals(null, Reports.spendingByEnvelope(b, now).first { it.name == Reports.TO_SORT }.id)
        assertEquals(ids["food"], Reports.spendingByEnvelope(b, now).first { it.name == "Food" }.id)
        assertEquals(138_00L, Reports.spentIn(b, now))
    }

    @Test fun aReturnTakesItsAmountBack() = runBlocking {
        val made = filled(); val ids = made.ids
        val credit = made.a.saveTransaction(null, TransactionDraft(ids["chk"]!!, p2.plusDays(3), "Sample Market", "", 10_00, listOf(LineDraft(null, 10_00))))
        Sorting(made.store, made.a).confirmReturn(credit, ids["shop2"]!!)
        val b = made.b()
        assertEquals(20_00L, Reports.spendingByEnvelope(b, b.month(0)).first { it.name == "Food" }.amount)
    }

    @Test fun spendingByCategoryIsTheCategoriesScreensNumbers() {
        val b = filled().b()
        val m = b.month(1)
        val r = Reports.spendingByCategory(b, m)
        assertEquals(b.categoryTotals(m).map { it.second }, r.map { it.amount })
        assertEquals("Groceries", r.first().name)
        assertEquals(64_00L, r.first().amount)
        assertEquals(10000L, r.sumOf { it.share })
    }

    @Test fun incomeVsExpenseIsMonthOverMonthOldestFirst() {
        val b = filled().b()
        val r = Reports.incomeVsExpense(b, today)
        assertEquals(listOf(p1, p2), r.map { it.month.start })
        assertEquals(1000_00L, r[0].income); assertEquals(64_00L, r[0].spent); assertEquals(936_00L, r[0].net)
        assertEquals(800_00L, r[1].income); assertEquals(138_00L, r[1].spent)
        assertEquals(9360L, r[0].savingsRate)
        // ending at the older month: only it
        assertEquals(listOf(p1), Reports.incomeVsExpense(b, today, b.month(1)).map { it.month.start })
    }

    @Test fun trendsAreALinePerEnvelopeOverTheMonths() {
        val made = filled(); val b = made.b(); val ids = made.ids
        val months = Reports.monthsEndingAt(b, today, b.month(0))
        val t = Reports.trends(b, months, listOf(ids["food"]!!, ids["fun"]!!))
        assertEquals(listOf(49_00L, 30_00L), t[0].spent)
        assertEquals(listOf(15_00L, 0L), t[1].spent)
        assertEquals(listOf(ids["loan"], ids["food"], ids["fun"]), Reports.topEnvelopes(b, months))
        assertEquals(listOf(ids["loan"]), Reports.topEnvelopes(b, months, 1))
    }

    @Test fun moneyFlowAddsUpInEveryColumn() {
        val made = filled(); val b = made.b()
        // the first month: 1000 in, 600 given (food 300, trip 250, fun 50), 64 spent of it
        val f = Reports.moneyFlow(b, b.month(1))
        assertEquals(1000_00L, f.income); assertEquals(0L, f.earlier)
        assertEquals(listOf("Food" to 300_00L, "Trip" to 250_00L, "Fun" to 50_00L), f.envelopes.map { it.name to it.given })
        assertEquals(400_00L, f.notAssigned)
        assertEquals(64_00L, f.spent)
        assertEquals(536_00L, f.kept)
        assertEquals(f.total, f.envelopes.sumOf { it.given } + f.notAssigned)
        assertEquals(f.total, f.spent + f.kept + f.notAssigned)
        // this month: 800 in, 200 given to Food, 30 of it spent; the loan payment came from older money (To sort's
        // spending isn't an envelope's)
        val g = Reports.moneyFlow(b, b.month(0))
        assertEquals(200_00L, g.envelopes.single().given)
        assertEquals(30_00L, g.spent)
        assertEquals(100_00L, g.spentBeyond)
        assertEquals(g.total, g.spent + g.kept + g.notAssigned)
    }

    @Test fun moneyFlowCountsEarlierMoneyWhenMoreIsGivenThanCameIn() = runBlocking {
        val made = filled(); val ids = made.ids
        made.a.move(null, ids["fun"], 700_00, date = p2.plusDays(1))
        val f = Reports.moneyFlow(made.b(), made.b().month(0))
        assertEquals(800_00L, f.income)
        assertEquals(100_00L, f.earlier)
        assertEquals(0L, f.notAssigned)
        assertEquals(f.total, f.envelopes.sumOf { it.given })
    }

    @Test fun billsCalendarShowsBillsAndDebtPaymentsInTheirMonth() = runBlocking {
        val made = filled()
        val bills = BillActions(made.store, made.a) { today }
        bills.saveBill(null, BillActions.BillDraft("Sample Rent", 900_00, LocalDate.of(2030, 1, 31), autopay = true))
        bills.saveBill(null, BillActions.BillDraft("Sample Phone", 45_00, LocalDate.of(2030, 3, 1)))
        val b = made.b()
        val march = Reports.billsCalendar(b, YearMonth.of(2030, 3))
        assertEquals(listOf(LocalDate.of(2030, 3, 1) to "Sample Phone", LocalDate.of(2030, 3, 31) to "Sample Loan", LocalDate.of(2030, 3, 31) to "Sample Rent"), march.map { it.date to it.name })
        assertTrue(march.first { it.name == "Sample Loan" }.debt)
        assertEquals(150_00L, march.first { it.name == "Sample Loan" }.amount)
        assertTrue(march.first { it.name == "Sample Rent" }.autopay)
        // a month-end bill in a short month falls on its last day
        val feb = Reports.billsCalendar(b, YearMonth.of(2030, 2))
        assertEquals(LocalDate.of(2030, 2, 28), feb.first { it.name == "Sample Rent" }.date)
        assertNull(feb.firstOrNull { it.name == "Sample Phone" })
    }

    @Test fun netWorthCountsEveryAccountAtEachMonthsEnd() {
        val made = filled(); val b = made.b(); val ids = made.ids
        val months = Reports.monthsEndingAt(b, today, b.month(0))
        val w = Reports.netWorth(b, today, months)
        // the first month ends the day before the second payday: 100 + 1000 - 60 - 4 in checking; the card -50
        assertEquals(p2.minusDays(1), w[0].date)
        assertEquals(1036_00L, w[0].balances[ids["chk"]])
        assertEquals(-50_00L, w[0].balances[ids["card"]])
        assertEquals(986_00L, w[0].total)
        // today: + 800 - 30 - 8 - 100 in checking, the track only card's -20 too
        assertEquals(today, w[1].date)
        assertEquals(1698_00L, w[1].balances[ids["chk"]])
        assertEquals(-70_00L, w[1].balances[ids["card"]])
    }

    @Test fun debtPaydownWorksOutThePayoffAndTheInterest() = runBlocking {
        val made = filled(); val ids = made.ids
        var b = made.b()
        val loan = b.envelopes.first { it.id == ids["loan"] }
        // 1200 owed as of today (the payment before it is already in that figure) at 100 a payday, 14 days apart, no
        // rate given: 12 payments, no interest
        val plain = Reports.debtPlan(b, loan, today)
        assertEquals(1200_00L, plain.owed); assertEquals(100_00L, plain.payment); assertTrue(plain.perPayday)
        assertEquals(12, plain.payments); assertEquals(0L, plain.interest)
        assertEquals(today.plusDays(14L * 12), plain.payoff)
        assertEquals(0L, plain.balances.last().second)
        // at 24.99% a year it takes longer and costs interest
        made.a.saveEnvelope(ids["loan"], EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00, interestRate = 2499), today)
        b = made.b()
        val withRate = Reports.debtPlan(b, b.envelopes.first { it.id == ids["loan"] }, today)
        assertEquals(2499L, withRate.rateHundredths)
        // charged by the day on what's left: 13 payments, 80.97 of interest
        assertEquals(13, withRate.payments)
        assertEquals(80_97L, withRate.interest)
        assertTrue(withRate.payoff!!.isAfter(plain.payoff))
        // a payment that doesn't cover the interest never pays it off
        made.a.saveEnvelope(ids["loan"], EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, perPayday = 1_00, debtOwed = 10000_00, interestRate = 2000), today)
        b = made.b()
        val never = Reports.debtPlan(b, b.envelopes.first { it.id == ids["loan"] }, today)
        assertNull(never.payoff); assertNotNull(never.whyNot)
        // nothing owed entered: no plan, and it says what to add
        made.a.saveEnvelope(ids["loan"], EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, perPayday = 100_00), today)
        b = made.b()
        val unknown = Reports.debtPlan(b, b.envelopes.first { it.id == ids["loan"] }, today)
        assertNull(unknown.payoff); assertTrue(unknown.whyNot!!.contains("owed"))
        // a required monthly payment alone is paid monthly
        made.a.saveEnvelope(ids["loan"], EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, debtOwed = 450_00, requiredPayment = 150_00), today)
        b = made.b()
        val monthly = Reports.debtPlan(b, b.envelopes.first { it.id == ids["loan"] }, today)
        assertTrue(!monthly.perPayday)
        assertEquals(today.plusMonths(3), monthly.payoff)
    }

    @Test fun anInterestRateIsTypedAsAPercentAndKeptInHundredths() {
        assertEquals(2499L, Reports.parseRate("24.99"))
        assertEquals(2499L, Reports.parseRate(" 24.99% "))
        assertEquals(500L, Reports.parseRate("5"))
        assertEquals(550L, Reports.parseRate("5.5"))
        assertEquals(0L, Reports.parseRate("0"))
        assertEquals(10000L, Reports.parseRate("100"))
        assertNull(Reports.parseRate("101"))
        assertNull(Reports.parseRate("abc"))
        assertNull(Reports.parseRate("5.555"))
        assertEquals("24.99%", Reports.rateText(2499))
        assertEquals(3333L, Reports.hundredths(1, 3))
        assertEquals(6667L, Reports.hundredths(2, 3))
        assertEquals(0L, Reports.hundredths(5, 0))
        assertEquals("5%", Reports.rateText(500))
        assertEquals("5.5%", Reports.rateText(550))
        assertEquals("0.05%", Reports.rateText(5))
    }

    @Test fun anInterestRateOutsideZeroToAHundredIsRefused() = runBlocking {
        val made = filled()
        val e = runCatching { made.a.saveEnvelope(made.ids["loan"], EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, interestRate = -1)) }.exceptionOrNull()
        assertIs<BudgetRuleException>(e)
        // and only a Debt envelope keeps one
        made.a.saveEnvelope(made.ids["fun"], EnvelopeDraft("Fun", EnvelopeMode.FILL_TO_TARGET, target = 50_00, interestRate = 500))
        assertNull(made.b().envelopes.first { it.id == made.ids["fun"] }.interestRate)
    }

    @Test fun paydayForecastIsTheBudgetScreensForecast() = runBlocking {
        val made = filled()
        BillActions(made.store, made.a) { today }.saveBill(null, BillActions.BillDraft("Sample Phone", 45_00, LocalDate.of(2030, 3, 25)))
        val b = made.b()
        val next = LocalDate.of(2030, 3, 29)
        val f = Forecast.build(b, emptyList(), today, next)
        val c = Reports.paydayCover(f)
        assertEquals(next, c.payday)
        assertEquals(f.plan.start, c.start)
        assertEquals(f.plan.end, c.end)
        assertTrue(c.bills >= 45_00L, "the phone bill is in it")
        assertEquals(f.plan.days.sumOf { d -> d.fixed.sumOf { it.amount } }, c.bills)
        assertEquals(f.plan.lowest!!.balance, c.lowest)
    }

    @Test fun savingsGoalsShowProgressAgainstEachTarget() {
        val made = filled(); val b = made.b()
        val g = Reports.savingsGoals(b, today).single()
        assertEquals("Trip", g.envelope.name)
        assertEquals(250_00L, g.saved); assertEquals(1000_00L, g.target)
        assertEquals(2500L, g.percent)
        assertEquals(750_00L, g.left)
        assertTrue(!g.reached)
        assertEquals(b.goalPerPayday(g.envelope, today), g.perPayday)
    }

    @Test fun merchantPagesGroupAPayeesTripsAndWhatWasBought() = runBlocking {
        val made = filled(); val ids = made.ids
        // a receipt for the first trip, with two items
        val r = ReceiptEntity(id = "r1", transactionId = ids["shop1"], store = "Sample Market", date = p1.plusDays(2), total = 60_00, createdAt = 1, updatedAt = 1)
        val items = listOf(
            ReceiptItemEntity(id = "i1", receiptId = "r1", name = "Milk", quantityMilli = 2000, unitPrice = 3_50, amount = 7_00, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i2", receiptId = "r1", name = "Bread", amount = 53_00, createdAt = 1, updatedAt = 1),
        )
        val b = made.b()
        val all = Reports.merchants(b, listOf(r), items, null)
        val market = all.first { it.name.equals("Sample Market", true) }
        // three trips (Sample Market and SAMPLE MARKET are one payee): 60 + 4 + 30, and the track only card's 20
        assertEquals(4, market.trips)
        assertEquals(114_00L, market.total)
        assertEquals(28_50L, market.average)
        // Milk: from the receipt, and the memo on the trip without one
        val milk = market.items.first { it.key == "milk" }
        assertEquals(2, milk.times)
        assertEquals(3000L, milk.quantityMilli)
        assertEquals(11_00L, milk.spent)
        assertEquals(listOf("milk", "bread"), market.items.map { it.key })
        assertEquals("Groceries", market.byCategory.first().name)
        // one budget month: only its trips
        val thisMonth = Reports.merchants(b, listOf(r), items, b.month(0)).first { it.key == market.key }
        assertEquals(2, thisMonth.trips)
        assertEquals(50_00L, thisMonth.total)
        // biggest first
        assertEquals(all.sortedByDescending { it.total }.map { it.key }, all.map { it.key })
    }

    @Test fun priceHistoryIsWhatAnItemCostEachTime() = runBlocking {
        val made = filled(); val ids = made.ids
        val receipts = listOf(
            ReceiptEntity(id = "r1", transactionId = ids["shop1"], store = "Sample Market", date = p1.plusDays(2), createdAt = 1, updatedAt = 1),
            ReceiptEntity(id = "r2", transactionId = ids["shop2"], store = "Sample Market", date = p2, createdAt = 1, updatedAt = 1),
            // a return's receipt isn't a price
            ReceiptEntity(id = "r3", store = "Sample Market", date = p2, kind = ReceiptKind.RETURN, createdAt = 1, updatedAt = 1),
        )
        val items = listOf(
            ReceiptItemEntity(id = "i1", receiptId = "r1", name = "Milk 2%", quantityMilli = 2000, amount = 7_00, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i2", receiptId = "r2", name = " milk  2% ", unitPrice = 3_75, amount = 3_75, createdAt = 1, updatedAt = 1),
            ReceiptItemEntity(id = "i3", receiptId = "r3", name = "Milk 2%", amount = 3_75, createdAt = 1, updatedAt = 1),
        )
        val b = made.b()
        val h = Reports.priceHistory(b, receipts, items, Reports.itemKey("Milk 2%"))
        assertEquals(listOf(p1.plusDays(2) to 3_50L, p2 to 3_75L), h.map { it.date to it.unitPrice })
        // the memo on a purchase with no receipt is an item too
        val memo = Reports.priceHistory(b, receipts, items, "milk")
        assertEquals(listOf(p2.minusDays(1) to 4_00L), memo.map { it.date to it.unitPrice })
        val list = Reports.priceItems(b, receipts, items)
        assertEquals("milk 2%", list.first().key)
        assertEquals(2, list.first().times)
        assertTrue(list.any { it.key == "milk" })
    }
}
