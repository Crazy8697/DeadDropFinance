package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.data.ItemReturnEntity
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.ItemReturns
import net.darkrelay.deaddropfinance.domain.ItemSplit
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.ReceiptMatch
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.domain.ReceiptTax
import net.darkrelay.deaddropfinance.domain.ItemEnvelopes
import net.darkrelay.deaddropfinance.domain.Receipts

// ---- the form ------------------------------------------------------------------------------------------------------

/** One item row on the form, as text the user can fix. */
data class ItemRow(
    val text: String, val qty: String, val amount: String, val unitPrice: Long? = null, val id: String? = null,
    /**
     * 1.9.3: the item's tax letter (1.9.5: never shown; the reader uses it to work out which items are taxed), and the
     * user's Taxed toggle (null: as the letters say, or not answered yet).
     */
    val letter: String = "", val taxed: Boolean? = null,
    /** 1.9.4: the envelope this item goes to (null: none chosen), and the receipt section it was under. */
    val envelopeId: String? = null, val section: String = "",
    /**
     * 1.9.5: the unit of a weighed quantity ("lb"), what the reader isn't sure of by field ("name", "qty", "price",
     * "taxed", "envelope") with why (each cleared when that field is edited), and the user's Checked mark (only a mark).
     */
    val unit: String = "", val unsure: Map<String, String> = emptyMap(), val checked: Boolean = false,
)

/** The totals breakdown (1.9.5), as the receipt shows it: untaxed items, taxed items, tax, total paid. */
data class ReceiptTotals(
    val untaxed: Long, val taxed: Long, val tax: Long, val total: Long?,
    /** The taxable amounts the receipt printed, when it printed them: untaxed (at 0%) and taxed. */
    val printedUntaxed: Long?, val printedTaxed: Long?,
) {
    /** Total paid less what the items and tax come to: positive when the items come to less than the total. */
    val gap: Long? get() = total?.let { it - (untaxed + taxed + tax) }
}

