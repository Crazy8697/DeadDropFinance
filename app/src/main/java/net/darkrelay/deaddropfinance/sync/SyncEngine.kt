package net.darkrelay.deaddropfinance.sync

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.Money

/**
 * Puts one provider account's transactions into its linked app account. Same for every provider. All writes go
 * through BudgetStore, so each one gets its change log row in the same database transaction.
 *
 * Matching, in order:
 *  1. by the provider's transaction id (rows synced before);
 *  2. against rows entered by hand or imported from CSV, with the same count-aware key as CSV import (account, date,
 *     cents, normalized description): each held row matches at most one synced row;
 *  3. against a pending row entered by hand: same amount within 5 days (as CSV import);
 *  4. a synced pending row that is gone from the provider's list is paired with a new posted row of the same amount
 *     within 7 days (a provider can re-issue the id when a row posts), so the user's envelope stays;
 *  5. anything left is new and lands in To sort. Synced pending rows still unmatched are removed, and noted.
 */
class SyncEngine(private val store: BudgetStore) {

    /** [newIds]: the transactions this sync added, for auto-approve and spend alerts. */
    data class Outcome(
        val added: Int = 0, val linked: Int = 0, val updated: Int = 0, val removed: Int = 0, val notes: List<String> = emptyList(),
        val newIds: List<String> = emptyList(),
    ) {
        operator fun plus(o: Outcome) = Outcome(added + o.added, linked + o.linked, updated + o.updated, removed + o.removed, notes + o.notes, newIds + o.newIds)
    }

    /**
     * [windowStart] to [windowEnd] is the range [data] covers: only synced pending rows dated inside it can have
     * vanished. [offBudget] (track-only or archived account): new rows are recorded as handled, not put in To sort.
     */
    suspend fun apply(
        provider: Provider, providerAccountId: String, appAccountId: String, data: AccountData, windowStart: LocalDate,
        windowEnd: LocalDate = LocalDate.MAX, offBudget: Boolean = false,
    ): Outcome = store.write {
        val prefix = externalPrefix(provider, providerAccountId)
        val all = store.dao.transactionsNow()
        val synced = all.filter { it.externalId?.startsWith(prefix) == true }
        val byExt = synced.associateBy { it.externalId!! }
        val seen = mutableSetOf<String>()
        val consumed = mutableSetOf<String>()
        var added = 0; var linked = 0; var updated = 0; var removed = 0
        val notes = mutableListOf<String>()

        val fetched = data.transactions.sortedWith(compareBy({ it.date }, { it.id }))
        val unmatched = mutableListOf<ProviderTxn>()

        // 1. by provider id
        for (f in fetched) {
            val ext = prefix + f.id
            val old = byExt[ext]
            if (old == null) { unmatched += f; continue }
            seen += ext
            if (updateFrom(old, f, now)) updated++
        }

        // 2. count-aware key against hand-entered and CSV rows in the linked account
        val held = all.filter { it.accountId == appAccountId && it.externalId == null && it.kind == TransactionKind.NORMAL }
        val heldByKey = HashMap<String, MutableList<TransactionEntity>>()
        for (t in held) {
            for (k in listOfNotNull(t.payee, t.importDescription).map { CsvImport.dupKey(appAccountId, t.date, t.amount, it) }.toSet()) {
                heldByKey.getOrPut(k) { mutableListOf() } += t
            }
        }
        val it2 = unmatched.iterator()
        while (it2.hasNext()) {
            val f = it2.next()
            val match = heldByKey[CsvImport.dupKey(appAccountId, f.date, f.amount, f.description)]?.firstOrNull { it.id !in consumed } ?: continue
            consumed += match.id
            it2.remove()
            seen += prefix + f.id
            put(match, match.copy(externalId = prefix + f.id, pending = match.pending && f.pending, importDescription = f.description, updatedAt = now))
            linked++
        }

        // 3. a pending row entered by hand: same amount within 5 days
        val it3 = unmatched.iterator()
        while (it3.hasNext()) {
            val f = it3.next()
            val match = held.filter { it.pending && it.id !in consumed && it.amount == f.amount && days(it.date, f.date) <= PENDING_MATCH_DAYS }
                .minByOrNull { days(it.date, f.date) } ?: continue
            consumed += match.id
            it3.remove()
            seen += prefix + f.id
            put(match, match.copy(externalId = prefix + f.id, pending = f.pending, date = f.date, importDescription = f.description, updatedAt = now))
            linked++
        }

        // 3b. a row logged from a bank notification: the bank's record can differ a little (a tip, a final amount, a
        // deposit that isn't quite what the notification said). Same direction, within 5 days, within 20% and the same
        // payee (or no payee read): it's the same money, updated to what the bank says, never a second row.
        val it3b = unmatched.iterator()
        while (it3b.hasNext()) {
            val f = it3b.next()
            val match = held.filter {
                it.pending && it.id !in consumed && it.source == TransactionSource.NOTIFICATION && (it.amount > 0) == (f.amount > 0) &&
                    days(it.date, f.date) <= PENDING_MATCH_DAYS && abs(it.amount - f.amount) * 5 <= abs(it.amount) &&
                    (it.importDescription.isNullOrBlank() || net.darkrelay.deaddropfinance.domain.Paydays.samePayer(
                        net.darkrelay.deaddropfinance.domain.Payees.clean(it.importDescription!!), net.darkrelay.deaddropfinance.domain.Payees.clean(f.description),
                    ))
            }.minByOrNull { days(it.date, f.date) } ?: continue
            consumed += match.id
            it3b.remove()
            seen += prefix + f.id
            put(match, match.copy(externalId = prefix + f.id, pending = f.pending, date = f.date, amount = f.amount, importDescription = f.description, updatedAt = now))
            if (f.amount != match.amount) {
                adjustLines(match.id, match.amount, f.amount, now)
                net.darkrelay.deaddropfinance.domain.TransactionCleanup.followAmount(this, store.dao, match.id, f.amount)
            }
            linked++
        }

        // 4 and 5. synced pending rows the provider no longer lists (only when its list is complete for the window)
        if (data.complete) {
            val vanished = synced.filter { it.pending && it.externalId !in seen && !it.date.isBefore(windowStart) && !it.date.isAfter(windowEnd) }
            for (v in vanished) {
                val repl = unmatched.filter { !it.pending && it.amount == v.amount && days(it.date, v.date) <= REISSUE_DAYS }.minByOrNull { days(it.date, v.date) }
                if (repl != null) {
                    unmatched.remove(repl)
                    put(v, v.copy(externalId = prefix + repl.id, pending = false, date = repl.date, importDescription = repl.description, updatedAt = now))
                    updated++
                } else {
                    // everything that counted it goes too: a payday's sweep and fill, bill payments, a receipt's link
                    net.darkrelay.deaddropfinance.domain.TransactionCleanup.releaseReceipts(this, store.dao, v.id)
                    net.darkrelay.deaddropfinance.domain.TransactionCleanup.tombstone(this, store.dao, v.id)
                    removed++
                    notes += "A pending ${v.payee.ifBlank { "transaction" }} for ${Money.format(v.amount)} on ${v.date} disappeared without posting and was removed."
                }
            }
        }

        // 5. new rows: To sort, unsorted (suggestions come from rules and history; auto-approve is the caller's)
        val newIds = mutableListOf<String>()
        for (f in unmatched) {
            val tx = put(
                null,
                TransactionEntity(
                    accountId = appAccountId, date = f.date, payee = f.description, amount = f.amount, pending = f.pending,
                    fee = CsvImport.isAtmFee(f.description, f.amount), feeRebate = CsvImport.isFeeRebate(f.description, f.amount),
                    source = TransactionSource.SYNC, importDescription = f.description, externalId = prefix + f.id,
                    createdAt = now, updatedAt = now,
                ),
            )
            put(null, TransactionLineEntity(transactionId = tx.id, bucket = if (offBudget) LineBucket.OFF_BUDGET else LineBucket.UNSORTED, amount = f.amount, createdAt = now, updatedAt = now))
            newIds += tx.id
            added++
        }
        Outcome(added, linked, updated, removed, notes, newIds)
    }

