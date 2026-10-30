package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Export CSV from Activity (phone 1.8.0, program 0.13.0): what Activity shows for the selected tab and budget month, as a
 * plain CSV file. One row per line, as Activity shows each line (a split purchase is a row per envelope), oldest first;
 * the envelope moves too where the tab shows them. UTF-8, a header row, amounts as plain decimal numbers (no currency
 * sign or thousands separator), dates as yyyy-mm-dd. Date, Description and Amount are the columns the CSV import reads,
 * so a file exported from an account's tab imports back as the same rows.
 */
object ActivityExport {

    val HEADER = listOf("Date", "Description", "Amount", "Envelope", "Category", "Account", "Memo", "Pending", "Kind")

    /**
     * The rows of [accountId]'s tab (null: All) in [month]. On an account's tab a transfer into it shows from that
     * account's side (money in). [moves]: the envelope moves, which the program's All tab shows.
     */
    fun rows(b: Budget, accountId: String?, month: Budget.Month, moves: Boolean): List<List<String>> {
        val accounts = b.accounts.associateBy { it.id }
        val envelopes = b.envelopes.associateBy { it.id }
        val out = mutableListOf<Pair<Pair<LocalDate, Long>, List<String>>>()
        val txs = b.transactions.filter { (accountId == null || it.accountId == accountId || it.transferAccountId == accountId) && month.contains(it.date) }
        for (t in txs) {
            val kind = when (t.kind) {
                TransactionKind.TRANSFER -> "Transfer"
                TransactionKind.BONUS -> "Bonus income"
                else -> if (t.amount >= 0) "Money in" else "Spending"
            }
            val pending = if (t.pending) "yes" else "no"
            // a transfer is one row, signed as Activity shows it: money in on the receiving account's tab, out everywhere else
            if (t.kind == TransactionKind.TRANSFER) {
                val incoming = accountId != null && t.transferAccountId == accountId
                val from = accounts[t.accountId]?.name ?: "an account"
                val to = accounts[t.transferAccountId]?.name ?: "an account"
                out += (t.date to t.createdAt) to listOf(
                    t.date.toString(), t.payee.ifBlank { if (incoming) "Transfer from $from" else "Transfer to $to" },
                    plain(if (incoming) t.amount else -t.amount), "Transfer", "", if (incoming) to else from, t.memo, pending, kind,
                )
                continue
            }
            val lines = b.lines.filter { it.transactionId == t.id }.ifEmpty { null }
            val account = accounts[t.accountId]?.name ?: ""
            if (lines == null) {
                out += (t.date to t.createdAt) to listOf(t.date.toString(), t.payee, plain(t.amount), "", "", account, t.memo, pending, kind)
                continue
            }
            for (l in lines) {
                val place = when (l.bucket) {
                    LineBucket.ENVELOPE -> envelopes[l.envelopeId]?.name ?: ""
                    LineBucket.READY_TO_ASSIGN -> "Ready to assign"
                    LineBucket.UNSORTED -> "To sort"
                    LineBucket.OFF_BUDGET -> "Off budget"
                    LineBucket.TRANSFER -> "Transfer"
                }
                val category = l.categoryId?.let { b.category(it)?.name } ?: ""
                out += (t.date to t.createdAt) to listOf(t.date.toString(), t.payee, plain(l.amount), place, category, account, t.memo, pending, kind)
            }
        }
        if (moves && accountId == null) {
            for (m in b.moves.filter { month.contains(it.date) }) {
                val from = m.fromEnvelopeId?.let { envelopes[it]?.name } ?: "Ready to assign"
                val to = m.toEnvelopeId?.let { envelopes[it]?.name } ?: "Ready to assign"
                out += (m.date to m.createdAt) to listOf(m.date.toString(), "Moved from $from to $to", plain(m.amount), to, "", "", "", "no", "Move")
            }
        }
        return out.sortedWith(compareBy<Pair<Pair<LocalDate, Long>, List<String>>> { it.first.first }.thenBy { it.first.second }).map { it.second }
    }

    /** The file's text: the header, then [rows], each field quoted when it holds a comma, a quote or a line break. */
    fun csv(rows: List<List<String>>): String = buildString {
        append(HEADER.joinToString(",") { field(it) }).append("\r\n")
        for (r in rows) append(r.joinToString(",") { field(it) }).append("\r\n")
    }

    fun csv(b: Budget, accountId: String?, month: Budget.Month, moves: Boolean): String = csv(rows(b, accountId, month, moves))

    /** The suggested file name: the tab and the dates, like deaddrop-activity-checking-2030-10-01-to-2030-10-29.csv. */
    fun fileName(accountName: String?, month: Budget.Month, today: LocalDate): String {
        val tab = (accountName ?: "all").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "account" }
        val from = month.start?.toString() ?: "start"
        val to = month.end?.minusDays(1)?.toString() ?: today.toString()
        return "deaddrop-activity-$tab-$from-to-$to.csv"
    }

    /** An amount as a plain decimal number: -42.50, 1200.00 (a period, whatever the currency's own separator). */
    fun plain(cents: Long): String {
        val abs = kotlin.math.abs(cents)
        return (if (cents < 0) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    private fun field(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