/** The receipt form while the user checks what was read. */
class ReceiptForm(
    store: String = "", date: String = "", total: String = "", tax: String = "",
    items: List<ItemRow> = emptyList(), val low: Set<String> = emptySet(), val kind: ReceiptKind = ReceiptKind.PURCHASE, val rawText: String = "",
    /** The tax lines read at the bottom of the receipt, to match to the items' letters. */
    val taxLines: List<ReceiptParser.TaxLine> = emptyList(),
    /** 1.9.5: why a receipt field is unsure ("store", "date", "tax", "total"); each cleared when that field is edited. */
    notes: Map<String, String> = emptyMap(),
) {
    var store by mutableStateOf(store)
    var date by mutableStateOf(date)
    var total by mutableStateOf(total)
    var tax by mutableStateOf(tax)
    var notes by mutableStateOf(notes)
    val items = mutableStateListOf<ItemRow>().apply { addAll(items) }

    private fun amt(s: String) = if (s.isBlank()) null else Money.parse(s)?.let { kotlin.math.abs(it) }

    private val live get() = items.filter { it.text.isNotBlank() || it.amount.isNotBlank() }

    /** Where the item at [index] is among the items that count (blank rows don't), or null. */
    fun liveIndex(index: Int): Int? {
        val r = items.getOrNull(index) ?: return null
        if (r.text.isBlank() && r.amount.isBlank()) return null
        return items.take(index).count { it.text.isNotBlank() || it.amount.isNotBlank() }
    }

    /** A receipt field edited: the reader's doubt about it goes. */
    fun edited(field: String) { if (field in notes) notes = notes - field }

    /** An item's field edited ([field] "name", "qty", "price", "taxed", "envelope"): the reader's doubt about it goes. */
    fun editItem(index: Int, field: String, change: (ItemRow) -> ItemRow) {
        val r = items[index]
        items[index] = change(r).let { if (field in it.unsure) it.copy(unsure = it.unsure - field) else it }
    }

    /**
     * Each item's tax (1.9.3): from its letter and the tax lines, prices with tax, and what to ask. 1.9.5: a Taxed toggle
     * the user set against what the letters say decides instead: the receipt's tax is shared between the items marked taxed.
     */
    fun taxWork(): ReceiptTax.Result {
        val rows = live
        val amounts = rows.map { Money.parse(it.amount) ?: 0 }
        val base = ReceiptTax.work(rows.mapIndexed { i, it -> ReceiptTax.In(it.letter.trim().uppercase(), amounts[i], it.taxed) }, taxLines, amt(tax))
        val overridden = rows.indices.any { i -> val t = rows[i].taxed; t != null && base.taxed.getOrNull(i) != null && t != base.taxed[i] }
        if (!overridden) return base
        return ReceiptTax.byMarks(amounts, rows.indices.map { i -> rows[i].taxed ?: base.taxed.getOrNull(i) ?: false }, amt(tax) ?: 0)
    }

    /** Whether the item at [index] is shown Taxed (true), Not taxed (false), or not answered yet (null). */
    fun taxedOf(index: Int, work: ReceiptTax.Result): Boolean? =
        items[index].taxed ?: liveIndex(index)?.let { work.taxed.getOrNull(it) }

    /** The item's price with its tax (its price when it carries none). */
    fun withTaxOf(index: Int, work: ReceiptTax.Result): Long {
        val shelf = Money.parse(items[index].amount) ?: 0
        val k = liveIndex(index) ?: return shelf
        return if (work.perItem) work.withTax.getOrNull(k) ?: shelf else shelf
    }

    /** Untaxed items, taxed items, tax and total paid, worked out from the items; the receipt's taxable amounts to check them by. */
    fun totals(work: ReceiptTax.Result): ReceiptTotals {
        var untaxed = 0L
        var taxed = 0L
        items.indices.forEach { i ->
            if (liveIndex(i) == null) return@forEach
            val a = Money.parse(items[i].amount) ?: 0
            if (taxedOf(i, work) == true) taxed += a else untaxed += a
        }
        val printed = taxLines.filter { it.taxable != null }
        val printedUntaxed = printed.filter { it.amount == 0L }.takeIf { it.isNotEmpty() }?.sumOf { it.taxable!! }
        val printedTaxed = printed.filter { it.amount > 0 }.takeIf { it.isNotEmpty() }?.sumOf { it.taxable!! }
        return ReceiptTotals(untaxed, taxed, amt(tax) ?: 0, amt(total), printedUntaxed, printedTaxed)
    }

    /**
     * When the items don't add up to the total paid: the items whose price, with one digit read differently, would close
     * the gap exactly, each with the price that would. Nothing is changed by itself. 0.15.5: only items the reader already
     * doubted (a smudged, low-confidence or guessed line: its price, quantity or name unsure); none, when none of those would.
     */
    fun misread(totals: ReceiptTotals): Map<Int, Long> {
        val gap = totals.gap ?: return emptyMap()
        if (gap == 0L) return emptyMap()
        val out = linkedMapOf<Int, Long>()
        items.forEachIndexed { i, r ->
            if (liveIndex(i) == null) return@forEachIndexed
            if (r.unsure.keys.none { it in setOf("price", "qty", "name") }) return@forEachIndexed
            val now = Money.parse(r.amount) ?: return@forEachIndexed
            val want = now + gap
            if (want <= 0) return@forEachIndexed
            if (oneDigitApart(now, want)) out[i] = want
        }
        return out
    }

    /** True when [a] and [b] in cents differ in exactly one digit (a missing leading digit counts). */
    private fun oneDigitApart(a: Long, b: Long): Boolean {
        val x = a.toString(); val y = b.toString()
        val n = maxOf(x.length, y.length)
        val px = x.padStart(n, '0'); val py = y.padStart(n, '0')
        return px.zip(py).count { (c, d) -> c != d } == 1
    }

    /**
     * 1.9.4: an envelope for each item that has none yet: the one last picked for the same item name, else one for the
     * receipt section it was under (1.9.5: by a word they share or the built-in map), else none. The user can change any.
     */
    fun guessEnvelopes(past: List<ReceiptItemEntity>, envelopes: List<net.darkrelay.deaddropfinance.data.EnvelopeEntity>) {
        items.forEachIndexed { i, r ->
            if (r.envelopeId == null) ItemEnvelopes.guess(r.text, r.section, past, envelopes)?.let { items[i] = r.copy(envelopeId = it) }
        }
    }

    fun draft(pages: Int): Receipts.Draft {
        val work = taxWork()
        return Receipts.Draft(
            store, parseDateOrNull(date), amt(total), amt(tax),
            live.mapIndexed { k, r ->
                val q = qtyMilli(r.qty)
                val a = Money.parse(r.amount) ?: 0
                Receipts.DraftItem(
                    r.text, q, r.unitPrice?.takeIf { up -> up * q / 1000 == kotlin.math.abs(a) } ?: if (a > 0 && q > 0) a * 1000 / q else null, a, r.id,
                    taxLetter = r.letter.trim().uppercase(), priceWithTax = if (work.perItem) work.withTax[k] else null,
                    envelopeId = r.envelopeId,
                )
            },
            kind, low, rawText, pages,
        )
    }

    /** 1.9.5: the form as the user has left it, every edit and mark, to keep it while they're away (JSON). */
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("store", store); put("date", date); put("total", total); put("tax", tax)
        put("low", org.json.JSONArray(low.toList())); put("kind", kind.name); put("rawText", rawText)
        put("notes", org.json.JSONObject(notes))
        put("taxLines", org.json.JSONArray(taxLines.map { t ->
            org.json.JSONObject().put("amount", t.amount).put("letter", t.letter ?: org.json.JSONObject.NULL)
                .put("rate", t.rateMilli ?: org.json.JSONObject.NULL).put("taxable", t.taxable ?: org.json.JSONObject.NULL)
        }))
        put("items", org.json.JSONArray(items.map { r ->
            org.json.JSONObject().put("text", r.text).put("qty", r.qty).put("amount", r.amount).put("unitPrice", r.unitPrice ?: org.json.JSONObject.NULL)
                .put("id", r.id ?: org.json.JSONObject.NULL).put("letter", r.letter).put("taxed", r.taxed ?: org.json.JSONObject.NULL)
                .put("envelopeId", r.envelopeId ?: org.json.JSONObject.NULL).put("section", r.section).put("unit", r.unit)
                .put("unsure", org.json.JSONObject(r.unsure)).put("checked", r.checked)
        }))
    }

    companion object {
        fun from(p: ReceiptParser.Parsed) = ReceiptForm(
            p.store, p.date?.toString() ?: "", p.total?.let { Money.plain(it) } ?: "", p.tax?.let { Money.plain(it) } ?: "",
            p.items.map { ItemRow(it.text, Receipts.qty(it.quantityMilli), Money.plain(it.amount), it.unitPrice, letter = it.taxLetter, section = it.section, unit = it.unitWord, unsure = it.unsure) },
            p.lowConfidence, p.kind, p.text, p.taxLines, p.notes,
        )

        fun from(r: ReceiptEntity, items: List<ReceiptItemEntity>) = ReceiptForm(
            r.store, r.date?.toString() ?: "", r.total?.let { Money.plain(it) } ?: "", r.tax?.let { Money.plain(it) } ?: "",
            // a saved receipt: its tax lines read again from its text, and what was taxed from the prices it kept
            items.map { ItemRow(it.text, Receipts.qty(it.quantityMilli), Money.plain(it.amount), it.unitPrice, it.id, it.taxLetter, it.priceWithTax?.let { w -> w > it.amount }, it.envelopeId) },
            emptySet(), r.kind, r.rawText,
            if (r.rawText.isBlank()) emptyList() else ReceiptParser.parseLines(r.rawText.lines().map { ReceiptParser.Line(it) }).taxLines,
        )

        /** The form as [toJson] kept it. */
        fun fromJson(o: org.json.JSONObject): ReceiptForm {
            fun str(j: org.json.JSONObject, k: String) = if (j.isNull(k)) null else j.optString(k)
            fun lng(j: org.json.JSONObject, k: String) = if (j.isNull(k) || !j.has(k)) null else j.getLong(k)
            fun map(j: org.json.JSONObject?) = j?.keys()?.asSequence()?.associateWith { j.getString(it) } ?: emptyMap()
            val lines = o.optJSONArray("taxLines")
            val its = o.optJSONArray("items")
            return ReceiptForm(
                o.optString("store"), o.optString("date"), o.optString("total"), o.optString("tax"),
                (0 until (its?.length() ?: 0)).map { k ->
                    val j = its!!.getJSONObject(k)
                    ItemRow(
                        j.optString("text"), j.optString("qty"), j.optString("amount"), lng(j, "unitPrice"), str(j, "id"),
                        j.optString("letter"), if (j.isNull("taxed") || !j.has("taxed")) null else j.getBoolean("taxed"),
                        str(j, "envelopeId"), j.optString("section"), j.optString("unit"), map(j.optJSONObject("unsure")), j.optBoolean("checked"),
                    )
                },
                (0 until (o.optJSONArray("low")?.length() ?: 0)).map { o.getJSONArray("low").getString(it) }.toSet(),
                runCatching { ReceiptKind.valueOf(o.optString("kind")) }.getOrDefault(ReceiptKind.PURCHASE), o.optString("rawText"),
                (0 until (lines?.length() ?: 0)).map { k ->
                    val j = lines!!.getJSONObject(k)
                    ReceiptParser.TaxLine(j.getLong("amount"), str(j, "letter"), lng(j, "rate"), lng(j, "taxable"))
                },
                map(o.optJSONObject("notes")),
            )
        }

        /** "2", "1.25": thousandths; anything unreadable is one. */
        fun qtyMilli(s: String): Long {
            val t = s.trim()
            if (t.isEmpty()) return 1000
            val parts = t.split('.')
            val whole = parts[0].toLongOrNull() ?: return 1000
            val frac = parts.getOrNull(1)?.filter { it.isDigit() }?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0
            return (whole * 1000 + frac).takeIf { it > 0 } ?: 1000
        }
    }
}

