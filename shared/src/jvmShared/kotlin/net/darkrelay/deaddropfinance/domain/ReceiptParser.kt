package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.ReceiptKind

/**
 * Reads a receipt from recognized text: store, date, total, tax and line items (description, quantity, price, tax
 * letter, the section it was under). Works on lines with the recognizer's confidence (0 to 100), so a field read from a
 * doubtful line, or one that doesn't add up, is marked for the user to check. Pure: no Android, no money in anything
 * but Long cents.
 *
 * 1.9.4: one reader for the common layouts of many stores, not one store's. Items have their price at the end of the
 * line, then any flags: a tax letter ("A", "N", "T") and marks ("*", "#"); the flags that aren't tax letters (W, M, unit
 * words) are never one. Quantities come as "2 @ 11.49", "2 x 11.49" or "QTY 2", and weights as "1.26 lb @ 2.89/lb", on
 * the item's line or the line under it, with the line total checked. Section headers (GROCERY, PRODUCE...) are the
 * section of the items under them, never an item or the store. Savings lines ("You saved 1.50") are never items; a
 * coupon or discount comes off the item above it. The tax block (per letter, per group "Tx 1", per rate, with or without
 * the taxable amount, "TOTAL TAX"), totals, balance due, payment and card lines are never items.
 */
object ReceiptParser {

    data class Line(val text: String, val confidence: Int = 100)

    /**
     * [taxLetter]: the flag at the end of the line ("A", "T", "*"), never part of [text] or [name]; empty when there is
     * none. [amount] is what was paid for the line (after any coupon or discount on it). [section]: the header it was under.
     */
    data class Item(
        val text: String, val name: String, val quantityMilli: Long = 1000, val unitPrice: Long? = null, val amount: Long,
        val taxLetter: String = "", val section: String = "",
    )

