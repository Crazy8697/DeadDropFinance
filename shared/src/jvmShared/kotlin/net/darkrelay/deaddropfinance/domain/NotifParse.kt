package net.darkrelay.deaddropfinance.domain

/**
 * Reading a bank or wallet app's notification into an amount, a payee and whether it's money in. Nothing app-specific
 * is built in: the first notification from an app gets a generic guess the user confirms or fixes, and that becomes
 * the app's template (the words around the amount and the payee), used from then on. Pure. The text is only ever
 * passed in; nothing here keeps it.
 */
object NotifParse {

    data class Parsed(val amount: Long, val payee: String, val deposit: Boolean)

    /**
     * How one kind of notification from an app reads: the words just before the amount, before and after the payee
     * (or a fixed payee, when the user typed one that isn't in the text), and whether it's money in.
     */
    data class Template(val deposit: Boolean, val amountBefore: String, val payeeBefore: String, val payeeAfter: String, val fixedPayee: String = "")

    data class Money(val range: IntRange, val cents: Long)

    private val money = Regex("""\$\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?|(?<![\w.,])(\d{1,3}(?:,\d{3})+|\d+)\.(\d{2})(?![\d])""")
    private val word = Regex("""[A-Za-z0-9']+""")
    private val depositWords = Regex("""\b(deposit|deposited|received|credited|direct dep|payroll|refund|refunded|incoming)\b""", RegexOption.IGNORE_CASE)
    private val spendWords = Regex("""\b(purchase|purchased|spent|charged|charge|debit|withdrawal|withdrew|paid|payment|sent|transaction)\b""", RegexOption.IGNORE_CASE)
    private val payeeLead = Regex("""\b(at|to|from|with|for)\s+""", RegexOption.IGNORE_CASE)
    private val payeeEnd = Regex("""(?i)[.!\n]|\s+(on|using|with card|card ending|ending in|was|has been|is|for)\b""")

    /** Every money amount in the text, in order. */
    fun amounts(text: String): List<Money> = money.findAll(text).mapNotNull { m ->
        val whole = (m.groups[1] ?: m.groups[3])?.value?.replace(",", "")?.toLongOrNull() ?: return@mapNotNull null
        val cents = (m.groups[2] ?: m.groups[4])?.value?.toLong() ?: 0L
        Money(m.range, whole * 100 + cents)
    }.filter { it.cents > 0 }.toList()

    /** A first guess, with no template: the first amount, money in or out by its words, the name after "at", "to" or "from". */
    fun guess(text: String): Parsed? {
        val a = amounts(text).firstOrNull() ?: return null
        val deposit = depositWords.containsMatchIn(text) && !Regex("""\b(purchase|spent|charged)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!deposit && !spendWords.containsMatchIn(text) && !Regex("""\b(at|card)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val lead = payeeLead.findAll(text).firstOrNull { it.range.first > a.range.last || payeeLead.findAll(text).count() == 1 } ?: payeeLead.find(text)
        val payee = lead?.let { l ->
            val rest = text.substring(l.range.last + 1)
            val end = payeeEnd.find(rest)?.range?.first ?: rest.length
            clean(rest.substring(0, end))
        } ?: ""
        return Parsed(a.cents, payee, deposit)
    }

    private fun clean(s: String) = s.trim().trim(',', ';', ':', '-', '.').trim().take(60)

    private fun words(s: String) = word.findAll(s).map { it.value.lowercase() }.toList()

    /** The last (up to) two words before [index]. */
    private fun before(text: String, index: Int) = words(text.substring(0, index)).takeLast(2).joinToString(" ")

    private fun after(text: String, index: Int) = words(text.substring(index)).take(2).joinToString(" ")

    /**
     * The user's answer to "what does this notification say": which amount (one of [amounts]) and which payee (as
     * typed or picked), and whether it's money in. Becomes a template for the app's next notifications.
     */
    fun learn(text: String, amount: Money, payee: String, deposit: Boolean): Template {
        val p = payee.trim()
        val at = if (p.isEmpty()) -1 else text.indexOf(p, ignoreCase = true)
        return if (at < 0) Template(deposit, before(text, amount.range.first), "", "", fixedPayee = p)
        else Template(deposit, before(text, amount.range.first), before(text, at), after(text, at + p.length))
    }

    /** Reads [text] with the first of the app's [templates] that fits; null when none does (not a money notification). */
    fun parse(text: String, templates: List<Template>): Parsed? {
        for (t in templates) parseWith(text, t)?.let { return it }
        return null
    }

    private fun findWords(text: String, phrase: String, from: Int = 0): IntRange? {
        if (phrase.isEmpty()) return null
        val want = phrase.split(' ')
        val ws = word.findAll(text).filter { it.range.first >= from }.toList()
        for (i in 0..ws.size - want.size) {
            if ((want.indices).all { ws[i + it].value.equals(want[it], ignoreCase = true) }) return ws[i].range.first..ws[i + want.size - 1].range.last
        }
        return null
    }

    private fun parseWith(text: String, t: Template): Parsed? {
        val all = amounts(text)
        val a = if (t.amountBefore.isEmpty()) all.firstOrNull() else all.firstOrNull { before(text, it.range.first).endsWith(t.amountBefore) }
        a ?: return null
        val payee = when {
            t.fixedPayee.isNotEmpty() -> t.fixedPayee
            t.payeeBefore.isEmpty() && t.payeeAfter.isEmpty() -> ""
            else -> {
                val start = if (t.payeeBefore.isEmpty()) 0 else (findWords(text, t.payeeBefore) ?: return null).last + 1
                val endAt = if (t.payeeAfter.isEmpty()) null else findWords(text, t.payeeAfter, start)?.first
                val rest = text.substring(start, endAt ?: text.length)
                clean(if (endAt == null) rest.substring(0, payeeEnd.find(rest)?.range?.first ?: rest.length) else rest)
            }
        }
        return Parsed(a.cents, payee, t.deposit)
    }

    // ---- stored form: fields joined by the unit separator, templates by the record separator ----------------------

    private const val FS = '\u001F'
    private const val RS = '\u001E'

    fun encode(ts: List<Template>): String = ts.joinToString(RS.toString()) {
        listOf(if (it.deposit) "1" else "0", it.amountBefore, it.payeeBefore, it.payeeAfter, it.fixedPayee).joinToString(FS.toString())
    }

    fun decode(s: String): List<Template> = s.split(RS).filter { it.isNotBlank() }.mapNotNull { r ->
        val f = r.split(FS)
        if (f.size < 4) null else Template(f[0] == "1", f[1], f[2], f[3], f.getOrElse(4) { "" })
    }
}
