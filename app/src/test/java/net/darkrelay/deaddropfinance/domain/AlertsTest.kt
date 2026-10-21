package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.SortedBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What spend alerts say: batching, the lock screen's public version, each toggle, and the warnings. Invented data. */
class AlertsTest {

    private val d = F.D0
    private val food = F.envelope("food", target = 100_00).copy(name = "Food", everyday = true)
    private val fun_ = F.envelope("fun", target = 40_00).copy(name = "Fun")
    private val rule = RuleEntity(id = "r", matchText = "sample market", splits = "food:1", createdAt = 0, updatedAt = 0)

    /** Food holds [foodHas], Fun 30.00; [news] are new unsorted charges (id, bank description, amount). */
    private fun budget(foodHas: Long, vararg news: Triple<String, String, Long>, sortedAuto: Set<String> = emptySet()): Budget {
        val txs = news.map { (id, desc, amt) ->
            F.tx(id, "chk", amt, d.plusDays(1), payee = desc).copy(importDescription = desc, sortedBy = if (id in sortedAuto) SortedBy.AUTO else null)
        }
        val lines = txs.map { t -> if (t.id in sortedAuto) F.line(t.id, t.amount, envelope = "food") else F.line(t.id, t.amount) }
        val moves = listOf(F.move(null, "food", foodHas), F.move(null, "fun", 30_00))
        return Budget(listOf(F.account("chk", 1000_00)), listOf(F.rollover(), food, fun_), txs, lines, moves)
    }

    @Test fun oneChargeOneNotificationWithSuggestionAndWhatsLeft() {
        val b = budget(80_00, Triple("t1", "SAMPLE MARKET", -12_50))
        val n = Alerts.build(b, listOf(rule), listOf("t1"), Alerts.Settings())
        assertEquals(1, n.size)
        assertEquals("SAMPLE MARKET -\$12.50", n[0].title)
        assertEquals(listOf("Suggested Food, \$67.50 left"), n[0].lines)
        assertEquals(listOf("t1"), n[0].approveIds)
    }

    @Test fun severalChargesBatchIntoOne() {
        val b = budget(80_00, Triple("t1", "SAMPLE MARKET", -10_00), Triple("t2", "SAMPLE MARKET", -5_00), Triple("t3", "SAMPLE UNKNOWN", -2_00))
        val n = Alerts.build(b, listOf(rule), listOf("t1", "t2", "t3"), Alerts.Settings())
        assertEquals(1, n.size)
        assertEquals("3 new charges, -\$17.00", n[0].title)
        assertEquals(3, n[0].lines.size)
        assertTrue(n[0].lines[0].endsWith("suggested Food, \$65.00 left"))
        assertTrue(n[0].lines[2].endsWith("not sorted yet"))
        assertEquals(listOf("t1", "t2"), n[0].approveIds)
        assertEquals("3 new charges synced", n[0].publicText)
    }

    @Test fun theLockScreenShowsNoPayeeEnvelopeOrAmountUnlessAllowed() {
        val b = budget(5_00, Triple("t1", "SAMPLE MARKET", -12_50), Triple("t2", "SAMPLE MARKET", -1_00))
        val quiet = Alerts.build(b, listOf(rule), listOf("t1", "t2"), Alerts.Settings())
        assertEquals(2, quiet.size)                                         // spending and a warning
        for (n in quiet) {
            assertFalse(n.showOnLockScreen)
            val pub = n.publicTitle + " " + n.publicText
            assertFalse(pub, pub.contains("$") || pub.contains("SAMPLE", ignoreCase = true) || pub.contains("Food"))
        }
        assertTrue(Alerts.build(b, listOf(rule), listOf("t1", "t2"), Alerts.Settings(amountsOnLockScreen = true)).all { it.showOnLockScreen })
    }

    @Test fun warningsForOverspentLowAndSafeToSpend() {
        // Food 20.00 and a 30.00 charge: overspent, and Safe to Spend (Food is everyday) goes negative
        val over = Alerts.build(budget(20_00, Triple("t1", "SAMPLE MARKET", -30_00)), listOf(rule), listOf("t1"), Alerts.Settings())
        val w = over.single { it.kind == Alerts.Kind.WARNING }
        assertEquals(listOf("Food is overspent by \$10.00", "Safe to Spend would go negative: -\$10.00"), w.lines)
        // Food 20.00 and a 10.00 charge: 10.00 left of a 100.00 target is under 15%
        val low = Alerts.build(budget(20_00, Triple("t1", "SAMPLE MARKET", -10_00)), listOf(rule), listOf("t1"), Alerts.Settings())
        assertEquals(listOf("Food is low: \$10.00 left"), low.single { it.kind == Alerts.Kind.WARNING }.lines)
        // already low before: no new warning
        val again = Alerts.build(budget(12_00, Triple("t1", "SAMPLE MARKET", -1_00)), listOf(rule), listOf("t1"), Alerts.Settings())
        assertTrue(again.none { it.kind == Alerts.Kind.WARNING })
    }

    @Test fun eachToggleTurnsItsAlertOff() {
        val b = budget(20_00, Triple("t1", "SAMPLE MARKET", -30_00))
        assertTrue(Alerts.build(b, listOf(rule), listOf("t1"), Alerts.Settings(spending = false)).none { it.kind == Alerts.Kind.SPENDING })
        val noLow = Alerts.build(b, listOf(rule), listOf("t1"), Alerts.Settings(lowOrOverspent = false)).single { it.kind == Alerts.Kind.WARNING }
        assertEquals(listOf("Safe to Spend would go negative: -\$10.00"), noLow.lines)
        assertTrue(Alerts.build(b, listOf(rule), listOf("t1"), Alerts.Settings(spending = false, lowOrOverspent = false, safeToSpend = false)).isEmpty())
    }

    @Test fun autoSortedChargesAreReportedAndNotDoubleCounted() {
        val b = budget(80_00, Triple("t1", "SAMPLE MARKET", -20_00), sortedAuto = setOf("t1"))
        val n = Alerts.build(b, listOf(rule), listOf("t1"), Alerts.Settings()).single()
        assertEquals(listOf("In Food, \$60.00 left"), n.lines)
        assertTrue("already sorted: nothing to approve", n.approveIds.isEmpty())
    }

    @Test fun incomeAndHistoryAreNotAlerts() {
        val pay = F.tx("p", "chk", 900_00).copy(importDescription = "SAMPLE PAYROLL")
        val b = Budget(listOf(F.account("chk")), listOf(F.rollover()), listOf(pay), listOf(F.line("p", 900_00)), emptyList())
        assertTrue(Alerts.build(b, emptyList(), listOf("p"), Alerts.Settings()).isEmpty())
    }
}