    /**
     * Updates a synced row from the provider. Keeps the user's payee, envelope and split, and how they were set, so a
     * pending row approved before it posted stays approved. True if anything changed.
     */
    private suspend fun BudgetStore.Writer.updateFrom(old: TransactionEntity, f: ProviderTxn, now: Long): Boolean {
        val pending = old.pending && f.pending                     // posted never goes back to pending
        val date = if (old.pending || old.date == f.date) f.date else old.date
        if (pending == old.pending && date == old.date && f.amount == old.amount && f.description == old.importDescription) return false
        put(old, old.copy(pending = pending, date = date, amount = f.amount, importDescription = f.description, updatedAt = now))
        if (f.amount != old.amount) {
            adjustLines(old.id, old.amount, f.amount, now)
            net.darkrelay.deaddropfinance.domain.TransactionCleanup.followAmount(this, store.dao, old.id, f.amount)
        }
        return true
    }

    /**
     * The amount changed when the row posted (a tip, a final fuel amount). One line takes the new amount. With a
     * split, the biggest line absorbs the difference; if that can't work (it would flip sign), the row goes back to
     * To sort as one line.
     */
    private suspend fun BudgetStore.Writer.adjustLines(txId: String, oldAmount: Long, newAmount: Long, now: Long) {
        val lines = store.dao.linesOf(txId)
        if (lines.isEmpty()) return
        if (lines.size == 1) { put(lines[0], lines[0].copy(amount = newAmount, updatedAt = now)); return }
        val big = lines.maxBy { abs(it.amount) }
        val adjusted = big.amount + (newAmount - oldAmount)
        if (adjusted != 0L && (adjusted > 0) == (newAmount > 0)) {
            put(big, big.copy(amount = adjusted, updatedAt = now))
        } else {
            lines.forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
            put(null, TransactionLineEntity(transactionId = txId, bucket = LineBucket.UNSORTED, amount = newAmount, createdAt = now, updatedAt = now))
        }
    }

    companion object {
        const val PENDING_MATCH_DAYS = 5L
        const val REISSUE_DAYS = 7L

        fun externalPrefix(provider: Provider, providerAccountId: String) = "${provider.name.lowercase()}:$providerAccountId:"

        private fun days(a: LocalDate, b: LocalDate) = abs(ChronoUnit.DAYS.between(a, b))
    }
}
