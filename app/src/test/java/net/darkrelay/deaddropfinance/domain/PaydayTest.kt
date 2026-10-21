package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The payday sweep and auto-fill, with synthetic amounts. */
class PaydayTest {

    private val today = F.D0.plusDays(30)

    private fun budget(start: Long, envelopes: List<net.darkrelay.deaddropfinance.data.EnvelopeEntity>, moves: List<net.darkrelay.deaddropfinance.data.MoveEntity> = emptyList(),
                       txs: List<net.darkrelay.deaddropfinance.data.TransactionEntity> = emptyList(), lines: List<net.darkrelay.deaddropfinance.data.TransactionLineEntity> = emptyList()) =
        Budget(listOf(F.account("a", start)), listOf(F.rollover()) + envelopes, txs, lines, moves)

    @Test fun fillToTargetTopsUpWhenNothingIsSwept() {
        // the first payday doesn't close a month: an envelope holding 30 with a target of 90 gets 60
        val b = budget(30_00 + 500_00, listOf(F.envelope("g", target = 90_00)), moves = listOf(F.move(null, "g", 30_00)))
        val p = Payday.plan(b, today, closesMonth = false)
        assertEquals(listOf(Payday.PlannedMove(null, "g", 60_00)), p.fills)
        assertTrue(p.sweeps.isEmpty())
    }

    @Test fun closingTheMonthSweepsFillToTargetLeftoversOnly() {
        val b = budget(
            30_00 + 20_00 + 10_00 + 1000_00,
            listOf(F.envelope("g", target = 90_00, order = 1), F.envelope("s", EnvelopeMode.ADD_EVERY_PAYDAY, order = 2, perPayday = 5_00), F.envelope("neg", target = 10_00, order = 3)),
            moves = listOf(F.move(null, "g", 30_00), F.move(null, "s", 20_00)),
            txs = listOf(F.tx("t", "a", -10_00)), lines = listOf(F.line("t", -10_00, "neg")),
        )
        val p = Payday.plan(b, today, closesMonth = true)
        // g's 30 goes to Rollover; s (add every payday) is never swept; neg (-10) has nothing to sweep
        assertEquals(listOf(Payday.PlannedMove("g", "roll", 30_00)), p.sweeps)
        // then auto-fill: g 0 -> 90, s +5, neg -10 -> 10 (20)
        assertEquals(listOf(Payday.PlannedMove(null, "g", 90_00), Payday.PlannedMove(null, "s", 5_00), Payday.PlannedMove(null, "neg", 20_00)), p.fills)
    }

    @Test fun fillsInListOrderAndReportsShortfalls() {
        val b = budget(100_00, listOf(F.envelope("x", target = 70_00, order = 1), F.envelope("y", target = 50_00, order = 2), F.envelope("z", target = 10_00, order = 3)))
        val p = Payday.plan(b, today, closesMonth = false)
        assertEquals(listOf(Payday.PlannedMove(null, "x", 70_00), Payday.PlannedMove(null, "y", 30_00)), p.fills)
        assertEquals(listOf(Payday.Shortfall("y", 50_00, 30_00), Payday.Shortfall("z", 10_00, 0)), p.shortfalls)
        assertEquals(20_00L, p.shortfalls[0].short)
        assertEquals(0L, p.leftInReadyToAssign)
    }

    @Test fun whateverIsLeftStaysInReadyToAssign() {
        val b = budget(500_00, listOf(F.envelope("x", target = 70_00)))
        assertEquals(430_00L, Payday.plan(b, today, false).leftInReadyToAssign)
    }

    @Test fun goalWorksOutThePerPaydayAmountAndStopsAtTheGoal() {
        val goal = F.envelope("goal", EnvelopeMode.GOAL).copy(goalAmount = 200_00, goalDate = today.plusDays(56))
        val b = budget(1000_00, listOf(goal))
        // no payday history: every 14 days -> 56/14 + 1 = 5 paydays left -> 40 each
        assertEquals(40_00L, Payday.amountFor(goal, 0, b, today))
        val nearlyThere = budget(1000_00, listOf(goal), moves = listOf(F.move(null, "goal", 190_00)))
        assertEquals(2_00L, Payday.amountFor(goal, 190_00, nearlyThere, today))      // 10 left over 5 paydays
        val late = goal.copy(goalDate = today.minusDays(1))
        assertEquals(10_00L, Payday.amountFor(late, 190_00, budget(1000_00, listOf(late), moves = listOf(F.move(null, "goal", 190_00))), today))
        val done = budget(1000_00, listOf(goal), moves = listOf(F.move(null, "goal", 200_00)))
        assertEquals(0L, Payday.amountFor(goal, 200_00, done, today))
    }

    @Test fun debtAddsUntilTheBalanceIsCovered() {
        val debt = F.envelope("d", EnvelopeMode.DEBT, perPayday = 40_00).copy(debtOwed = 50_00, debtOwedSince = F.D0)
        val b = budget(1000_00, listOf(debt), moves = listOf(F.move(null, "d", 20_00)))
        assertEquals(30_00L, Payday.amountFor(debt, 20_00, b, today))
        val unknown = F.envelope("d2", EnvelopeMode.DEBT, perPayday = 40_00)
        assertEquals(40_00L, Payday.amountFor(unknown, 0, budget(0, listOf(unknown)), today))
    }

    @Test fun seasonalUsesItsAmountUntilBillsGiveAnAverage() {
        val s = F.envelope("s", EnvelopeMode.SEASONAL, perPayday = 25_00)
        assertEquals(25_00L, Payday.amountFor(s, 0, budget(0, listOf(s)), today))
    }

    @Test fun rolloverAndCashNeverAutoFill() {
        val cash = F.envelope("c", EnvelopeMode.CASH)
        val b = budget(1000_00, listOf(cash))
        assertTrue(Payday.plan(b, today, false).fills.isEmpty())
    }
}