/**
 * 1.9.5: a scan being checked, kept (encrypted, beside the receipt photos) until it's saved or cleared, so it's there
 * after another screen or a restart: its id, the transaction it's for, what was read from each page, and the form.
 */
data class ScanDraft(val scanId: String, val scanFor: String?, val pages: List<List<ReceiptParser.Line>>, val form: ReceiptForm) {
    fun toJson(): String = org.json.JSONObject().put("scanId", scanId).put("scanFor", scanFor ?: org.json.JSONObject.NULL)
        .put("pages", org.json.JSONArray(pages.map { p -> org.json.JSONArray(p.map { org.json.JSONObject().put("t", it.text).put("c", it.confidence) }) }))
        .put("form", form.toJson()).toString()

    companion object {
        fun fromJson(s: String): ScanDraft? = runCatching {
            val o = org.json.JSONObject(s)
            val pages = o.getJSONArray("pages")
            ScanDraft(
                o.getString("scanId"), if (o.isNull("scanFor")) null else o.getString("scanFor"),
                (0 until pages.length()).map { k -> val p = pages.getJSONArray(k); (0 until p.length()).map { n -> val l = p.getJSONObject(n); ReceiptParser.Line(l.getString("t"), l.getInt("c")) } },
                ReceiptForm.fromJson(o.getJSONObject("form")),
            )
        }.getOrNull()
    }
}

// ---- photos --------------------------------------------------------------------------------------------------------

