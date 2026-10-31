package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/**
 * The twelve reports' numbers (stage 27: phone 1.9.0, program 0.15.0), worked out from the budget's rows alone, the
 * same on the phone and the program. The report screens draw these; nothing here draws. Amounts are in cents.
 *
 * Spending is a purchase's lines in envelopes or still in To sort, less returns, in budget accounts, not washes and not
 * history before the budget start: the money the envelopes paid. Categories counts history too, as its screen does.
 */
object Reports {

    enum class Kind(val title: String, val sub: String) {
        SPENDING_BY_ENVELOPE("Spending by envelope", "Where the month went, per envelope"),
        SPENDING_BY_CATEGORY("Spending by category", "Rolled up across envelopes"),
        INCOME_VS_EXPENSE("Income vs expense", "Month over month"),
        TRENDS("Trends", "A line per envelope over time"),
        MONEY_FLOW("Money flow", "Income into envelopes into spending"),
        BILLS_CALENDAR("Bills calendar", "What is due and when"),
        NET_WORTH("Net worth", "Every account over time"),
        DEBT_PAYDOWN("Debt paydown", "Interest, plan and payoff date"),
        PAYDAY_FORECAST("Payday forecast", "What the next payday covers"),
        SAVINGS_GOALS("Savings goals", "Progress against each target"),
        MERCHANTS("Merchant pages", "Totals, trips and most bought"),
        PRICE_HISTORY("Price history", "What an item cost over time"),
    }

    /**
     * One slice of a total: an envelope, a category or To sort ([id] null), with its share of the total in hundredths
     * of a percent (0 to 10000). No Float or Double in the domain (RulesTest): shares are whole hundredths, rounded.
     */
    data class Share(val id: String?, val name: String, val amount: Long, val share: Long)

    /** [part] of [whole] in hundredths of a percent, rounded; 0 when [whole] isn't above zero. */
    fun hundredths(part: Long, whole: Long): Long = if (whole <= 0) 0 else Math.floorDiv(part * 10000 + whole / 2, whole)

    /** How many budget months the over-time reports show. */
    const val MONTHS = 12

    const val TO_SORT = "To sort"

    private fun shares(raw: List<Triple<String?, String, Long>>): List<Share> {
        val total = raw.filter { it.third > 0 }.sumOf { it.third }
        return raw.filter { it.third != 0L }
            .sortedWith(compareByDescending<Triple<String?, String, Long>> { it.third }.thenBy { it.second })
            .map { (id, name, amt) -> Share(id, name, amt, if (amt > 0) hundredths(amt, total) else 0) }
    }

    /** A purchase's (or a return's) lines in envelopes or To sort that count for the budget in [m]. */
    private fun spendLines(b: Budget, m: Budget.Month): List<Pair<TransactionLineEntity, TransactionEntity>> {
        val tx = b.transactions.associateBy { it.id }
        return b.lines.mapNotNull { l ->
            val t = tx[l.transactionId] ?: return@mapNotNull null
            if (t.kind != TransactionKind.NORMAL || t.isWash || !b.isOnBudget(t.accountId) || !b.countsFor(t.accountId, t.date)) return@mapNotNull null
            if (!m.contains(t.date) || !(t.amount < 0 || t.returnOf != null)) return@mapNotNull null
            if (l.bucket != LineBucket.ENVELOPE && l.bucket != LineBucket.UNSORTED) return@mapNotNull null
            l to t
        }
    }

    /** What was spent in [m], all envelopes and To sort together (returns taken off). */
    fun spentIn(b: Budget, m: Budget.Month): Long = -spendLines(b, m).sumOf { it.first.amount }

    // ---- 1. spending by envelope --------------------------------------------------------------------------------

