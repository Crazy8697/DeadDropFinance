package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity

/**
 * An envelope for each receipt item (1.9.4), guessed for the form: the envelope last picked for an item of the same name
 * (receipt items keep their envelope, and sync like the rest of the budget, so every device learns the same picks);
 * else one for the receipt section the item was under; else none. Only live envelopes that can hold spending are guessed.
 *
 * 1.9.5, a section's envelope, for any user's envelope names: first an envelope whose name shares a word with the
 * section ("HOUSEHOLD" and "Household items"); then a small built-in map of what the common sections are (GROCERY, MEAT,
 * PRODUCE... toward groceries or food; HOME, HOUSEHOLD, HBA, PAPER, CLEANING toward household or home; PET toward pet,
 * dog or cat...). No match, no envelope.
 */
object ItemEnvelopes {

    /** Section words to the words an envelope for them is likely to have in its name. */
    private val sectionMap: List<Pair<Set<String>, List<String>>> = listOf(
        ReceiptParser.foodSections to listOf("grocer", "food"),
        setOf("HOME", "HOUSEHOLD", "HBA", "HBC", "PAPER", "CLEANING", "GENERAL", "MERCHANDISE", "GM") to listOf("household", "home"),
        setOf("PET", "PETS") to listOf("pet", "dog", "cat"),
        setOf("HEALTH", "BEAUTY", "PHARMACY", "RX", "PERSONAL", "CARE") to listOf("health", "pharmacy", "medical", "personal", "beauty"),
        setOf("BABY", "KIDS", "TOYS") to listOf("baby", "kid", "child", "toy"),
        setOf("BEER", "WINE", "LIQUOR", "ALCOHOL", "SPIRITS") to listOf("alcohol", "beer", "wine", "liquor", "drinks"),
        setOf("APPAREL", "CLOTHING") to listOf("cloth", "apparel"),
        setOf("ELECTRONICS") to listOf("electronic", "tech", "gadget"),
        setOf("GARDEN", "HARDWARE", "AUTOMOTIVE") to listOf("garden", "hardware", "car", "auto", "home"),
        setOf("OFFICE", "SCHOOL") to listOf("office", "school"),
        setOf("ENTERTAINMENT", "SPORTS", "SEASONAL") to listOf("fun", "entertainment", "hobby", "sport"),
    )

    fun guess(text: String, section: String, past: List<ReceiptItemEntity>, envelopes: List<EnvelopeEntity>): String? {
        val live = envelopes.filter { !it.deleted && !it.archived && !it.isSystem }.associateBy { it.id }
        val name = ReceiptParser.cleanName(text)
        if (name.isNotBlank()) {
            past.filter { !it.deleted && it.envelopeId != null && it.envelopeId in live && it.name == name }
                .maxByOrNull { it.updatedAt }?.let { return it.envelopeId }
        }
        return forSection(section, live.values)
    }

    /** The envelope for a receipt section: one sharing a word with it, else one the built-in map points to; else none. */
    fun forSection(section: String, envelopes: Collection<EnvelopeEntity>): String? {
        val words = sectionWords(section)
        if (words.isEmpty()) return null
        val ordered = envelopes.sortedBy { it.sortOrder }
        fun envWords(e: EnvelopeEntity) = e.name.uppercase().split(Regex("""[^A-Z]+""")).filter { it.length >= 3 }
        ordered.firstOrNull { e -> envWords(e).any { w -> words.any { s -> s.length >= 3 && (w == s || w.removeSuffix("S") == s.removeSuffix("S")) } } }?.let { return it.id }
        for ((sections, hints) in sectionMap) {
            if (words.none { it in sections }) continue
            for (h in hints) ordered.firstOrNull { it.name.contains(h, ignoreCase = true) }?.let { return it.id }
        }
        return null
    }

    private fun sectionWords(section: String) = section.uppercase().split(Regex("""[\s/&,+-]+""")).map { it.trim('.', ':') }.filter { it.isNotBlank() }

    /** "FROZEN/DAIRY", "MEAT", "PRODUCE": a section whose items are food. */
    fun isFood(section: String): Boolean = sectionWords(section).any { it in ReceiptParser.foodSections }

    /** The groceries envelope: one named for groceries, first in fill order. */
    fun groceries(envelopes: Collection<EnvelopeEntity>): String? =
        envelopes.filter { it.name.contains("grocer", ignoreCase = true) }.minByOrNull { it.sortOrder }?.id
}
