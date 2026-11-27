package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode

/**
 * What a payday does (FEATURES.md, Payday and Ready to Assign, Rollover envelope):
 *  1. it starts the new budget month. Since 1.9.9 (program 0.15.12) every envelope's leftover carries into it, and
 *     overspending carries as negative: nothing is swept into Rollover any more (the Payday button's Clean up zeroes
 *     envelopes instead). Money assigned ahead to the new month is the envelopes' already, so the fill counts it.
 *  2. the deposit itself lands in Ready to Assign (its line says so)
 *  3. auto-fill goes through the envelopes in fill order and fills each by its mode, from Ready to Assign
 *  4. if the money runs out first, the plan lists which envelopes didn't fill and by how much
 * Pure: it plans moves, BudgetActions writes them.
 */
object Payday {

    data class PlannedMove(val fromEnvelopeId: String?, val toEnvelopeId: String?, val amount: Long)
    data class Shortfall(val envelopeId: String, val needed: Long, val got: Long) {
        val short get() = needed - got
    }
    data class Plan(val sweeps: List<PlannedMove>, val fills: List<PlannedMove>, val shortfalls: List<Shortfall>, val leftInReadyToAssign: Long)

    /**
     * [budget] is the state BEFORE this payday's moves, including the payday deposit's own Ready to Assign line.
     * [closesMonth]: true when this isn't the first payday ever (a budget month ends here).
     */
    @Suppress("UNUSED_PARAMETER")
    fun plan(budget: Budget, today: LocalDate, closesMonth: Boolean): Plan {
        // carry over (1.9.9): what each envelope has now, plus anything assigned ahead to the month this payday starts
        val balances = budget.envelopeBalances.toMutableMap()
        for ((id, a) in budget.aheadBalances) balances[id] = (balances[id] ?: 0) + a
        val sweeps = emptyList<PlannedMove>()
        var rta = budget.readyToAssign
        val fills = mutableListOf<PlannedMove>()
        val shortfalls = mutableListOf<Shortfall>()
        for (e in budget.fillOrder) {
            val need = amountFor(e, balances[e.id] ?: 0, budget, today)
            if (need <= 0) continue
            val give = minOf(need, maxOf(0, rta))
            if (give > 0) {
                fills += PlannedMove(null, e.id, give)
                rta -= give
                balances[e.id] = (balances[e.id] ?: 0) + give
            }
            if (give < need) shortfalls += Shortfall(e.id, need, give)
        }
        return Plan(sweeps, fills, shortfalls, rta)
    }

    /** How much an envelope wants this payday, by its mode. */
    fun amountFor(e: EnvelopeEntity, balance: Long, budget: Budget, today: LocalDate): Long = when (e.mode) {
        // no target, but bills feed it: a little each payday toward each bill's next due date
        // 1.9.9: the month's target when its bills have amounts typed for this budget month; the standing one otherwise
        EnvelopeMode.FILL_TO_TARGET -> (budget.monthTarget(e, today) ?: e.target)?.let { maxOf(0, it - balance) } ?: budget.billFillPerPayday(e, balance, today)
        EnvelopeMode.ADD_EVERY_PAYDAY -> e.perPayday ?: 0
        EnvelopeMode.GOAL -> budget.goalPerPayday(e, today)
        EnvelopeMode.SEASONAL -> budget.seasonalPerPayday(e, today) ?: e.perPayday ?: 0
        EnvelopeMode.DEBT -> {
            val add = e.perPayday ?: 0
            val left = budget.debtRemaining(e)
            if (left == null) add else minOf(add, maxOf(0, left - balance))
        }
        EnvelopeMode.CASH, EnvelopeMode.ROLLOVER -> 0
    }
}
