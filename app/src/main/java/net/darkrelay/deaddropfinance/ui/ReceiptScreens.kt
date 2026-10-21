package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import net.darkrelay.deaddropfinance.domain.Receipts

// ---- the form ------------------------------------------------------------------------------------------------------

/** One item row on the form, as text the user can fix. */
data class ItemRow(val text: String, val qty: String, val amount: String, val unitPrice: Long? = null, val id: String? = null)

/** The receipt form while the user checks what was read. */
class ReceiptForm(
    store: String = "", date: String = "", total: String = "", tax: String = "",
    items: List<ItemRow> = emptyList(), val low: Set<String> = emptySet(), val kind: ReceiptKind = ReceiptKind.PURCHASE, val rawText: String = "",
) {
    var store by mutableStateOf(store)
    var date by mutableStateOf(date)
    var total by mutableStateOf(total)
    var tax by mutableStateOf(tax)
    val items = mutableStateListOf<ItemRow>().apply { addAll(items) }

    fun draft(pages: Int): Receipts.Draft {
        fun amt(s: String) = if (s.isBlank()) null else Money.parse(s)?.let { kotlin.math.abs(it) }
        return Receipts.Draft(
            store, parseDateOrNull(date), amt(total), amt(tax),
            items.filter { it.text.isNotBlank() || it.amount.isNotBlank() }.map { r ->
                val q = qtyMilli(r.qty)
                val a = Money.parse(r.amount) ?: 0
                Receipts.DraftItem(r.text, q, r.unitPrice?.takeIf { up -> up * q / 1000 == kotlin.math.abs(a) } ?: if (a > 0 && q > 0) a * 1000 / q else null, a, r.id)
            },
            kind, low, rawText, pages,
        )
    }

    companion object {
        fun from(p: ReceiptParser.Parsed) = ReceiptForm(
            p.store, p.date?.toString() ?: "", p.total?.let { Money.plain(it) } ?: "", p.tax?.let { Money.plain(it) } ?: "",
            p.items.map { ItemRow(it.text, Receipts.qty(it.quantityMilli), Money.plain(it.amount), it.unitPrice) }, p.lowConfidence, p.kind, p.text,
        )

        fun from(r: ReceiptEntity, items: List<ReceiptItemEntity>) = ReceiptForm(
            r.store, r.date?.toString() ?: "", r.total?.let { Money.plain(it) } ?: "", r.tax?.let { Money.plain(it) } ?: "",
            items.map { ItemRow(it.text, Receipts.qty(it.quantityMilli), Money.plain(it.amount), it.unitPrice, it.id) },
            emptySet(), r.kind, r.rawText,
        )

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
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
            pages.drop(1).forEach { p ->
                if (p != null) Image(p, contentDescription = "More of the receipt", contentScale = ContentScale.Fit, modifier = Modifier.height(90.dp).width(60.dp).padding(end = 4.dp))
            }
        }
        Text("${pages.size} photos", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ---- scanning ------------------------------------------------------------------------------------------------------

/** Where a receipt comes from: the camera, or an image or PDF on the phone. */
@Composable
fun ScanScreen(forPayee: String?, working: Boolean, error: String?, onBack: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Scan a receipt", onBack)
        if (forPayee != null) Text("For $forPayee", color = DdfColors.Muted)
        Text(
            "Take a photo of the receipt, or choose an image or a PDF. A long receipt can be more than one photo: add the rest on the next " +
                "screen. It's read on this phone; nothing is sent anywhere.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        Gap()
        if (working) {
            Text("Reading the receipt...")
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
        } else {
            Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Text("Take a photo") }
            Gap()
            OutlinedButton(onClick = onFile, modifier = Modifier.fillMaxWidth()) { Text("Choose an image or PDF") }
        }
        ErrorLine(error)
        Gap(24)
    }
}

/** What was read, as a form to fix before saving. Fields the reading wasn't sure of say so. */
@Composable
fun ReceiptEditScreen(
    form: ReceiptForm, pages: List<ImageBitmap?>, isNew: Boolean, working: Boolean, error: String?,
    onBack: () -> Unit, onAddCamera: () -> Unit, onAddFile: () -> Unit, onSave: () -> Unit,
) {
    fun label(name: String, field: String) = if (field in form.low) "$name (check this)" else name
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (isNew) "Check the receipt" else "Edit receipt", onBack)
        ReceiptPhotos(pages)
        if (isNew) {
            Row {
                TextButton(onClick = onAddCamera, enabled = !working) { Text("Add another photo") }
                TextButton(onClick = onAddFile, enabled = !working) { Text("Add from a file") }
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (form.low.isNotEmpty()) {
            Text("Some of this wasn't clear on the photo. Check what's marked.", color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall)
        }
        if (form.kind == ReceiptKind.RETURN) Pill("return receipt")
        TextFieldRow(label("Store", "store"), form.store, { form.store = it })
        DateField(label("Date", "date"), form.date, { form.date = it })
        Row {
            Box(Modifier.weight(1f)) { AmountField(label("Total", "total"), form.total, { form.total = it }) }
            HGap()
            Box(Modifier.weight(1f)) { AmountField(label("Tax", "tax"), form.tax, { form.tax = it }) }
        }
        SectionTitle(label("Items", "items"))
        form.items.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(3f)) { TextFieldRow("Item", r.text, { form.items[i] = r.copy(text = it) }) }
                HGap(4)
                Box(Modifier.weight(1f)) { TextFieldRow("Qty", r.qty, { form.items[i] = r.copy(qty = it) }) }
                HGap(4)
                Box(Modifier.weight(1.6f)) { AmountField("Price", r.amount, { form.items[i] = r.copy(amount = it) }) }
                TextButton(onClick = { form.items.removeAt(i) }) { Text("Remove") }
            }
        }
        TextButton(onClick = { form.items.add(ItemRow("", "1", "")) }) { Text("Add an item") }
        // does it add up?
        val sum = form.items.sumOf { Money.parse(it.amount) ?: 0 }
        val tax = Money.parse(form.tax)?.let { kotlin.math.abs(it) } ?: 0
        val total = Money.parse(form.total)?.let { kotlin.math.abs(it) }
        if (form.items.isNotEmpty() && total != null) {
            val diff = total - (kotlin.math.abs(sum) + tax)
            Text(
                "Items ${Money.format(kotlin.math.abs(sum))} + tax ${Money.format(tax)} = ${Money.format(kotlin.math.abs(sum) + tax)}" +
                    if (diff == 0L) ", the total." else "; the total says ${Money.format(total)} (${Money.format(diff)} " + (if (diff > 0) "more" else "less") + ", a tip or a missed line?).",
                color = if (diff == 0L) DdfColors.Muted else DdfColors.Warning, style = MaterialTheme.typography.bodySmall,
            )
        }
        ErrorLine(error)
        Gap()
        Button(onClick = onSave, enabled = !working, modifier = Modifier.fillMaxWidth()) { Text("Save receipt") }
        Gap(24)
    }
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
                    val sub = listOfNotNull(i.envelopeId?.let { envelopes[it]?.name }, if (back > 0) "${Receipts.qty(back)} returned" else null)
                    if (sub.isNotEmpty()) Text(sub.joinToString(", "), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
                MoneyText(i.amount, color = if (i.amount < 0) DdfColors.Cyan else null)
            }
        }
        Gap()
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete receipt", color = DdfColors.Error) }
        Gap(24)
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this receipt?") },
            text = { Text("The photo and the items are deleted for good. The transaction stays as it is.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Several transactions could be this receipt's: pick one, or leave it waiting. */
@Composable
fun ReceiptMatchScreen(r: ReceiptEntity, candidates: List<ReceiptMatch.Candidate>, onBack: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Split by items", onBack)
        Text("${r.store}, ${Money.format(tx.amount)}. " + (if (kotlin.math.abs(tx.amount) > (r.total ?: 0)) "Tax and the tip are" else "Tax is") + " shared out by what each envelope's items cost.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        items.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(i.text)
                    MoneyText(i.amount, small = true, color = if (i.amount < 0) DdfColors.Cyan else null)
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
                    Text("items ${Money.format(s.items)}, tax ${Money.format(s.tax)}  ", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
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
