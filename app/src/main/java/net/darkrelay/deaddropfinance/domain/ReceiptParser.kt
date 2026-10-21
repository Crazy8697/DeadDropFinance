package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.ReceiptKind

/**
 * Reads a receipt from recognized text: store, date, total, tax and line items (description, quantity, price). Works
 * on lines with the recognizer's confidence (0 to 100), so a field read from a doubtful line, or one that doesn't add
 * up, is marked for the user to check. Pure: no Android, no money in anything but Long cents.
 *
 * The layout it expects is the common one: the store at the top, items with their price at the end of the line, then
 * subtotal, tax and total, then the payment. Quantities appear as "2 @ 1.99" (on the item's line or the next one),
 * "2 x", "QTY 2", or a weight ("1.25 lb @ 3.99/lb"). A discount or coupon line is a negative item.
 */
object ReceiptParser {

    data class Line(val text: String, val confidence: Int = 100)

    data class Item(val text: String, val name: String, val quantityMilli: Long = 1000, val unitPrice: Long? = null, val amount: Long)

    data class Parsed(
        val store: String,
        val date: LocalDate?,
        val total: Long?,
        val subtotal: Long?,
        val tax: Long?,
        val tip: Long?,
        val items: List<Item>,
        val kind: ReceiptKind,
        /** store, date, total, tax, items: what to check. */
        val lowConfidence: Set<String>,
        val text: String,
    )

    /** Below this, a line's reading is doubtful. */
    const val LOW_CONFIDENCE = 60

