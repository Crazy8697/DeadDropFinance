package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Old School CSV import (FEATURES.md, Transactions): reads a bank or payment app export, guesses its layout, and
 * plans the import. The file is only read; nothing is kept. Pure, so the whole thing is unit tested.
 */
object CsvImport {

    // ---- parsing ---------------------------------------------------------------------------------------------

    /** Splits [text] into rows of fields with [delimiter]; handles quotes, doubled quotes and newlines in quotes. */
    fun parse(text: String, delimiter: Char): List<List<String>> = parseNumbered(text, delimiter).map { it.second }

    /** Like [parse], with each row's 1-based line number in the file (blank lines are skipped, not renumbered). */
    fun parseNumbered(text: String, delimiter: Char): List<Pair<Int, List<String>>> {
        val rows = mutableListOf<Pair<Int, List<String>>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var line = 1
        var rowStart = 1
        val s = text.removePrefix("\uFEFF")
        var i = 0
        fun endRow() {
            row.add(field.toString()); field.clear()
            val r = row.map { it.trim() }
            if (r.any { it.isNotEmpty() }) rows.add(rowStart to r)
            row = mutableListOf()
        }
        while (i < s.length) {
            val c = s[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else {
                    if (c == '\n') line++
                    field.append(c)
                }
            } else when (c) {
                '"' -> quoted = true
                delimiter -> { row.add(field.toString()); field.clear() }
                '\r' -> {}
                '\n' -> { endRow(); line++; rowStart = line }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** The delimiter that splits the most lines into the same number of fields (comma, semicolon, tab or pipe). */
    fun detectDelimiter(text: String): Char {
        val lines = text.lineSequence().filter { it.isNotBlank() }.take(30).toList()
        return listOf(',', ';', '\t', '|').maxByOrNull { d ->
            val counts = lines.map { l -> parse(l, d).firstOrNull()?.size ?: 1 }
            val common = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }
            if (common == null || common.key < 2) 0 else common.value * 100 + common.key
        } ?: ','
    }

    // ---- layout guessing -------------------------------------------------------------------------------------

    enum class Role { DATE, DESCRIPTION, AMOUNT, DEBIT, CREDIT, BALANCE, MEMO, IGNORE }

    data class Layout(
        val delimiter: Char,
        /** Index of the header row in the parsed rows; data starts on the next row. -1 when there is no header. */
        val headerRow: Int,
        val headers: List<String>,
        val roles: List<Role>,
        val dateFormat: String?,
        /** True when the dates read correctly both day-first and month-first, so the user should confirm. */
        val dateAmbiguous: Boolean,
        val decimalComma: Boolean,
    )

    private val HEADER_WORDS = mapOf(
        Role.DATE to listOf("date", "posted", "transaction date", "posting date", "trans date"),
        Role.DESCRIPTION to listOf("description", "payee", "name", "merchant", "details", "narrative", "transaction"),
        Role.DEBIT to listOf("debit", "withdrawal", "withdrawals", "money out", "paid out", "charge"),
        Role.CREDIT to listOf("credit", "deposit", "deposits", "money in", "paid in"),
        Role.AMOUNT to listOf("amount", "value", "net"),
        Role.BALANCE to listOf("balance", "running balance"),
        Role.MEMO to listOf("memo", "note", "notes", "reference", "category", "type"),
    )

    fun roleForHeader(h: String): Role {
        val n = h.lowercase(Locale.ROOT).replace(Regex("[^a-z ]"), " ").trim().replace(Regex(" +"), " ")
        if (n.isEmpty()) return Role.IGNORE
        for (role in listOf(Role.BALANCE, Role.DEBIT, Role.CREDIT, Role.DATE, Role.AMOUNT, Role.DESCRIPTION, Role.MEMO)) {
            if (HEADER_WORDS.getValue(role).any { n == it || n.startsWith("$it ") || n.endsWith(" $it") }) return role
        }
        return Role.IGNORE
    }

    /** Guesses everything about the file. The user confirms or changes it on the mapping screen. */
    fun detect(text: String): Layout {
        val d = detectDelimiter(text)
        val rows = parse(text, d)
        val width = rows.map { it.size }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 0
        // the header is the first full-width row (lines above it, like an account name, are skipped)
        val headerRow = rows.indexOfFirst { r -> r.size == width && r.count { roleForHeader(it) != Role.IGNORE } >= 2 }
        val headers = if (headerRow >= 0) rows[headerRow] else List(width) { "Column ${it + 1}" }
        val data = rows.drop(headerRow + 1).filter { it.size == width }
        val roles = if (headerRow >= 0) headers.map { roleForHeader(it) }.toMutableList() else MutableList(width) { Role.IGNORE }
        // without names, guess from the values: the first date column, the first money column, the longest text
        if (Role.DATE !in roles) data.firstOrNull()?.let { r -> r.indices.firstOrNull { looksLikeDate(r[it]) }?.let { roles[it] = Role.DATE } }
        if (Role.AMOUNT !in roles && Role.DEBIT !in roles) data.firstOrNull()?.let { r ->
            r.indices.firstOrNull { roles[it] == Role.IGNORE && parseCents(r[it], false) != null }?.let { roles[it] = Role.AMOUNT }
        }
        if (Role.DESCRIPTION !in roles) data.firstOrNull()?.let { r ->
            r.indices.filter { roles[it] == Role.IGNORE }.maxByOrNull { r[it].length }?.let { roles[it] = Role.DESCRIPTION }
        }
        // keep one column per single role
        for (role in listOf(Role.DATE, Role.DESCRIPTION, Role.AMOUNT, Role.DEBIT, Role.CREDIT, Role.BALANCE)) {
            roles.indices.filter { roles[it] == role }.drop(1).forEach { roles[it] = Role.IGNORE }
        }
        val dateCol = roles.indexOf(Role.DATE)
        val dates = if (dateCol >= 0) data.map { it[dateCol] } else emptyList()
        val (fmt, ambiguous) = detectDateFormat(dates)
        val moneyCols = roles.indices.filter { roles[it] in setOf(Role.AMOUNT, Role.DEBIT, Role.CREDIT) }
        // the file's own amounts decide; when they don't show it either way, the budget's currency does (1.7.0: EUR "1.234,56")
        val amounts = data.flatMap { r -> moneyCols.map { r[it] } }
        val commaDec = amounts.any { Regex("""\d,\d{2}(\s*\D{0,3})$""").containsMatchIn(it.trim()) }
        val dotDec = amounts.any { Regex("""\d\.\d{2}(\s*\D{0,3})$""").containsMatchIn(it.trim()) }
        val decimalComma = d != ',' && when {
            commaDec && !dotDec -> true
            dotDec && !commaDec -> false
            else -> Money.style.decimal == ','
        }
        return Layout(d, headerRow, headers, roles, fmt, ambiguous, decimalComma)
    }

    // ---- dates -----------------------------------------------------------------------------------------------

    val DATE_FORMATS = listOf(
        "yyyy-MM-dd", "MM/dd/yyyy", "M/d/yyyy", "dd/MM/yyyy", "d/M/yyyy", "MM/dd/yy", "M/d/yy", "dd/MM/yy",
        "dd.MM.yyyy", "d.M.yyyy", "yyyy/MM/dd", "MM-dd-yyyy", "dd-MM-yyyy", "MMM d, yyyy", "d MMM yyyy", "dd MMM yyyy", "yyyyMMdd",
    )

    private fun formatter(p: String) = DateTimeFormatter.ofPattern(p.replace("yyyy", "uuuu").replace("yy", "uu"), Locale.US)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parseDate(s: String, pattern: String): LocalDate? = try {
        LocalDate.parse(s.trim(), formatter(pattern))
    } catch (e: DateTimeParseException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun looksLikeDate(s: String) = DATE_FORMATS.any { parseDate(s, it) != null }

    /**
     * The format that reads the most dates (a few bad rows don't hide it), first in [DATE_FORMATS] on a tie. It must
     * read more than half of them. Ambiguous when a day-first format reads just as many as a month-first one.
     */
    fun detectDateFormat(dates: List<String>): Pair<String?, Boolean> {
        val sample = dates.filter { it.isNotBlank() }.take(200)
        if (sample.isEmpty()) return null to false
        val counts = DATE_FORMATS.associateWith { f -> sample.count { parseDate(it, f) != null } }
        val best = counts.values.max()
        if (best * 2 <= sample.size) return null to false
        val fits = DATE_FORMATS.filter { counts.getValue(it) == best }
        val pick = fits.first()
        val ambiguous = (pick.startsWith("M") && fits.any { it.startsWith("d") && it.contains('/') == pick.contains('/') }) ||
            (pick.startsWith("d") && fits.any { it.startsWith("M") })
        return pick to ambiguous
    }

    // ---- amounts ---------------------------------------------------------------------------------------------

    /**
     * A bank amount to cents, without floating point: "1,234.56", "-12.34", "(12.34)", "$12.34", "12.34-",
     * "12.34 CR" / "DR", and with [decimalComma] "1.234,56". Null if it isn't an amount.
     */
    fun parseCents(raw: String, decimalComma: Boolean): Long? {
        var s = raw.trim().replace("\u00A0", "").replace(" ", "")
        if (s.isEmpty()) return null
        var negative = false
        val upper = s.uppercase(Locale.ROOT)
        if (upper.endsWith("CR")) s = s.dropLast(2) else if (upper.endsWith("DR")) { negative = true; s = s.dropLast(2) }
        if (s.startsWith("(") && s.endsWith(")")) { negative = !negative; s = s.substring(1, s.length - 1) }
        if (s.endsWith("-")) { negative = !negative; s = s.dropLast(1) }
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1) } else if (s.startsWith("+")) s = s.substring(1)
        // a currency sign or the budget's currency code at either end ("$12.34", "12,34 €", "12.34 EUR")
        s = s.trimStart { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }.trimEnd { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }
        val code = Money.style.code
        if (s.uppercase(Locale.ROOT).endsWith(code)) s = s.dropLast(code.length) else if (s.uppercase(Locale.ROOT).startsWith(code)) s = s.drop(code.length)
        if (s.startsWith("-")) { negative = !negative; s = s.substring(1) }
        s = if (decimalComma) s.replace(".", "").replace(",", ".") else s.replace(",", "")
        val m = Regex("""^(\d+)(?:\.(\d{1,2}))?$""").matchEntire(s) ?: return null
        val whole = m.groupValues[1].toLongOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
        val cents = whole * 100 + frac
        return if (negative) -cents else cents
    }

    // ---- rows ------------------------------------------------------------------------------------------------

    data class Row(val rowNumber: Int, val date: LocalDate, val description: String, val amount: Long, val memo: String)
    data class BadRow(val rowNumber: Int, val reason: String)
    data class Read(val rows: List<Row>, val bad: List<BadRow>)

    /**
     * The rows as they'll import, with the user's (or the guessed) layout. Row numbers are the file's line numbers
     * (1-based, from the first line of the row), so a bad row can be found in the file. One bad row never stops the
     * rest.
     */
    fun read(text: String, layout: Layout): Read {
        val all = parseNumbered(text, layout.delimiter)
        val rows = mutableListOf<Row>()
        val bad = mutableListOf<BadRow>()
        val col = { r: CsvImport.Role -> layout.roles.indexOf(r) }
        val (dc, desc, amt, deb, cred, memo) = listOf(col(Role.DATE), col(Role.DESCRIPTION), col(Role.AMOUNT), col(Role.DEBIT), col(Role.CREDIT), col(Role.MEMO))
        all.forEachIndexed { i, (n, r) ->
            if (i <= layout.headerRow) return@forEachIndexed
            if (r.size < layout.roles.size) { bad += BadRow(n, "has ${r.size} fields, expected ${layout.roles.size}"); return@forEachIndexed }
            if (dc < 0) { bad += BadRow(n, "no date column chosen"); return@forEachIndexed }
            val date = layout.dateFormat?.let { parseDate(r[dc], it) }
            if (date == null) { bad += BadRow(n, "the date \"${r[dc]}\" doesn't read as ${layout.dateFormat ?: "a date"}"); return@forEachIndexed }
            val amount: Long? = when {
                amt >= 0 -> parseCents(r[amt], layout.decimalComma)
                deb >= 0 || cred >= 0 -> {
                    val d = if (deb >= 0) r[deb] else ""
                    val c = if (cred >= 0) r[cred] else ""
                    val dv = if (d.isBlank()) null else parseCents(d, layout.decimalComma)
                    val cv = if (c.isBlank()) null else parseCents(c, layout.decimalComma)
                    when {
                        d.isNotBlank() && dv == null -> null
                        c.isNotBlank() && cv == null -> null
                        dv == null && cv == null -> null
                        else -> (cv?.let { kotlin.math.abs(it) } ?: 0) - (dv?.let { kotlin.math.abs(it) } ?: 0)
                    }
                }
                else -> null
            }
            if (amount == null) { bad += BadRow(n, "no readable amount"); return@forEachIndexed }
            if (amount == 0L) { bad += BadRow(n, "the amount is zero"); return@forEachIndexed }
            val description = if (desc >= 0) r[desc] else ""
            rows += Row(n, date, description, amount, if (memo >= 0) r[memo] else "")
        }
        return Read(rows, bad)
    }

    private operator fun <T> List<T>.component6() = this[5]

    // ---- duplicates and pending matches ----------------------------------------------------------------------

    /** Lowercase letters and digits only, single spaces: how descriptions are compared for duplicates. */
    fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun dupKey(accountId: String, date: LocalDate, amount: Long, description: String) = "$accountId|$date|$amount|${normalize(description)}"

    /** Identical rows (same date, cents and description) inside one file: the user chooses to import or skip each. */
    data class DupGroup(val rows: List<Row>)

    data class Plan(
        /** Rows that will be imported: new rows, plus the rows of possible-duplicate groups the user didn't skip. */
        val toImport: List<Row>,
        /** Rows the account already holds (same date, cents and description), counted per key: skipped. */
        val alreadyHere: List<Row>,
        /** Rows that are the posted version of a pending transaction entered by hand: that one gets posted instead. */
        val matchedPending: List<Pair<Row, TransactionEntity>>,
        val bad: List<BadRow>,
        /** Groups of identical rows in the file that are not already here. Imported by default. */
        val possibleDuplicates: List<DupGroup> = emptyList(),
        /** Rows in those groups that the user chose to skip. */
        val skippedByUser: List<Row> = emptyList(),
    ) {
        val total get() = toImport.size + alreadyHere.size + matchedPending.size + bad.size + skippedByUser.size
        val possibleDuplicateRows get() = possibleDuplicates.sumOf { it.rows.size }
    }

    /**
     * Plans the import. Against the account the match is count-aware: for each key, if the file has N rows and the
     * account holds M transactions with it, min(N, M) rows are "already here" and the rest are new. Identical rows
     * inside the file are never skipped on their own: they are listed as [Plan.possibleDuplicates] and imported
     * unless their row number is in [skippedRows].
     */
    fun plan(read: Read, accountId: String, existing: List<TransactionEntity>, skippedRows: Set<Int> = emptySet()): Plan {
        val inAccount = existing.filter { it.accountId == accountId && it.kind == TransactionKind.NORMAL }
        val held = mutableMapOf<String, Int>()
        for (t in inAccount) {
            val keys = listOfNotNull(t.payee, t.importDescription).map { dupKey(accountId, t.date, t.amount, it) }.toSet()
            for (k in keys) held[k] = (held[k] ?: 0) + 1
        }
        val pending = inAccount.filter { it.pending }.toMutableList()
        val fresh = mutableListOf<Row>()
        val dup = mutableListOf<Row>()
        val matched = mutableListOf<Pair<Row, TransactionEntity>>()
        for (r in read.rows) {
            val key = dupKey(accountId, r.date, r.amount, r.description)
            val m = held[key] ?: 0
            if (m > 0) { held[key] = m - 1; dup += r; continue }
            val p = pending.firstOrNull { it.amount == r.amount && kotlin.math.abs(ChronoUnit.DAYS.between(it.date, r.date)) <= 5 }
            if (p != null) { pending.remove(p); matched += r to p; continue }
            fresh += r
        }
        val groups = fresh.groupBy { dupKey(accountId, it.date, it.amount, it.description) }.values
            .filter { it.size > 1 }.map { DupGroup(it) }.sortedBy { it.rows.first().rowNumber }
        val skipped = groups.flatMap { it.rows }.filter { it.rowNumber in skippedRows }
        val skippedNumbers = skipped.map { it.rowNumber }.toSet()
        return Plan(fresh.filter { it.rowNumber !in skippedNumbers }, dup, matched, read.bad, groups, skipped)
    }

    /** ATM fees are tagged automatically; a rebate of one offsets it. */
    fun isAtmFee(description: String, amount: Long): Boolean {
        val n = normalize(description)
        return amount < 0 && n.contains("atm") && (n.contains("fee") || n.contains("surcharge"))
    }

    fun isFeeRebate(description: String, amount: Long): Boolean {
        val n = normalize(description)
        return amount > 0 && (n.contains("atm") || n.contains("fee")) && (n.contains("rebate") || n.contains("refund") || n.contains("reimburse"))
    }
}
