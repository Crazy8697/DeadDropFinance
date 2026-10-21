package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Payee cleaning, rule matching and priority, split shares. Invented payees and amounts only. */
class PayeeRuleTest {

    private fun rule(id: String, text: String, splits: String = "e1:1", priority: Int = 0, direction: Int = -1, account: String? = null,
                     min: Long? = null, max: Long? = null, action: RuleAction = RuleAction.ENVELOPE, ask: Boolean = false) =
        RuleEntity(id = id, matchText = Payees.clean(text), splits = splits, priority = priority, direction = direction, accountId = account,
            minAmount = min, maxAmount = max, action = action, alwaysAsk = ask, createdAt = priority.toLong(), updatedAt = 0)

    @Test fun cleaningStripsCardNoiseStoreNumbersDatesAndCodes() {
        val same = listOf(
            "POS PURCHASE SAMPLE MARKET #0412 ANYTOWN CA 03/14",
            "DEBIT CARD PURCHASE 03/15 SAMPLE MARKET 0412 ANYTOWN CA",
            "Sample Market #988 Anytown CA",
            "SAMPLE MARKET XXXX1234 ANYTOWN CA REF A1B2C3D4",
            "PURCHASE AUTHORIZED ON 03/16 SAMPLE MARKET 12:41 ANYTOWN CA",
        ).map { Payees.clean(it) }
        assertEquals(setOf("sample market anytown"), same.toSet())
        assertEquals("sample cafe", Payees.clean("SQ *SAMPLE CAFE"))
        assertEquals("sample grill", Payees.clean("TST* Sample Grill 2210"))
        assertEquals("joes sample shop", Payees.clean("Joe's Sample Shop"))
        assertEquals("sample store", Payees.clean("SAMPLE STORE REFUND"))
        // the merchant's own words stay, and an all-noise description still cleans to something
        assertEquals("sample store", Payees.clean("Sample Store"))
        assertTrue(Payees.clean("POS 1234").isNotEmpty())
    }

    @Test fun keyPrefersTheBanksDescription() {
        val t = F.tx("t", "a", -5_00, payee = "My nickname").copy(importDescription = "CARD 03/01 SAMPLE CAFE 77")
        assertEquals("sample cafe", Payees.key(t))
        assertEquals(listOf("sample cafe", "my nickname"), Payees.forms(t))
        assertTrue(Payees.contains("sample cafe anytown", "sample cafe"))
        assertFalse(Payees.contains("sample cafeteria", "sample cafe"))
        assertTrue(Payees.sameMerchant("sample store", "sample store anytown"))
        assertFalse(Payees.sameMerchant("sample store", "sample storage"))
    }

    @Test fun matchingUsesPayeeAccountDirectionAndAmountRange() {
        val t = F.tx("t", "chk", -25_00).copy(importDescription = "POS SAMPLE MARKET #12")
        assertTrue(Rules.matches(rule("r", "sample market"), t))
        assertTrue(Rules.matches(rule("r", "SAMPLE MARKET #99"), t))                 // cleaned the same way
        assertFalse(Rules.matches(rule("r", "other market"), t))
        assertFalse(Rules.matches(rule("r", "sample market", account = "sav"), t))
        assertTrue(Rules.matches(rule("r", "sample market", account = "chk"), t))
        assertFalse(Rules.matches(rule("r", "sample market", direction = 1), t))   // money in only
        assertTrue(Rules.matches(rule("r", "sample market", direction = 0), t))
        assertTrue(Rules.matches(rule("r", "sample market", min = 20_00, max = 30_00), t))
        assertFalse(Rules.matches(rule("r", "sample market", min = 30_00), t))
        assertFalse(Rules.matches(rule("r", "sample market", max = 20_00), t))
    }

    @Test fun firstRuleByPriorityWinsAndSkipsOnesThatCantApply() {
        val e1 = F.envelope("e1"); val e2 = F.envelope("e2"); val gone = F.envelope("e3").copy(archived = true)
        val t = F.tx("t", "chk", -40_00).copy(importDescription = "SAMPLE MARKET")
        val b = Budget(listOf(F.account("chk", 100_00)), listOf(F.rollover(), e1, e2, gone), listOf(t), listOf(F.line("t", -40_00)), emptyList())
        val broad = rule("broad", "sample", splits = "e1:1", priority = 1)
        val narrow = rule("narrow", "sample market", splits = "e2:1", priority = 0)
        val archived = rule("arch", "sample market", splits = "e3:1", priority = -1)
        val s = Suggest.forTx(b, listOf(broad, narrow, archived), t)!!
        assertEquals(Suggest.Basis.RULE, s.basis)
        assertEquals("narrow", s.rule!!.id)                                         // the archived envelope's rule is skipped
        assertEquals("e2", s.lines.single().envelopeId)
        // reordered: the broad one first
        assertEquals("broad", Suggest.forTx(b, listOf(broad.copy(priority = -5), narrow, archived), t)!!.rule!!.id)
    }

    @Test fun splitRulesShareTheAmountExactly() {
        assertEquals(listOf(-60_00L, -40_00L), Rules.allocate(-100_00, listOf(60, 40)))
        assertEquals(listOf(34L, 33L, 33L), Rules.allocate(100, listOf(1, 1, 1)))
        assertEquals(-7_77L, Rules.allocate(-7_77, listOf(50_00, 30_00, 20_00)).sum())
        val r = rule("r", "sample phone", splits = "e1:50_00;e2:30_00".replace("_", ""))
        val t = F.tx("t", "chk", -96_00).copy(importDescription = "SAMPLE PHONE")
        val lines = Rules.linesFor(r, t, setOf("e1", "e2"))!!
        assertEquals(listOf("e1" to -60_00L, "e2" to -36_00L), lines.map { it.envelopeId to it.amount })
        assertNull("an envelope that's gone", Rules.linesFor(r, t, setOf("e1")))
    }

    @Test fun transferAndReadyToAssignRules() {
        val tr = rule("t", "sample savings", splits = "", action = RuleAction.TRANSFER, direction = 0)
        val out = F.tx("o", "chk", -200_00).copy(importDescription = "TRANSFER TO SAMPLE SAVINGS")
        assertTrue(Rules.linesFor(tr, out, emptySet())!!.single().transfer)
        val pay = rule("p", "sample employer", splits = "rta:1", direction = 1)
        val inc = F.tx("i", "chk", 900_00).copy(importDescription = "SAMPLE EMPLOYER PAYROLL")
        assertTrue(Rules.linesFor(pay, inc, emptySet())!!.single().toReadyToAssign)
        assertNull("spending never goes to Ready to Assign", Rules.linesFor(pay.copy(direction = 0), F.tx("x", "chk", -1_00), emptySet()))
    }

    @Test fun splitsTextRoundTrips() {
        val s = listOf(Rules.Split("abc-1", 3), Rules.Split(Rules.RTA, 1))
        assertEquals(s, Rules.parse(Rules.format(s)))
        assertEquals(emptyList<Rules.Split>(), Rules.parse("broken;x:0;:5"))
    }

    @Test fun describeInWords() {
        val r = rule("r", "sample market", splits = "e1:1", min = 10_00, max = 40_00, account = "chk", ask = true)
        assertEquals("Envelope e1", Rules.describe(r) { "Envelope $it" })
        assertEquals("money out, $10.00 to $40.00, Checking only, always asks", Rules.conditions(r) { "Checking" })
        assertEquals(EnvelopeMode.FILL_TO_TARGET, F.envelope("e1").mode)
    }
}