    // a price at the end of a line: optional minus or trailing minus, optional $, cents, an optional tax flag
    private val price = Regex("""(-)?\$?\s?(\d{1,3}(?:,\d{3})+|\d{1,6})\s?[.,]\s?(\d{2})\s*(-)?(?:\s+[A-Z]{1,2}|\s*\*)?\s*$""")
    private val qtyAt = Regex("""(\d{1,4}(?:\.\d{1,3})?)\s*(LB|LBS|KG|OZ|EA)?\s*@\s*\$?(\d{1,5}\.\d{2})(?:\s*/\s*(?:LB|KG|EA|OZ))?""", RegexOption.IGNORE_CASE)
    private val qtyX = Regex("""^(\d{1,3})\s*[xX]\s+""")
    private val qtyWord = Regex("""\bQTY\s*:?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val sku = Regex("""\b\d{5,}\b""")

    private val subtotalWord = Regex("""\bSUB\s?-?\s?TOTAL\b|\bSUBTTL\b|\bSUB\s?TTL\b""")
    private val totalWord = Regex("""\b(GRAND TOTAL|TOTAL DUE|AMOUNT DUE|BALANCE DUE|TOTAL|AMOUNT|TTL|REFUND TOTAL)\b""")
    private val notTotal = Regex("""\b(SAVINGS|SAVED|ITEMS|DISCOUNT|TAX|POINTS|QTY|NUMBER)\b""")
    private val taxWord = Regex("""\b(SALES TAX|TAX|VAT|GST|HST|PST)\b""")
    private val notTax = Regex("""\b(TAXABLE|NON[- ]?TAX|PRE[- ]?TAX|TAX EXEMPT)\b""")
    private val tipWord = Regex("""\b(TIP|GRATUITY)\b""")
    private val payWord = Regex(
        """\b(CASH|CHANGE|VISA|MASTERCARD|MC|AMEX|DISCOVER|DEBIT|CREDIT|TEND|TENDERED|CARD|AUTH|APPROVAL|APPROVED|ACCOUNT|ACCT|BALANCE|""" +
            """YOU SAVED|SAVINGS|POINTS|REWARDS|ITEMS SOLD|CHIP|PIN|TERMINAL|CASHIER|THANK|REF|TRANS|INVOICE)\b""",
    )
    private val discountWord = Regex("""\b(DISCOUNT|COUPON|PROMO|MARKDOWN|INSTANT SAVINGS|SAVINGS|OFF|DISC|CPN)\b""")
    private val returnWord = Regex("""\b(RETURN|RETURNS|REFUND|REFUNDED)\b""")
    private val headerSkip = Regex("""\b(WELCOME|RECEIPT|TEL|PHONE|WWW|HTTP|STORE\s*#|ST\s*#|OPEN|HOURS|CUSTOMER COPY|COPY)\b|\.COM\b""")

    private val months = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val dateNumeric = Regex("""\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{2,4})\b""")
    private val dateIso = Regex("""\b(20\d{2})-(\d{2})-(\d{2})\b""")
    private val dateMonthFirst = Regex("""\b(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?\s+(\d{1,2}),?\s+(\d{4})\b""")
    private val dateDayFirst = Regex("""\b(\d{1,2})[\s-](JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\.?[\s-](\d{2,4})\b""")

    fun parse(pages: List<List<Line>>): Parsed = parseLines(joinPages(pages))

    /**
     * Long receipts come as several photos that may overlap: the lines at the start of a page that repeat the end of
     * the page before are dropped.
     */
    fun joinPages(pages: List<List<Line>>): List<Line> {
        val out = mutableListOf<Line>()
        for (p in pages) {
            val page = p.filter { it.text.isNotBlank() }
            var overlap = 0
            for (k in minOf(12, page.size, out.size) downTo 1) {
                if ((0 until k).all { norm(out[out.size - k + it].text) == norm(page[it].text) }) { overlap = k; break }
            }
            out += page.drop(overlap)
        }
        return out
    }

    private fun norm(s: String) = s.uppercase().replace(Regex("""\s+"""), " ").trim()

    /** O and l read in place of 0 and 1 inside a price ("1O.5O"). */
    private fun fixDigits(s: String): String = s.replace(Regex("""(?<=\s|^)[lI](?=\d{0,5}[.,]\d{2})""")) { "1" }
        .replace(Regex("""(?<=\s|^)O(?=\d{0,5}[.,]\d{2})""")) { "0" }
        .replace(Regex("""(?<=\d)[Oo](?=[\d.,])|(?<=[\d.,])[Oo](?=\d|\b)""")) { "0" }
        .replace(Regex("""(?<=\d)[lI](?=[\d.,])|(?<=[\d.,])[lI](?=\d)""")) { "1" }

    private data class Priced(val before: String, val cents: Long, val line: Line, val index: Int)

    private fun priceOf(raw: String): Pair<String, Long>? {
        val s = fixDigits(raw.trim())
        val m = price.find(s) ?: return null
        val whole = m.groupValues[2].replace(",", "").toLong()
        val cents = whole * 100 + m.groupValues[3].toLong()
        val negative = m.groupValues[1].isNotEmpty() || m.groupValues[4].isNotEmpty()
        val before = s.substring(0, m.range.first).trim().trimEnd('$').trim()
        return before to if (negative) -cents else cents
    }

    fun parseLines(lines: List<Line>): Parsed {
        val text = lines.joinToString("\n") { it.text }
        val upper = lines.map { norm(it.text) }
        val low = mutableSetOf<String>()

        // the summary block: subtotal, tax, tip, total
        var subtotal: Priced? = null
        var total: Priced? = null
        var tip: Priced? = null
        val taxes = mutableListOf<Priced>()
        var firstSummary = lines.size
        lines.forEachIndexed { i, l ->
            val u = upper[i]
            val p = priceOf(l.text) ?: return@forEachIndexed
            val pr = Priced(p.first, p.second, l, i)
            when {
                subtotalWord.containsMatchIn(u) -> { subtotal = subtotal ?: pr; firstSummary = minOf(firstSummary, i) }
                taxWord.containsMatchIn(u) && !notTax.containsMatchIn(u) && !totalWord.containsMatchIn(u.replace(taxWord, "")) -> {
                    taxes += pr; firstSummary = minOf(firstSummary, i)
                }
                tipWord.containsMatchIn(u) -> { tip = pr; firstSummary = minOf(firstSummary, i) }
                totalWord.containsMatchIn(u) && !notTotal.containsMatchIn(u.replace(totalWord, "")) && !payWord.containsMatchIn(u) -> {
                    // the last total before the payment lines; a later "TOTAL" after a tip line is the one paid
                    if (total == null || i > total!!.index) total = pr
                    firstSummary = minOf(firstSummary, i)
                }
            }
        }

        // header: the store is the first line that reads like a name
        val firstPriced = lines.indices.firstOrNull { priceOf(lines[it].text) != null } ?: lines.size
        var storeLine: Line? = null
        var storeIndex = -1
        for (i in 0 until minOf(6, firstPriced, lines.size)) {
            val t = lines[i].text.trim()
            val u = upper[i]
            val letters = t.count { it.isLetter() }
            if (letters < 3 || letters * 2 < t.count { !it.isWhitespace() } || headerSkip.containsMatchIn(u) && !u.startsWith("WELCOME TO ")) continue
            if (findDate(u) != null) continue
            storeLine = lines[i].copy(text = t.replace(Regex("""(?i)^welcome to\s+"""), "").replace(Regex("""\s+"""), " "))
            storeIndex = i
            break
        }
        val store = storeLine?.text ?: ""
        if (storeLine == null || storeLine.confidence < LOW_CONFIDENCE) low += "store"

        val dateIdx = upper.indices.firstOrNull { findDate(upper[it]) != null }
        val date = dateIdx?.let { findDate(upper[it]) }
        if (date == null || lines[dateIdx].confidence < LOW_CONFIDENCE) low += "date"

        // items: priced lines between the header and the summary that aren't payment lines
        val items = mutableListOf<Item>()
        var pendingDesc: Line? = null
        val start = storeIndex + 1
        for (i in start until firstSummary) {
            val l = lines[i]
            val u = upper[i]
            // a line that is only a quantity, "3 @ 1.99", with no line total
            qtyAt.matchEntire(l.text.trim())?.let { q ->
                val qm = milli(q.groupValues[1]); val up = cents(q.groupValues[3])
                val pd = pendingDesc
                if (pd != null) {
                    val d = pd.text.trim().replace(sku, " ").replace(Regex("""\s+"""), " ").trim()
                    items += Item(d, cleanName(d), qm, up, up * qm / 1000)
                    pendingDesc = null
                } else if (items.isNotEmpty()) {
                    // under an item that already has its price: that item's quantity
                    val last = items.last()
                    if (kotlin.math.abs(up * qm / 1000 - last.amount) <= 1) items[items.lastIndex] = last.copy(quantityMilli = qm, unitPrice = up)
                }
                continue
            }
            val p = priceOf(l.text)
            if (p == null) {
                val q = qtyAt.find(l.text)
                // a description on its own line, its price on the next
                pendingDesc = if (q == null && l.text.count { it.isLetter() } >= 2 && !payWord.containsMatchIn(u) && findDate(u) == null) l else pendingDesc
                continue
            }
            if (payWord.containsMatchIn(u) && !discountWord.containsMatchIn(u) || findDate(u) != null) { pendingDesc = null; continue }
            var desc = p.first
            var amount = p.second
            var qty = 1000L
            var unit: Long? = null
            qtyAt.find(desc)?.let { m ->
                qty = milli(m.groupValues[1])
                unit = cents(m.groupValues[3])
                desc = desc.removeRange(m.range).trim()
            }
            if (desc.count { it.isLetter() } < 2 && pendingDesc != null) desc = pendingDesc.text.trim()
            if (desc.count { it.isLetter() } < 2) { pendingDesc = null; continue }
            qtyX.find(desc)?.let { m -> qty = m.groupValues[1].toLong() * 1000; desc = desc.removeRange(m.range).trim() }
            qtyWord.find(desc)?.let { m -> qty = m.groupValues[1].toLong() * 1000; desc = desc.removeRange(m.range).trim() }
            desc = desc.replace(sku, " ").replace(Regex("""\s+[A-Z]$"""), "").replace(Regex("""\s+"""), " ").trim()
            if (discountWord.containsMatchIn(u) && amount > 0) amount = -amount
            if (unit == null && amount > 0 && qty > 0) unit = amount * 1000 / qty
            items += Item(desc, cleanName(desc), qty, unit, amount)
            if (l.confidence < LOW_CONFIDENCE) low += "items"
            pendingDesc = null
        }

        val isReturn = returnWord.containsMatchIn(upper.joinToString(" ")) || (total?.cents ?: 0) < 0
        val tax = taxes.takeIf { it.isNotEmpty() }?.sumOf { kotlin.math.abs(it.cents) }
        if (taxes.any { it.line.confidence < LOW_CONFIDENCE }) low += "tax"
        var totalCents = total?.cents?.let { kotlin.math.abs(it) }
        if (total == null || total!!.line.confidence < LOW_CONFIDENCE) low += "total"
        val itemsSum = items.sumOf { it.amount }.let { kotlin.math.abs(it) }
        val sub = subtotal?.cents?.let { kotlin.math.abs(it) }
        val tipCents = tip?.cents?.let { kotlin.math.abs(it) }
        if (totalCents == null) {
            // no total line read: work it out, but ask
            totalCents = (sub ?: itemsSum.takeIf { it > 0 })?.let { it + (tax ?: 0) + (tipCents ?: 0) }
            low += "total"
        }
        // does it add up?
        if (items.isEmpty()) low += "items"
        else if (sub != null && kotlin.math.abs(itemsSum - sub) > 1) low += "items"
        else if (totalCents != null && kotlin.math.abs((sub ?: itemsSum) + (tax ?: 0) + (tipCents ?: 0) - totalCents) > 1) {
            low += "items"; low += "total"
        }
        val outItems = if (isReturn) items.map { it.copy(amount = kotlin.math.abs(it.amount), unitPrice = it.unitPrice?.let { u -> kotlin.math.abs(u) }) } else items
        return Parsed(store, date, totalCents, sub, tax, tipCents, outItems, if (isReturn) ReceiptKind.RETURN else ReceiptKind.PURCHASE, low, text)
    }

    /** The item's name for matching and price history: lower case words, no codes, numbers or signs. */
    fun cleanName(desc: String): String = desc.lowercase().replace(Regex("""[^a-z ]+"""), " ").split(' ')
        .filter { it.length >= 2 }.joinToString(" ")

    private fun milli(s: String): Long {
        val parts = s.split('.')
        val whole = parts[0].toLong() * 1000
        val frac = parts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        return whole + frac
    }

    private fun cents(s: String): Long { val (w, c) = s.split('.'); return w.toLong() * 100 + c.toLong() }

    /** The first date on a line, or null. US order (month first) unless the first number can't be a month. */
    fun findDate(u: String): LocalDate? {
        fun ok(y: Int, m: Int, d: Int) = runCatching { LocalDate.of(y, m, d) }.getOrNull()?.takeIf { y in 2000..2099 }
        fun year(s: String) = s.toInt().let { if (it < 100) 2000 + it else it }
        dateIso.find(u)?.let { m -> ok(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { return it } }
        dateNumeric.find(u)?.let { m ->
            val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); val y = year(m.groupValues[3])
            (if (a > 12) ok(y, b, a) else ok(y, a, b))?.let { return it }
        }
        dateMonthFirst.find(u)?.let { m -> ok(m.groupValues[3].toInt(), months.indexOf(m.groupValues[1]) + 1, m.groupValues[2].toInt())?.let { return it } }
        dateDayFirst.find(u)?.let { m -> ok(year(m.groupValues[3]), months.indexOf(m.groupValues[2]) + 1, m.groupValues[1].toInt())?.let { return it } }
        return null
    }
}
