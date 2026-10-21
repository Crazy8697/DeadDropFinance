package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Bills as cycles (FEATURES.md, Bills). A bill's first due date and how often it repeats give each due date; each due
 * date is a cycle, and payments count toward one cycle. A cycle is paid only when its payments reach the amount due,
 * so a bill paid in parts, by different methods, stays open with the rest owed until they add up. Pure.
 */
object Bills {

    data class Cycle(val bill: BillEntity, val due: LocalDate, val expected: Long, val payments: List<BillPaymentEntity>) {
        val paid: Long get() = payments.sumOf { it.amount }
        val remaining: Long get() = maxOf(0L, expected - paid)
        val isPaid: Boolean get() = if (expected > 0) paid >= expected else payments.isNotEmpty()
        val partial: Boolean get() = paid > 0 && !isPaid
        fun overdue(today: LocalDate) = !isPaid && due.isBefore(today)
    }

    /** Every due date from the first through [through] (a one-off bill has just the one). */
    fun dueDates(b: BillEntity, through: LocalDate): List<LocalDate> {
        val first = b.dueDate ?: return emptyList()
        val m = b.recurrence.months ?: return listOf(first)
        val out = mutableListOf<LocalDate>()
        var k = 0L
        while (out.size < 2000) {
            val d = first.plusMonths(k * m)
            if (d.isAfter(through)) break
            out += d
            k++
        }
        return out
    }

    private fun live(payments: List<BillPaymentEntity>, b: BillEntity) = payments.filter { it.billId == b.id && !it.deleted }

    /** Which cycle a payment counts toward: its own [BillPaymentEntity.periodDue], or the nearest due date. */
    fun periodOf(p: BillPaymentEntity, b: BillEntity): LocalDate? = p.periodDue ?: dueDates(b, p.date.plusDays(60)).minByOrNull { abs(ChronoUnit.DAYS.between(it, p.date)) }

    /**
     * What a cycle is expected to cost. A bill whose amount varies (utilities) expects what the latest earlier bank
     * charge was, and a cycle the bank already paid expects what it charged. Otherwise the amount due.
     */
    fun expectedFor(b: BillEntity, due: LocalDate, payments: List<BillPaymentEntity>): Long {
        if (!b.varies) return b.amountDue
        val bank = live(payments, b).filter { it.method == PaymentMethod.BANK }.groupBy { periodOf(it, b) }
        bank[due]?.let { own -> return own.sumOf { it.amount } }
        val earlier = bank.keys.filterNotNull().filter { it.isBefore(due) }.maxOrNull() ?: return b.amountDue
        return bank[earlier]!!.sumOf { it.amount }
    }

    fun cycles(b: BillEntity, payments: List<BillPaymentEntity>, through: LocalDate): List<Cycle> {
        val mine = live(payments, b)
        val byDue = mine.groupBy { periodOf(it, b) }
        return dueDates(b, through).map { d -> Cycle(b, d, expectedFor(b, d, mine), byDue[d].orEmpty()) }
    }

    /**
     * The cycle the next payment counts toward: the earliest one not paid yet, looking up to one interval past
     * [today]. Null for a one-off bill that's paid.
     */
    fun openCycle(b: BillEntity, payments: List<BillPaymentEntity>, today: LocalDate): Cycle? {
        val m = b.recurrence.months?.toLong() ?: 0L
        return cycles(b, payments, today.plusMonths(m + 1)).firstOrNull { !it.isPaid }
    }

    /** Unpaid cycles due by [until] (overdue ones included), earliest first: the Coming up list. */
    fun comingUp(bills: List<BillEntity>, payments: List<BillPaymentEntity>, until: LocalDate): List<Cycle> =
        bills.filter { !it.deleted }.flatMap { b -> cycles(b, payments, until).filter { !it.isPaid } }.sortedBy { it.due }

    /**
     * The payday checklist: after the latest payday ([lastPayday]), the bills due before the next one, paid or not
     * (ticked ones stay until all are paid), plus anything overdue from before. Empty before the first payday.
     */
    fun checklist(bills: List<BillEntity>, payments: List<BillPaymentEntity>, lastPayday: LocalDate?, nextPayday: LocalDate): List<Cycle> {
        if (lastPayday == null) return emptyList()
        return bills.filter { !it.deleted }.flatMap { b ->
            cycles(b, payments, nextPayday.minusDays(1)).filter { c -> !c.due.isBefore(lastPayday) || !c.isPaid }
        }.sortedBy { it.due }
    }

