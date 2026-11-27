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

    /**
     * What a column holds. ORIGINAL (1.9.7): the bank's own original description (USAA's "Original Description"),
     * stored with the transaction for duplicate checks; STATUS (1.9.7): "Pending" or "Posted".
     */
    enum class Role { DATE, DESCRIPTION, ORIGINAL, AMOUNT, DEBIT, CREDIT, BALANCE, MEMO, STATUS, IGNORE }

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
        /** 1.9.8: a Venmo statement, read by its own columns ([Venmo]); the mapping above is not used. */
        val venmo: Boolean = false,
    )

    private val HEADER_WORDS = mapOf(
        Role.ORIGINAL to listOf("original description", "original name", "original"),
        Role.STATUS to listOf("status"),
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
        for (role in listOf(Role.ORIGINAL, Role.STATUS, Role.BALANCE, Role.DEBIT, Role.CREDIT, Role.DATE, Role.AMOUNT, Role.DESCRIPTION, Role.MEMO)) {
            if (HEADER_WORDS.getValue(role).any { n == it || n.startsWith("$it ") || n.endsWith(" $it") }) return role
        }
        return Role.IGNORE
    }

    /** Guesses everything about the file. The user confirms or changes it on the mapping screen. */
    fun detect(text: String): Layout {
        Venmo.layout(text)?.let { return it }
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
        for (role in listOf(Role.DATE, Role.DESCRIPTION, Role.ORIGINAL, Role.AMOUNT, Role.DEBIT, Role.CREDIT, Role.BALANCE, Role.STATUS)) {
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

    /**
     * One row as it imports. [original] (1.9.7): the bank's original description (the ORIGINAL column, else the
     * description): what's stored on the transaction and compared on a later import. [pending]: the STATUS column says
     * Pending (not posted yet); such a row isn't added.
     */
    data class Row(
        val rowNumber: Int, val date: LocalDate, val description: String, val amount: Long, val memo: String,
        val original: String = description, val pending: Boolean = false,
    )
    data class BadRow(val rowNumber: Int, val reason: String)

    /** 1.9.8: a transaction row of the file the importer leaves out on purpose, and why (listed under "Not added"). */
    data class Left(val rowNumber: Int, val date: LocalDate?, val description: String, val amount: Long?, val reason: String)

    data class Read(
        val rows: List<Row>, val bad: List<BadRow>,
        /** 1.9.8: rows left out on purpose (a Venmo repayment that doesn't change the balance), with why. */
        val notAdded: List<Left> = emptyList(),
        /** 1.9.8: the balances the file itself states (a Venmo statement's beginning and ending balance), when it does. */
        val beginningBalance: Long? = null,
        val endingBalance: Long? = null,
    )

    /**
     * The rows as they'll import, with the user's (or the guessed) layout. Row numbers are the file's line numbers
     * (1-based, from the first line of the row), so a bad row can be found in the file. One bad row never stops the
     * rest.
     */
    fun read(text: String, layout: Layout): Read {
        if (layout.venmo) return Venmo.read(text)
        val all = parseNumbered(text, layout.delimiter)
        val rows = mutableListOf<Row>()
        val bad = mutableListOf<BadRow>()
        val col = { r: CsvImport.Role -> layout.roles.indexOf(r) }
        val (dc, desc, amt, deb, cred, memo) = listOf(col(Role.DATE), col(Role.DESCRIPTION), col(Role.AMOUNT), col(Role.DEBIT), col(Role.CREDIT), col(Role.MEMO))
        val orig = col(Role.ORIGINAL)
        val status = col(Role.STATUS)
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
            val original = if (orig >= 0) r[orig] else ""
            val description = (if (desc >= 0) r[desc] else "").ifBlank { original }
            val pending = status >= 0 && r[status].trim().equals("pending", ignoreCase = true)
            rows += Row(n, date, description, amount, if (memo >= 0) r[memo] else "", original.ifBlank { description }, pending)
        }
        return Read(rows, bad)
    }

    private operator fun <T> List<T>.component6() = this[5]

    // ---- duplicates and pending matches ----------------------------------------------------------------------

    /** Lowercase letters and digits only, single spaces: how descriptions are compared for duplicates. */
    fun normalize(s: String) = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun dupKey(accountId: String, date: LocalDate, amount: Long, description: String) = "$accountId|$date|$amount|${normalize(description)}"

    /** Why a row is listed as unsure: the transaction already here it may be, and how many days apart they are. */
    data class Unsure(val row: Row, val maybe: TransactionEntity, val others: List<TransactionEntity> = emptyList()) {
        val daysApart: Long get() = kotlin.math.abs(ChronoUnit.DAYS.between(maybe.date, row.date))
    }

    data class Plan(
        /** Rows that will be imported: the new rows left ticked, and the unsure rows the user ticked. */
        val toImport: List<Row>,
        /** Rows the account already holds, each matched to its own transaction there: skipped. */
        val alreadyHere: List<Row>,
        /** Rows that are the posted version of a pending transaction entered by hand: that one gets posted instead. */
        val matchedPending: List<Pair<Row, TransactionEntity>>,
        val bad: List<BadRow>,
        /** Rows not found here for sure, imported by default (the user can untick each). */
        val new: List<Row> = emptyList(),
        /** Rows that may already be here (see [Unsure]): not imported unless the user ticks them. */
        val unsure: List<Unsure> = emptyList(),
        /** Rows the bank lists as Pending: never added (they come back posted in a later file). */
        val pending: List<Row> = emptyList(),
        /** What each "already here" row matched, in the same order as [alreadyHere]. */
        val matches: List<TransactionEntity> = emptyList(),
        /** 1.9.8: rows left out on purpose, with why ([Read.notAdded]). */
        val notAdded: List<Left> = emptyList(),
        /** 1.9.8: the "already here" rows matched by several transactions together (a purchase entered as one per envelope), by row number. */
        val groups: Map<Int, List<TransactionEntity>> = emptyMap(),
    ) {
        val total get() = new.size + unsure.size + alreadyHere.size + matchedPending.size + pending.size + bad.size + notAdded.size
        /** Already here: matched rows and rows posting a pending transaction. */
        val alreadyCount get() = alreadyHere.size + matchedPending.size
    }

    /** How far apart a row's date and a hand-entered (or older) transaction's date can be and still be the same one. */
    const val NEAR_DAYS = 3L

    /**
     * Plans the import (1.9.7, program 0.15.10). Against the transactions already on the account:
     *
     *  1. A transaction that has the bank's original description (an import from 1.9.7 on) is the same one when the
     *     amount, the date and that description are all the same. Renaming its payee changes nothing.
     *  2. One entered by hand (or imported before 1.9.7) has no original description. On the same day with the same
     *     amount: as many rows as there are such transactions are already here, the rest go on (three $3.00 rows in the
     *     file and two here: two already here, one goes on). More such transactions than rows: each row is unsure.
     *  3. A row left over with the same amount within [NEAR_DAYS] days of one of those (a hand entry on 11-02 that the
     *     bank posted on 11-03) is unsure, with that transaction beside it.
     *  4. Anything else is new.
     *
     * An existing transaction is matched (or shown beside an unsure row) once at most. Rows the bank lists as Pending
     * are set aside, and a row matching a pending transaction entered by hand posts that one. [choices] is the user's
     * ticks by row number: a new row is imported unless it's false, an unsure row only when it's true.
     */
    fun plan(read: Read, accountId: String, existing: List<TransactionEntity>, choices: Map<Int, Boolean> = emptyMap()): Plan {
        // 1.9.8: a transfer to or from the account is a leg of it, signed as the bank sees it (out: minus)
        val legs = existing.filter { it.kind == TransactionKind.TRANSFER && !it.deleted && (it.accountId == accountId || it.transferAccountId == accountId) }
            .map { t -> t.copy(accountId = accountId, amount = if (t.accountId == accountId) -t.amount else t.amount, payee = t.payee.ifBlank { "Transfer" }) }
        val inAccount = existing.filter { it.accountId == accountId && it.kind == TransactionKind.NORMAL && !it.deleted } + legs
        val withOriginal = inAccount.filter { !it.pending && !it.originalDescription.isNullOrBlank() }
        val handPending = inAccount.filter { it.pending }.toMutableList()
        val legacy = inAccount.filter { !it.pending && it.originalDescription.isNullOrBlank() }.toMutableList()
        val (bankPending, rows) = read.rows.partition { it.pending }

        val already = mutableListOf<Pair<Row, TransactionEntity>>()
        val matchedPending = mutableListOf<Pair<Row, TransactionEntity>>()
        val unsure = mutableListOf<Unsure>()
        val fresh = mutableListOf<Row>()

        // 1. the bank's original description, the amount and the date
        val byKey = withOriginal.groupBy { dupKey(accountId, it.date, it.amount, it.originalDescription!!) }
            .mapValues { it.value.sortedBy { t -> t.createdAt }.toMutableList() }
        var left = mutableListOf<Row>()
        for (r in rows) {
            val t = byKey[dupKey(accountId, r.date, r.amount, r.original)]?.removeFirstOrNull()
            if (t != null) already += r to t else left += r
        }
        // a pending transaction entered by hand: posted by the row
        left = left.filterTo(mutableListOf()) { r ->
            val p = handPending.filter { it.amount == r.amount && kotlin.math.abs(ChronoUnit.DAYS.between(it.date, r.date)) <= 5 }
                .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(it.date, r.date)) }
            if (p != null) { handPending.remove(p); matchedPending += r to p; false } else true
        }
        // 2. no original description: the same day and amount, count by count
        val after2 = mutableListOf<Row>()
        for ((key, group) in left.groupBy { it.date to it.amount }) {
            val here = legacy.filter { it.date == key.first && it.amount == key.second }.sortedBy { it.createdAt }
            when {
                here.isEmpty() -> after2 += group
                here.size <= group.size -> {
                    group.zip(here).forEach { (r, t) -> already += r to t; legacy.remove(t) }
                    after2 += group.drop(here.size)
                }
                else -> group.zip(here).forEach { (r, t) -> unsure += Unsure(r, t); legacy.remove(t) }
            }
        }
        // 2b (1.9.8). one purchase entered as several transactions on its day (one per envelope): 2 to 4 of them, the
        // same day, that add up to the row exactly. All the same payee: already here; otherwise, or more than one way to
        // make it up: unsure, with them beside it
        val groups = mutableMapOf<Int, List<TransactionEntity>>()
        val after2b = mutableListOf<Row>()
        for (r in after2.sortedBy { it.rowNumber }) {
            val day = legacy.filter { it.date == r.date && it.kind == TransactionKind.NORMAL && (it.amount < 0) == (r.amount < 0) }.sortedBy { it.createdAt }.take(MAX_GROUP_POOL)
            val found = subsets(day, r.amount)
            val g = found.firstOrNull()
            if (g == null) { after2b += r; continue }
            legacy.removeAll(g.toSet())
            val samePayee = g.map { Payees.clean(it.payee) }.distinct().size == 1
            if (found.size == 1 && samePayee) { already += r to g.first(); groups[r.rowNumber] = g } else unsure += Unsure(r, g.first(), g.drop(1))
        }
        // 3. the same amount a few days apart; 4. new
        for (r in after2b.sortedBy { it.rowNumber }) {
            val near = legacy.filter { it.amount == r.amount && kotlin.math.abs(ChronoUnit.DAYS.between(it.date, r.date)) <= NEAR_DAYS }
                .minWithOrNull(compareBy<TransactionEntity> { kotlin.math.abs(ChronoUnit.DAYS.between(it.date, r.date)) }.thenBy { it.createdAt })
            if (near != null) { unsure += Unsure(r, near); legacy.remove(near) } else fresh += r
        }

        val byRow = compareBy<Row> { it.rowNumber }
        val sortedAlready = already.sortedWith(compareBy { it.first.rowNumber })
        val sortedUnsure = unsure.sortedWith(compareBy { it.row.rowNumber })
        val newRows = fresh.sortedWith(byRow)
        val toImport = (newRows.filter { choices[it.rowNumber] != false } + sortedUnsure.map { it.row }.filter { choices[it.rowNumber] == true }).sortedWith(byRow)
        return Plan(
            toImport, sortedAlready.map { it.first }, matchedPending.sortedWith(compareBy { it.first.rowNumber }), read.bad,
            newRows, sortedUnsure, bankPending.sortedWith(byRow), sortedAlready.map { it.second }, read.notAdded, groups,
        )
    }

    /** The most transactions of one day looked at for a group, and the most in a group. */
    private const val MAX_GROUP_POOL = 14
    private const val MAX_GROUP = 4

    /** The sets of 2 to [MAX_GROUP] of [pool] that add up to [amount] exactly (at most two: one, or "more than one"). */
    private fun subsets(pool: List<TransactionEntity>, amount: Long): List<List<TransactionEntity>> {
        val out = mutableListOf<List<TransactionEntity>>()
        fun go(start: Int, picked: List<TransactionEntity>, sum: Long) {
            if (out.size >= 2) return
            if (picked.size >= 2 && sum == amount) { out += picked; return }
            if (picked.size == MAX_GROUP) return
            for (i in start until pool.size) go(i + 1, picked + pool[i], sum + pool[i].amount)
        }
        go(0, emptyList(), 0)
        return out
    }

    /**
     * The account's balance once [plan] is imported, as the bank shows it (posted, without pending): today's balance in
     * the app, less the pending transactions entered by hand that this import doesn't post, plus what it adds (1.9.8).
     */
    fun balanceAfter(budget: Budget, accountId: String, plan: Plan): Long {
        val posted = plan.matchedPending.map { it.second.id }.toSet()
        val stillPending = budget.transactions.filter { it.accountId == accountId && it.pending && it.kind == TransactionKind.NORMAL && it.id !in posted }.sumOf { it.amount }
        return budget.accountBalance(accountId) - stillPending + plan.toImport.sumOf { it.amount }
    }

    /**
     * One CSV import's transactions on an account (1.9.7): those with its import id; for an import made before import ids
     * existed, the CSV transactions saved in the same write (every row of one import shares its created-at instant to
     * the millisecond, as the import is one write).
     */
    data class Batch(val accountId: String, val importId: String?, val createdAt: Long, val transactionIds: List<String>) {
        val size get() = transactionIds.size
    }

    /** The account's most recent import that still has transactions, or null when there's none to undo. */
    fun lastImport(transactions: List<TransactionEntity>, accountId: String): Batch? {
        val imported = transactions.filter {
            it.accountId == accountId && !it.deleted && it.kind == TransactionKind.NORMAL &&
                (it.importId != null || it.source == net.darkrelay.deaddropfinance.data.TransactionSource.CSV)
        }
        if (imported.isEmpty()) return null
        val groups = imported.groupBy { it.importId ?: "created@${it.createdAt}" }
        val (key, rows) = groups.entries.maxBy { e -> e.value.maxOf { it.createdAt } }
        val withId = rows.firstOrNull { it.importId != null }?.importId
        return Batch(accountId, withId, rows.minOf { it.createdAt }, rows.sortedBy { it.id }.map { it.id }).takeIf { key.isNotEmpty() }
    }

    /**
     * A Venmo statement (1.9.8, "VenmoStatement_<Month>_<Year>.csv"): a title line and an "Account Activity" line,
     * then the header row (ID, Datetime, Type, Status, Note, From, To, Amount (total), ... Beginning Balance, Ending
     * Balance, ... Disclaimer), a row with only the beginning balance, the transactions, and a closing row with the
     * ending balance, the fees and the disclaimer. Found by its header row wherever it sits. Amounts are "+ $100.00" and
     * "- $8.16"; the payee is From for money in and To for money out; the note is the memo; the ID is the original
     * description, so the same statement imported twice adds nothing. An Instant Transfer is one row for its total
     * (the fee included). A Venmo account repayment is added only when the statement's balances show it moving the
     * balance; otherwise it's left out and says why.
     */
    object Venmo {
        private val must = listOf("id", "datetime", "type", "status", "note", "from", "to", "amount (total)")

        private fun headerAt(rows: List<Pair<Int, List<String>>>) = rows.indexOfFirst { (_, r) -> r.map { it.trim().lowercase(Locale.ROOT) }.containsAll(must) }

        fun layout(text: String): Layout? {
            val rows = parseNumbered(text, ',')
            val at = headerAt(rows)
            if (at < 0) return null
            val headers = rows[at].second
            return Layout(',', at, headers, headers.map { Role.IGNORE }, "yyyy-MM-dd", dateAmbiguous = false, decimalComma = false, venmo = true)
        }

        fun read(text: String): Read {
            val all = parseNumbered(text, ',')
            val at = headerAt(all)
            if (at < 0) return Read(emptyList(), listOf(BadRow(1, "no Venmo header row")))
            val names = all[at].second.map { it.trim().lowercase(Locale.ROOT) }
            fun List<String>.of(name: String) = names.indexOf(name).let { if (it < 0) "" else getOrElse(it) { "" }.trim() }
            val rows = mutableListOf<Row>()
            val bad = mutableListOf<BadRow>()
            val left = mutableListOf<Left>()
            val repayments = mutableListOf<Row>()
            var beginning: Long? = null
            var ending: Long? = null
            for ((n, r) in all.drop(at + 1)) {
                val id = r.of("id")
                if (id.isEmpty()) {
                    // the beginning balance row, and the closing row (ending balance, fees, the disclaimer)
                    r.of("beginning balance").takeIf { it.isNotEmpty() }?.let { v -> if (beginning == null) beginning = parseCents(v, false) }
                    r.of("ending balance").takeIf { it.isNotEmpty() }?.let { v -> ending = parseCents(v, false) ?: ending }
                    continue
                }
                val date = r.of("datetime").take(10).let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val amount = parseCents(r.of("amount (total)"), false)
                val type = r.of("type")
                if (date == null) { bad += BadRow(n, "the date \"${r.of("datetime")}\" doesn't read"); continue }
                if (amount == null || amount == 0L) { bad += BadRow(n, "no readable amount"); continue }
                val payee = (if (amount > 0) r.of("from") else r.of("to")).ifBlank { type.ifBlank { "Venmo" } }
                val status = r.of("status").lowercase(Locale.ROOT)
                if (status.isNotEmpty() && status !in setOf("complete", "completed", "issued", "pending")) {
                    left += Left(n, date, payee, amount, "Venmo lists it as ${r.of("status")}")
                    continue
                }
                val row = Row(n, date, payee, amount, r.of("note"), original = id, pending = status == "pending")
                if (type.equals("Venmo account repayment", ignoreCase = true)) repayments += row else rows += row
            }
            if (repayments.isNotEmpty()) {
                val b = beginning
                val e = ending
                val base = rows.filter { !it.pending }.sumOf { it.amount }
                val moves = b != null && e != null && b + base + repayments.sumOf { it.amount } == e && b + base != e
                if (moves) rows += repayments
                else repayments.forEach {
                    left += Left(it.rowNumber, it.date, it.description, it.amount,
                        if (b == null || e == null) "a Venmo account repayment, and the statement has no balances to show it changes yours"
                        else "a Venmo account repayment that doesn't change the Venmo balance")
                }
            }
            return Read(rows.sortedBy { it.rowNumber }, bad, left.sortedBy { it.rowNumber }, beginning, ending)
        }
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
