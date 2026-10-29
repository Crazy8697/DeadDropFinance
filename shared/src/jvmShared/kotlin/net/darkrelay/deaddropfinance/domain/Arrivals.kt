package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ReceiptFiles

/**
 * What new transactions from a CSV import start: parked buys' charges,
 * auto-approve, bill payments and waiting receipts. Payday is never started here: it is marked by hand only (1.3.1).
 */
class Arrivals(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val files: ReceiptFiles? = null,
) {
    /**
     * Everything that runs on new transactions from a CSV import, in order: parked buys' charges, auto-approve (when
     * it's on), bill payments, and receipts waiting for their charge. Each step stands alone, so one failing never
     * stops the rest.
     */
    suspend fun landed(newIds: Collection<String>, autoApprove: Boolean) {
        if (newIds.isEmpty()) return
        runCatching { ParkedBuys(store, actions, today, clock).matchCharges(newIds) }
        if (autoApprove) runCatching { Sorting(store, actions).autoApprove(newIds) }
        runCatching { Sorting(store, actions).labelHistory(newIds) }
        runCatching { BillActions(store, actions, today).matchPayments(newIds) }
        runCatching { Receipts(store, actions, files).matchWaiting() }
    }
}
