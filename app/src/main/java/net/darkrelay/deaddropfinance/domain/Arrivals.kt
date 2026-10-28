package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ReceiptFiles

/**
 * What new transactions start, however they came in (CSV import, a bank notification). The payday deposit
 * starts payday by itself (FEATURES.md, Payday): Rollover sweep, auto-fill, and the checklist. No tap.
 */
class Arrivals(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val files: ReceiptFiles? = null,
) {
    /**
     * Runs payday for each of [txIds] that looks like the payday deposit (Paydays.looksLikePayday, the Stage 10
     * rules), oldest first. Once per deposit: a deposit already marked, or one within [SAME_PAYDAY_DAYS] of a payday
     * already run (the notification came first, then sync or CSV brought it in again), is left alone. Returns the
     * deposits it ran payday for.
     */
    suspend fun paydays(txIds: Collection<String>): List<String> {
        val ids = txIds.toSet()
        if (ids.isEmpty()) return emptyList()
        val bills = BillActions(store, actions, today)
        val deposits = actions.snapshot().transactions.filter { it.id in ids && it.amount > 0 }.sortedBy { it.date }
        val ran = mutableListOf<String>()
        for (d in deposits) {
            val b = actions.snapshot()
            val t = b.transactions.firstOrNull { it.id == d.id } ?: continue
            if (!b.isOnBudget(t.accountId) || b.beforeStart(t)) continue
            if (b.paydayDates.any { abs(ChronoUnit.DAYS.between(it, t.date)) < SAME_PAYDAY_DAYS }) continue
            val expected = Paydays.predict(b.paydayDates, today(), bills.paydayCorrection()).next
            if (!Paydays.looksLikePayday(t, b, expected)) continue
            actions.markPayday(t.id, today())
            ran += t.id
        }
        return ran
    }

    /**
     * Everything that runs on new transactions from a CSV import, in order: payday, parked buys'
     * charges, auto-approve (when it's on), bill payments, and receipts waiting for their charge. Each step stands
     * alone, so one failing never stops the rest. Returns the deposits payday ran for.
     */
    suspend fun landed(newIds: Collection<String>, autoApprove: Boolean): List<String> {
        if (newIds.isEmpty()) return emptyList()
        val ran = runCatching { paydays(newIds) }.getOrDefault(emptyList())
        runCatching { ParkedBuys(store, actions, today, clock).matchCharges(newIds) }
        if (autoApprove) runCatching { Sorting(store, actions).autoApprove(newIds) }
        runCatching { Sorting(store, actions).labelHistory(newIds) }
        runCatching { BillActions(store, actions, today).matchPayments(newIds) }
        runCatching { Receipts(store, actions, files).matchWaiting() }
        return ran
    }

    companion object {
        /** Two paydays this close together are the same deposit seen twice. */
        const val SAME_PAYDAY_DAYS = 5L
    }
}
