package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * The balance forecast (FEATURES.md, Balance forecast): the budget accounts' balance for each day from today to the
 * day before the next payday. Unpaid bills on their due dates (autopays marked), subscriptions on their next charge
 * dates, debt payments, and everyday spending. Two lines: Plan spreads the everyday envelopes' money evenly over the
 * days; Usual pace spends the way the user did in recent budget months, day by day since payday. Only budget accounts
 * count, and history before an account's starting balance never does. Pure; cents only.
 */
object Forecast {

    enum class Kind { BILL, AUTOPAY, SUBSCRIPTION, DEBT, SPENDING }

    data class Out(val label: String, val amount: Long, val kind: Kind)

    data class Day(val date: LocalDate, val outs: List<Out>, val balance: Long) {
        val fixed get() = outs.filter { it.kind != Kind.SPENDING }
        val spending get() = outs.filter { it.kind == Kind.SPENDING }.sumOf { it.amount }
    }

    data class Line(val start: Long, val days: List<Day>) {
        val firstNegative: Day? get() = days.firstOrNull { it.balance < 0 }
        val lowest: Day? get() = days.minByOrNull { it.balance }
        val end: Long get() = days.lastOrNull()?.balance ?: start
    }

    data class Result(
        val payday: LocalDate,
        val plan: Line,
        /** Null when there isn't enough history; [usualWhyNot] says why. */
        val usual: Line?,
        val usualWhyNot: String?,
        /** No envelope is marked everyday spending, so Plan has no spending in it. */
        val noEverydayEnvelopes: Boolean,
    )

    /** Budget months (payday to payday) of history Usual pace needs, and how many it looks at. */
    const val MIN_MONTHS = 2
    const val LOOKBACK_MONTHS = 3

    fun build(b: Budget, series: List<Recurring.Series>, today: LocalDate, payday: LocalDate): Result {
        val n = maxOf(1L, ChronoUnit.DAYS.between(today, payday)).toInt()
        val dates = (0 until n).map { today.plusDays(it.toLong()) }
        val start = b.accounts.filter { it.onBudget }.sumOf { b.accountBalance(it.id) }
        val last = dates.last()
        val fixed = HashMap<LocalDate, MutableList<Out>>()
        fun add(d: LocalDate, o: Out) { fixed.getOrPut(if (d.isBefore(today)) today else d) { mutableListOf() } += o }

        for (bill in b.bills.filter { !it.deleted }) {
            for (c in Bills.cycles(bill, b.billPayments, last).filter { !it.isPaid && it.remaining > 0 }) {
                add(c.due, Out(bill.name + if (bill.autopay) " (autopay)" else "", c.remaining, if (bill.autopay) Kind.AUTOPAY else Kind.BILL))
            }
        }
        val billWords = b.bills.filter { !it.deleted }.mapNotNull { it.matchText?.takeIf { w -> w.isNotBlank() } }
        for (s in series.filter { !it.dismissed && it.subscription?.billId == null }) {
            // a subscription that's also a bill is counted as the bill
            if (s.keys.any { k -> billWords.any { Payees.contains(k, it) } }) continue
            val next = s.nextDate
            if (next.isAfter(last) || next.isBefore(today.minusDays(7))) continue
            add(next, Out(s.name, s.amount, Kind.SUBSCRIPTION))
        }
        for (e in b.envelopes.filter { it.mode == EnvelopeMode.DEBT && !it.archived }) {
            val due = Bills.nextDebtDue(b, e, today) ?: continue
            if (due.isAfter(last)) continue
            add(due, Out(e.name, e.requiredPayment ?: continue, Kind.DEBT))
        }

        val everyday = b.everydayEnvelopes
        val planMoney = everyday.sumOf { maxOf(0L, b.envelopeBalance(it.id)) }
        val planDays = Rules.allocate(planMoney, List(n) { 1L })
        val plan = line(start, dates, fixed, planDays)

        val usualDays = usualPace(b, series, today, n)
        val usual = usualDays?.let { line(start, dates, fixed, it) }
        val why = when {
            usualDays != null -> null
            b.paydayDates.isEmpty() -> "Usual pace needs paydays marked: it follows how you spend from one payday to the next."
            else -> "Usual pace needs $MIN_MONTHS full budget months (payday to payday) of your own spending. It shows once there's enough history."
        }
        return Result(payday, plan, usual, why, everyday.isEmpty())
    }

    private fun line(start: Long, dates: List<LocalDate>, fixed: Map<LocalDate, List<Out>>, spend: List<Long>): Line {
        var bal = start
        val days = dates.mapIndexed { i, d ->
            val outs = fixed[d].orEmpty() + if (spend[i] > 0) listOf(Out("Everyday spending", spend[i], Kind.SPENDING)) else emptyList()
            bal -= outs.sumOf { it.amount }
            Day(d, outs, bal)
        }
        return Line(start, days)
    }

    /**
     * Spending per day for the next [n] days, as the user spent on the same day after payday in the last budget
     * months (bills, subscriptions, transfers and washes left out: those are counted on their own). Null without
     * [MIN_MONTHS] closed budget months.
     */
    fun usualPace(b: Budget, series: List<Recurring.Series>, today: LocalDate, n: Int): List<Long>? {
        val months = b.closedMonths().takeLast(LOOKBACK_MONTHS)
        if (months.size < MIN_MONTHS) return null
        val lastPayday = b.paydayDates.lastOrNull() ?: return null
        val billTx = b.billPayments.mapNotNull { it.transactionId }.toSet()
        val subTx = series.flatMap { s -> s.charges.map { it.id } }.toSet()
        val transferTx = b.lines.filter { it.bucket == LineBucket.TRANSFER }.map { it.transactionId }.toSet()
        val spends = b.transactions.filter {
            it.kind == TransactionKind.NORMAL && it.amount < 0 && !it.isWash && b.isOnBudget(it.accountId) && !b.beforeStart(it) &&
                it.id !in billTx && it.id !in subTx && it.id !in transferTx
        }
        val perMonth = months.map { (from, to) ->
            val len = ChronoUnit.DAYS.between(from, to).toInt()
            val byDay = LongArray(maxOf(1, len))
            for (t in spends) if (!t.date.isBefore(from) && t.date.isBefore(to)) byDay[ChronoUnit.DAYS.between(from, t.date).toInt()] += -t.amount
            byDay
        }
        val dailyAll = perMonth.sumOf { it.sum() } / maxOf(1, perMonth.sumOf { it.size })
        val offset0 = ChronoUnit.DAYS.between(lastPayday, today).toInt()
        return (0 until n).map { i ->
            val o = offset0 + i
            val have = perMonth.filter { o in it.indices }
            if (have.isEmpty()) dailyAll else have.sumOf { it[o] } / have.size
        }
    }
}
