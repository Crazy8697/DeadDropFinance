package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MetaEntity
import net.darkrelay.deaddropfinance.data.ParkedBuyEntity
import net.darkrelay.deaddropfinance.data.ParkedStatus
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Parked buys (FEATURES.md): something the user wants is parked with a name and a price, waits a set time, then the
 * app says whether Rollover covers it. Buying marks it bought (no money moves yet); its real charge is sorted against
 * the chosen envelope when it comes in. Dropping it adds to the dropped total.
 */
class ParkedBuys(
    private val store: BudgetStore,
    private val actions: BudgetActions = BudgetActions(store),
    private val today: () -> LocalDate = { LocalDate.now() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = store.dao

    /** The wait a new parked buy gets unless the user picks another (Settings, 1 day out of the box). */
    suspend fun defaultWait(): Int = dao.meta(META_WAIT)?.toIntOrNull()?.takeIf { it in WAITS } ?: 1

    suspend fun setDefaultWait(days: Int) {
        if (days !in WAITS) throw BudgetRuleException("Wait 1 day, 2 days or 1 week.")
        dao.putMeta(MetaEntity(META_WAIT, days.toString()))
    }

    suspend fun park(name: String, amount: Long, waitDays: Int? = null): String {
        if (name.isBlank()) throw BudgetRuleException("Give it a name.")
        if (amount <= 0) throw BudgetRuleException("Enter its price.")
        val wait = waitDays ?: defaultWait()
        if (wait !in WAITS) throw BudgetRuleException("Wait 1 day, 2 days or 1 week.")
        val at = clock()
        return store.write {
            put(null, ParkedBuyEntity(name = name.trim(), amount = amount, waitDays = wait, parkedAt = at, readyAt = at + wait * DAY_MS, createdAt = now, updatedAt = now)).id
        }
    }

    private suspend fun open(id: String): ParkedBuyEntity {
        val p = dao.parkedBuy(id)?.takeIf { !it.deleted } ?: throw BudgetRuleException("That parked buy is gone.")
        if (p.status != ParkedStatus.WAITING) throw BudgetRuleException("That one is already decided.")
        return p
    }

    /** Not decided yet: it waits one more day. */
    suspend fun keepWaiting(id: String) {
        val p = open(id)
        store.write { put(p, p.copy(readyAt = clock() + DAY_MS, updatedAt = now)) }
    }

    /** Not buying it: its price goes on the dropped total. */
    suspend fun drop(id: String) {
        val p = open(id)
        store.write { put(p, p.copy(status = ParkedStatus.DROPPED, updatedAt = now)) }
    }

    /** Bought, with its money from [envelopeId] (Rollover, or another envelope the user picks). Only after the wait. */
    suspend fun buy(id: String, envelopeId: String) {
        val p = open(id)
        if (!isReady(p, clock())) throw BudgetRuleException("It's still waiting.")
        val e = dao.envelope(envelopeId)?.takeIf { !it.deleted && !it.archived } ?: throw BudgetRuleException("That envelope is gone.")
        store.write { put(p, p.copy(status = ParkedStatus.BOUGHT, envelopeId = e.id, boughtOn = today(), updatedAt = now)) }
    }

    /**
     * New transactions ([txIds]) that are a bought parked buy's charge are sorted against its envelope. A charge
     * counts when it's money out in the budget, still unsorted (or sorted by a rule as it came in), from the day
     * before buying to [MATCH_DAYS] after, and within [MATCH_UNDER_PERCENT] under to [MATCH_OVER_PERCENT] over the
     * parked price (tax, shipping). The closest amount wins; each charge goes to one parked buy. Returns how many.
     */
    suspend fun matchCharges(txIds: Collection<String>): Int {
        if (txIds.isEmpty()) return 0
        val waiting = dao.parkedBuysNow().filter { it.status == ParkedStatus.BOUGHT && it.transactionId == null && it.envelopeId != null }
        if (waiting.isEmpty()) return 0
        val b = actions.snapshot()
        val taken = dao.parkedBuysNow().mapNotNull { it.transactionId }.toMutableSet()
        val ids = txIds.toSet()
        var n = 0
        for (p in waiting.sortedBy { it.boughtOn }) {
            val env = b.envelopes.firstOrNull { it.id == p.envelopeId && !it.archived } ?: continue
            val bought = p.boughtOn ?: continue
            val t = b.transactions.filter { t ->
                t.id in ids && t.id !in taken && t.kind == TransactionKind.NORMAL && t.amount < 0 && !t.isWash && t.returnOf == null &&
                    b.isOnBudget(t.accountId) && !b.beforeStart(t) && (t.sortedBy == null || t.sortedBy == SortedBy.AUTO) &&
                    b.lines.none { it.transactionId == t.id && it.bucket == LineBucket.OFF_BUDGET } &&
                    ChronoUnit.DAYS.between(bought, t.date) in -1..MATCH_DAYS && chargeFits(-t.amount, p.amount)
            }.minByOrNull { kotlin.math.abs(-it.amount - p.amount) } ?: continue
            actions.setLines(t.id, listOf(BudgetActions.LineDraft(env.id, t.amount)), SortedBy.AUTO)
            store.write {
                val old = dao.parkedBuy(p.id)!!
                put(old, old.copy(transactionId = t.id, updatedAt = now))
            }
            taken += t.id
            n++
        }
        return n
    }

    /** Where a ready parked buy stands against Rollover: what Rollover has (less what's bought from it and not charged yet). */
    data class Verdict(val fits: Boolean, val rollover: Long, val left: Long)

    companion object {
        /** The waits a parked buy can have, in days: 1 day, 2 days, 1 week. */
        val WAITS = listOf(1, 2, 7)
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val META_WAIT = "parked.wait"
        const val MATCH_DAYS = 14L
        const val MATCH_UNDER_PERCENT = 10L
        const val MATCH_OVER_PERCENT = 25L

        fun chargeFits(charge: Long, price: Long): Boolean =
            charge * 100 >= price * (100 - MATCH_UNDER_PERCENT) && charge * 100 <= price * (100 + MATCH_OVER_PERCENT)

        fun waitLabel(days: Int): String = when (days) { 1 -> "1 day"; 7 -> "1 week"; else -> "$days days" }

        fun isReady(p: ParkedBuyEntity, now: Long): Boolean = p.status == ParkedStatus.WAITING && now >= p.readyAt

        fun waiting(all: List<ParkedBuyEntity>): List<ParkedBuyEntity> = all.filter { it.status == ParkedStatus.WAITING && !it.deleted }

        fun ready(all: List<ParkedBuyEntity>, now: Long): List<ParkedBuyEntity> = waiting(all).filter { isReady(it, now) }

        /** The running total of what was dropped instead of bought. */
        fun droppedTotal(all: List<ParkedBuyEntity>): Long = all.filter { it.status == ParkedStatus.DROPPED && !it.deleted }.sumOf { it.amount }

        fun verdict(p: ParkedBuyEntity, b: Budget, all: List<ParkedBuyEntity>): Verdict {
            val roll = b.envelopes.firstOrNull { it.mode == EnvelopeMode.ROLLOVER } ?: return Verdict(false, 0, -p.amount)
            val held = all.filter { it.status == ParkedStatus.BOUGHT && it.transactionId == null && it.envelopeId == roll.id && !it.deleted }.sumOf { it.amount }
            val has = b.envelopeBalance(roll.id) - held
            return Verdict(has >= p.amount, has, has - p.amount)
        }
    }
}
