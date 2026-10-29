package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.MetaEntity
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.RuleOrigin
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft

/**
 * Sorting what's in To sort: approving suggestions (one, all, or automatically for rule matches), sorting by hand
 * with learning, returns, and the rules themselves. Every write goes through BudgetActions and BudgetStore, so it's
 * one atomic write with its change log rows.
 */
class Sorting(private val store: BudgetStore, private val actions: BudgetActions = BudgetActions(store)) {

    private val dao get() = store.dao

    suspend fun rules(): List<RuleEntity> = dao.rulesNow()

    // ---- approving ----------------------------------------------------------------------------------------------

    /** Approves [txId]'s suggestion. False when it has none (or isn't in To sort any more). */
    suspend fun approve(txId: String): Boolean {
        val b = actions.snapshot()
        val t = b.toSort.firstOrNull { it.id == txId } ?: return false
        val s = Suggest.forTx(b, rules(), t) ?: return false
        apply(t, s, if (s.basis == Suggest.Basis.RETURN) SortedBy.RETURN else SortedBy.APPROVED)
        learnAfter(txId)
        return true
    }

    /** How many in To sort have a suggestion "Approve all suggested" would take. */
    suspend fun suggestedCount(): Int = Suggest.all(actions.snapshot(), rules()).size

    /** Approves every suggestion in To sort (or only those in [ids]). Returns how many were sorted. */
    suspend fun approveAll(ids: Collection<String>? = null): Int {
        val b = actions.snapshot()
        val all = Suggest.all(b, rules())
        var n = 0
        for ((id, s) in all) {
            if (ids != null && id !in ids) continue
            val t = b.toSort.first { it.id == id }
            runCatching { apply(t, s, if (s.basis == Suggest.Basis.RETURN) SortedBy.RETURN else SortedBy.APPROVED) }.onSuccess { n++ }
        }
        return n
    }

    /**
     * Auto-approve (a setting, off by default): of the new transactions [ids], those a rule matches are sorted as
     * they arrive, unless the rule always asks. History and return suggestions still wait for a tap. Returns how many.
     */
    suspend fun autoApprove(ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val b = actions.snapshot()
        val rules = rules()
        var n = 0
        for (t in b.toSort) {
            if (t.id !in ids) continue
            val s = Suggest.forTx(b, rules, t) ?: continue
            if (!s.autoApprovable) continue
            runCatching { apply(t, s, SortedBy.AUTO) }.onSuccess { n++ }
        }
        return n
    }

    private suspend fun apply(t: TransactionEntity, s: Suggest.Suggestion, by: SortedBy) =
        actions.setLines(t.id, s.lines, by, ruleId = s.rule?.id, returnOf = s.returnOf?.id)

    /**
     * History before the budget start (1.1.1) isn't sorted, but a payee rule that sets a category still labels it, as it
     * would any purchase: the first rule that matches, if it has a category, gives it to each line that has none.
     * Nothing else changes. [ids] limits it to those transactions. Returns how many were labeled.
     */
    suspend fun labelHistory(ids: Collection<String>? = null): Int {
        val b = actions.snapshot()
        val rules = rules()
        if (rules.none { it.categoryId != null }) return 0
        var n = 0
        for (t in b.transactions) {
            if (ids != null && t.id !in ids) continue
            if (!b.beforeBudgetStart(t) || t.kind != net.darkrelay.deaddropfinance.data.TransactionKind.NORMAL || t.amount >= 0 || t.isWash) continue
            val open = b.lines.filter {
                it.transactionId == t.id && it.categoryId == null &&
                    (it.bucket == net.darkrelay.deaddropfinance.data.LineBucket.ENVELOPE || it.bucket == net.darkrelay.deaddropfinance.data.LineBucket.UNSORTED)
            }
            if (open.isEmpty()) continue
            val cat = rules.firstOrNull { Rules.matches(it, t) }?.categoryId?.takeIf { b.category(it) != null } ?: continue
            open.forEach { actions.setLineCategory(it.id, cat) }
            n++
        }
        return n
    }

    // ---- by hand, with learning ---------------------------------------------------------------------------------

    /** After sorting by hand: nothing, an offer to make a rule, or a rule just made from repeated choices. */
    sealed interface Learned {
        /** "Always put <payee> in <envelope>?" Offered once per payee. */
        data class Offer(val key: String, val target: String, val direction: Int) : Learned
        /** A rule made from the user's last [count] identical choices, shown once so it can be undone. */
        data class Made(val rule: RuleEntity, val count: Int) : Learned
    }

    /** Sorts a To sort transaction by hand into one place, then learns from it. */
    suspend fun sortByHand(txId: String, envelopeId: String?, toReadyToAssign: Boolean = false, transfer: Boolean = false): Learned? {
        actions.sortInto(txId, envelopeId, toReadyToAssign, transfer)
        return learnAfter(txId)
    }