@Composable
fun ReceiptPhotos(pages: List<ImageBitmap?>, big: Boolean = true) {
    if (pages.isEmpty()) return
    val first = pages.firstOrNull()
    if (first != null) {
        Image(
            first, contentDescription = "Receipt photo", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = if (big) 320.dp else 160.dp).border(1.dp, DdfColors.Outline),
        )
    }
    if (pages.size > 1) {
        ScrollRow(Modifier.padding(top = 4.dp)) {
            pages.drop(1).forEach { p ->
                if (p != null) Image(p, contentDescription = "More of the receipt", contentScale = ContentScale.Fit, modifier = Modifier.height(90.dp).width(60.dp).padding(end = 4.dp))
            }
        }
        Text("${pages.size} photos", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- scanning ------------------------------------------------------------------------------------------------------

/** Where a receipt comes from: the camera, or an image or PDF on the phone (on the program, 0.14.0: an image or PDF file). */
@Composable
fun ScanScreen(forPayee: String?, working: Boolean, error: String?, onBack: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("Scan a receipt", onBack)
        if (forPayee != null) Text("For $forPayee", color = DdfColors.Muted)
        val camera = LocalDeviceWords.current.camera
        Text(
            if (camera) "Take a photo of the receipt, or choose an image or a PDF. A long receipt can be more than one photo: add the rest on the next " +
                "screen. It's read on this phone; nothing is sent anywhere."
            // the program (0.14.0): an image or a PDF file, read on the computer as the phone reads its photos
            else "Choose an image or a PDF of the receipt. A long receipt can be more than one image: add the rest on the next screen. " +
                "It's read on this computer; nothing is sent anywhere.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Gap()
        if (working) {
            Text("Reading the receipt...")
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else {
            if (camera) {
                Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Take a photo") }
                Gap()
                OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose an image or PDF") }
            } else Button(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose an image or PDF") }
        }
        ErrorLine(error)
        Gap(24)
    }
}

/**
 * What was read, as a form to fix before saving. 1.9.5: each item in its own card (name, quantity, price, price with
 * tax, Taxed or Not taxed, envelope, Checked and Remove), then the totals as the receipt shows them: untaxed items,
 * taxed items, tax, total paid. A field the reader wasn't sure of has a yellow border and says why, until it's edited (or
 * its item is marked Checked). [onClear] (a new scan): Clear throws the whole scan out, after asking.
 */
@Composable
fun ReceiptEditScreen(
    form: ReceiptForm, pages: List<ImageBitmap?>, isNew: Boolean, working: Boolean, error: String?,
    onBack: () -> Unit, onAddCamera: () -> Unit, onAddFile: () -> Unit, onSave: () -> Unit,
    /** 1.9.4: the envelopes an item can go to (its own dropdown); none hides the dropdowns. */
    envelopes: List<net.darkrelay.deaddropfinance.data.EnvelopeEntity> = emptyList(),
    /** 1.9.5: Clear (a scan being checked): throws it out, photos and all; null hides it. */
    onClear: (() -> Unit)? = null,
    scroll: androidx.compose.foundation.ScrollState = rememberScrollState(),
) {
    val envelopeOptions: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "No envelope") + envelopes.map { it.id to it.name }
    var confirmClear by remember { mutableStateOf(false) }
    val work = form.taxWork()
    val totals = form.totals(work)
    val misread = form.misread(totals)
    ScrollColumn(Modifier.fillMaxWidth(), scroll, Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle(if (isNew) "Check the receipt" else "Edit receipt", onBack) {
            if (onClear != null) TextButton(onClick = { confirmClear = true }, modifier = Modifier.testTag("receipt.clear")) { Text("Clear") }
        }
        ReceiptPhotos(pages)
        if (isNew) {
            Row {
                if (LocalDeviceWords.current.camera) TextButton(onClick = onAddCamera, enabled = !working) { Text("Add another photo") }
                TextButton(onClick = onAddFile, enabled = !working) { Text("Add from a file") }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val anyUnsure = form.notes.isNotEmpty() || form.items.any { !it.checked && it.unsure.isNotEmpty() } || misread.isNotEmpty() || totals.gap.let { it != null && it != 0L }
        if (anyUnsure) {
            Text("Fields in yellow weren't clear on the photo, or don't add up. Check them.", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        if (form.kind == ReceiptKind.RETURN) Pill("return receipt")
        // 0.15.9: the program's Store and Date are boxes in the item card's style, their reasons listed on the right
        if (LocalProgramStyle.current) ProgramStoreDate(form) else {
            TextFieldRow("Store", form.store, { form.store = it; form.edited("store") }, Modifier.testTag("receipt.store"), warn = form.notes["store"])
            DateField("Date", form.date, { form.date = it; form.edited("date") }, warn = form.notes["date"], modifier = Modifier.testTag("receipt.date"))
        }
        SectionTitle("Items")
        form.items.forEachIndexed { i, r -> ItemCard(form, i, r, work, misread[i], envelopes.isNotEmpty(), envelopeOptions) }
        TextButton(onClick = { form.items.add(ItemRow("", "1", "")) }) { Text("Add an item") }
        if (!work.resolved) TaxQuestion(form, work)
        // the totals, as the receipt shows them; each number says what it is
        SectionTitle("Totals")
        fun printedNote(read: Long?, worked: Long) = read?.takeIf { kotlin.math.abs(it - worked) > maxOf(1L, it / 200) }?.let { "the receipt says ${Money.format(it)}" }
        ReadOnlyAmount("Untaxed items", totals.untaxed, Modifier.testTag("breakdown.untaxed"), warn = printedNote(totals.printedUntaxed, totals.untaxed))
        ReadOnlyAmount("Taxed items", totals.taxed, Modifier.testTag("breakdown.taxed"), warn = printedNote(totals.printedTaxed, totals.taxed))
        AmountField("Tax", form.tax, { form.tax = it; form.edited("tax") }, Modifier.testTag("breakdown.tax"), warn = form.notes["tax"])
        val gap = totals.gap
        val totalWarn = form.notes["total"] ?: gap?.takeIf { it != 0L }?.let { g ->
            "the items come to ${Money.format(kotlin.math.abs(g))} " + (if (g < 0) "more" else "less") + " than the total paid" +
                (if (g > 0 && misread.isEmpty()) " (a tip, or a missed line?)" else "")
        }
        AmountField("Total paid", form.total, { form.total = it; form.edited("total") }, Modifier.testTag("breakdown.total"), warn = totalWarn)
        if (gap == 0L && "total" !in form.notes && totals.total != null) {
            Text(
                "Untaxed items, taxed items and tax come to the total paid.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("tax.addsUp"),
            )
        }
        ErrorLine(error)
        Gap()
        Button(onClick = onSave, enabled = !working && work.resolved, modifier = Modifier.fillMaxWidth().testTag("receipt.save")) { Text("Save receipt") }
        Gap(24)
    }
    if (confirmClear) {
        ConfirmDialog(
            if (isNew) "Clear this receipt?" else "Clear your changes?",
            if (isNew) "Nothing has been saved. The photos, what was read and your changes are thrown out."
            else "Only the changes not saved yet are thrown out. The saved receipt stays as it is.",
            "Clear",
            onCancel = { confirmClear = false }, onConfirm = { confirmClear = false; onClear?.invoke() },
            destructive = true, cancelTag = "clear.cancel", actionTag = "clear.confirm",
        )
    }
}

/** One item, in its own card: what the reader wasn't sure of in yellow; Checked dims the card and quiets its yellow. */
@Composable
private fun ItemCard(
    form: ReceiptForm, i: Int, r: ItemRow, work: ReceiptTax.Result, misreadTo: Long?, showEnvelope: Boolean, envelopeOptions: List<Pair<String?, String>>,
) {
    // 0.15.8: the program's card, as drawn in the approved mockup (ProgramItemCard)
    if (LocalProgramStyle.current) { ProgramItemCard(form, i, r, work, misreadTo, showEnvelope, envelopeOptions); return }
    // the price the gap points at says which price would add up, before the reader's own doubt about it
    fun warn(field: String): String? = if (r.checked) null
        else if (field == "price" && misreadTo != null) "price may be misread: ${Money.format(misreadTo)} would make it add up" else r.unsure[field]
    val taxedNow = form.taxedOf(i, work)
    val taxWarn = warn("taxed") ?: if (!r.checked && taxedNow == null) "not sure whether it's taxed" else null
    ItemCardFrame(Modifier.padding(bottom = 10.dp).alpha(if (r.checked) 0.6f else 1f).testTag("item.card.$i")) {
        if (r.checked) Text("✓ Checked", color = DdfColors.Cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("item.checkedMark.$i"))
        TextFieldRow("Item", r.text, { v -> form.editItem(i, "name") { it.copy(text = v) } }, Modifier.testTag("item.name.$i"), warn = warn("name"))
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(0.8f)) { TextFieldRow("Qty", r.qty, { v -> form.editItem(i, "qty") { it.copy(qty = v) } }, Modifier.testTag("item.qty.$i"), warn = warn("qty")) }
            HGap(6)
            Box(Modifier.weight(1f)) { AmountField("Price", r.amount, { v -> form.editItem(i, "price") { it.copy(amount = v) } }, Modifier.testTag("item.price.$i"), warn = warn("price")) }
            HGap(6)
            Box(Modifier.weight(1f)) { ReadOnlyAmount("With tax", form.withTaxOf(i, work), Modifier.testTag("item.withTax.$i")) }
        }
        qtyLine(r)?.let { Text(it, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("item.qtyLine.$i")) }
        TaxedToggle(taxedNow, { v -> form.editItem(i, "taxed") { it.copy(taxed = v) } }, i, taxWarn)
        if (showEnvelope) {
            Box(Modifier.testTag("item.envelope.$i")) {
                Picker("Envelope", envelopeOptions, r.envelopeId, { v -> form.editItem(i, "envelope") { it.copy(envelopeId = v) } }, warn = warn("envelope"))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { form.items[i] = r.copy(checked = !r.checked) }, modifier = Modifier.testTag("item.checked.$i")) { Text(if (r.checked) "Not checked" else "Checked") }
                TextButton(onClick = { form.items.removeAt(i) }, modifier = Modifier.testTag("item.remove.$i")) { Text("Remove") }
            }
        }
    }
}

/** "2 @ $2.79 = $5.58", "1.26 lb @ $2.89/lb = $3.64": a quantity other than one, as the receipt shows it. */
fun qtyLine(r: ItemRow): String? {
    val q = ReceiptForm.qtyMilli(r.qty)
    val amount = Money.parse(r.amount) ?: return null
    if (q == 1000L && r.unit.isEmpty()) return null
    val unit = r.unitPrice?.takeIf { (it * q + 500) / 1000 == amount || kotlin.math.abs((it * q + 500) / 1000 - amount) <= 1 } ?: if (q > 0) (amount * 1000 + q / 2) / q else return null
    val qty = Receipts.qty(q)
    return if (r.unit.isNotEmpty()) "$qty ${r.unit} @ ${Money.format(unit)}/${r.unit} = ${Money.format(amount)}" else "$qty @ ${Money.format(unit)} = ${Money.format(amount)}"
}

/** Taxed or Not taxed: two buttons, the chosen one filled; neither chosen while it's not answered. */
@Composable
private fun TaxedToggle(taxed: Boolean?, onChange: (Boolean) -> Unit, i: Int, warn: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        val shape = RoundedCornerShape(8.dp)
        Row(
            Modifier.then(if (warn != null) Modifier.border(1.dp, DdfColors.Warning, shape) else Modifier).padding(2.dp).testTag("item.taxToggle.$i"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((value, label) in listOf(true to "Taxed", false to "Not taxed")) {
                val tag = if (value) "item.taxed.$i" else "item.untaxed.$i"
                if (taxed == value) Button(onClick = { onChange(value) }, modifier = Modifier.padding(end = 6.dp).testTag(tag)) { Text(label) }
                else OutlinedButton(onClick = { onChange(value) }, modifier = Modifier.padding(end = 6.dp).testTag(tag)) { Text(label) }
            }
        }
        if (warn != null) Text(warn, color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall)
    }
}

/** A card for one item: the program's Settings card style in the program (0.15.4), the phone's card on the phone. */
@Composable
private fun ItemCardFrame(modifier: Modifier, content: @Composable () -> Unit) {
    if (LocalProgramStyle.current) {
        val shape = RoundedCornerShape(10.dp)
        Box(modifier.fillMaxWidth().clip(shape).background(DdfColors.Surface).border(1.dp, DdfColors.Outline, shape)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
        }
    } else {
        Card(modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(12.dp)) { content() } }
    }
}

/**
 * The receipt's tax couldn't be matched to its items (no tax letters, or letters no tax line settles): nothing is
 * guessed. Each item's Taxed toggle is the answer (or all at once); the tax is then shared between the taxed items by
 * price. Save waits for it.
 */
@Composable
private fun TaxQuestion(form: ReceiptForm, work: ReceiptTax.Result) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("tax.ask")) {
        Text("Which items are taxed?", style = MaterialTheme.typography.titleSmall, color = DdfColors.Warning)
        Text(
            "The receipt's tax (${form.tax.ifBlank { "none read" }}) couldn't be matched to its items. Mark each item in yellow Taxed or Not taxed; " +
                "the tax is shared between the taxed items by price.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("All of them", Modifier.weight(1f))
            TextButton(onClick = { setAll(form, work, true) }, modifier = Modifier.testTag("tax.all.taxed")) { Text("Taxed") }
            TextButton(onClick = { setAll(form, work, false) }, modifier = Modifier.testTag("tax.all.untaxed")) { Text("Not taxed") }
        }
    }
}

/** Every item the question is about, Taxed (or Not taxed). */
private fun setAll(form: ReceiptForm, work: ReceiptTax.Result, taxed: Boolean) {
    form.items.indices.forEach { i -> if (form.taxedOf(i, work) == null) form.editItem(i, "taxed") { it.copy(taxed = taxed) } }
}

// ---- one receipt ---------------------------------------------------------------------------------------------------

@Composable
fun ReceiptDetailScreen(
    r: ReceiptEntity, items: List<ReceiptItemEntity>, pages: List<ImageBitmap?>, tx: TransactionEntity?, budget: Budget,
    returns: List<ItemReturnEntity>, message: String?,
    onBack: () -> Unit, onEdit: () -> Unit, onMatch: () -> Unit, onSplit: () -> Unit, onDetach: () -> Unit, onDelete: () -> Unit, onTransaction: (String) -> Unit,
) {
    val envelopes = budget.envelopes.associateBy { it.id }
    var confirmDelete by remember { mutableStateOf(false) }
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle(r.store.ifBlank { "Receipt" }, onBack) { TextButton(onClick = onEdit) { Text("Edit") } }
        ReceiptPhotos(pages)
        if (r.pages == 0) Text("No photo kept for this receipt (0.9.0 deleted photos after a split). The items and prices are here.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Gap()
        Row {
            Text(listOfNotNull(r.date?.toString(), if (r.kind == ReceiptKind.RETURN) "return" else null).joinToString(", "), Modifier.weight(1f), color = DdfColors.Muted)
            r.total?.let { MoneyText(if (r.kind == ReceiptKind.RETURN) it else -it, bold = true) }
        }
        r.tax?.let { Text("Tax ${Money.format(it)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
        SectionTitle("Transaction")
        if (tx == null) {
            Text("Waiting for its charge. It's matched by itself when the charge comes in, or match it now.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Button(onClick = onMatch) { Text("Match to a transaction") }
        } else {
            ListRow(onClick = { onTransaction(tx.id) }) {
                Row { Column(Modifier.weight(1f)) { Text(tx.payee.ifBlank { "Transaction" }); Text(tx.date.toString(), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }; MoneyText(tx.amount) }
            }
            val total = r.total
            if (total != null && kotlin.math.abs(tx.amount) > total && r.kind == ReceiptKind.PURCHASE) {
                Text(
                    "The charge is ${Money.format(kotlin.math.abs(tx.amount) - total)} more than the receipt: a tip, or a pump's final amount. Split by items spreads it with the rest.",
                    color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
            Row {
                if (items.isNotEmpty() && r.kind == ReceiptKind.PURCHASE) Button(onClick = onSplit) { Text("Split by items") }
                TextButton(onClick = onDetach) { Text("Not this transaction") }
            }
        }
        if (message != null) Text(message, color = DdfColors.Cyan, modifier = Modifier.padding(vertical = 6.dp))
        SectionTitle("Items")
        if (items.isEmpty()) Text("No items were read. Edit to add them.", color = DdfColors.Muted)
        items.forEach { i ->
            val back = ItemReturns.returnedMilli(i.id, returns)
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(i.text + if (i.quantityMilli != 1000L) "  x${Receipts.qty(i.quantityMilli)}" else "")
                    val sub = listOfNotNull(
                        i.envelopeId?.let { envelopes[it]?.name }, if (back > 0) "${Receipts.qty(back)} returned" else null,
                        if (i.priceWithTax != null && i.priceWithTax != i.amount) "${Money.format(i.amount)} on the shelf, tax ${i.taxLetter}".trim() else null,
                    )
                    if (sub.isNotEmpty()) Text(sub.joinToString(", "), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                MoneyText(i.paid, color = if (i.amount < 0) DdfColors.Cyan else null)
            }
        }
        Gap()
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete receipt", color = DdfColors.Error) }
        Gap(24)
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete this receipt?", "The photo and the items are deleted for good. The transaction stays as it is.", "Delete",
            onCancel = { confirmDelete = false }, onConfirm = { confirmDelete = false; onDelete() }, destructive = true,
        )
    }
}

/** Several transactions could be this receipt's: pick one, or leave it waiting. */
@Composable
fun ReceiptMatchScreen(r: ReceiptEntity, candidates: List<ReceiptMatch.Candidate>, onBack: () -> Unit, onPick: (String) -> Unit) {
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("Which transaction?", onBack)
        Text("${r.store.ifBlank { "Receipt" }}, ${r.total?.let { Money.format(it) } ?: "no total"}${r.date?.let { ", $it" } ?: ""}", color = DdfColors.Muted)
        if (candidates.isEmpty()) EmptyState("No transaction matches yet. The receipt waits in Receipts and is matched when its charge comes in.")
        candidates.forEach { c ->
            ListRow(onClick = { onPick(c.tx.id) }) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(c.tx.payee.ifBlank { "Transaction" })
                        Text(
                            "${c.tx.date}" + (if (c.fit == ReceiptMatch.Fit.TIP) ", more than the receipt (tip or fuel)" else "") + (if (c.tx.pending) ", pending" else ""),
                            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    MoneyText(c.tx.amount)
                }
            }
        }
        Gap()
        TextButton(onClick = onBack) { Text("None of these: leave it waiting") }
        Gap(24)
    }
}

/** Split by items: an envelope for each item; tax (and any tip) follows in proportion. */
@Composable
fun ItemSplitScreen(
    r: ReceiptEntity, items: List<ReceiptItemEntity>, tx: TransactionEntity, budget: Budget, initial: Map<String, String?>, error: String?,
    onBack: () -> Unit, onApply: (Map<String, String?>) -> Unit,
) {
    val pick = remember { mutableStateMapOf<String, String?>().apply { putAll(initial) } }
    val options: List<Pair<String?, String>> = budget.fillOrder.map { it.id to it.name }
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("Split by items", onBack)
        Text("${r.store}, ${Money.format(tx.amount)}. " + (if (kotlin.math.abs(tx.amount) > (r.total ?: 0)) "Tax and the tip are" else "Tax is") + " shared out by what each envelope's items cost with their tax.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        items.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.text)
                    MoneyText(i.paid, small = true, color = if (i.amount < 0) DdfColors.Cyan else null)
                }
                Box(Modifier.weight(1f)) { Picker("Envelope", options, pick[i.id], { pick[i.id] = it }) }
            }
        }
        val shares = runCatching { ItemSplit.shares(tx.amount, items, r.tax ?: 0, pick) }.getOrNull()
        if (shares != null) {
            SectionTitle("The transaction becomes")
            shares.forEach { s ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(budget.envelopes.firstOrNull { it.id == s.envelopeId }?.name ?: "?", Modifier.weight(1f))
                    Text("items with tax ${Money.format(s.items)}" + (if (s.tax != 0L) ", plus ${Money.format(s.tax)} of the charge" else "") + "  ", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    MoneyText(-s.total)
                }
            }
        }
        ErrorLine(error)
        Gap()
        Button(onClick = { onApply(pick.toMap()) }, modifier = Modifier.fillMaxWidth()) { Text("Apply split") }
        Gap(24)
    }
}

/** More, Receipts: the ones waiting for their charge, then the matched ones. */
@Composable
fun ReceiptsScreen(receipts: List<ReceiptEntity>, budget: Budget, onBack: () -> Unit, onReceipt: (String) -> Unit, onScan: () -> Unit) {
    val txs = budget.transactions.associateBy { it.id }
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("Receipts", onBack) { TextButton(onClick = onScan) { Text("Scan") } }
        val waiting = receipts.filter { it.transactionId == null || txs[it.transactionId] == null }
        SectionTitle("Waiting for a charge")
        if (waiting.isEmpty()) Text("None. Every receipt is matched.", color = DdfColors.Muted)
        waiting.forEach { ReceiptRow(it, null, onReceipt) }
        SectionTitle("Matched")
        val matched = receipts.filter { it.transactionId != null && txs[it.transactionId] != null }.take(50)
        if (matched.isEmpty()) Text("None yet.", color = DdfColors.Muted)
        matched.forEach { ReceiptRow(it, txs[it.transactionId], onReceipt) }
        Gap(24)
    }
}

