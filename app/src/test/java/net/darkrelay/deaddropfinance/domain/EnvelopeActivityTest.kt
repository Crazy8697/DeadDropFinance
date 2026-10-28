package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.budget
import net.darkrelay.deaddropfinance.domain.EnvelopeActivityFixture.today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An envelope's Activity list (1.2.3): spending and every money move, labels, signs, order, and the sum. Invented numbers. */
class EnvelopeActivityTest {

    @Test fun everyKindOfRowWithItsLabelSignAndOrder() {
        val rows = budget().envelopeActivity("g")
        assertEquals(
            listOf(
                "SAMPLE REFUND" to 5_00L, "SAMPLE GAME STORE" to -29_97L, "Moved in from Rollover" to 4_00L, "Moved to Ready to Assign" to -1_00L,
                "Moved from Ready to Assign" to 2_00L, "Rollover sweep" to -12_00L, "Payday auto-fill" to 20_00L, "Moved out to Food" to -3_00L,
                "Moved in from Food" to 5_00L, "Assigned" to 10_00L, "Assigned" to 40_00L, "SAMPLE OLD SHOP" to -7_00L,
            ),
            rows.map { it.label to it.amount },
        )
        val old = rows.last()
        assertTrue("listed and marked", old.beforeBudgetStart)
        assertFalse(old.counts)
        assertEquals("spending rows open their transaction", "s1", rows.first { it.label == "SAMPLE GAME STORE" }.transactionId)
        assertTrue("moves open nothing", rows.filter { it.id.startsWith("m") }.all { it.transactionId == null })
    }

    @Test fun theCountedRowsSumToTheBalance() {
        val b = budget()
        for (e in b.envelopes) assertEquals(e.name, b.envelopeBalance(e.id), b.envelopeActivity(e.id).filter { it.counts }.sumOf { it.amount })
        assertEquals(40_03L, b.envelopeBalance("g"))
    }

    @Test fun rolloverAndUnplannedHaveTheirLists() {
        val b = budget()
        assertEquals(listOf("Moved out to Gaming" to -4_00L, "From rollover sweep" to 12_00L), b.envelopeActivity("r").map { it.label to it.amount })
        assertEquals(listOf("SAMPLE SNACKS" to -9_99L), b.envelopeActivity("u").map { it.label to it.amount })
    }

    @Test fun onlyATargetMeansNoRowsAndOnlyAnAssignIsOneRow() {
        assertTrue(budget().envelopeActivity("t").isEmpty())
        assertEquals(listOf("Assigned" to 30_00L), budget().envelopeActivity("o").map { it.label to it.amount })
    }

    @Test fun sameDaySameTimeFallsBackToTheIdSoTheOrderIsStable() {
        val d = today.minusDays(2)
        val extra = listOf("x2", "x1", "x3").map { MoveEntity(id = it, fromEnvelopeId = null, toEnvelopeId = "t", amount = 1_00, date = d, kind = MoveKind.ASSIGN, createdAt = 5, updatedAt = 5) }
        assertEquals(listOf("x3", "x2", "x1"), budget(extra).envelopeActivity("t").map { it.id })
    }

    @Test fun aLongHistoryIsQuickToWorkOut() {
        val extra = (1..5000).map { MoveEntity(id = "x$it", fromEnvelopeId = null, toEnvelopeId = "t", amount = 1, date = today.minusDays(it % 300L), kind = MoveKind.ASSIGN, createdAt = it.toLong(), updatedAt = 1) }
        val b = budget(extra)
        b.envelopeActivity("t")
        val t0 = System.nanoTime()
        val rows = b.envelopeActivity("t")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(5000, rows.size)
        assertTrue("took $ms ms", ms < 500)
    }
}
