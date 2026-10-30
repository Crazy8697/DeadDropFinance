package net.darkrelay.deaddropfinance.domain

import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The budget's currency (1.7.0): how amounts show and are typed in it, from the platform's own currency data. Amounts
 * are whole hundredths in every currency, so each shows two decimals. Display only: nothing here converts.
 */
class CurrencyTest {

    @After fun backToUsd() = Money.use(null)

    /** The convention's spaces are no-break ones on some platforms; compared as plain spaces. */
    private fun f(cents: Long, code: String) = Money.format(cents, Currencies.style(code)).replace(' ', ' ').replace(' ', ' ')

    @Test fun usdIsTheDefaultAndUnchanged() {
        assertEquals("USD", Money.style.code)
        assertEquals("$1,234.56", Money.format(123456))
        assertEquals("-$0.05", Money.format(-5))
        assertEquals("1234.56", Money.plain(123456))
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(-1234L, Money.parse("(12.34)"))
        assertEquals(1250L, Money.parse("12.5"))
        assertNull(Money.parse("1.234"), "three decimals is no amount in USD")
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
    }

    @Test fun eachCurrencyShowsItsOwnConvention() {
        assertEquals("$1,234.56", f(123456, "USD"))
        assertEquals("1.234,56 €", f(123456, "EUR"))
        assertEquals("£1,234.56", f(123456, "GBP"))
        // JPY's own convention has no decimals, but amounts are hundredths, so two show (see the class note)
        assertTrue(Regex("""^\D?1,234\.56$""").matches(f(123456, "JPY")), f(123456, "JPY"))
        assertTrue(f(123456, "JPY").first() in "¥￥", f(123456, "JPY"))
        assertEquals(0, Currencies.style("JPY").ownDigits)
        assertEquals("-1.234,56 €", f(-123456, "EUR"))
    }

    @Test fun aThreeDecimalCurrencyShowsTwoAndReadsBack() {
        for (code in listOf("KWD", "BHD", "TND")) {
            val st = Currencies.style(code)
            assertEquals(3, st.ownDigits, code)
            val shown = Money.format(123456, st)
            assertTrue(shown.contains(st.symbol.trim().filter { it.isLetter() }.take(1)), "$code: $shown")
            assertEquals(123456L, Money.parse(shown, st), "$code reads its own text back: $shown")
            assertEquals(123456L, Money.parse(Money.plain(123456, st), st), "$code reads its edit text back")
            assertEquals(1250L, Money.parse("12.5", st), "$code: a typed point is the decimal point")
        }
    }

    @Test fun everyCurrencyReadsBackWhatItShows() {
        for (c in Currencies.all()) {
            val st = Currencies.style(c.code)
            for (v in listOf(0L, 5L, 123456L, -987654321L)) {
                assertEquals(v, Money.parse(Money.format(v, st), st), "${c.code}: ${Money.format(v, st)}")
                assertEquals(v, Money.parse(Money.plain(v, st), st), "${c.code}: ${Money.plain(v, st)}")
            }
        }
    }

    @Test fun typedAmountsInEur() {
        Money.use("EUR")
        assertEquals(123456L, Money.parse("1.234,56"))
        assertEquals(123456L, Money.parse("1234,56 €"))
        assertEquals(1250L, Money.parse("12,5"))
        // a phone keyboard may offer only a point: one or two digits after it is still the decimal point
        assertEquals(1250L, Money.parse("12.50"))
        assertEquals(123400L, Money.parse("1.234"), "a point with three digits after it is thousands in EUR")
        assertNull(Money.parse("1,234"), "three decimals is no amount")
        assertEquals("1234,56", Money.plain(123456))
        assertEquals("1.234,56 €", Money.format(123456).replace(' ', ' '))
    }

    @Test fun typedAmountsInGbpAndJpy() {
        Money.use("GBP")
        assertEquals(123456L, Money.parse("£1,234.56"))
        Money.use("JPY")
        assertEquals(123400L, Money.parse("1234"))
        assertEquals(123456L, Money.parse(Money.format(123456)))
    }

    @Test fun anUnknownCodeStaysUsd() {
        Money.use("XYZ-not-a-currency")
        assertEquals("USD", Money.style.code)
        Money.use(null)
        assertEquals("USD", Money.style.code)
    }

    @Test fun thePickerListsTheCommonOnesFirstThenEveryTwoDecimalCurrency() {
        val list = Currencies.pickerList()
        assertEquals(listOf("USD", "EUR", "GBP", "CAD", "AUD", "MXN", "CHF", "INR", "NZD"), Currencies.commonOffered)
        assertEquals(Currencies.commonOffered, list.take(9).map { it.code })
        assertTrue(list.size > 100, "the rest of the ISO 4217 list")
        assertEquals(list.size, list.map { it.code }.toSet().size, "each once")
        // 1.7.1: only currencies whose usual convention is two decimals: amounts are hundredths
        assertTrue(list.all { java.util.Currency.getInstance(it.code).defaultFractionDigits == 2 })
        for (code in listOf("JPY", "KRW", "VND", "CLP", "ISK", "KWD", "BHD", "TND", "JOD", "OMR")) {
            assertTrue(list.none { it.code == code }, "$code isn't offered")
            assertTrue(Currencies.hidden().any { it.code == code }, "$code is listed as hidden")
        }
        assertEquals(Currencies.all().size, list.size + Currencies.hidden().size)
        assertTrue(Currencies.pickerList("yen").isEmpty())
        assertTrue(Currencies.pickerList("eur").any { it.code == "EUR" })
        assertTrue(Currencies.pickerList("swiss").any { it.code == "CHF" })
        assertTrue(Currencies.pickerList("no such money").isEmpty())
    }

    @Test fun csvAmountsFollowTheFileThenTheCurrency() {
        Money.use("EUR")
        assertEquals(123456L, CsvImport.parseCents("1.234,56 €", decimalComma = true))
        assertEquals(-1234L, CsvImport.parseCents("-12,34 EUR", decimalComma = true))
        // a semicolon file with whole amounts only: the currency decides the decimal comma
        val eurLayout = CsvImport.detect("Date;Description;Amount\n2030-01-02;Sample Market;-12\n2030-01-03;Sample Pay;1200\n")
        assertTrue(eurLayout.decimalComma)
        // the file's own amounts win over the currency
        val dotFile = CsvImport.detect("Date;Description;Amount\n2030-01-02;Sample Market;-12.34\n")
        assertTrue(!dotFile.decimalComma)
        Money.use("USD")
        val usdLayout = CsvImport.detect("Date;Description;Amount\n2030-01-02;Sample Market;-12\n")
        assertTrue(!usdLayout.decimalComma)
        assertEquals(1234567L, CsvImport.parseCents("$12,345.67", decimalComma = false))
    }
}