@Composable
private fun ReceiptRow(r: ReceiptEntity, tx: TransactionEntity?, onReceipt: (String) -> Unit) {
    ListRow(onClick = { onReceipt(r.id) }) {
        Row {
            Column(Modifier.weight(1f)) {
                Row { Text(r.store.ifBlank { "Receipt" }); if (r.kind == ReceiptKind.RETURN) Pill("return") }
                Text(listOfNotNull(r.date?.toString(), tx?.let { "matched to ${it.payee.ifBlank { "a transaction" }}" }).joinToString(", "), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            r.total?.let { MoneyText(if (r.kind == ReceiptKind.RETURN) it else -it) }
        }
    }
}

/**
 * A return of a purchase that has a receipt: tick what came back (and how many). The refund goes back to each
 * item's envelope. Items already returned can't be picked again.
 */
@Composable
fun ReturnItemsScreen(
    credit: TransactionEntity, purchase: TransactionEntity, r: ReceiptEntity, items: List<ReceiptItemEntity>, returns: List<ItemReturnEntity>,
    preselect: Map<String, Long>, budget: Budget, error: String?, onBack: () -> Unit, onApply: (Map<String, Long>) -> Unit, onWholePurchase: () -> Unit,
) {
    val picks = remember { mutableStateMapOf<String, Long>().apply { putAll(preselect) } }
    val envelopes = budget.envelopes.associateBy { it.id }
    ScrollColumn(Modifier.fillMaxWidth(), rememberScrollState(), Modifier.padding(horizontal = 16.dp)) {
        ScreenTitle("What came back?", onBack)
        Text("Refund ${Money.format(credit.amount)} on ${credit.date}, from ${purchase.payee.ifBlank { "the purchase" }} on ${purchase.date}.", color = DdfColors.Muted)
        if (preselect.isNotEmpty()) Text("Ticked from the return receipt.", color = DdfColors.Cyan, style = MaterialTheme.typography.bodySmall)
        items.filter { it.amount > 0 }.forEach { i ->
            val left = ItemReturns.remainingMilli(i, returns, except = credit.id)
            val q = picks[i.id] ?: 0L
            val whole = i.quantityMilli % 1000 == 0L && i.quantityMilli > 1000
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(q > 0, { on -> if (left > 0) picks[i.id] = if (on) (if (whole) 1000L else left) else 0L }, enabled = left > 0)
                    Column(Modifier.weight(1f)) {
                        Text(i.text + if (i.quantityMilli != 1000L) "  x${Receipts.qty(i.quantityMilli)}" else "")
                        Text(
                            (i.envelopeId?.let { envelopes[it]?.name } ?: "the purchase's envelope") + if (left < i.quantityMilli) ", ${Receipts.qty(i.quantityMilli - left)} returned already" else "",
                            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (whole && q > 0) {
                        TextButton(onClick = { picks[i.id] = maxOf(1000L, q - 1000) }) { Text("-") }
                        Text(Receipts.qty(q))
                        TextButton(onClick = { picks[i.id] = minOf(left, q + 1000) }) { Text("+") }
                    }
                    MoneyText(ItemReturns.value(i, if (q > 0) q else i.quantityMilli, items, r.tax ?: 0), small = true)
                }
            }
        }
        val chosen = picks.filterValues { it > 0 }
        val selected = items.filter { it.id in chosen }.sumOf { ItemReturns.value(it, chosen[it.id]!!, items, r.tax ?: 0) }
        val ok = chosen.isNotEmpty() && ItemReturns.closeEnough(credit.amount, selected)
        Text(
            "Ticked: ${Money.format(selected)} with tax. Refund: ${Money.format(credit.amount)}." + if (chosen.isNotEmpty() && !ok) " These don't match the refund yet." else "",
            color = if (ok || chosen.isEmpty()) DdfColors.Muted else DdfColors.Warning,
        )
        Text("Tax is shared over all items by price, since the receipt doesn't say which were taxed.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        ErrorLine(error)
        Gap()
        Button(onClick = { onApply(chosen) }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Return these") }
        TextButton(onClick = onWholePurchase) { Text("Return to the purchase's envelopes instead") }
        Gap(24)
    }
}