    /** Sorts a To sort purchase by hand by its category, then learns from it. */
    suspend fun sortByCategory(txId: String, categoryId: String): Learned? {
        actions.sortIntoCategory(txId, categoryId)
        return learnAfter(txId)
    }

    /**
     * Learning, after a transaction was sorted by hand or its suggestion approved: when the user's last
     * [LEARN_AFTER] choices for the same payee all went to the same single place and no rule covers the payee, a
     * rule is made (and shown so it can be undone). After a first choice by hand, a rule is offered once instead.
     * A payee the user said "no" to is never learned.
     */
    suspend fun learnAfter(txId: String): Learned? {
        val b = actions.snapshot()
        val t = b.transactions.firstOrNull { it.id == txId } ?: return null
        // only what came from the bank (sync or CSV) is learned; something entered by hand is already where it goes
        if (t.source == net.darkrelay.deaddropfinance.data.TransactionSource.MANUAL) return null
        if (!b.isOnBudget(t.accountId) || b.beforeStart(t) || t.returnOf != null || t.isWash) return null
        val key = Payees.key(t)
        if (key.isEmpty()) return null
        if (dao.meta(DECLINED + key) != null) return null
        val rules = rules()
        if (rules.any { Rules.matches(it, t) }) return null
        val target = singleTarget(b, t) ?: return null
        val direction = if (t.amount < 0) -1 else 1
        val choices = b.transactions.asSequence()
            .filter { it.sortedBy == SortedBy.HAND || it.sortedBy == SortedBy.APPROVED }
            .filter { (it.amount < 0) == (t.amount < 0) && b.isOnBudget(it.accountId) && Payees.key(it) == key }
            .sortedWith(compareByDescending<TransactionEntity> { it.sortedAt ?: 0L }.thenByDescending { it.createdAt })
            .take(LEARN_AFTER)
            .map { singleTarget(b, it) }
            .toList()
        if (choices.size >= LEARN_AFTER && choices.all { it == target }) {
            val r = saveRule(null, RuleDraft(key, splits = listOf(Rules.Split(target, 1)), direction = direction, action = if (target == TRANSFER) RuleAction.TRANSFER else RuleAction.ENVELOPE), RuleOrigin.LEARNED)
            return Learned.Made(dao.rule(r)!!, LEARN_AFTER)
        }
        if (t.sortedBy == SortedBy.HAND && dao.meta(OFFERED + key) == null) {
            dao.putMeta(MetaEntity(OFFERED + key, "1"))
            return Learned.Offer(key, target, direction)
        }
        return null
    }

    /** The one place a transaction's money went: an envelope id, [Rules.RTA] or [TRANSFER]; null for a split. */
    private fun singleTarget(b: Budget, t: TransactionEntity): String? {
        val l = b.lines.filter { it.transactionId == t.id }.singleOrNull() ?: return null
        return when (l.bucket) {
            net.darkrelay.deaddropfinance.data.LineBucket.ENVELOPE -> l.envelopeId
            net.darkrelay.deaddropfinance.data.LineBucket.READY_TO_ASSIGN -> Rules.RTA
            net.darkrelay.deaddropfinance.data.LineBucket.TRANSFER -> TRANSFER
            else -> null
        }
    }

    /** The offer's Yes: makes the rule (the user's own, so no notice). */
    suspend fun acceptOffer(o: Learned.Offer): String = saveRule(
        null,
        RuleDraft(o.key, splits = listOf(Rules.Split(o.target, 1)), direction = o.direction, action = if (o.target == TRANSFER) RuleAction.TRANSFER else RuleAction.ENVELOPE),
    )

    /** The offer's No: this payee is never offered or learned again. */
    suspend fun declineOffer(o: Learned.Offer) = dao.putMeta(MetaEntity(DECLINED + o.key, "1"))

    // ---- returns --------------------------------------------------------------------------------------------------

    /** Confirms [creditId] as a return of [purchaseId]: the money goes back to the purchase's envelopes. */
    suspend fun confirmReturn(creditId: String, purchaseId: String) {
        val b = actions.snapshot()
        val credit = b.transactions.firstOrNull { it.id == creditId } ?: throw BudgetRuleException("That transaction is gone.")
        if (credit.amount <= 0) throw BudgetRuleException("Only money coming in can be a return.")
        if (credit.isPayday) throw BudgetRuleException("Unmark the payday first.")
        val purchase = b.transactions.firstOrNull { it.id == purchaseId } ?: throw BudgetRuleException("That purchase is gone.")
        val left = -purchase.amount - b.transactions.filter { it.returnOf == purchaseId && it.id != creditId }.sumOf { it.amount }
        if (credit.amount > left) throw BudgetRuleException("That's more than is left to return on this purchase (${Money.format(left)}).")
        val lines = Suggest.returnLines(b, credit, purchase) ?: throw BudgetRuleException("Sort the purchase into an envelope first.")
        actions.setLines(creditId, lines, SortedBy.RETURN, returnOf = purchaseId)
    }

    /** Not a return after all: back to To sort. */
    suspend fun notAReturn(creditId: String) = actions.unsort(creditId)

