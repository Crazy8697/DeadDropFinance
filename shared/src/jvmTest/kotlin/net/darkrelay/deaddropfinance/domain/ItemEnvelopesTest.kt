package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 1.9.4: an envelope guessed for each receipt item: the one last picked for the same item name wins; then the groceries
 * envelope for an item under a food section; then none. Invented envelopes and items.
 */
class ItemEnvelopesTest {

    private fun env(id: String, name: String, archived: Boolean = false, order: Int = 0) =
        EnvelopeEntity(id = id, name = name, mode = EnvelopeMode.FILL_TO_TARGET, archived = archived, sortOrder = order, createdAt = 1, updatedAt = 1)
    private fun past(name: String, env: String?, at: Long) =
        ReceiptItemEntity(receiptId = "old", text = name, name = ReceiptParser.cleanName(name), amount = 1_00, envelopeId = env, createdAt = at, updatedAt = at)

    private val envelopes = listOf(env("g", "Groceries", order = 2), env("h", "Household", order = 3), env("x", "Old Groceries", archived = true, order = 1))

    @Test fun thePastPickForTheSameNameWinsOverTheSection() {
        val history = listOf(past("SAMPLE PAPER TOWELS", "g", 1), past("SAMPLE PAPER TOWELS", "h", 5))
        // under GROCERY, but last picked Household: Household
        assertEquals("h", ItemEnvelopes.guess("SAMPLE PAPER TOWELS", "GROCERY", history, envelopes))
    }

    @Test fun aFoodSectionGoesToTheGroceriesEnvelope() {
        for (s in listOf("GROCERY", "MEAT", "PRODUCE", "FROZEN/DAIRY", "DELI")) {
            assertEquals("g", ItemEnvelopes.guess("SAMPLE NEW THING", s, emptyList(), envelopes), s)
        }
    }

    @Test fun otherwiseNothingIsChosen() {
        assertNull(ItemEnvelopes.guess("SAMPLE NEW THING", "HOME", emptyList(), envelopes))
        assertNull(ItemEnvelopes.guess("SAMPLE NEW THING", "", emptyList(), envelopes))
        // no groceries envelope: nothing for a food section either
        assertNull(ItemEnvelopes.guess("SAMPLE NEW THING", "PRODUCE", emptyList(), listOf(env("h", "Household"))))
        // a past pick in an archived envelope isn't used
        assertEquals("g", ItemEnvelopes.guess("SAMPLE MILK", "DAIRY", listOf(past("SAMPLE MILK", "x", 9)), envelopes))
    }
}
