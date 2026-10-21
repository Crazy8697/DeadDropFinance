package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {
    @Test fun formats() {
        assertEquals("$0.00", Money.format(0))
        assertEquals("$1,234.56", Money.format(123456))
        assertEquals("-$0.05", Money.format(-5))
        assertEquals("$1,000,000.00", Money.format(100_000_000))
        assertEquals("12.30", Money.plain(1230))
        assertEquals("-0.07", Money.plain(-7))
    }

    @Test fun parsesWithoutRounding() {
        assertEquals(1200L, Money.parse("12"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(1234L, Money.parse("12.34"))
        assertEquals(-1234L, Money.parse("-12.34"))
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(-1234L, Money.parse("(12.34)"))
        assertEquals(5L, Money.parse(".05"))
        assertNull(Money.parse("12.345"))   // never rounded: refused
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("1,23"))
    }
}

/** Synthetic amounts only. Everything is cents: no rounding can happen anywhere. */
class BudgetMathTest {

    private fun budget(
        accounts: List<net.darkrelay.deaddropfinance.data.AccountEntity> = listOf(F.account("a", 100_00)),
        envelopes: List<net.darkrelay.deaddropfinance.data.EnvelopeEntity> = listOf(F.rollover(), F.envelope("g", target = 60_00), F.envelope("h", order = 2)),
        txs: List<net.darkrelay.deaddropfinance.data.TransactionEntity> = emptyList(),
        lines: List<net.darkrelay.deaddropfinance.data.TransactionLineEntity> = emptyList(),
        moves: List<net.darkrelay.deaddropfinance.data.MoveEntity> = emptyList(),
    ) = Budget(accounts, envelopes, txs, lines, moves)

    @Test fun startingBalanceIsReadyToAssign() {
        val b = budget()
        assertEquals(100_00L, b.readyToAssign)
        assertEquals(100_00L, b.accountBalance("a"))
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun assignAndMove() {
        val b = budget(moves = listOf(F.move(null, "g", 40_00), F.move("g", "h", 15_00), F.move("h", null, 5_00)))
        assertEquals(65_00L, b.readyToAssign)
        assertEquals(25_00L, b.envelopeBalance("g"))
        assertEquals(10_00L, b.envelopeBalance("h"))
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun spendingFromEnvelopesAndSplits() {
        val b = budget(
            txs = listOf(F.tx("t1", "a", -30_00)),
            lines = listOf(F.line("t1", -20_00, "g"), F.line("t1", -10_00, "h")),
            moves = listOf(F.move(null, "g", 50_00)),
        )
        assertEquals(30_00L, b.envelopeBalance("g"))
        assertEquals(-10_00L, b.envelopeBalance("h"))       // negative balances are allowed
        assertEquals(70_00L, b.accountBalance("a"))
        assertEquals(50_00L, b.readyToAssign)
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun incomeToReadyToAssignOrStraightToAnEnvelope() {
        val b = budget(
            txs = listOf(F.tx("i1", "a", 200_00), F.tx("i2", "a", 25_00)),
            lines = listOf(F.line("i1", 200_00, rta = true), F.line("i2", 25_00, "h")),
        )
        assertEquals(300_00L, b.readyToAssign)
        assertEquals(25_00L, b.envelopeBalance("h"))
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun unsortedSitsInToSortNotInReadyToAssign() {
        val b = budget(txs = listOf(F.tx("t", "a", -12_34)), lines = listOf(F.line("t", -12_34)))
        assertEquals(100_00L, b.readyToAssign)
        assertEquals(-12_34L, b.toSortTotal)
        assertEquals(listOf("t"), b.toSort.map { it.id })
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun transfersMoveBetweenAccountsAndTouchNoEnvelope() {
        val b = budget(accounts = listOf(F.account("a", 100_00), F.account("c", 0, AccountType.CASH)), txs = listOf(F.transfer("x", "a", "c", 40_00)))
        assertEquals(60_00L, b.accountBalance("a"))
        assertEquals(40_00L, b.accountBalance("c"))
        assertEquals(100_00L, b.readyToAssign)
        assertTrue(b.envelopeBalances.values.all { it == 0L })
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun washIsNeitherIncomeNorSpending() {
        val b = budget(
            txs = listOf(F.tx("in", "a", 50_00, wash = "w"), F.tx("out", "a", -50_00, wash = "w")),
            lines = listOf(F.line("in", 50_00, rta = true), F.line("out", -50_00, "g")),
        )
        assertEquals(100_00L, b.readyToAssign)
        assertEquals(0L, b.envelopeBalance("g"))
        assertEquals(100_00L, b.accountBalance("a"))
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun softDeletedRowsDontCount() {
        // the store hands Budget live rows only; a tombstoned transaction's lines no longer match a transaction
        val b = budget(txs = emptyList(), lines = listOf(F.line("gone", -99_00, "g")))
        assertEquals(0L, b.envelopeBalance("g"))
        assertEquals(100_00L, b.accountBalance("a"))
    }

    @Test fun splitLinesMustAddUpAndKeepTheSign() {
        val a = BudgetActions
        fun d(amount: Long, vararg lines: BudgetActions.LineDraft) = BudgetActions.TransactionDraft("a", F.D0, "p", "", amount, lines.toList())
        a.checkLines(d(-30_00, BudgetActions.LineDraft("g", -20_00), BudgetActions.LineDraft("h", -10_00)))
        assertThrows(BudgetRuleException::class.java) { a.checkLines(d(-30_00, BudgetActions.LineDraft("g", -20_00), BudgetActions.LineDraft("h", -9_99))) }
        assertThrows(BudgetRuleException::class.java) { a.checkLines(d(-30_00, BudgetActions.LineDraft("g", -40_00), BudgetActions.LineDraft("h", 10_00))) }
        assertThrows(BudgetRuleException::class.java) { a.checkLines(d(-30_00, BudgetActions.LineDraft(null, -30_00, toReadyToAssign = true))) }
        assertThrows(BudgetRuleException::class.java) { a.checkLines(d(0)) }
    }

    @Test fun coverFromRolloverOnlyWhenReadyToAssignCantCover() {
        val env = F.envelope("g", target = 60_00)
        val b = budget(
            accounts = listOf(F.account("a", 10_00)),
            envelopes = listOf(F.rollover(), env),
            txs = listOf(F.tx("t", "a", -30_00)),
            lines = listOf(F.line("t", -30_00, "g")),
            moves = listOf(F.move(null, "roll", 10_00)),
        )
        // g is -30, RTA is 0, Rollover has 10: cover 10
        assertEquals(0L, b.readyToAssign)
        assertEquals(10_00L, b.canCoverFromRollover(env))
        val rich = budget(accounts = listOf(F.account("a", 100_00)), envelopes = listOf(F.rollover(), env),
            txs = listOf(F.tx("t", "a", -30_00)), lines = listOf(F.line("t", -30_00, "g")), moves = listOf(F.move(null, "roll", 10_00)))
        assertNull(rich.canCoverFromRollover(env))        // RTA can cover it
    }

    @Test fun debtRemainingGoesDownWithPayments() {
        val debt = F.envelope("d", EnvelopeMode.DEBT, perPayday = 40_00).copy(debtOwed = 500_00, debtOwedSince = F.D0)
        val b = budget(envelopes = listOf(F.rollover(), debt), txs = listOf(F.tx("p", "a", -120_00)), lines = listOf(F.line("p", -120_00, "d")))
        assertEquals(380_00L, b.debtRemaining(debt))
    }

    @Test fun suggestedTargetNeedsTwoClosedMonths() {
        val e = F.envelope("g")      // no target
        val d0 = F.D0
        val one = budget(envelopes = listOf(F.rollover(), e),
            txs = listOf(F.tx("p1", "a", 1_00, d0, payday = true), F.tx("p2", "a", 1_00, d0.plusDays(14), payday = true), F.tx("s", "a", -40_00, d0.plusDays(3))),
            lines = listOf(F.line("p1", 1_00, rta = true), F.line("p2", 1_00, rta = true), F.line("s", -40_00, "g")))
        assertNull(one.suggestedTarget(e))
        val two = budget(envelopes = listOf(F.rollover(), e),
            txs = listOf(F.tx("p1", "a", 1_00, d0, payday = true), F.tx("p2", "a", 1_00, d0.plusDays(14), payday = true), F.tx("p3", "a", 1_00, d0.plusDays(28), payday = true),
                F.tx("s1", "a", -40_00, d0.plusDays(3)), F.tx("s2", "a", -60_00, d0.plusDays(17))),
            lines = listOf(F.line("p1", 1_00, rta = true), F.line("p2", 1_00, rta = true), F.line("p3", 1_00, rta = true), F.line("s1", -40_00, "g"), F.line("s2", -60_00, "g")))
        assertEquals(50_00L, two.suggestedTarget(e))
    }
}