    // ---- rules ----------------------------------------------------------------------------------------------------

    data class RuleDraft(
        val matchText: String,
        val accountId: String? = null,
        val direction: Int = -1,
        val minAmount: Long? = null,
        val maxAmount: Long? = null,
        val action: RuleAction = RuleAction.ENVELOPE,
        val splits: List<Rules.Split> = emptyList(),
        val alwaysAsk: Boolean = false,
        /** A match counts as a payment toward this bill. */
        val billId: String? = null,
        /** The category a match gets (money out only). With no envelope in [splits], a match sorts by the category's claim. */
        val categoryId: String? = null,
    )

    /** Checks a rule before it's saved: the same checks the editor shows. */
    fun check(d: RuleDraft) {
        if (Payees.words(d.matchText).isEmpty()) throw BudgetRuleException("Enter part of the payee's name.")
        if (d.minAmount != null && d.minAmount < 0 || d.maxAmount != null && d.maxAmount < 0) throw BudgetRuleException("Amounts here can't be negative.")
        if (d.minAmount != null && d.maxAmount != null && d.minAmount > d.maxAmount) throw BudgetRuleException("The lowest amount is above the highest.")
        if (d.action == RuleAction.ENVELOPE) {
            if (d.splits.isEmpty() && d.categoryId == null) throw BudgetRuleException("Choose an envelope or a category.")
            if (d.categoryId != null && d.direction >= 0) throw BudgetRuleException("A category is for spending. Make the rule for money out, or take the category off.")
            if (d.splits.isNotEmpty() && d.splits.any { it.weight <= 0 }) throw BudgetRuleException("Every split line needs an amount.")
            if (d.splits.map { it.target }.distinct().size != d.splits.size) throw BudgetRuleException("An envelope is in the split twice.")
            if (d.direction <= 0 && d.splits.any { it.target == Rules.RTA }) throw BudgetRuleException("Spending can't go to Ready to Assign. Choose an envelope, or make the rule for money in.")
        }
    }

    /**
     * Saves a rule. A new one made by the user goes first in priority (it's usually the more specific one); a learned
     * one goes last. Returns its id.
     */
    suspend fun saveRule(id: String?, d: RuleDraft, origin: RuleOrigin = RuleOrigin.USER): String {
        check(d)
        return store.write {
            val old = id?.let { dao.rule(it) }
            val all = dao.rulesNow()
            val priority = when {
                old != null -> old.priority
                origin == RuleOrigin.LEARNED -> (all.maxOfOrNull { it.priority } ?: -1) + 1
                else -> {
                    // first: everything else moves down one
                    all.forEach { r -> put(r, r.copy(priority = r.priority + 1, updatedAt = now)) }
                    0
                }
            }
            val base = old ?: RuleEntity(matchText = "", origin = origin, acknowledged = origin != RuleOrigin.LEARNED, createdAt = now, updatedAt = now)
            put(
                old,
                base.copy(
                    matchText = Payees.clean(d.matchText), accountId = d.accountId, direction = d.direction, minAmount = d.minAmount,
                    maxAmount = d.maxAmount, action = d.action, splits = if (d.action == RuleAction.ENVELOPE) Rules.format(d.splits) else "",
                    alwaysAsk = d.alwaysAsk, priority = priority, billId = d.billId, categoryId = d.categoryId.takeIf { d.action == RuleAction.ENVELOPE }, updatedAt = now,
                ),
            ).id
        }.also { if (d.categoryId != null) runCatching { labelHistory() } }
    }

    suspend fun deleteRule(id: String) = store.write {
        val old = dao.rule(id) ?: return@write
        put(old, old.copy(deleted = true, updatedAt = now))
    }

    /** Moves a rule one place up (earlier, tried first) or down in priority. */
    suspend fun moveRule(id: String, up: Boolean) = store.write {
        val all = dao.rulesNow()
        val i = all.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in all.indices) return@write
        val order = all.toMutableList().apply { add(j, removeAt(i)) }
        order.forEachIndexed { n, r -> if (r.priority != n) put(r, r.copy(priority = n, updatedAt = now)) }
    }

    /** A learned rule's notice was seen (OK). */
    suspend fun acknowledge(id: String) = store.write {
        val old = dao.rule(id) ?: return@write
        if (!old.acknowledged) put(old, old.copy(acknowledged = true, updatedAt = now))
    }

    /** A learned rule's Undo: the rule goes, and the payee isn't learned again. */
    suspend fun undoLearned(id: String) {
        val r = dao.rule(id) ?: return
        deleteRule(id)
        dao.putMeta(MetaEntity(DECLINED + r.matchText, "1"))
    }

    companion object {
        /** Identical choices in a row that make a rule by themselves. */
        const val LEARN_AFTER = 3
        /** A rule target meaning "transfer between my accounts" (learning only; rules use their action). */
        const val TRANSFER = "transfer"
        private const val OFFERED = "learn.offered:"
        private const val DECLINED = "learn.declined:"
    }
}