    /**
     * A tax line at the bottom: its [amount], the item letter it is for ([letter], null when it names none), its rate in
     * thousandths of a percent ([rateMilli], 6000 = 6%; null when it prints none) and the taxable amount it was worked on
     * ([taxable], null when it prints none).
     */
    data class TaxLine(val amount: Long, val letter: String? = null, val rateMilli: Long? = null, val taxable: Long? = null)

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
        /** Each tax line read at the bottom (a "TOTAL TAX" line is [tax], not one of these). */
        val taxLines: List<TaxLine> = emptyList(),
    )

    /** Below this, a line's reading is doubtful. */
    const val LOW_CONFIDENCE = 60

    // a price at the end of a line: optional minus or trailing minus, optional $, cents, then any flags (letters, marks)
    private val price = Regex("""(-)?\$?\s?(\d{1,3}(?:,\d{3})+|\d{1,6})\s?[.,]\s?(\d{2})\s*(-)?((?:\s*(?:[A-Za-z]{1,2}|[*#+%^~×]))*)\s*$""")

    /** Flags after (or before) a price that are never tax letters: weighed, member price, unit words. */
    private val notTaxFlags = setOf("W", "M", "EA", "LB", "OZ", "KG", "CT", "PK", "PC", "QT", "FT", "GA", "G", "L")

    // a quantity or weight line: "2 @ 11.49", "2 x 11.49", "QTY 2 @ 1.99", "1.26 lb @ 2.89/lb", then an optional line total
    private val qtyLine = Regex(
        """^\s*(?:QTY\s*:?\s*)?(\d{1,4}(?:[.,]\d{1,3})?)\s*(LB|LBS|KG|OZ|EA|G)?\s*(?:@|[xX×]|\bAT\b)\s*\$?(\d{1,5}[.,]\d{2})\s*(?:/\s*(?:LB|LBS|KG|EA|OZ|G)\b)?(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    // the same with the "@" misread ("2 0 11.49", "2 e 11.49"): only taken when its line total checks
    private val qtyLineMisread = Regex("""^\s*(\d{1,3})\s+[0OoeEaA©®]\s+\$?(\d{1,5}[.,]\d{2})\s+(.+)$""")
    private val qtyAt = Regex("""(?<![\d.])(\d{1,4}(?:\.\d{1,3})?)\s*(LB|LBS|KG|OZ|EA)?\s*(?:@|[xX×](?=\s*\$?\d))\s*\$?(\d{1,5}\.\d{2})(?:\s*/\s*(?:LB|KG|EA|OZ))?""", RegexOption.IGNORE_CASE)
    private val qtyX = Regex("""^(\d{1,3})\s*[xX]\s+""")
    private val qtyWord = Regex("""\bQTY\s*:?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE)
    private val sku = Regex("""\b\d{5,}\b""")

    private val subtotalWord = Regex("""\bSUB\s?-?\s?TOTAL\b|\bSUBTTL\b|\bSUB\s?TTL\b""")
    private val totalWord = Regex("""\b(GRAND TOTAL|TOTAL DUE|AMOUNT DUE|BALANCE DUE|BAL DUE|TOTAL|AMOUNT|TTL|REFUND TOTAL)\b""")
    private val dueWord = Regex("""\b(TOTAL DUE|AMOUNT DUE|BALANCE DUE|BAL DUE|GRAND TOTAL)\b""")
    private val notTotal = Regex("""\b(SAVINGS|SAVED|ITEMS|DISCOUNT|TAX|POINTS|QTY|NUMBER|COUPONS?)\b""")
    // "Tx 1" as printed, and as text recognition reads it: "Txt", "TxI", "Tx|"
    private val taxWord = Regex("""\b(SALES TAX|TAX|TAXES|VAT|GST|HST|PST)\b|\bTX\s*[0-9TLI|](?![A-Z0-9])""")
    private val taxTotalWord = Regex("""\bTOTAL\s+(SALES\s+)?TAX(ES)?\b|\bTAX\s+TOTAL\b|\bTOTAL\s+TX\b""")
    private val notTax = Regex("""\b(TAXABLE|NON[- ]?TAX(ABLE)?|PRE[- ]?TAX|TAX EXEMPT)\b""")
    // a tax table row that starts with its letter and rate: "A 7.00% 45.46 3.18", "B = 6.25 %"
    private val taxTableRow = Regex("""^\s*[A-Z*]\s*[=:-]?\s*\d{1,2}(?:[.,]\d{1,4})?\s*%""")
    private val tipWord = Regex("""\b(TIP|GRATUITY)\b""")
    private val payWord = Regex(
        """\b(CASH|CHANGE|VISA|MASTERCARD|MC|AMEX|DISCOVER|DEBIT|CREDIT|TEND|TENDERED|CARD|AUTH|APPROVAL|APPROVED|ACCOUNT|ACCT|BALANCE|""" +
            """YOU SAVED|SAVINGS|POINTS|REWARDS|ITEMS SOLD|CHIP|PIN|TERMINAL|CASHIER|THANK|REF|TRANS|INVOICE|RRN|EBT|PAYMENT|PAID)\b""",
    )
    // lines that only say what was saved or what a price was: never an item, nothing comes off
    private val infoWord = Regex("""\b(YOU SAVED|YOU SAVE|SAVED|TOTAL SAVINGS|YOUR SAVINGS|MEMBER PRICE|REG PRICE|REGULAR PRICE|ORIGINAL PRICE|WAS)\b""")
    // a coupon or discount: comes off the item above it
    private val discountWord = Regex("""\b(DISCOUNT|COUPON|PROMO|MARKDOWN|INSTANT SAVINGS|SAVINGS|DISC|CPN)\b|\d\s*OFF\b|%\s*OFF\b|\bOFF\s*\$?\d""")
    private val alwaysOff = Regex("""\b(DISCOUNT|COUPON|PROMO|MARKDOWN|DISC|CPN)\b|\d\s*OFF\b|%\s*OFF\b|\bOFF\s*\$?\d""")
    private val returnWord = Regex("""\b(RETURN|RETURNS|REFUND|REFUNDED)\b""")
    private val headerSkip = Regex("""\b(WELCOME|RECEIPT|TEL|PHONE|WWW|HTTP|STORE\s*#|ST\s*#|OPEN|HOURS|CUSTOMER COPY|COPY)\b|\.COM\b""")

    /** Words a section header is made of (GROCERY, FROZEN/DAIRY, HEALTH & BEAUTY...). */
    private val sectionWords = setOf(
        "GROCERY", "GROCERIES", "MEAT", "MEATS", "PRODUCE", "DAIRY", "FROZEN", "FROZENS", "DELI", "BAKERY", "SEAFOOD", "REFRIGERATED",
        "FRESH", "FOOD", "FOODS", "BEVERAGE", "BEVERAGES", "DRINKS", "SNACKS", "CANDY", "BEER", "WINE", "LIQUOR", "ALCOHOL", "SPIRITS",
        "HEALTH", "BEAUTY", "HBA", "HBC", "PHARMACY", "RX", "HOUSEHOLD", "HOME", "CLEANING", "PAPER", "PET", "PETS", "BABY", "KIDS",
        "GENERAL", "MERCHANDISE", "GM", "MISC", "MISCELLANEOUS", "NON", "NONFOOD", "ELECTRONICS", "APPAREL", "CLOTHING", "TOYS",
        "ENTERTAINMENT", "SEASONAL", "GARDEN", "HARDWARE", "AUTOMOTIVE", "OFFICE", "SCHOOL", "SPORTS", "PERSONAL", "CARE", "FLORAL",
        "NATURAL", "ORGANIC", "INTERNATIONAL", "BULK", "DEPT", "DEPARTMENT", "BAKED", "GOODS",
    )

    /** Sections whose items are food: they default to a groceries envelope. */
    val foodSections = setOf(
        "GROCERY", "GROCERIES", "MEAT", "MEATS", "PRODUCE", "DAIRY", "FROZEN", "FROZENS", "DELI", "BAKERY", "SEAFOOD", "REFRIGERATED",
        "FRESH", "FOOD", "FOODS", "BAKED", "NATURAL", "ORGANIC", "BULK", "SNACKS", "CANDY", "BEVERAGE", "BEVERAGES",
    )

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

    /** A price at the end of [raw]: the words before it, the cents (negative when marked so) and the flags after it. */
    private data class Price(val before: String, val cents: Long, val flags: List<String>)

    private fun priceOf(raw: String): Price? {
        val s = fixDigits(raw.trim())
        val m = price.find(s) ?: return null
        val whole = m.groupValues[2].replace(",", "").toLong()
        val cents = whole * 100 + m.groupValues[3].toLong()
        val negative = m.groupValues[1].isNotEmpty() || m.groupValues[4].isNotEmpty()
        val before = s.substring(0, m.range.first).trim().trimEnd('$').trim()
        // "A *", "A*", and as text recognition reads them, "Ax", "A x": a capital letter, then marks
        val flags = Regex("""[A-Z]{1,2}(?![A-Z])|[a-z]+|[*#+%^~×]""").findAll(m.groupValues[5]).map { it.value }.toList()
        return Price(before, if (negative) -cents else cents, flags)
    }

    /** The tax letter among a price's flags: the first one or two capital letters that isn't a unit word or W/M. */
    private fun letterOf(flags: List<String>): String = flags.firstOrNull { it.matches(Regex("[A-Z]{1,2}")) && it !in notTaxFlags } ?: ""

    /** "GROCERY", "FROZEN/DAIRY", "HEALTH & BEAUTY": the header's words, or null when the line isn't a section header. */
    private fun sectionOf(u: String): String? {
        if (u.any { it.isDigit() }) return null
        // marks the text recognition adds around it (quotes, commas) don't count
        val clean = u.replace(Regex("""[^A-Z/&+\s-]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val words = clean.split(Regex("""[\s/&,+-]+""")).filter { it.isNotBlank() && it != "AND" }
        if (words.isEmpty() || words.size > 4) return null
        val fixed = words.map { w -> sectionWords.firstOrNull { it == w } ?: sectionWords.firstOrNull { w.length >= 5 && it.length == w.length && it.zip(w).count { (a, b) -> a != b } == 1 } }
        if (fixed.any { it == null }) return null
        // a word read with one letter wrong ("EROZEN") is taken as the section word it nearly is
        var out = clean
        words.zip(fixed).forEach { (w, f) -> if (w != f) out = out.replace(w, f!!) }
        return out.trim()
    }

    /** A capital letter, then any marks, at the very end of a line whose price couldn't be read ("a.64 Ax"). */
    private val trailingLetter = Regex("""\s([A-Z])(?:\s*[*xX×#+]+)?\s*$""")

    /** Glued or misread "@": "20 11.49 22.98" is 2 @ 11.49 when 2 x 11.49 is the line total. */
    private val qtyLineGlued = Regex("""^\s*(\d{1,3}[0-9OoeEaA©®@]?)\s+\$?(\d{1,5}[.,]\d{2})\s+(.+)$""")

    private val money = Regex("""(?<![\d.,])(\d{1,3}(?:,\d{3})+|\d{1,6})[.,](\d{2})(?![\d%])""")
    private val ratePct = Regex("""(\d{1,2}(?:[.,]\d{1,4})?)\s*%""")
    // a group row: the rate, then "Tx 2" or "TAX 2" ("7.00 Tx 2 45.46 3.18")
    private val groupRow = Regex("""(?<![\d.,:])(\d{1,2}[.,:]\d{1,4})\s+(?:TX|TAX)\s*([0-9TLI|])(?![A-Z0-9])""")
    private val taxWords = Regex("""\b(SALES|TAX|TAXES|TX|VAT|GST|HST|PST|RATE|CODE|TYPE|ON|PAID)\b""")
    private val letterToken = Regex("""(?<![A-Z0-9])([A-Z]|\*)(?![A-Z0-9])""")

    private fun milli(s: String): Long {
        val parts = s.replace(',', '.').replace(':', '.').split('.')
        val whole = parts[0].toLong() * 1000
        val frac = parts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        return whole + frac
    }

    private fun cents(s: String): Long { val (w, c) = s.replace(',', '.').split('.'); return w.toLong() * 100 + c.toLong() }

    /**
     * A tax line: its letter, rate, taxable amount and tax, from the common layouts ("TAX A 6.000% 0.47", "A TAX 6% 0.47",
     * "0.00 Tx 1 63.56 0.00", "SALES TAX 8% 1.36", "TAX 0.60"). With two amounts the first is the taxable amount and the
     * last the tax. A group row ("7.00 Tx 2 45.46") whose tax column wasn't read has its taxable amount only: its tax is
     * worked out from the rate, unless the one amount is the receipt's total tax.
     */
    private fun taxLineOf(raw: String, totalTax: Long?): TaxLine? {
        val u = norm(fixDigits(raw))
        val group = groupRow.find(u)
        val pct = ratePct.find(u)
        val rate = pct?.let { milli(it.groupValues[1]) } ?: group?.let { milli(it.groupValues[1]) }
        val rateRange = pct?.groups?.get(1)?.range ?: group?.groups?.get(1)?.range
        val nums = money.findAll(u).filter { m -> rateRange == null || m.range.last < rateRange.first || m.range.first > rateRange.last }
            .map { cents("${it.groupValues[1].replace(",", "")}.${it.groupValues[2]}") }.toList()
        if (nums.isEmpty()) return null
        var rest = u
        listOfNotNull(pct?.value, group?.value).forEach { rest = rest.replace(it, " ") }
        rest = rest.replace(money, " ").replace(taxWords, " ")
        // a group row ("Tx 2") names a group, not a letter: what's left on it is misread marks
        val letter = if (group != null) null else letterToken.findAll(rest).map { it.groupValues[1] }.filter { it !in notTaxFlags }.toList().singleOrNull()
        return when {
            nums.size >= 2 -> TaxLine(nums.last(), letter, rate, nums[nums.size - 2])
            group != null && rate != null && nums[0] != totalTax -> TaxLine((nums[0] * rate + 50_000) / 100_000, letter, rate, nums[0])
            else -> TaxLine(nums[0], letter, rate)
        }
    }

    fun parseLines(lines: List<Line>): Parsed {
        val text = lines.joinToString("\n") { it.text }
        val upper = lines.map { norm(it.text) }
        val low = mutableSetOf<String>()

        // the summary block: subtotal, tax, tip, total
        var subtotal: Long? = null
        var total: Pair<Long, Int>? = null
        var tip: Long? = null
        var taxTotal: Pair<Long, Line>? = null
        val taxRows = mutableListOf<Int>()
        var firstSummary = lines.size
        lines.forEachIndexed { i, l ->
            val u = upper[i]
            val isTaxRow = (taxWord.containsMatchIn(u) || taxTableRow.containsMatchIn(u)) && !notTax.containsMatchIn(u) && !dueWord.containsMatchIn(u) &&
                !taxTotalWord.containsMatchIn(u) && !totalWord.containsMatchIn(u.replace(taxWord, ""))
            val p = priceOf(l.text)
            if (p == null) {
                // a tax row whose end the text recognition garbled ("7.00 Tx 2 45,46 muy!") is still a tax row
                if (isTaxRow && money.containsMatchIn(fixDigits(u))) { taxRows += i; firstSummary = minOf(firstSummary, i) }
                // "23 BALANCE DUE" with its amount read onto the next line
                val next = lines.getOrNull(i + 1)?.let { priceOf(it.text) }?.takeIf { it.before.count { c -> c.isLetter() } < 2 }
                if (dueWord.containsMatchIn(u) && !taxWord.containsMatchIn(u) && next != null) {
                    if (total == null) total = next.cents to i + 1
                    firstSummary = minOf(firstSummary, i)
                }
                return@forEachIndexed
            }
            when {
                subtotalWord.containsMatchIn(u) -> { subtotal = subtotal ?: p.cents; firstSummary = minOf(firstSummary, i) }
                taxTotalWord.containsMatchIn(u) -> { taxTotal = p.cents to l; firstSummary = minOf(firstSummary, i) }
                isTaxRow -> { taxRows += i; firstSummary = minOf(firstSummary, i) }
                tipWord.containsMatchIn(u) -> { tip = p.cents; firstSummary = minOf(firstSummary, i) }
                dueWord.containsMatchIn(u) || totalWord.containsMatchIn(u) && !notTotal.containsMatchIn(u.replace(totalWord, "")) && !payWord.containsMatchIn(u) -> {
                    // the last total before the payment lines; a later "TOTAL" after a tip line is the one paid
                    val t = total
                    if (t == null || i > t.second) total = p.cents to i
                    firstSummary = minOf(firstSummary, i)
                }
            }
        }

        // header: the store is the first line that reads like a name (never a section header)
        val firstPriced = lines.indices.firstOrNull { priceOf(lines[it].text) != null } ?: lines.size
        var storeLine: Line? = null
        var storeIndex = -1
        for (i in 0 until minOf(6, firstPriced, lines.size)) {
            val t = lines[i].text.trim()
            val u = upper[i]
            val letters = t.count { it.isLetter() }
            if (sectionOf(u) != null) break
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

        // items: the lines between the header and the summary that are items
        val items = mutableListOf<Item>()
        var pendingDesc: Line? = null
        var section = ""
        // an item made from a quantity line with no line total: the bare total on the next line is its own
        var waiting: Int? = null
        fun descOf(raw: String): Pair<String, String> {
            // marks the text recognition reads at either end ("© FOOD LION LIME JUICE", "MILK GALLON =") aren't the name
            var d = raw.replace(sku, " ").replace(Regex("""\s+"""), " ").trim()
                .replace(Regex("""^[^A-Za-z0-9]+\s*"""), "").replace(Regex("""\s+[^A-Za-z0-9*#+\s]+(?=(\s|$))"""), " ").replace(Regex("""\s+"""), " ").trim()
            // a capital letter just before the price may be a tax letter (counted only when a tax line names it)
            val before = Regex("""\s([A-Z])$""").find(d)?.groupValues?.get(1)?.takeIf { it !in notTaxFlags } ?: ""
            // flags left at the end of the name: one capital ("W"), marks, and W or M as read twice ("Ww")
            d = d.replace(Regex("""(\s+(?:[A-Z]|[*#+]|[Ww]{2}|[Mm]{2}))+$"""), "").trim()
            return d to before
        }
        fun add(desc: String, qty: Long, unit: Long?, amount: Long, letter: String, line: Line) {
            val (d, before) = descOf(desc)
            val l = letter.ifEmpty { if (before.isNotEmpty()) "?$before" else "" }
            val up = unit ?: if (amount > 0 && qty > 0) amount * 1000 / qty else null
            items += Item(d, cleanName(d), qty, up, amount, l, section)
            if (line.confidence < LOW_CONFIDENCE) low += "items"
        }
        val start = storeIndex + 1
        for (i in start until firstSummary) {
            val l = lines[i]
            val u = upper[i]
            if (u.isBlank()) continue
            val sec = sectionOf(u)
            if (sec != null) { section = sec; pendingDesc = null; waiting = null; continue }
            val p = priceOf(l.text)

            // savings that only say what was saved: never an item
            if (infoWord.containsMatchIn(u) && (p == null || p.cents >= 0)) { waiting = null; continue }
            // a coupon or discount (marked negative, or a coupon word): comes off the item above it; a plain
            // "SAVINGS 1.50" only says what was saved
            if (discountWord.containsMatchIn(u)) {
                val off = when {
                    p != null && (p.cents < 0 || alwaysOff.containsMatchIn(u)) -> kotlin.math.abs(p.cents)
                    p == null && alwaysOff.containsMatchIn(u) -> money.find(u)?.let { cents("${it.groupValues[1].replace(",", "")}.${it.groupValues[2]}") }
                    else -> null
                }
                if (off != null && items.isNotEmpty() && items.last().amount - off >= 0) {
                    val last = items.last()
                    val paid = last.amount - off
                    items[items.lastIndex] = last.copy(amount = paid, unitPrice = if (last.quantityMilli > 0) paid * 1000 / last.quantityMilli else last.unitPrice)
                } else if (off != null && off > 0) low += "items"
                pendingDesc = null; waiting = null
                continue
            }

            // a quantity or weight line, with or without its line total
            val fixed = fixDigits(l.text.trim()).replace(Regex("""/\s*[Il1|]b\b"""), "/lb")
            val q = qtyLine.matchEntire(fixed)
            val qm = q ?: qtyLineMisread.matchEntire(fixed)?.takeIf { m ->
                val tot = priceOf(m.groupValues[3])
                tot != null && kotlin.math.abs(m.groupValues[1].toLong() * cents(m.groupValues[2]) - tot.cents) <= 1
            }
            // "20 11.49 22.98 B", "28 2.79 5.58 A": the "@" glued to the quantity and misread; taken only when it checks
            val glued = if (q == null && qm == null) qtyLineGlued.matchEntire(fixed)?.let { m ->
                val tot = priceOf(m.groupValues[3])?.takeIf { it.before.count { c -> c.isLetter() } < 2 } ?: return@let null
                // the unit price may have the misread "@" glued to its front too ("20 811.49 22.98")
                val unitText = m.groupValues[2].replace(',', '.')
                val units = listOfNotNull(cents(unitText), unitText.takeIf { it.substringBefore('.').length >= 2 }?.drop(1)?.let { cents(it) })
                val qtyText = m.groupValues[1]
                val qtys = listOfNotNull(qtyText.toLongOrNull(), qtyText.dropLast(1).toLongOrNull()).filter { it in 1..999 }
                qtys.flatMap { n -> units.map { u -> n to u } }.firstOrNull { (n, u) -> kotlin.math.abs(n * u - tot.cents) <= 1 }
                    ?.let { (n, u) -> Triple(n * 1000, u, m.groupValues[3]) }
            } else null
            if (qm != null || glued != null) {
                val qty = glued?.first ?: milli(qm!!.groupValues[1])
                val unit = glued?.second ?: cents(if (q != null) qm!!.groupValues[3] else qm!!.groupValues[2])
                val restText = glued?.third ?: if (q != null) qm!!.groupValues[4] else qm!!.groupValues[3]
                val tot = priceOf(restText)?.takeIf { it.before.count { c -> c.isLetter() } < 2 }
                val worked = (unit * qty + 500) / 1000
                val pd = pendingDesc
                if (pd != null) {
                    val amount = tot?.cents ?: worked
                    if (tot != null && kotlin.math.abs(tot.cents - worked) > 1) low += "items"
                    // a line total too garbled to read ("a.64 Ax") may still end in its letter
                    val letter = tot?.let { letterOf(it.flags) } ?: trailingLetter.find(restText)?.groupValues?.get(1)?.takeIf { it !in notTaxFlags } ?: ""
                    add(pd.text.trim(), qty, unit, amount, letter, l)
                    if (tot == null && letter.isNotEmpty()) low += "items"
                    waiting = if (tot == null) items.lastIndex else null
                    pendingDesc = null
                } else if (items.isNotEmpty()) {
                    // under an item that already has its price: that item's quantity
                    val last = items.last()
                    if (kotlin.math.abs(worked - last.amount) <= 1) items[items.lastIndex] = last.copy(quantityMilli = qty, unitPrice = unit)
                }
                continue
            }

            if (p == null) {
                // a description on its own line, its price (or quantity) on the next
                val skip = l.text.count { it.isLetter() } < 2 || payWord.containsMatchIn(u) || findDate(u) != null || taxWord.containsMatchIn(u) ||
                    qtyAt.containsMatchIn(l.text)
                if (!skip) { pendingDesc = l; waiting = null }
                continue
            }
            if (payWord.containsMatchIn(u) && !discountWord.containsMatchIn(u) || findDate(u) != null) { pendingDesc = null; waiting = null; continue }
            var desc = p.before
            val letter = letterOf(p.flags)
            // a bare line total under a quantity line ("22.98 B"): that item's total and letter
            val w = waiting
            if (desc.count { it.isLetter() } < 2 && w != null && pendingDesc == null && kotlin.math.abs(items[w].amount - p.cents) <= 1) {
                items[w] = items[w].copy(amount = p.cents, taxLetter = letter)
                waiting = null
                continue
            }
            waiting = null
            var amount = p.cents
            var qty = 1000L
            var unit: Long? = null
            qtyAt.find(desc)?.let { m ->
                qty = milli(m.groupValues[1])
                unit = cents(m.groupValues[3])
                desc = desc.removeRange(m.range).trim()
            }
            if (desc.count { it.isLetter() } < 2 && pendingDesc != null) desc = pendingDesc!!.text.trim()
            if (desc.count { it.isLetter() } < 2) { pendingDesc = null; continue }
            qtyX.find(desc)?.let { m -> qty = m.groupValues[1].toLong() * 1000; desc = desc.removeRange(m.range).trim() }
            qtyWord.find(desc)?.let { m -> qty = m.groupValues[1].toLong() * 1000; desc = desc.removeRange(m.range).trim() }
            unit?.let { up -> if (kotlin.math.abs((up * qty + 500) / 1000 - kotlin.math.abs(amount)) > 1) low += "items" }
            add(desc, qty, unit, amount, letter, l)
            pendingDesc = null
        }

        val isReturn = returnWord.containsMatchIn(upper.joinToString(" ")) || (total?.first ?: 0) < 0
        val tt = taxTotal
        var taxLines = taxRows.mapNotNull { taxLineOf(lines[it].text, tt?.first?.let { v -> kotlin.math.abs(v) }) }
            .map { it.copy(amount = kotlin.math.abs(it.amount), taxable = it.taxable?.let { t -> kotlin.math.abs(t) }) }
        // a total tax line whose "TOTAL" wasn't read ("aa TAX 3.18" under the group rows): the plain one that is all the others
        var totalTaxRead = tt?.first?.let { kotlin.math.abs(it) }
        if (totalTaxRead == null && taxLines.size >= 3) {
            val whole = taxLines.firstOrNull { t -> t.letter == null && t.taxable == null && t.amount > 0 && t.amount == taxLines.filter { it !== t }.sumOf { it.amount } }
            if (whole != null) { totalTaxRead = whole.amount; taxLines = taxLines.filter { it !== whole } }
        }
        val tax = totalTaxRead ?: taxLines.takeIf { it.isNotEmpty() }?.sumOf { it.amount }
        if (taxRows.any { lines[it].confidence < LOW_CONFIDENCE } || tt != null && tt.second.confidence < LOW_CONFIDENCE) low += "tax"
        var totalCents = total?.first?.let { kotlin.math.abs(it) }
        val totalLine = total?.second
        if (totalLine == null || lines[totalLine].confidence < LOW_CONFIDENCE) low += "total"
        // a letter before the price counts only when a tax line names it (it may be part of the name: "VITAMIN C")
        val named = taxLines.mapNotNull { it.letter }.toSet()
        val lettered = items.map { it.copy(taxLetter = if (it.taxLetter.startsWith("?")) it.taxLetter.drop(1).takeIf { f -> f in named } ?: "" else it.taxLetter) }
        val itemsSum = lettered.sumOf { it.amount }.let { kotlin.math.abs(it) }
        val sub = subtotal?.let { kotlin.math.abs(it) }
        val tipCents = tip?.let { kotlin.math.abs(it) }
        if (totalCents == null) {
            // no total line read: work it out, but ask
            totalCents = (sub ?: itemsSum.takeIf { it > 0 })?.let { it + (tax ?: 0) + (tipCents ?: 0) }
            low += "total"
        }
        // does it add up? (only real items count: savings, tax, total and payment lines never are items)
        if (lettered.isEmpty()) low += "items"
        else if (sub != null && kotlin.math.abs(itemsSum - sub) > 1) low += "items"
        else if (totalCents != null && kotlin.math.abs((sub ?: itemsSum) + (tax ?: 0) + (tipCents ?: 0) - totalCents) > 1) {
            low += "items"; low += "total"
        }
        val outItems = if (isReturn) lettered.map { it.copy(amount = kotlin.math.abs(it.amount), unitPrice = it.unitPrice?.let { u -> kotlin.math.abs(u) }) } else lettered
        return Parsed(store, date, totalCents, sub, tax, tipCents, outItems, if (isReturn) ReceiptKind.RETURN else ReceiptKind.PURCHASE, low, text, taxLines)
    }

    /** The item's name for matching and price history: lower case words, no codes, numbers or signs. */
    fun cleanName(desc: String): String = desc.lowercase().replace(Regex("""[^a-z ]+"""), " ").split(' ')
        .filter { it.length >= 2 }.joinToString(" ")

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
