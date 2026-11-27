package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity

/**
 * The Payday button (phone 1.9.9, program 0.15.12): the account's real balance after payday, from the bank (posted,
 * without pending), against what the app shows. Above it: the paycheck isn't in the app yet, so a deposit for the
 * difference is added (dated today, payee "Payday") and marked as payday. Equal: the deposit is in already, and the
 * newest money in on that account since the last payday is marked. Below: the app is higher than the bank; nothing
 * goes ahead. Clean up, when ticked, first brings every envelope's Available to zero through Unplanned (Unplanned and
 * Rollover untouched). Then the payday runs as Mark as payday always has, Fill envelopes on. Pure, but [BudgetActions.payday].
 */
object PaydayButton {

    /** The account the last payday went into, else the budget's first account. */
    fun defaultAccount(b: Budget): String? =
        b.transactions.filter { it.isPayday }.maxByOrNull { it.date }?.accountId?.takeIf { id -> b.accounts.any { it.id == id && !it.archived } }
            ?: b.accounts.firstOrNull { !it.archived }?.id

    /** What the app shows for [accountId], as the bank does: posted, without hand-entered pending transactions. */
    fun appBalance(b: Budget, accountId: String): Long =
        b.accountBalance(accountId) - b.transactions.filter { it.accountId == accountId && it.pending && it.kind == TransactionKind.NORMAL }.sumOf { it.amount }

    /** The newest money in on [accountId] since the last payday, not a payday itself: the deposit to mark. */
    fun existingDeposit(b: Budget, accountId: String): TransactionEntity? {
        val last = b.paydayDates.lastOrNull()
        return b.transactions.filter {
            it.accountId == accountId && it.kind == TransactionKind.NORMAL && it.amount > 0 && !it.isPayday && !it.isWash && it.returnOf == null &&
                !it.pending && (last == null || it.date.isAfter(last)) && b.countsFor(it.accountId, it.date)
        }.maxWithOrNull(compareBy<TransactionEntity> { it.date }.thenBy { it.createdAt })
    }

    data class Check(
        val app: Long, val bank: Long, val difference: Long,
        /** Equal: the deposit that will be marked (null: there's none, and [problem] says so). */
        val existing: TransactionEntity?,
        /** Why it can't go ahead, or null. */
        val problem: String?,
    ) {
        val adds: Long? get() = difference.takeIf { it > 0 }
    }

    fun check(b: Budget, accountId: String, bank: Long): Check {
        val app = appBalance(b, accountId)
        val diff = bank - app
        return when {
            diff < 0 -> Check(app, bank, diff, null, "The app is ${Money.format(-diff)} higher than the bank. Import or add the missing transactions first.")
            diff == 0L -> existingDeposit(b, accountId).let { t ->
                Check(app, bank, diff, t, if (t == null) "The balances match, but there's no money in on this account since the last payday to mark as the payday deposit." else null)
            }
            else -> Check(app, bank, diff, null, null)
        }
    }

    /** Clean up: each move that brings an envelope to zero, and what Unplanned couldn't cover. */
    data class Cleanup(val moves: List<Payday.PlannedMove>, val uncovered: Long)

    /**
     * Every envelope's Available to $0.00 (1.9.9): positive amounts back to Unplanned first, then overspent ones covered
     * from Unplanned while it has money (in fill order). Unplanned and Rollover themselves are never touched.
     */
    fun cleanup(b: Budget): Cleanup {
        val unplanned = b.unplannedEnvelope ?: return Cleanup(emptyList(), 0)
        val rollover = b.rollover
        val envs = b.fillOrder.filter { it.id != unplanned.id && it.id != rollover?.id }
        val moves = mutableListOf<Payday.PlannedMove>()
        var pot = b.envelopeBalance(unplanned.id)
        for (e in envs) {
            val bal = b.envelopeBalance(e.id)
            if (bal > 0) { moves += Payday.PlannedMove(e.id, unplanned.id, bal); pot += bal }
        }
        var uncovered = 0L
        for (e in envs) {
            val bal = b.envelopeBalance(e.id)
            if (bal >= 0) continue
            val give = minOf(-bal, maxOf(0, pot))
            if (give > 0) { moves += Payday.PlannedMove(unplanned.id, e.id, give); pot -= give }
            uncovered += -bal - give
        }
        return Cleanup(moves, uncovered)
    }

    /** What the confirm lists: the deposit (added, or marked), the clean up moves, and the fill. */
    data class Preview(val check: Check, val cleanup: Cleanup?, val fill: Payday.Plan, val depositDate: LocalDate)

    /**
     * Works the payday out without saving anything: the budget as it will be with the deposit (a new one, or the one
     * marked) in Ready to Assign and the clean up moves made, then the payday plan Mark as payday would make on it.
     */
    fun preview(b: Budget, accountId: String, bank: Long, cleanUp: Boolean, today: LocalDate): Preview? {
        val c = check(b, accountId, bank)
        if (c.problem != null) return null
        val dep = c.existing ?: TransactionEntity(id = "payday-preview", accountId = accountId, date = today, payee = "Payday", amount = c.difference, createdAt = 0, updatedAt = 0)
        val depLine = TransactionLineEntity(transactionId = dep.id, bucket = LineBucket.READY_TO_ASSIGN, amount = dep.amount, createdAt = 0, updatedAt = 0)
        val cl = if (cleanUp) cleanup(b) else null
        val cleanMoves = cl?.moves.orEmpty().map { MoveEntity(fromEnvelopeId = it.fromEnvelopeId, toEnvelopeId = it.toEnvelopeId, amount = it.amount, date = today, kind = MoveKind.MOVE, createdAt = 0, updatedAt = 0) }
        val txs = b.transactions.filter { it.id != dep.id } + dep.copy(isPayday = true)
        val lines = b.lines.filter { it.transactionId != dep.id } + depLine
        val sim = Budget(b.accounts, b.envelopes, txs, lines, b.moves + cleanMoves, b.bills, b.billPayments, b.categories, b.budgetStart, b.billMonths, b.paySchedule)
        val closes = b.paydayDates.any { it.isBefore(dep.date) } || b.transactions.count { it.isPayday } >= 1
        return Preview(c, cl, Payday.plan(sim, today, closes), dep.date)
    }
}
