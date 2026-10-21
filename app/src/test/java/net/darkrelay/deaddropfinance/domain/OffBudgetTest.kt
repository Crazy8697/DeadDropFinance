package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.LineBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Accounts outside the budget (archived or track only) and held amounts. Synthetic numbers only. In every case the
 * budget still balances (sortedCheck is 0).
 */
class OffBudgetTest {

    private fun budget(vararg accounts: net.darkrelay.deaddropfinance.data.AccountEntity, txs: List<net.darkrelay.deaddropfinance.data.TransactionEntity> = emptyList(),
                       lines: List<net.darkrelay.deaddropfinance.data.TransactionLineEntity> = emptyList(), moves: List<net.darkrelay.deaddropfinance.data.MoveEntity> = emptyList()) =
        Budget(accounts.toList(), listOf(F.rollover(), F.envelope("e")), txs, lines, moves).also { assertEquals("budget balances", 0L, it.sortedCheck) }

    private val checking = F.account("chk", 500_00)

    @Test fun anArchivedAccountLeavesReadyToAssignAndUnarchivingPutsItBack() {
        val savings = F.account("sav", 1_200_00)
        val txs = listOf(F.tx("i", "sav", 300_00), F.tx("s", "sav", -40_00))
        val lines = listOf(F.line("i", 300_00, rta = true), F.line("s", -40_00))
        val on = budget(checking, savings, txs = txs, lines = lines)
        assertEquals(500_00L + 1_200_00 + 300_00, on.readyToAssign)
        assertEquals(1, on.toSort.size)
        val off = budget(checking, savings.copy(archived = true), txs = txs, lines = lines)
        assertEquals("as if the account was never there", 500_00L, off.readyToAssign)
        assertTrue(off.toSort.isEmpty())
        assertEquals("its balance still shows", 1_460_00L, off.accountBalance("sav"))
        assertEquals(on.readyToAssign, budget(checking, savings.copy(archived = false), txs = txs, lines = lines).readyToAssign)
    }

    @Test fun archivingANegativeAccountRaisesReadyToAssign() {
        val overdrawn = F.account("od", -75_00)
        assertEquals(500_00L - 75_00, budget(checking, overdrawn).readyToAssign)
        assertEquals(500_00L, budget(checking, overdrawn.copy(archived = true)).readyToAssign)
    }

    @Test fun trackOnlyMoneyNeverCountsAndNeverLandsInToSort() {
        val share = F.account("share", 25_00).copy(trackOnly = true)
        val txs = listOf(F.tx("d", "share", 5_00))
        val lines = listOf(F.line("d", 5_00).copy(bucket = LineBucket.OFF_BUDGET))
        val b = budget(checking, share, txs = txs, lines = lines)
        assertEquals(500_00L, b.readyToAssign)
        assertTrue(b.toSort.isEmpty())
        assertEquals(30_00L, b.accountBalance("share"))
        assertFalse(b.isOnBudget("share"))
    }

    @Test fun switchingTrackOnlyBothWays() {
        val acct = F.account("a", 100_00)
        val txs = listOf(F.tx("x", "a", 20_00))
        val lines = listOf(F.line("x", 20_00).copy(bucket = LineBucket.OFF_BUDGET))
        assertEquals(500_00L, budget(checking, acct.copy(trackOnly = true), txs = txs, lines = lines).readyToAssign)
        // joining the budget brings the whole balance (start plus what was recorded as handled) into Ready to Assign
        assertEquals(500_00L + 120_00, budget(checking, acct.copy(trackOnly = false), txs = txs, lines = lines).readyToAssign)
        assertEquals(500_00L, budget(checking, acct.copy(trackOnly = true), txs = txs, lines = lines).readyToAssign)
    }

    @Test fun transfersAcrossTheBudgetLineComeOutOfReadyToAssign() {
        val share = F.account("share", 0).copy(trackOnly = true)
        val out = budget(checking, share, txs = listOf(F.transfer("t", "chk", "share", 50_00)))
        assertEquals(450_00L, out.readyToAssign)
        val back = budget(checking, share, txs = listOf(F.transfer("t", "share", "chk", 10_00)))
        assertEquals(510_00L, back.readyToAssign)
    }

    @Test fun aHeldAmountIsSubtracted() {
        val savings = F.account("sav", 300_00).copy(heldAmount = 25_00)
        val b = budget(checking, savings)
        assertEquals(500_00L + 300_00 - 25_00, b.readyToAssign)
        assertEquals(25_00L, b.held["sav"])
        assertFalse(b.heldShort("sav"))
    }

    @Test fun belowTheHeldAmountOnlyTheRealBalanceIsHeld() {
        val savings = F.account("sav", 10_00).copy(heldAmount = 25_00)
        val b = budget(checking, savings)
        assertEquals(10_00L, b.held["sav"])
        assertTrue(b.heldShort("sav"))
        assertEquals("never lower than without the account", 500_00L, b.readyToAssign)
        val negative = budget(checking, F.account("sav", -5_00).copy(heldAmount = 25_00))
        assertEquals(0L, negative.held["sav"])
        assertEquals(500_00L - 5_00, negative.readyToAssign)
    }

    @Test fun changingTheHeldAmountLater() {
        val savings = F.account("sav", 300_00)
        assertEquals(800_00L, budget(checking, savings.copy(heldAmount = 0)).readyToAssign)
        assertEquals(700_00L, budget(checking, savings.copy(heldAmount = 100_00)).readyToAssign)
        assertEquals(750_00L, budget(checking, savings.copy(heldAmount = 50_00)).readyToAssign)
        // a track-only account holds nothing (it doesn't count at all)
        assertEquals(500_00L, budget(checking, savings.copy(heldAmount = 50_00, trackOnly = true)).readyToAssign)
    }

    @Test fun moneyFormatterKeepsTheSign() {
        assertEquals("-$1,234.56", Money.format(-123456))
        assertEquals("$0.00", Money.format(0))
        assertEquals("$0.01", Money.format(1))
        assertEquals("-$0.01", Money.format(-1))
    }
}
