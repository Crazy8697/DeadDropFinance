package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * What the spend alerts say after new transactions came in (a bank notification read). Pure: the phone side (SpendAlerts)
 * only posts what this returns. Several new charges make one notification, never one each; warnings make a second.
 *
 * The lock screen never shows a payee, an envelope or an amount unless the user turns that on: each notice has a
 * public version with only a count in it.
 */
object Alerts {

    data class Settings(
        val spending: Boolean = true,
        val lowOrOverspent: Boolean = true,
        val safeToSpend: Boolean = true,
        val amountsOnLockScreen: Boolean = false,
    )

    enum class Kind { SPENDING, WARNING }

    data class Notice(
        val kind: Kind,
        val title: String,
        val lines: List<String>,
        /** What the lock screen shows unless amounts are allowed there: no payees, envelopes or amounts. */
        val publicTitle: String,
        val publicText: String,
        /** Transactions the notification's Approve button would approve (each has a suggestion). */
        val approveIds: List<String> = emptyList(),
        val showOnLockScreen: Boolean = false,
    ) {
        val text get() = lines.joinToString("\n")
    }

    /** An envelope is low when what's left is under this share of what it fills to (its target or payday amount). */
    const val LOW_PERCENT = 15L

    fun isLow(e: EnvelopeEntity, balance: Long): Boolean {
        val ref = e.target ?: e.perPayday ?: return false
        return ref > 0 && balance > 0 && balance * 100 < ref * LOW_PERCENT
    }

    fun build(b: Budget, rules: List<RuleEntity>, newIds: Collection<String>, s: Settings): List<Notice> {
        val ids = newIds.toSet()
        val spends = b.transactions.filter {
            it.id in ids && it.kind == TransactionKind.NORMAL && it.amount < 0 && !it.isWash && b.isOnBudget(it.accountId) && !b.beforeStart(it)
        }.sortedWith(compareBy<TransactionEntity> { it.date }.thenBy { it.createdAt })
        if (spends.isEmpty()) return emptyList()
        val suggestions = Suggest.all(b, rules)
        val names = b.envelopes.associate { it.id to it.name }
        val linesOf = b.lines.groupBy { it.transactionId }

        // where each new charge's money goes: its lines if sorted already (auto-approve), else its suggestion
        val effect = HashMap<String, Long>()          // envelope id -> change the new charges make
        val sortedNow = HashMap<String, Long>()       // envelope id -> part of that already in the balance
        data class Row(val t: TransactionEntity, val dest: String?, val sorted: Boolean)
        val rows = spends.map { t ->
            val ls = linesOf[t.id].orEmpty()
            val sorted = ls.none { it.bucket == LineBucket.UNSORTED }
            val dests = if (sorted) ls.map { it.envelopeId to it.amount } else suggestions[t.id]?.lines?.map { it.envelopeId to it.amount }.orEmpty()
            for ((e, a) in dests) if (e != null) {
                effect[e] = (effect[e] ?: 0L) + a
                if (sorted) sortedNow[e] = (sortedNow[e] ?: 0L) + a
            }
            val envs = dests.mapNotNull { it.first }
            Row(t, when { envs.size == 1 -> envs[0]; envs.size > 1 -> SPLIT; else -> null }, sorted)
        }
        fun before(e: String) = b.envelopeBalance(e) - (sortedNow[e] ?: 0L)
        fun after(e: String) = before(e) + (effect[e] ?: 0L)

        val out = mutableListOf<Notice>()
        if (s.spending) {
            fun describe(r: Row): String = when {
                r.dest == null -> "not sorted yet"
                r.dest == SPLIT -> if (r.sorted) "split by a rule" else "suggested split"
                else -> (if (r.sorted) "in " else "suggested ") + "${names[r.dest] ?: "an envelope"}, ${Money.format(after(r.dest))} left"
            }
            val approve = rows.filter { !it.sorted && suggestions.containsKey(it.t.id) }.map { it.t.id }
            out += if (rows.size == 1) {
                val r = rows[0]
                Notice(
                    Kind.SPENDING, "${r.t.payee.ifBlank { "New charge" }} ${Money.format(r.t.amount)}", listOf(describe(r).replaceFirstChar { it.uppercase() }),
                    PUBLIC_TITLE, "New spending", approve, s.amountsOnLockScreen,
                )
            } else {
                Notice(
                    Kind.SPENDING, "${rows.size} new charges, ${Money.format(rows.sumOf { it.t.amount })}",
                    rows.map { "${it.t.payee.ifBlank { "No description" }} ${Money.format(it.t.amount)}: ${describe(it)}" },
                    PUBLIC_TITLE, "${rows.size} new charges", approve, s.amountsOnLockScreen,
                )
            }
        }

        val warnings = mutableListOf<String>()
        if (s.lowOrOverspent) {
            for (e in b.envelopes.filter { it.id in effect.keys && !it.archived }.sortedBy { it.sortOrder }) {
                val was = before(e.id)
                val now = after(e.id)
                when {
                    now < 0 && was >= 0 -> warnings += "${e.name} is overspent by ${Money.format(-now)}"
                    now >= 0 && isLow(e, now) && !isLow(e, was) && was > now -> warnings += "${e.name} is low: ${Money.format(now)} left"
                }
            }
        }
        if (s.safeToSpend) {
            val everyday = b.everydayEnvelopes
            if (everyday.isNotEmpty()) {
                val was = everyday.sumOf { before(it.id) }
                val now = everyday.sumOf { after(it.id) }
                if (now < 0 && was >= 0) warnings += "Safe to Spend would go negative: ${Money.format(now)}"
            }
        }
        if (warnings.isNotEmpty()) {
            out += Notice(
                Kind.WARNING, if (warnings.size == 1) "Envelope warning" else "${warnings.size} envelope warnings", warnings,
                PUBLIC_TITLE, if (warnings.size == 1) "An envelope needs a look" else "${warnings.size} envelopes need a look", emptyList(), s.amountsOnLockScreen,
            )
        }
        return out
    }

    const val PUBLIC_TITLE = "Dead Drop Finance"
    private const val SPLIT = "\u0000split"
}
