package net.darkrelay.deaddropfinance.domain

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * How the budget's one currency is shown and typed (1.7.0): its symbol and where it goes, and the decimal and thousands
 * separators of its usual convention ("1.234,56 €" for EUR, "$1,234.56" for USD). Display only: no rates, no
 * conversion, nothing fetched; it comes from the platform's own currency and locale data.
 *
 * Amounts are stored as whole hundredths (cents) whatever the currency, so every currency shows two decimal places
 * here, even one whose own convention has none (JPY) or three (KWD): showing those any other way would change what a
 * stored amount means. [ownDigits] is the currency's own convention, kept for the screens to mention.
 */
data class CurrencyStyle(
    val code: String,
    /** Before the number, symbol included ("$", "€ "), or empty. */
    val prefix: String,
    /** After the number, symbol included (" €"), or empty. */
    val suffix: String,
    val decimal: Char,
    val group: Char,
    val ownDigits: Int,
) {
    val symbol: String get() = (prefix + suffix).trim()
}

object Currencies {
    const val DEFAULT = "USD"

    /** Shown first in the picker, in this order. */
    val COMMON = listOf("USD", "EUR", "GBP", "CAD", "AUD", "MXN", "JPY", "CHF", "INR", "NZD")

    /** The locale whose convention a currency follows, where a currency is used in many places. */
    private val HOME = mapOf(
        "USD" to Locale.US, "EUR" to Locale.GERMANY, "GBP" to Locale.UK, "CAD" to Locale.CANADA, "AUD" to Locale("en", "AU"),
        "MXN" to Locale("es", "MX"), "JPY" to Locale.JAPAN, "CHF" to Locale("de", "CH"), "INR" to Locale("en", "IN"),
        "NZD" to Locale("en", "NZ"),
    )

    data class Info(val code: String, val name: String)

    /** Every ISO 4217 currency the platform knows, by code. */
    fun all(): List<Info> = Currency.getAvailableCurrencies()
        .map { Info(it.currencyCode, it.getDisplayName(Locale.ENGLISH)) }
        .sortedBy { it.code }

    /** The picker's list: the common ones first, then the rest by code; [query] matches a code or a name. */
    fun pickerList(query: String = ""): List<Info> {
        val q = query.trim().lowercase(Locale.ROOT)
        val every = all()
        val byCode = every.associateBy { it.code }
        val common = COMMON.mapNotNull { byCode[it] }
        val list = common + every.filter { it.code !in COMMON }
        if (q.isEmpty()) return list
        return list.filter { it.code.lowercase(Locale.ROOT).contains(q) || it.name.lowercase(Locale.ROOT).contains(q) }
    }

    fun isKnown(code: String): Boolean = runCatching { Currency.getInstance(code) }.isSuccess

    private val cache = java.util.concurrent.ConcurrentHashMap<String, CurrencyStyle>()

    val USD: CurrencyStyle get() = style(DEFAULT)

    /** The style of [code] (USD for anything the platform doesn't know). */
    fun style(code: String): CurrencyStyle = cache.getOrPut(code) { build(code) }

    private fun homeOf(c: Currency): Locale {
        HOME[c.currencyCode]?.let { return it }
        return Locale.getAvailableLocales()
            .filter { it.country.isNotEmpty() && it.variant.isEmpty() && runCatching { Currency.getInstance(it) }.getOrNull() == c }
            .minByOrNull { it.toLanguageTag() } ?: Locale.US
    }

    private fun build(code: String): CurrencyStyle {
        val c = runCatching { Currency.getInstance(code) }.getOrNull() ?: return if (code == DEFAULT) fallbackUsd() else style(DEFAULT)
        val home = homeOf(c)
        val symbols = DecimalFormatSymbols.getInstance(home)
        val f = NumberFormat.getCurrencyInstance(home) as? DecimalFormat ?: return fallbackUsd()
        f.currency = c
        // the pattern's own spacing (often a no-break space), kept as an ordinary space
        fun clean(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ').replace("-", "")
        val symbol = clean(f.decimalFormatSymbols.currencySymbol)
        var prefix = clean(f.positivePrefix)
        var suffix = clean(f.positiveSuffix)
        if (!prefix.contains(symbol) && !suffix.contains(symbol)) prefix = symbol
        // grouping as the convention has it; a no-break space shows as a plain space
        val group = symbols.groupingSeparator.let { if (it == '\u00A0' || it == '\u202F') ' ' else it }
        return CurrencyStyle(code, prefix, suffix, symbols.monetaryDecimalSeparator, group, c.defaultFractionDigits.coerceAtLeast(0))
    }

    private fun fallbackUsd() = CurrencyStyle(DEFAULT, "$", "", '.', ',', 2)
}
