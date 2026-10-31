package net.darkrelay.deaddropfinance.domain

import kotlin.math.abs

/**
 * Tax letters on receipts (1.9.3): each item line can end in a letter ("LIME JUICE 1.99 A") that says which tax it
 * carries, and the tax lines at the bottom say how much tax each letter came to. This works out what each item really
 * cost: its shelf price plus its share of its letter's tax. Items with no letter, or whose letter is untaxed, stay at
 * their shelf price. Pure; cents only, shares by largest remainder so a letter's shares always add up to its tax.
 *
 * It never guesses: when the letters can't be matched to the tax lines for sure, [Result.needsChoice] lists the
 * letters to ask about, and the items stay at their shelf prices until the user marks which are taxed.
 */
object ReceiptTax {

    /** One item as the form has it: its [letter] ("" for none), shelf [amount], and the user's mark (taxed or not) once they've made one. */
    data class In(val letter: String, val amount: Long, val taxed: Boolean? = null)

    data class Result(
        /** Each item's price with its tax (the same order as the input); the shelf price when it carries no tax. */
        val withTax: List<Long>,
        /** Letters that couldn't be matched to a tax line: ask the user which are taxed. Empty when everything matched. */
        val needsChoice: Set<String>,
        /** Tax given to items, in cents. */
        val distributed: Long,
        /** Tax on the receipt that no item carries (no letters on the items, or not yet chosen). */
        val unassigned: Long,
        /** True when the items carry any letters at all. */
        val hasLetters: Boolean,
    ) {
        val resolved get() = needsChoice.isEmpty()
    }

    /**
     * [tax] is the receipt's tax in total (what the form says); [lines] are the tax lines read at the bottom.
     * A letter's tax comes from the line that names it. A line that names no letter goes to the one letter left, or to
     * the one set of leftover letters whose items at that line's rate come to its amount; anything else is a question.
     */
    fun work(items: List<In>, lines: List<ReceiptParser.TaxLine>, tax: Long?): Result {
        val totalTax = tax ?: lines.sumOf { it.amount }
        val letters = items.map { it.letter }.filter { it.isNotEmpty() }.toSet()
        val shelf = items.map { it.amount }
        if (totalTax <= 0 || letters.isEmpty()) return Result(shelf, emptySet(), 0, totalTax.coerceAtLeast(0), letters.isNotEmpty())

        // 1. a letter's tax is the line that names it
        val given = linkedMapOf<String, Long>()
        for (l in lines) if (l.letter != null && l.letter in letters) given.merge(l.letter, l.amount, Long::plus)
        var pool = totalTax - given.values.sum()
        val rest = letters - given.keys
        val sums = letters.associateWith { l -> items.filter { it.letter == l }.sumOf { it.amount } }

        // 2. what's left goes to the one letter left, or to the one set of letters it adds up for at the line's rate
        var question = emptySet<String>()
        if (pool > 0 && rest.isNotEmpty()) {
            val rate = lines.filter { it.letter == null || it.letter !in letters }.mapNotNull { it.rateMilli }.distinct().singleOrNull()
            val pick = if (rest.size == 1) rest else if (rate != null) matching(rest, sums, items, rate, pool) else null
            if (pick == null) question = rest
            else if (pick.isNotEmpty()) {
                val shares = largestRemainder(pool, pick.map { sums.getValue(it).coerceAtLeast(0) })
                pick.forEachIndexed { k, l -> given[l] = shares[k] }
                pool = 0
            }
        }

        // 3. a question is answered by the user's marks: the left over tax goes to the items marked taxed
        var chosen = emptyList<Int>()
        if (question.isNotEmpty()) {
            val asked = items.indices.filter { items[it].letter in question }
            if (asked.any { items[it].taxed == null }) return build(items, given, emptyList(), 0, question)
            chosen = asked.filter { items[it].taxed == true }
        }
        return build(items, given, chosen, pool, emptySet())
    }

    private fun build(items: List<In>, byLetter: Map<String, Long>, chosen: List<Int>, pool: Long, question: Set<String>): Result {
        val out = items.map { it.amount }.toMutableList()
        var carried = 0L
        for ((l, t) in byLetter) {
            val idx = items.indices.filter { items[it].letter == l }
            val shares = largestRemainder(t, idx.map { items[it].amount.coerceAtLeast(0) })
            idx.forEachIndexed { k, i -> out[i] += shares[k]; carried += shares[k] }
        }
        if (chosen.isNotEmpty() && pool > 0) {
            val shares = largestRemainder(pool, chosen.map { items[it].amount.coerceAtLeast(0) })
            chosen.forEachIndexed { k, i -> out[i] += shares[k]; carried += shares[k] }
        }
        val all = byLetter.values.sum() + pool
        return Result(out, question, carried, (all - carried).coerceAtLeast(0), true)
    }

    /** The leftover letters whose items at [rateMilli] come to [pool], when exactly one set does; else null. */
    private fun matching(rest: Set<String>, sums: Map<String, Long>, items: List<In>, rateMilli: Long, pool: Long): Set<String>? {
        val ls = rest.toList()
        if (ls.size > 8) return null
        val found = mutableListOf<Set<String>>()
        for (mask in 1 until (1 shl ls.size)) {
            val set = ls.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
            val expect = (set.sumOf { sums.getValue(it) } * rateMilli + 50_000) / 100_000
            if (abs(expect - pool) <= maxOf(2L, items.count { it.letter in set }.toLong())) found += set
        }
        return found.singleOrNull()
    }

    /** [amount] split in proportion to [weights], in whole cents, adding up to exactly [amount] (an even split when all weights are zero). */
    fun largestRemainder(amount: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val sum = weights.sum()
        if (sum <= 0) {
            val each = amount / weights.size
            return weights.indices.map { if (it < (amount - each * weights.size)) each + 1 else each }
        }
        val base = weights.map { amount * it / sum }
        var left = amount - base.sum()
        val order = weights.indices.sortedByDescending { amount * weights[it] % sum }
        val out = base.toMutableList()
        for (i in order) { if (left <= 0) break; out[i] += 1; left-- }
        return out
    }
}
