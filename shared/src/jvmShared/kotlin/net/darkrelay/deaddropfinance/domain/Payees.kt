package net.darkrelay.deaddropfinance.domain

import net.darkrelay.deaddropfinance.data.TransactionEntity

/**
 * Bank descriptions carry noise that changes from one charge to the next at the same place: card numbers, store
 * numbers, dates, times, reference codes, "POS PURCHASE" and the like. Rules, learning and returns compare payees
 * after this cleaning, so one rule catches every charge from the same merchant.
 */
object Payees {

    /** Words that say how a card or bank moved the money, not who it went to. */
    private val noise = setOf(
        "pos", "debit", "credit", "card", "purchase", "purchases", "authorized", "on", "recurring", "payment", "pmt",
        "ach", "visa", "mastercard", "mc", "checkcard", "refund", "return", "returned", "pending", "transaction", "trans",
        "id", "ref", "reference", "conf", "ppd", "ccd", "web", "withdrawal", "deposit", "memo", "the", "inc", "llc",
        "co", "corp", "dbt", "crd", "pin", "sig", "terminal", "num", "no", "ending", "in", "at",
    )

    /** Payment processor marks in front of the merchant's name (Square, Toast and similar). */
    private val processor = setOf("sq", "tst", "sp", "pp", "py", "ckt")

    /** Two-letter US state codes: a trailing one is the merchant's location, not its name. */
    private val states = (
        "al ak az ar ca co ct de fl ga hi id il in ia ks ky la me md ma mi mn ms mo mt ne nv nh nj nm ny nc nd oh ok or " +
            "pa ri sc sd tn tx ut vt va wa wv wi wy dc"
        ).split(' ').toSet()

    private val date = Regex("""\b\d{1,2}[/-]\d{1,2}(?:[/-]\d{2,4})?\b""")
    private val time = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
    private val mask = Regex("""[x*#]{2,}\d{2,6}""")
    private val apostrophe = Regex("""['’]""")
    private val nonWord = Regex("""[^a-z0-9&]+""")

    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 2000
    }

    /** The merchant part of a payee or bank description, lower case, single spaces. Never empty for a non-blank input. */
    fun clean(raw: String): String = synchronized(cache) { cache[raw] } ?: compute(raw).also { synchronized(cache) { cache[raw] = it } }

    /** Only the merchant's words: empty when the text is all card noise, numbers and dates (what a rule needs). */
    fun words(raw: String): String = merchantWords(raw)

    private fun compute(raw: String): String = merchantWords(raw).ifEmpty {
        // all noise: fall back to the plain words so two different blank-ish payees still differ
        raw.lowercase().replace(nonWord, " ").trim().replace(Regex(" +"), " ")
    }

    private fun merchantWords(raw: String): String {
        var s = raw.lowercase()
        s = date.replace(s, " ")
        s = time.replace(s, " ")
        s = mask.replace(s, " ")
        s = apostrophe.replace(s, "")
        s = nonWord.replace(s, " ")
        var words = s.split(' ').filter { it.isNotEmpty() }
        while (words.isNotEmpty() && words.first() in processor) words = words.drop(1)
        words = words.filter { w -> w.none { it.isDigit() } && w !in noise && w != "&" }
        if (words.size >= 3 && words.last() in states) words = words.dropLast(1)
        return words.joinToString(" ")
    }

    /** The key a transaction is learned and matched by: the bank's own description when there is one, else the payee. */
    fun key(t: TransactionEntity): String = clean(t.importDescription?.takeIf { it.isNotBlank() } ?: t.payee)

    /** Both cleaned forms of a transaction (bank description and the payee as shown), for matching rules. */
    fun forms(t: TransactionEntity): List<String> = listOfNotNull(t.importDescription?.takeIf { it.isNotBlank() }, t.payee.takeIf { it.isNotBlank() })
        .map { clean(it) }.filter { it.isNotEmpty() }.distinct()

    /** [text] appears in [payee] as whole words, in order. */
    fun contains(payee: String, text: String): Boolean = text.isNotEmpty() && " $payee ".contains(" $text ")

    /**
     * The same merchant: one cleaned name is the other, or starts with it as whole words ("sample store" and
     * "sample store anytown"). Used for matching a return to its purchase.
     */
    fun sameMerchant(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || "$a ".startsWith("$b ") || "$b ".startsWith("$a ")
    }
}
