package net.darkrelay.deaddropfinance.domain

/**
 * Money is a Long count of cents everywhere. Never a Float or Double: no rounding can ever happen.
 * These turn cents into text for the screen and typed text back into cents.
 */
object Money {

    /** 123456 -> "$1,234.56", -5 -> "-$0.05". */
    fun format(cents: Long): String {
        val neg = cents < 0
        // Long.MIN_VALUE has no positive twin; it is not a real amount, but don't crash on it
        val abs = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(cents)
        val whole = (abs / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val frac = (abs % 100).toString().padStart(2, '0')
        return (if (neg) "-" else "") + "$" + whole + "." + frac
    }

    /** Like [format] without the dollar sign or grouping, for an edit field: 123456 -> "1234.56". */
    fun plain(cents: Long): String {
        val neg = cents < 0
        val abs = kotlin.math.abs(cents)
        return (if (neg) "-" else "") + (abs / 100) + "." + (abs % 100).toString().padStart(2, '0')
    }

    private val AMOUNT = Regex("""^([+-])?\$?(\d{1,15}|\d{1,3}(?:,\d{3})+)?(?:\.(\d{0,2}))?$""")

    /**
     * Typed text to cents: "12", "12.5", "12.34", "-12.34", "$1,234.56", "(12.34)" (negative). Null if it isn't
     * an amount, has more than two decimals, or is empty.
     */
    fun parse(input: String): Long? {
        var s = input.trim().replace(" ", "")
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        val m = AMOUNT.matchEntire(s) ?: return null
        val (sign, wholeRaw, fracRaw) = m.destructured
        if (wholeRaw.isEmpty() && fracRaw.isEmpty()) return null
        if (sign == "-") negative = !negative
        val whole = if (wholeRaw.isEmpty()) 0L else wholeRaw.replace(",", "").toLong()
        val frac = fracRaw.padEnd(2, '0').ifEmpty { "00" }.toLong()
        val cents = whole * 100 + frac
        return if (negative) -cents else cents
    }
}
