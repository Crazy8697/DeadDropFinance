package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft

/** Reading and applying auto-categorize rules. Pure functions: nothing here writes. */
object Rules {

    /** Ready to Assign in a rule's splits (income rules). */
    const val RTA = "rta"

    /** One destination of a rule: an envelope id (or [RTA]) and its weight. */
    data class Split(val target: String, val weight: Long)

    fun splits(r: RuleEntity): List<Split> = parse(r.splits)

    fun parse(text: String): List<Split> = text.split(';').mapNotNull { part ->
        val i = part.lastIndexOf(':')
        if (i <= 0) return@mapNotNull null
        val w = part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null
        if (w <= 0) null else Split(part.substring(0, i), w)
    }

    fun format(splits: List<Split>): String = splits.joinToString(";") { "${it.target}:${it.weight}" }

    /** The rule matches [t]: its words are in the payee, and the account, direction and amount fit. */
    fun matches(r: RuleEntity, t: TransactionEntity, forms: List<String> = Payees.forms(t)): Boolean {
        if (r.deleted || r.matchText.isBlank()) return false
        if (r.accountId != null && r.accountId != t.accountId) return false
        if (r.direction < 0 && t.amount >= 0) return false
        if (r.direction > 0 && t.amount <= 0) return false
        val abs = kotlin.math.abs(t.amount)
        if (r.minAmount != null && abs < r.minAmount) return false
        if (r.maxAmount != null && abs > r.maxAmount) return false
        return forms.any { Payees.contains(it, r.matchText) }
    }

    /**
     * The lines a rule gives [t], or null if it can't apply (an envelope it names is gone or archived, Ready to
     * Assign for spending). A split shares the amount in proportion to its weights, to the cent.
     */
    fun linesFor(r: RuleEntity, t: TransactionEntity, liveEnvelopes: Set<String>, categoryEnvelope: (String) -> String? = { null }): List<LineDraft>? {
        if (r.action == RuleAction.TRANSFER) return listOf(LineDraft(null, t.amount, transfer = true))
        val s = splits(r)
        val cat = r.categoryId?.takeIf { t.amount < 0 }
        if (s.isEmpty()) {
            // a category and no envelope: it sorts by the category's claim (the claiming envelope, or Unplanned)
            val env = cat?.let(categoryEnvelope)?.takeIf { it in liveEnvelopes } ?: return null
            return listOf(LineDraft(env, t.amount, categoryId = cat))
        }
        if (s.any { it.target != RTA && it.target !in liveEnvelopes }) return null
        if (t.amount < 0 && s.any { it.target == RTA }) return null
        val amounts = allocate(t.amount, s.map { it.weight })
        return s.zip(amounts).filter { it.second != 0L }.map { (sp, a) ->
            if (sp.target == RTA) LineDraft(null, a, toReadyToAssign = true) else LineDraft(sp.target, a, categoryId = cat)
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * Shares [amount] (either sign) in proportion to [weights], exactly: the cents left after rounding down go to
     * the biggest shares first, so the parts always add up to the amount.
     */
    fun allocate(amount: Long, weights: List<Long>): List<Long> {
        if (weights.isEmpty()) return emptyList()
        val total = weights.sum()
        if (total <= 0) return weights.map { 0L }
        val sign = if (amount < 0) -1 else 1
        val abs = kotlin.math.abs(amount)
        val parts = weights.map { w -> (abs.toBigInteger() * w.toBigInteger() / total.toBigInteger()).toLong() }.toMutableList()
        var left = abs - parts.sum()
        val order = weights.indices.sortedWith(compareByDescending<Int> { weights[it] }.thenBy { it })
        var i = 0
        while (left > 0) { parts[order[i % order.size]]++; left--; i++ }
        return parts.map { it * sign }
    }

    /** The rule's destinations in words, for lists and notices. */
    fun describe(r: RuleEntity, envelopeName: (String) -> String?): String = describe(r, envelopeName) { null }

    /** [describe] with the rule's category named too. */
    fun describe(r: RuleEntity, envelopeName: (String) -> String?, categoryName: (String) -> String?): String = when (r.action) {
        RuleAction.TRANSFER -> "Transfer between your accounts"
        RuleAction.ENVELOPE -> {
            val s = splits(r)
            fun name(t: String) = if (t == RTA) "Ready to assign" else envelopeName(t) ?: "a removed envelope"
            val cat = r.categoryId?.let { categoryName(it) }
            when {
                s.isEmpty() -> "Category $cat, by its envelope"
                s.size == 1 -> name(s[0].target) + (cat?.let { ", category $it" } ?: "")
                else -> "Split: " + s.joinToString(", ") { name(it.target) } + (cat?.let { ", category $it" } ?: "")
            }
        }
    }

    /** The conditions besides the payee, in words ("money out, $10.00 to $40.00, Checking only"). */
    fun conditions(r: RuleEntity, accountName: (String) -> String?): String {
        val parts = mutableListOf<String>()
        parts += when { r.direction < 0 -> "money out"; r.direction > 0 -> "money in"; else -> "in or out" }
        when {
            r.minAmount != null && r.maxAmount != null -> parts += "${Money.format(r.minAmount)} to ${Money.format(r.maxAmount)}"
            r.minAmount != null -> parts += "${Money.format(r.minAmount)} or more"
            r.maxAmount != null -> parts += "up to ${Money.format(r.maxAmount)}"
        }
        r.accountId?.let { parts += "${accountName(it) ?: "a removed account"} only" }
        if (r.alwaysAsk) parts += "always asks"
        return parts.joinToString(", ")
    }
}