    data class AutopayCheck(val cycle: Cycle, val envelope: EnvelopeEntity?, val envelopeHas: Long, val needed: Long) {
        val short: Long get() = maxOf(0L, needed - envelopeHas)
    }

    /**
     * Autopays due before the next payday, and whether their envelope has enough for them (all of that envelope's
     * autopays due by then together). [short] above zero is the warning.
     */
    fun autopayChecks(b: Budget, payments: List<BillPaymentEntity>, today: LocalDate, nextPayday: LocalDate): List<AutopayCheck> {
        val due = b.bills.filter { it.autopay && !it.deleted }.flatMap { bill ->
            cycles(bill, payments, nextPayday.minusDays(1)).filter { !it.isPaid && !it.due.isBefore(today.minusDays(3)) }
        }
        val byEnvelope = due.groupBy { it.bill.envelopeId }
        return due.sortedBy { it.due }.map { c ->
            val e = b.envelopes.firstOrNull { it.id == c.bill.envelopeId }
            val has = e?.let { b.envelopeBalance(it.id) } ?: 0L
            AutopayCheck(c, e, has, byEnvelope[c.bill.envelopeId].orEmpty().sumOf { it.remaining })
        }
    }

    /**
     * Whether a bank transaction is a payment toward [b]: a rule linked to the bill matches it, or the bill's payee
     * words are in it and the amount fits (any amount if the bill says so; a part payment is fine).
     */
    fun matches(b: BillEntity, t: TransactionEntity, rules: List<RuleEntity>, payments: List<BillPaymentEntity>, today: LocalDate): Boolean {
        if (t.amount >= 0 || t.kind != TransactionKind.NORMAL || t.isWash || b.deleted) return false
        if (rules.any { it.billId == b.id && !it.deleted && Rules.matches(it, t) }) return true
        val words = b.matchText?.takeIf { it.isNotBlank() } ?: return false
        if (Payees.forms(t).none { Payees.contains(it, words) }) return false
        if (b.anyAmount || b.varies) return true
        val exp = openCycle(b, payments, today)?.expected ?: b.amountDue
        return -t.amount <= exp + exp / 5
    }

    /** Debt envelopes with a required payment and a due date: their monthly due dates, like a bill's. */
    fun debtDueDates(e: EnvelopeEntity, through: LocalDate): List<LocalDate> {
        if (e.mode != EnvelopeMode.DEBT || e.requiredPayment == null) return emptyList()
        val first = e.dueDate ?: return emptyList()
        val out = mutableListOf<LocalDate>()
        var k = 0L
        while (out.size < 2000) {
            val d = first.plusMonths(k)
            if (d.isAfter(through)) break
            out += d; k++
        }
        return out
    }

    /** Whether a debt envelope's payment due on [due] was made: payments out of it in the month before reach it. */
    fun debtPaid(b: Budget, e: EnvelopeEntity, due: LocalDate): Boolean {
        val req = e.requiredPayment ?: return true
        val from = due.minusMonths(1)
        val paid = -b.lines.filter { it.bucket == LineBucket.ENVELOPE && it.envelopeId == e.id && it.amount < 0 }
            .filter { l -> b.transactions.firstOrNull { it.id == l.transactionId }?.date?.let { it.isAfter(from) && !it.isAfter(due.plusDays(3)) } == true }
            .sumOf { it.amount }
        return paid >= req
    }

    /** The next unpaid debt payment due (from [today] back a month for an overdue one). */
    fun nextDebtDue(b: Budget, e: EnvelopeEntity, today: LocalDate): LocalDate? =
        debtDueDates(e, today.plusMonths(2)).firstOrNull { !it.isBefore(today.minusMonths(1)) && !debtPaid(b, e, it) }

    /** Months between due dates, in words. */
    fun every(b: BillEntity): String = when (b.recurrence.months) {
        1 -> "monthly"; 3 -> "every 3 months"; 6 -> "every 6 months"; 12 -> "yearly"; else -> "one-off"
    }
}