    /** Spending per envelope in [m], biggest first; To sort (id null) is spending not given an envelope yet. */
    fun spendingByEnvelope(b: Budget, m: Budget.Month): List<Share> {
        val names = b.envelopes.associate { it.id to it.name }
        return shares(spendLines(b, m).groupBy { (l, _) -> if (l.bucket == LineBucket.UNSORTED) null else l.envelopeId }
            .map { (id, ls) -> Triple(id, if (id == null) TO_SORT else names[id] ?: "A deleted envelope", -ls.sumOf { it.first.amount }) })
    }

    // ---- 2. spending by category --------------------------------------------------------------------------------

    /** Spending per category in [m] (the Categories screen's numbers), biggest first; id null is Uncategorized. */
    fun spendingByCategory(b: Budget, m: Budget.Month): List<Share> =
        shares(b.categoryTotals(m).map { (c, amt) -> Triple(c?.id, c?.name ?: Budget.UNCATEGORIZED, amt) })

    // ---- 3. income vs expense -----------------------------------------------------------------------------------

    data class MonthTotals(val month: Budget.Month, val income: Long, val spent: Long) {
        val net: Long get() = income - spent
        /** Of the income, the share not spent, in hundredths of a percent (below 0 when more was spent); null with no income. */
        val savingsRate: Long? get() = if (income > 0) hundredths(net, income) else null
    }

    /** Income and spending per budget month, oldest first, the last [count] months ending with [m]'s. */
    fun incomeVsExpense(b: Budget, today: LocalDate, m: Budget.Month = b.months(today).first(), count: Int = MONTHS): List<MonthTotals> =
        monthsEndingAt(b, today, m, count).map { MonthTotals(it, b.incomeIn(it), spentIn(b, it)) }

    /** The budget months up to and including [m], oldest first, at most [count]. */
    fun monthsEndingAt(b: Budget, today: LocalDate, m: Budget.Month, count: Int = MONTHS): List<Budget.Month> {
        val all = b.months(today)
        val i = all.indexOf(m).let { if (it < 0) 0 else it }
        return all.drop(i).take(count).reversed()
    }

    // ---- 4. trends ----------------------------------------------------------------------------------------------

    data class Trend(val envelope: EnvelopeEntity, val spent: List<Long>)

    /** Each of [envelopeIds]' spending per budget month, the months oldest first (as [monthsEndingAt]). */
    fun trends(b: Budget, months: List<Budget.Month>, envelopeIds: List<String>): List<Trend> =
        envelopeIds.mapNotNull { id -> b.envelopes.firstOrNull { it.id == id } }.map { e ->
            Trend(e, months.map { m -> -spendLines(b, m).filter { (l, _) -> l.bucket == LineBucket.ENVELOPE && l.envelopeId == e.id }.sumOf { it.first.amount } })
        }

