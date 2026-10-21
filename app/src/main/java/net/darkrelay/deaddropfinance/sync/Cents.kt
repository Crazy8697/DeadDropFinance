package net.darkrelay.deaddropfinance.sync

import java.math.BigDecimal
import java.math.RoundingMode

/** Provider amounts arrive as numeric strings. They become whole cents without ever touching floating point. */
object Cents {
    /** "-12.34" -> -1234, "12.3400" -> 1234, "5" -> 500. Null if it isn't a plain decimal number. */
    fun parse(s: String?): Long? {
        val t = s?.trim() ?: return null
        if (t.isEmpty() || !Regex("""[+-]?(\d+(\.\d*)?|\.\d+)""").matches(t)) return null
        return try {
            BigDecimal(t).movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        } catch (e: ArithmeticException) {
            null
        }
    }
}
