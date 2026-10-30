package net.darkrelay.deaddropfinance.domain

/**
 * Money is a Long count of cents everywhere. Never a Float or Double: no rounding can ever happen.
 * These turn cents into text for the screen and typed text back into cents, in the budget's currency (1.7.0: [style],
 * set from the budget's currency setting; USD until one is chosen). The style changes only how amounts look and are
 * typed, never a stored amount.
 */
object Money {

    /** The budget's currency style; [use] sets it when the budget opens and when the setting changes. */
    @Volatile var style: CurrencyStyle = Currencies.USD
        private set

    /** Shows and reads amounts in [code]'s convention from now on (USD for a code the platform doesn't know). */
    fun use(code: String?) {
        style = Currencies.style(code?.takeIf { Currencies.isKnown(it) } ?: Currencies.DEFAULT)
    }

    /** In USD: 123456 -> "$1,234.56", -5 -> "-$0.05". In EUR: "1.234,56 €", "-0,05 €". */
    fun format(cents: Long): String = format(cents, style)

    fun format(cents: Long, s: CurrencyStyle): String {
        val neg = cents < 0
        // Long.MIN_VALUE has no positive twin; it is not a real amount, but don't crash on it
        val abs = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(s.group.toString()).reversed()
        val frac = (abs % 100).toString().padStart(2, '0')
        return (if (neg) "-" else "") + s.prefix + whole + s.decimal + frac + s.suffix
    }

    /** Like [format] without the symbol or grouping, for an edit field: 123456 -> "1234.56" (EUR "1234,56"). */
    fun plain(cents: Long): String = plain(cents, style)

    fun plain(cents: Long, s: CurrencyStyle): String {
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        return (if (neg) "-" else "") + (abs / 100) + s.decimal + (abs % 100).toString().padStart(2, '0')
    }

    /**
     * Typed text to cents, in the budget's currency: "12", "12.5", "12.34", "-12.34", "$1,234.56", "(12.34)"
     * (negative); in EUR also "1.234,56", "12,5" and "12,34 €". A "." or "," followed by one or two digits at the end is
     * the decimal point either way, since a phone keyboard may offer only one of them. Null if it isn't an amount, has
     * more than two decimals, or is empty.
     */
    fun parse(input: String): Long? = parse(input, style)

    fun parse(input: String, s: CurrencyStyle): Long? {
        var t = input.trim().replace(" ", "").replace("\u00A0", "").replace("\u202F", "")
            .filter { it != '\u200E' && it != '\u200F' && it != '\u061C' }
        // a convention with its own separators (Arabic's) reads as the "." and "," ones
        if (s.decimal != '.' && s.decimal != ',') t = t.replace(s.decimal, '.')
        if (s.group !in ".,'\u2019") t = t.replace(s.group.toString(), "")
        if (t.isEmpty()) return null
        var negative = false
        if (t.startsWith("(") && t.endsWith(")")) {
            negative = true
            t = t.substring(1, t.length - 1)
        }
        if (t.startsWith("-")) { negative = !negative; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
        t = stripSymbols(t, s)
        if (t.startsWith("-")) { negative = !negative; t = t.substring(1) }
        if (t.isEmpty() || t.any { !it.isDigit() && it != '.' && it != ',' && it != s.group && it != '\'' && it != '\u2019' }) return null
        val split = decimalAt(t, s) ?: return null
        val wholeRaw = if (split < 0) t else t.substring(0, split)
        val fracRaw = if (split < 0) "" else t.substring(split + 1)
        if (fracRaw.length > 2 || fracRaw.any { !it.isDigit() }) return null
        if (!groupedOk(wholeRaw, s)) return null
        val digits = wholeRaw.filter { it.isDigit() }
        if (digits.isEmpty() && fracRaw.isEmpty()) return null
        if (digits.length > 15) return null
        val whole = if (digits.isEmpty()) 0L else digits.toLong()
        val cents = whole * 100 + fracRaw.padEnd(2, '0').toLong()
        return if (negative) -cents else cents
    }

    /**
     * Where the decimal point is: -1 for none, null when the text can't be an amount. The currency's own decimal
     * separator with up to two digits after it is the decimal point; with three or more, it's too many decimals ("1.234"
     * is no amount in USD). The other separator groups thousands ("1,234" in USD, "1.234" in EUR), and with one or two
     * digits after it it's no amount ("1,23" in USD), except that a decimal-comma currency also takes a lone point as
     * the decimal point ("12.50" in EUR), since a phone keyboard may offer only a point.
     */
    private fun decimalAt(t: String, s: CurrencyStyle): Int? {
        val last = t.indexOfLast { it == '.' || it == ',' }
        if (last < 0) return -1
        val after = t.length - last - 1
        // a convention with its own decimal sign (Arabic's) was read as a point by parse
        val dec = if (s.decimal == ',') ',' else '.'
        val lonePoint = dec == ',' && t[last] == '.' && t.count { it == '.' || it == ',' } == 1
        return when {
            t[last] == dec -> if (after <= 2) last else null
            after <= 2 -> if (lonePoint) last else null
            else -> -1
        }
    }

    /** The whole part: plain digits, or groups of three after a first group of one to three. */
    private fun groupedOk(w: String, s: CurrencyStyle): Boolean {
        if (w.all { it.isDigit() }) return true
        val parts = w.split('.', ',', '\'', '\u2019', s.group)
        if (parts.any { p -> p.isEmpty() || p.any { !it.isDigit() } }) return false
        return parts.first().length in 1..3 && parts.drop(1).all { it.length == 3 }
    }

    private fun stripSymbols(t: String, s: CurrencyStyle): String {
        var r = t
        val sym = s.symbol.replace(" ", "").replace("\u00A0", "").replace("\u202F", "").filter { it != '\u200E' && it != '\u200F' && it != '\u061C' }
        for (x in listOf(s.code, sym).filter { it.isNotEmpty() }.sortedByDescending { it.length }) {
            if (r.startsWith(x)) r = r.removePrefix(x)
            if (r.endsWith(x)) r = r.removeSuffix(x)
        }
        // any other currency sign ("$", "€", "£") at either end
        r = r.trimStart { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }
        r = r.trimEnd { Character.getType(it) == Character.CURRENCY_SYMBOL.toInt() }
        return r
    }
}