    /** The envelopes a trend starts with: the [n] that spent most over [months]. */
    fun topEnvelopes(b: Budget, months: List<Budget.Month>, n: Int = 3): List<String> {
        val spent = HashMap<String, Long>()
        for (m in months) for ((l, _) in spendLines(b, m)) if (l.bucket == LineBucket.ENVELOPE && l.envelopeId != null) spent.merge(l.envelopeId!!, -l.amount, Long::plus)
        return spent.filter { it.value > 0 && b.envelopes.any { e -> e.id == it.key } }.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    // ---- 5. money flow ------------------------------------------------------------------------------------------

    /**
     * Where [m]'s money went, in three columns. Left: the income that came in, and money there from before when more
     * was assigned than came in. Middle: what each envelope was given (Not assigned is income still in Ready to
     * assign). Right: of that, what was spent and what's kept. Each column adds up to the same [total]. [spentBeyond]
     * is spending paid from money envelopes held before this month, which this picture leaves out.
     */
    data class Flow(
        val income: Long,
        val earlier: Long,
        val envelopes: List<FlowEnvelope>,
        val notAssigned: Long,
        val spent: Long,
        val kept: Long,
        val spentBeyond: Long,
    ) {
        val total: Long get() = income + earlier
        val isEmpty: Boolean get() = total <= 0
    }

    data class FlowEnvelope(val id: String?, val name: String, val given: Long, val spent: Long) {
        val kept: Long get() = given - spent
    }

    /** At most this many envelopes are drawn; the rest are one Other. */
    const val FLOW_ENVELOPES = 8

    fun moneyFlow(b: Budget, m: Budget.Month): Flow {
        val income = maxOf(0L, b.incomeIn(m))
        val given = HashMap<String, Long>()
        for (mv in b.moves) {
            if (!m.contains(mv.date)) continue
            if (mv.fromEnvelopeId == null && mv.toEnvelopeId != null) given.merge(mv.toEnvelopeId!!, mv.amount, Long::plus)
            if (mv.toEnvelopeId == null && mv.fromEnvelopeId != null) given.merge(mv.fromEnvelopeId!!, -mv.amount, Long::plus)
        }
        // income put straight into an envelope is given to it too
        val tx = b.transactions.associateBy { it.id }
        for (l in b.lines) {
            val t = tx[l.transactionId] ?: continue
            if (l.bucket == LineBucket.ENVELOPE && l.amount > 0 && l.envelopeId != null && t.kind == TransactionKind.NORMAL && t.amount > 0 &&
                t.returnOf == null && !t.isWash && b.isOnBudget(t.accountId) && b.countsFor(t.accountId, t.date) && m.contains(t.date)
            ) given.merge(l.envelopeId!!, l.amount, Long::plus)
        }
        val spentBy = spendingByEnvelope(b, m).filter { it.id != null }.associate { it.id!! to it.amount }
        val names = b.envelopes.associate { it.id to it.name }
        val all = given.filter { it.value > 0 }.map { (id, g) -> FlowEnvelope(id, names[id] ?: "A deleted envelope", g, minOf(g, maxOf(0L, spentBy[id] ?: 0L))) }
            .sortedWith(compareByDescending<FlowEnvelope> { it.given }.thenBy { it.name })
        val shown = if (all.size <= FLOW_ENVELOPES) all else all.take(FLOW_ENVELOPES - 1) + all.drop(FLOW_ENVELOPES - 1).let { rest ->
            FlowEnvelope(null, "Other", rest.sumOf { it.given }, rest.sumOf { it.spent })
        }
        val assigned = shown.sumOf { it.given }
        val spent = shown.sumOf { it.spent }
        val spentTotal = spentBy.values.filter { it > 0 }.sum()
        return Flow(
            income = income, earlier = maxOf(0L, assigned - income), envelopes = shown, notAssigned = maxOf(0L, income - assigned),
            spent = spent, kept = assigned - spent, spentBeyond = maxOf(0L, spentTotal - spent),
        )
    }

    // ---- 6. bills calendar --------------------------------------------------------------------------------------

    data class Due(val date: LocalDate, val name: String, val amount: Long, val debt: Boolean, val autopay: Boolean, val paid: Boolean)

    /** Every bill and debt payment due in [month], by date then name. */
    fun billsCalendar(b: Budget, month: YearMonth): List<Due> {
        val last = month.atEndOfMonth()
        val out = mutableListOf<Due>()
        for (bill in b.bills.filter { !it.deleted }) {
            for (c in Bills.cycles(bill, b.billPayments, last)) {
                if (YearMonth.from(c.due) == month) out += Due(c.due, bill.name, c.expected, debt = false, autopay = bill.autopay, paid = c.isPaid)
            }
        }
        for (e in b.envelopes.filter { it.mode == EnvelopeMode.DEBT && !it.archived }) {
            for (d in Bills.debtDueDates(e, last)) {
                if (YearMonth.from(d) == month) out += Due(d, e.name, e.requiredPayment ?: 0, debt = true, autopay = false, paid = Bills.debtPaid(b, e, d))
            }
        }
        return out.sortedWith(compareBy<Due> { it.date }.thenBy { it.name })
    }

    // ---- 7. net worth -------------------------------------------------------------------------------------------

    /** Every account's balance (track only and archived accounts too) at the end of a budget month, and their sum. */
    data class Worth(val month: Budget.Month, val date: LocalDate, val balances: Map<String, Long>) {
        val total: Long get() = balances.values.sum()
    }

    /** Net worth at the end of each of [months] (the open month: today). */
    fun netWorth(b: Budget, today: LocalDate, months: List<Budget.Month>): List<Worth> = months.map { m ->
        val date = m.end?.minusDays(1) ?: today
        Worth(m, date, b.accounts.associate { it.id to b.balanceOn(it.id, date) })
    }

    // ---- 8. debt paydown ----------------------------------------------------------------------------------------

    /**
     * A Debt envelope's plan: what's owed now, the payment (what it gets each payday, else its required monthly
     * payment), the yearly interest rate, and from those the payoff date and the interest paid on the way.
     * [whyNot] says what's missing when there's no payoff date.
     */
    data class DebtPlan(
        val envelope: EnvelopeEntity,
        val owed: Long?,
        val payment: Long?,
        val perPayday: Boolean,
        val rateHundredths: Long?,
        val payoff: LocalDate?,
        val interest: Long,
        val payments: Int,
        val balances: List<Pair<LocalDate, Long>>,
        val whyNot: String?,
    )

    /** The highest interest rate taken: 100% a year, in hundredths of a percent. */
    const val MAX_RATE = 10000L

    /** "24.99", "24.99%" or "5" as hundredths of a percent (2499, 500); null when it isn't a rate from 0 to 100%. */
    fun parseRate(text: String): Long? {
        val t = text.trim().removeSuffix("%").trim()
        if (!Regex("""\d{1,3}(\.\d{1,2})?""").matches(t)) return null
        val whole = t.substringBefore('.').toLong()
        val frac = t.substringAfter('.', "").padEnd(2, '0').toLong()
        return (whole * 100 + frac).takeIf { it in 0..MAX_RATE }
    }

    /** 2499 as "24.99%", 500 as "5%". */
    fun rateText(hundredths: Long): String {
        val w = hundredths / 100
        val f = hundredths % 100
        return if (f == 0L) "$w%" else "$w." + f.toString().padStart(2, '0').trimEnd('0') + "%"
    }

    /** No plan runs past this many years. */
    const val DEBT_YEARS = 50L

    fun debtPlans(b: Budget, today: LocalDate): List<DebtPlan> =
        b.envelopes.filter { it.mode == EnvelopeMode.DEBT && !it.archived }.sortedBy { it.sortOrder }.map { debtPlan(b, it, today) }

    fun debtPlan(b: Budget, e: EnvelopeEntity, today: LocalDate): DebtPlan {
        val owed = b.debtRemaining(e)
        val perPayday = e.perPayday != null && e.perPayday!! > 0
        val payment = if (perPayday) e.perPayday else e.requiredPayment?.takeIf { it > 0 }
        val rate = e.interestRate
        fun plan(payoff: LocalDate?, interest: Long, n: Int, balances: List<Pair<LocalDate, Long>>, why: String?) =
            DebtPlan(e, owed, payment, perPayday, rate, payoff, interest, n, balances, why)
        if (owed == null) return plan(null, 0, 0, emptyList(), "Add what's owed on this envelope to see a payoff date.")
        if (owed == 0L) return plan(today, 0, 0, listOf(today to 0L), null)
        val pay: Long = payment ?: return plan(null, 0, 0, listOf(today to owed), "Add an amount per payday or a required payment to see a payoff date.")
        val interval = b.paydayIntervalDays()
        var balance: Long = owed
        var interest = 0L
        var date = today
        var n = 0
        val balances = mutableListOf<Pair<LocalDate, Long>>(today to balance)
        val end = today.plusYears(DEBT_YEARS)
        while (balance > 0) {
            val next = if (perPayday) date.plusDays(interval) else date.plusMonths(1)
            if (next.isAfter(end)) return plan(null, interest, n, balances, "At this payment it isn't paid off within $DEBT_YEARS years.")
            val days = ChronoUnit.DAYS.between(date, next)
            // simple yearly rate, charged by the day: hundredths of a percent, so 10000 hundredths a year is 100%
            val per = 10000L * 365
            val charge = if (rate == null || rate <= 0) 0L else (balance * rate * days + per / 2) / per
            if (charge >= pay) return plan(null, 0, 0, listOf(today to owed), "The payment doesn't cover the interest, so the balance never goes down.")
            interest += charge
            balance = maxOf(0L, balance + charge - pay)
            date = next
            n++
            balances += date to balance
        }
        return plan(date, interest, n, balances, null)
    }

    // ---- 9. payday forecast -------------------------------------------------------------------------------------

    /** What the next payday has to cover, from the Budget screen's own forecast ([Forecast.build]). */
    data class PaydayCover(val payday: LocalDate, val start: Long, val bills: Long, val spending: Long, val end: Long, val lowest: Long, val lowestOn: LocalDate?)

    fun paydayCover(f: Forecast.Result): PaydayCover {
        val days = f.plan.days
        val lowest = f.plan.lowest
        return PaydayCover(
            f.payday, f.plan.start, days.sumOf { d -> d.fixed.sumOf { it.amount } }, days.sumOf { it.spending },
            f.plan.end, lowest?.balance ?: f.plan.start, lowest?.date,
        )
    }

    // ---- 10. savings goals --------------------------------------------------------------------------------------

    data class Goal(val envelope: EnvelopeEntity, val saved: Long, val target: Long?, val by: LocalDate?, val perPayday: Long) {
        /** Hundredths of a percent, 0 to 10000; null without a target. */
        val percent: Long? get() = target?.takeIf { it > 0 }?.let { hundredths(saved.coerceAtLeast(0), it).coerceAtMost(10000) }
        val left: Long get() = maxOf(0L, (target ?: 0L) - saved)
        val reached: Boolean get() = target != null && saved >= target
    }

    /** Each Goal envelope (not archived), in fill order. */
    fun savingsGoals(b: Budget, today: LocalDate): List<Goal> =
        b.envelopes.filter { it.mode == EnvelopeMode.GOAL && !it.archived }.sortedBy { it.sortOrder }.map { e ->
            Goal(e, b.envelopeBalance(e.id), e.goalAmount, e.goalDate, b.goalPerPayday(e, today))
        }

    // ---- 11. merchant pages -------------------------------------------------------------------------------------

    /** An item bought: a scanned receipt's line (1.9.1: only those; a transaction's memo is never an item). */
    data class Bought(val key: String, val name: String, val times: Int, val quantityMilli: Long, val spent: Long)

    data class Merchant(
        val key: String,
        val name: String,
        /** Spent less returns. */
        val total: Long,
        val trips: Int,
        val last: LocalDate?,
        val items: List<Bought>,
        val byCategory: List<Share>,
    ) {
        val average: Long get() = if (trips > 0) total / trips else 0
    }

    /** A purchase or a return: NORMAL, not a wash, in any account (a merchant's history isn't only the budget's). */
    private fun shopping(t: TransactionEntity) = t.kind == TransactionKind.NORMAL && !t.isWash && (t.amount < 0 || t.returnOf != null)

    /** Item names, written the same way: "Milk 2%" and " milk  2% " are one item. */
    fun itemKey(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun itemName(i: ReceiptItemEntity) = i.name.ifBlank { i.text }.trim()

    /** Every payee spent at in [m] (null: all time), biggest total first. */
    fun merchants(b: Budget, receipts: List<ReceiptEntity>, items: List<ReceiptItemEntity>, m: Budget.Month?): List<Merchant> {
        val txs = b.transactions.filter { shopping(it) && (m == null || m.contains(it.date)) }
        val byTx = receipts.filter { !it.deleted && it.transactionId != null }.groupBy { it.transactionId!! }
        val itemsOf = items.filter { !it.deleted }.groupBy { it.receiptId }
        val lines = b.lines.groupBy { it.transactionId }
        return txs.groupBy { Payees.key(it) }.filterKeys { it.isNotBlank() }.map { (key, ts) ->
            val purchases = ts.filter { it.amount < 0 }
            val name = ts.maxByOrNull { it.date }?.payee?.ifBlank { null } ?: key
            val bought = HashMap<String, Bought>()
            for (t in purchases) {
                val its = byTx[t.id].orEmpty().flatMap { r -> itemsOf[r.id].orEmpty() }.filter { itemName(it).isNotBlank() && it.amount > 0 }
                for (i in its) {
                    val k = itemKey(itemName(i))
                    bought.merge(k, Bought(k, itemName(i), 1, i.quantityMilli, i.amount)) { a, n -> a.copy(times = a.times + 1, quantityMilli = a.quantityMilli + n.quantityMilli, spent = a.spent + n.spent) }
                }
            }
            val cats = ts.flatMap { t -> lines[t.id].orEmpty().filter { it.bucket == LineBucket.ENVELOPE || it.bucket == LineBucket.UNSORTED } }
                .groupBy { it.categoryId }.map { (c, ls) -> Triple(c, b.categoryName(c), -ls.sumOf { it.amount }) }
            Merchant(
                key, name, -ts.sumOf { it.amount }, purchases.size, ts.maxOfOrNull { it.date },
                bought.values.sortedWith(compareByDescending<Bought> { it.times }.thenByDescending { it.spent }.thenBy { it.name }),
                shares(cats),
            )
        }.sortedWith(compareByDescending<Merchant> { it.total }.thenBy { it.name })
    }

    // ---- 12. price history --------------------------------------------------------------------------------------

    /** One time an item was bought: the price of one (a receipt's unit price, else its amount over the quantity). */
    data class Price(val date: LocalDate, val store: String, val unitPrice: Long, val quantityMilli: Long)

    /** An item that can be looked up, with how many times it was bought. */
    data class Item(val key: String, val name: String, val times: Int)

    private class Seen(val key: String, val name: String, val price: Price)

    private fun seen(b: Budget, receipts: List<ReceiptEntity>, items: List<ReceiptItemEntity>): List<Seen> {
        val tx = b.transactions.associateBy { it.id }
        val live = receipts.filter { !it.deleted && it.kind == net.darkrelay.deaddropfinance.data.ReceiptKind.PURCHASE }.associateBy { it.id }
        val out = mutableListOf<Seen>()
        for (i in items) {
            if (i.deleted || i.amount <= 0) continue
            val r = live[i.receiptId] ?: continue
            val name = itemName(i)
            if (name.isBlank()) continue
            val t = r.transactionId?.let { tx[it] }
            val date = r.date ?: t?.date ?: continue
            val qty = if (i.quantityMilli > 0) i.quantityMilli else 1000
            val unit = i.unitPrice ?: ((i.amount * 1000 + qty / 2) / qty)
            out += Seen(itemKey(name), name, Price(date, r.store.ifBlank { t?.payee.orEmpty() }, unit, qty))
        }
        return out
    }

    /** Every item bought more than once comes first; then by how often, then by name. */
    fun priceItems(b: Budget, receipts: List<ReceiptEntity>, items: List<ReceiptItemEntity>): List<Item> =
        seen(b, receipts, items).groupBy { it.key }.map { (k, s) -> Item(k, s.maxByOrNull { it.price.date }!!.name, s.size) }
            .sortedWith(compareByDescending<Item> { it.times }.thenBy { it.name })

    /** What [key] cost each time it was bought, oldest first. */
    fun priceHistory(b: Budget, receipts: List<ReceiptEntity>, items: List<ReceiptItemEntity>, key: String): List<Price> =
        seen(b, receipts, items).filter { it.key == key }.map { it.price }.sortedWith(compareBy<Price> { it.date }.thenBy { it.store })
}
