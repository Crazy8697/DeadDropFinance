package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The balance check: match, mismatch, flipped sign, and the as-of date. Invented numbers only. */
class BalanceCheckTest {

    private val d = F.D0

    /** A starting balance of 50.00, a posted -12.30 two days in, a posted -7.70 five days in, and a pending -3.00. */
    private fun budget(): Budget {
        val txs = listOf(
            F.tx("a", "chk", -12_30, d.plusDays(2)),
            F.tx("b", "chk", -7_70, d.plusDays(5)),
            F.tx("p", "chk", -3_00, d.plusDays(6)).copy(pending = true),
        )
        return Budget(listOf(F.account("chk", 50_00)), listOf(F.rollover()), txs, txs.map { F.line(it.id, it.amount) }, emptyList())
    }

    @Test fun matchCountsPostedTransactionsUpToTheAsOfDate() {
        val b = budget()
        assertTrue(BalanceCheck.check(b, "chk", 30_00, d.plusDays(6)) is BalanceCheck.Result.Match)        // pending left out
        // the bank's figure from day 3: the later -7.70 isn't in it yet
        val r = BalanceCheck.check(b, "chk", 37_70, d.plusDays(3))
        assertTrue(r is BalanceCheck.Result.Match)
        assertEquals(37_70L, r.app)
        // a transaction dated on the as-of day itself may not be in the bank's figure yet: the day before also matches
        assertTrue(BalanceCheck.check(b, "chk", 37_70, d.plusDays(5)) is BalanceCheck.Result.Match)
        assertTrue(BalanceCheck.check(b, "chk", 30_00, null) is BalanceCheck.Result.Match)
    }

    @Test fun mismatchShowsTheDifferencePlainly() {
        val r = BalanceCheck.check(budget(), "chk", 25_00, d.plusDays(6))
        r as BalanceCheck.Result.Mismatch
        assertEquals(30_00L, r.app)
        assertEquals("an adjustment would add this", -5_00L, r.difference)
    }

    @Test fun aFlippedSignIsCalledOutAndTheAppIsTrusted() {
        // overdrawn: the app says -41.07, the bank sends 41.07
        val txs = listOf(F.tx("x", "chk", -91_07, d.plusDays(1)))
        val b = Budget(listOf(F.account("chk", 50_00)), listOf(F.rollover()), txs, txs.map { F.line(it.id, it.amount) }, emptyList())
        val r = BalanceCheck.check(b, "chk", 41_07, d.plusDays(2))
        assertTrue(r is BalanceCheck.Result.FlippedSign)
        assertEquals(-41_07L, r.app)
        assertTrue("a real match still wins", BalanceCheck.check(b, "chk", -41_07, d.plusDays(2)) is BalanceCheck.Result.Match)
        // zero is never a flipped sign
        val z = Budget(listOf(F.account("zero", 0)), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(BalanceCheck.check(z, "zero", 0, d) is BalanceCheck.Result.Match)
    }

    @Test fun historyBeforeTheStartDateNeverCounts() {
        val start = LocalDate.of(2030, 2, 1)
        val old = F.tx("old", "chk", -99_00, start.minusDays(10))
        val b = Budget(listOf(F.account("chk", 20_00).copy(startDate = start)), emptyList(), listOf(old), listOf(F.line("old", -99_00)), emptyList())
        assertTrue(BalanceCheck.check(b, "chk", 20_00, start.plusDays(1)) is BalanceCheck.Result.Match)
    }
}
