package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/**
 * All of the budget's math, from the rows alone (tombstones already filtered out). No screen does arithmetic on
 * money; it asks this. Everything is in cents, so nothing is ever rounded.
 *
 * The money always balances: the sum of the account balances equals Ready to Assign plus every envelope's balance
 * plus whatever is still in To sort (sortedCheck).
 */
class Budget(
    val accounts: List<AccountEntity>,
    val envelopes: List<EnvelopeEntity>,
    val transactions: List<TransactionEntity>,
    val lines: List<TransactionLineEntity>,
    val moves: List<MoveEntity>,
    val bills: List<BillEntity> = emptyList(),
    val billPayments: List<BillPaymentEntity> = emptyList(),
) {
    private val txById = transactions.associateBy { it.id }

    /** Lines that count for the budget: of live transactions, not washes. */
    private val budgetLines = lines.filter { l -> txById[l.transactionId]?.let { !it.isWash } == true }

    val accountBalances: Map<String, Long> = accounts.associate { a ->
        var b = a.startingBalance
        for (t in transactions) {
            when {
                t.kind == TransactionKind.TRANSFER && t.accountId == a.id -> b -= t.amount
                t.kind == TransactionKind.TRANSFER && t.transferAccountId == a.id -> b += t.amount
                t.kind == TransactionKind.NORMAL && t.accountId == a.id -> b += t.amount
            }
        }
        a.id to b
    }

    val envelopeBalances: Map<String, Long> = envelopes.associate { e ->
        var b = 0L
        for (l in budgetLines) if (l.bucket == LineBucket.ENVELOPE && l.envelopeId == e.id) b += l.amount
        for (m in moves) {
            if (m.toEnvelopeId == e.id) b += m.amount
            if (m.fromEnvelopeId == e.id) b -= m.amount
        }
        e.id to b
    }

    /** Money not in any envelope yet: starting balances and income lines, minus what was assigned out of it. */
    val readyToAssign: Long = run {
        var r = accounts.sumOf { it.startingBalance }
        for (l in budgetLines) if (l.bucket == LineBucket.READY_TO_ASSIGN) r += l.amount
        for (m in moves) {
            if (m.fromEnvelopeId == null) r -= m.amount
            if (m.toEnvelopeId == null) r += m.amount
        }
        r
    }

    /** Signed total of lines nobody has sorted yet (To sort): spending shows negative, income positive. */
    val toSortTotal: Long = budgetLines.filter { it.bucket == LineBucket.UNSORTED }.sumOf { it.amount }

    /** Transactions with at least one unsorted line, newest first. */
    val toSort: List<TransactionEntity> = run {
        val ids = budgetLines.filter { it.bucket == LineBucket.UNSORTED }.map { it.transactionId }.toSet()
        transactions.filter { it.id in ids }.sortedWith(compareByDescending<TransactionEntity> { it.date }.thenByDescending { it.createdAt })
    }

    /** sum(accounts) - (RTA + envelopes + To sort): always 0 (washes net to zero in pairs). */
    val sortedCheck: Long
        get() = accountBalances.values.sum() - (readyToAssign + envelopeBalances.values.sum() + toSortTotal) -
            transactions.filter { it.isWash && it.kind == TransactionKind.NORMAL }.sumOf { it.amount }

    fun envelopeBalance(id: String) = envelopeBalances[id] ?: 0L
    fun accountBalance(id: String) = accountBalances[id] ?: 0L

    val rollover: EnvelopeEntity? get() = envelopes.firstOrNull { it.mode == EnvelopeMode.ROLLOVER }
    val cash: EnvelopeEntity? get() = envelopes.firstOrNull { it.mode == EnvelopeMode.CASH && !it.archived }

    /** Envelopes in fill order (Rollover included, archived left out). */
    val fillOrder: List<EnvelopeEntity> get() = envelopes.filter { !it.archived }.sortedBy { it.sortOrder }

    // ---- budget months: payday to payday ---------------------------------------------------------------------

    /** The dates of the deposits marked as payday, oldest first. */
    val paydayDates: List<LocalDate> get() = transactions.filter { it.isPayday }.map { it.date }.distinct().sorted()

    /** Average days between paydays (the last six gaps); 14 when there aren't two paydays yet. */
    fun paydayIntervalDays(): Long {
        val d = paydayDates.takeLast(7)
        if (d.size < 2) return 14
        val gaps = d.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.filter { it > 0 }
        return if (gaps.isEmpty()) 14 else maxOf(1, gaps.sum() / gaps.size)
    }

    /** The closed budget months: [start, end) between consecutive paydays. */
    fun closedMonths(): List<Pair<LocalDate, LocalDate>> = paydayDates.zipWithNext()

    /** Money spent from an envelope in each closed budget month (positive cents). */
    fun spentPerClosedMonth(envelopeId: String): List<Long> = closedMonths().map { (from, to) ->
        -budgetLines.filter { l ->
            l.bucket == LineBucket.ENVELOPE && l.envelopeId == envelopeId && l.amount < 0 &&
                txById[l.transactionId]!!.date.let { !it.isBefore(from) && it.isBefore(to) }
        }.sumOf { it.amount }
    }

    /**
     * For an envelope with no target: the real average spent per budget month, offered as its target once there
     * are at least two closed budget months of history. Null when there isn't enough history.
     */
    fun suggestedTarget(e: EnvelopeEntity): Long? {
        if (e.target != null || e.mode != EnvelopeMode.FILL_TO_TARGET) return null
        val spent = spentPerClosedMonth(e.id)
        if (spent.size < 2) return null
        return spent.sum() / spent.size
    }

    /** Debt envelope: what's still owed, after the payments made from it since [EnvelopeEntity.debtOwedSince]. */
    fun debtRemaining(e: EnvelopeEntity): Long? {
        val owed = e.debtOwed ?: return null
        val since = e.debtOwedSince ?: LocalDate.MIN
        val paid = -budgetLines.filter { l ->
            l.bucket == LineBucket.ENVELOPE && l.envelopeId == e.id && l.amount < 0 && !txById[l.transactionId]!!.date.isBefore(since)
        }.sumOf { it.amount }
        return maxOf(0, owed - paid)
    }

    /** Seasonal: the 12-month average of its linked bills per payday, or null without a year of bill history. */
    fun seasonalPerPayday(e: EnvelopeEntity, today: LocalDate): Long? {
        val billIds = bills.filter { it.envelopeId == e.id }.map { it.id }.toSet()
        if (billIds.isEmpty()) return null
        val since = today.minusDays(365)
        val paid = billPayments.filter { it.billId in billIds && it.date.isAfter(since) }
        if (paid.isEmpty() || paid.minOf { it.date }.isAfter(today.minusDays(330))) return null
        val paydaysPerYear = maxOf(1, 365 / paydayIntervalDays())
        return paid.sumOf { it.amount } / paydaysPerYear
    }

    /** Goal: the amount to add per payday to reach the goal by its date. */
    fun goalPerPayday(e: EnvelopeEntity, today: LocalDate): Long {
        val goal = e.goalAmount ?: return 0
        val remaining = goal - envelopeBalance(e.id)
        if (remaining <= 0) return 0
        val date = e.goalDate ?: return remaining
        val days = ChronoUnit.DAYS.between(today, date)
        val paydaysLeft = if (days <= 0) 1 else days / paydayIntervalDays() + 1
        return ceilDiv(remaining, paydaysLeft)
    }

    /** Whether the envelope's screen offers "Cover from Rollover": it's negative and Ready to Assign can't cover it. */
    fun canCoverFromRollover(e: EnvelopeEntity): Long? {
        if (e.mode == EnvelopeMode.ROLLOVER) return null
        val bal = envelopeBalance(e.id)
        val roll = rollover?.let { envelopeBalance(it.id) } ?: 0
        if (bal >= 0 || readyToAssign >= -bal || roll <= 0) return null
        return minOf(-bal, roll)
    }

    companion object {
        fun ceilDiv(a: Long, b: Long): Long = if (b <= 0) a else (a + b - 1) / b
    }
}
