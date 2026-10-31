package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity

/**
 * An envelope for each receipt item (1.9.4), guessed for the form: the envelope last picked for an item of the same name
 * (receipt items keep their envelope, and sync like the rest of the budget, so every device learns the same picks);
 * else, for an item under a food section of the receipt (GROCERY, MEAT, PRODUCE, FROZEN/DAIRY...), the groceries
 * envelope when there is one; else none. Only live envelopes that can hold spending are guessed.
 */
object ItemEnvelopes {

    fun guess(text: String, section: String, past: List<ReceiptItemEntity>, envelopes: List<EnvelopeEntity>): String? {
        val live = envelopes.filter { !it.deleted && !it.archived && !it.isSystem }.associateBy { it.id }
        val name = ReceiptParser.cleanName(text)
        if (name.isNotBlank()) {
            past.filter { !it.deleted && it.envelopeId != null && it.envelopeId in live && it.name == name }
                .maxByOrNull { it.updatedAt }?.let { return it.envelopeId }
        }
        return if (isFood(section)) groceries(live.values) else null
    }

    /** "FROZEN/DAIRY", "MEAT", "PRODUCE": a section whose items are food. */
    fun isFood(section: String): Boolean =
        section.uppercase().split(Regex("""[\s/&,+-]+""")).any { it.trim('.', ':') in ReceiptParser.foodSections }

    /** The groceries envelope: one named for groceries, first in fill order. */
    fun groceries(envelopes: Collection<EnvelopeEntity>): String? =
        envelopes.filter { it.name.contains("grocer", ignoreCase = true) }.minByOrNull { it.sortOrder }?.id
}
