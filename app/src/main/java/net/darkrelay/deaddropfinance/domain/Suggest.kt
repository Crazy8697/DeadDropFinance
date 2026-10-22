package net.darkrelay.deaddropfinance.domain

import java.time.temporal.ChronoUnit
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft

/**
 * Suggestions for what's in To sort, worked out from the budget and the rules every time (nothing is stored, so a
 * suggestion is never stale). Only To sort is looked at, so a suggestion never touches anything already sorted, or
 * history before an account's starting balance date, or an account outside the budget (archived or track only).
 *
 * In order: a money-in transaction from a merchant with a recent purchase is a likely return; then the first rule
 * that matches; then the user's own past choices for the same payee, when they agree.
 */
object Suggest {

    enum class Basis { RETURN, RULE, BILL, HISTORY }

    data class Suggestion(
        val lines: List<LineDraft>,
        val basis: Basis,
        val rule: RuleEntity? = null,
        /** BILL: the bill this payment counts toward (its envelope is the suggestion). */
        val bill: net.darkrelay.deaddropfinance.data.BillEntity? = null,
        /** RETURN: the purchase it gives money back to. */
        val returnOf: TransactionEntity? = null,
    ) {
        /** Approve-all and auto-approve can take it without asking. */
        val autoApprovable get() = basis == Basis.RULE && rule?.alwaysAsk == false
    }

    /** Past choices that count as the user's own: by hand or approved (and anything sorted before 0.8.0). */
    private fun ownChoice(t: TransactionEntity) = t.sortedBy == null || t.sortedBy == SortedBy.HAND || t.sortedBy == SortedBy.APPROVED

    /** How many recent choices for a payee are looked at for a history suggestion (all of them must agree). */
    const val HISTORY_LOOKBACK = 3

    /** How far back a purchase can be for a credit to count as its return. */
    const val RETURN_WINDOW_DAYS = 90L

    fun all(b: Budget, rules: List<RuleEntity>): Map<String, Suggestion> {
        val ctx = Context(b, rules)
        return b.toSort.mapNotNull { t -> ctx.forTx(t)?.let { t.id to it } }.toMap()
    }

    fun forTx(b: Budget, rules: List<RuleEntity>, t: TransactionEntity): Suggestion? =
        if (b.toSort.none { it.id == t.id }) null else Context(b, rules).forTx(t)

    /** The first rule (in priority order) that matches [t] and can apply. */
    fun ruleFor(b: Budget, rules: List<RuleEntity>, t: TransactionEntity): RuleEntity? = Context(b, rules).ruleFor(t)?.first

    /**
     * Purchases a credit could be the return of, most likely first: earlier spending within the window (not more
     * than [RETURN_WINDOW_DAYS] before), already sorted into envelopes, with at least the credit's amount still
     * unreturned. The same merchant comes first. [sameMerchantOnly] for the automatic suggestion.
     */
    fun returnCandidates(b: Budget, credit: TransactionEntity, sameMerchantOnly: Boolean = false): List<TransactionEntity> {
        if (credit.amount <= 0 || credit.kind != TransactionKind.NORMAL || credit.isWash) return emptyList()
        val key = Payees.key(credit)
        val linesByTx = b.lines.groupBy { it.transactionId }
        val returned = b.transactions.filter { it.returnOf != null && it.id != credit.id }.groupBy({ it.returnOf!! }, { it.amount }).mapValues { it.value.sum() }
        return b.transactions.asSequence()
            .filter { p ->
                p.id != credit.id && p.kind == TransactionKind.NORMAL && p.amount < 0 && !p.isWash && b.isOnBudget(p.accountId) && !b.beforeStart(p) &&
                    !p.date.isAfter(credit.date) && ChronoUnit.DAYS.between(p.date, credit.date) <= RETURN_WINDOW_DAYS &&
                    -p.amount - (returned[p.id] ?: 0L) >= credit.amount &&
                    linesByTx[p.id].orEmpty().let { ls -> ls.isNotEmpty() && ls.any { it.bucket == LineBucket.ENVELOPE } && ls.none { it.bucket == LineBucket.UNSORTED } }
            }
            .map { it to Payees.sameMerchant(Payees.key(it), key) }
            .filter { !sameMerchantOnly || it.second }
            .sortedWith(compareByDescending<Pair<TransactionEntity, Boolean>> { it.second }.thenByDescending { it.first.date }.thenByDescending { it.first.createdAt })
            .map { it.first }
            .toList()
    }

    /** A return's lines: back into the purchase's envelopes, in proportion to how the purchase was split. */
    fun returnLines(b: Budget, credit: TransactionEntity, purchase: TransactionEntity): List<LineDraft>? {
        val ls = b.lines.filter { it.transactionId == purchase.id && it.bucket == LineBucket.ENVELOPE }.sortedBy { it.sortOrder }
        if (ls.isEmpty()) return null
        val parts = Rules.allocate(credit.amount, ls.map { -it.amount })
        return ls.zip(parts).filter { it.second != 0L }.map { (l, a) -> LineDraft(l.envelopeId, a, categoryId = l.categoryId) }
    }

    private class Context(val b: Budget, rules: List<RuleEntity>) {
        val rules = rules.filter { !it.deleted }.sortedWith(compareBy({ it.priority }, { it.createdAt }))
        val live = b.envelopes.filter { !it.archived }.map { it.id }.toSet()
        val linesByTx = b.lines.groupBy { it.transactionId }

        /** The user's own past choices, newest first, by payee key: (sign, single destination). */
        val history: Map<String, List<Pair<Int, LineDraft>>> by lazy {
            b.transactions.asSequence()
                .filter { it.kind == TransactionKind.NORMAL && !it.isWash && ownChoice(it) && it.returnOf == null && b.isOnBudget(it.accountId) && !b.beforeStart(it) }
                .mapNotNull { t ->
                    val ls = linesByTx[t.id].orEmpty()
                    val l = ls.singleOrNull() ?: return@mapNotNull null
                    val d = when (l.bucket) {
                        LineBucket.ENVELOPE -> if (l.envelopeId in live) LineDraft(l.envelopeId, 0, categoryId = l.categoryId) else null
                        LineBucket.READY_TO_ASSIGN -> if (t.amount > 0) LineDraft(null, 0, toReadyToAssign = true) else null
                        LineBucket.TRANSFER -> LineDraft(null, 0, transfer = true)
                        else -> null
                    } ?: return@mapNotNull null
                    Triple(t, Payees.key(t), d)
                }
                .filter { it.second.isNotEmpty() }
                .sortedWith(compareByDescending<Triple<TransactionEntity, String, LineDraft>> { it.first.sortedAt ?: 0L }.thenByDescending { it.first.date }.thenByDescending { it.first.createdAt })
                .groupBy({ it.second }, { (if (it.first.amount < 0) -1 else 1) to it.third })
        }

        fun ruleFor(t: TransactionEntity): Pair<RuleEntity, List<LineDraft>>? {
            val forms = Payees.forms(t)
            for (r in rules) {
                if (!Rules.matches(r, t, forms)) continue
                val lines = Rules.linesFor(r, t, live) { b.envelopeForCategory(it) } ?: continue
                return r to lines
            }
            return null
        }

        fun forTx(t: TransactionEntity): Suggestion? {
            if (t.kind != TransactionKind.NORMAL || t.isWash) return null
            if (t.amount > 0) {
                returnCandidates(b, t, sameMerchantOnly = true).firstOrNull()?.let { p ->
                    returnLines(b, t, p)?.let { return Suggestion(it, Basis.RETURN, returnOf = p) }
                }
            }
            ruleFor(t)?.let { (r, lines) -> return Suggestion(lines, Basis.RULE, rule = r) }
            // a payment toward a bill goes to the bill's envelope
            b.bills.firstOrNull { bill -> bill.envelopeId in live && Bills.matches(bill, t, rules, b.billPayments, t.date) }?.let { bill ->
                return Suggestion(listOf(LineDraft(bill.envelopeId, t.amount)), Basis.BILL, bill = bill)
            }
            val sign = if (t.amount < 0) -1 else 1
            val past = history[Payees.key(t)].orEmpty().filter { it.first == sign }.take(HISTORY_LOOKBACK).map { it.second }
            if (past.isEmpty() || past.any { it != past[0] }) return null
            val d = past[0]
            return Suggestion(listOf(d.copy(amount = t.amount)), Basis.HISTORY)
        }
    }
}
