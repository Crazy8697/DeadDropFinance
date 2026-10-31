package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.ui.Nav

/**
 * Receipts in the program's layout (0.14.1), the phone's Receipts list's data: the ones waiting for a charge and the
 * last 50 matched, each in its own card across the window; the screen's name and Scan are in the top bar. A receipt
 * opens the phone's receipt screen (edit, match, split by items, delete); Scan the phone's Scan screen.
 */
@Composable
fun ProgramReceiptsScreen(model: AppModel) {
    val b = model.budget ?: return
    // read again whenever the budget is, and on coming back from the phone's receipt screens
    val receipts by produceState(emptyList<ReceiptEntity>(), b, model.phoneNav, model.phoneGeneration) {
        value = withContext(Dispatchers.IO) { model.store()?.dao?.receiptsNow().orEmpty().filter { !it.deleted } }
    }
    val txs = b.transactions.associateBy { it.id }
    val waiting = receipts.filter { it.transactionId == null || txs[it.transactionId] == null }
    val matched = receipts.filter { it.transactionId != null && txs[it.transactionId] != null }.take(50)
    Body {
        ReceiptCard("Waiting for a charge", "Scanned receipts not matched to a transaction yet. Each is matched when its charge comes in.",
            waiting, "None. Every receipt is matched.", model, "receipts.waiting")
        Spacer(Modifier.height(16.dp))
        ReceiptCard("Matched", "The last 50, each with the transaction it belongs to.", matched, "None yet.", model, "receipts.matched")
    }
}

@Composable
private fun ReceiptCard(title: String, hint: String, list: List<ReceiptEntity>, empty: String, model: AppModel, tag: String) {
    val b = model.budget ?: return
    val txs = b.transactions.associateBy { it.id }
    Panel(title, hint, Modifier.fillMaxWidth().testTag(tag)) {
        Row(Modifier.fillMaxWidth().background(C.Panel2).padding(horizontal = 10.dp, vertical = 7.dp)) {
            HeadCell("Store", Modifier.weight(0.36f)); HeadCell("Date", Modifier.weight(0.16f)); HeadCell("Matched to", Modifier.weight(0.32f))
            HeadCell("Total", Modifier.weight(0.16f), end = true)
        }
        if (list.isEmpty()) T(empty, 13.sp, C.Dim, modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp))
        list.forEach { r ->
            val src = remember { MutableInteractionSource() }
            val tx = r.transactionId?.let { txs[it] }
            Row(
                Modifier.fillMaxWidth().background(if (hovered(src)) C.Hover else Color.Transparent).bottomLine().hoverable(src)
                    .clickable(interactionSource = src, indication = null) { model.openPhone(Nav.Receipt(r.id)) }
                    .padding(horizontal = 10.dp, vertical = 9.dp).testTag("receipt.${r.store.ifBlank { "Receipt" }}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(0.36f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(r.store.ifBlank { "Receipt" }, maxLines = 1)
                    if (r.kind == ReceiptKind.RETURN) Tag("return")
                }
                T(r.date?.toString() ?: "", 13.sp, C.Dim, modifier = Modifier.weight(0.16f))
                T(tx?.payee?.ifBlank { "a transaction" } ?: "", 13.sp, C.Dim, maxLines = 1, modifier = Modifier.weight(0.32f))
                val total = r.total?.let { if (r.kind == ReceiptKind.RETURN) it else -it }
                T(total?.let { money(it) } ?: "", color = total?.let { moneyColor(it) } ?: C.Dim2, align = TextAlign.End, modifier = Modifier.weight(0.16f))
            }
        }
    }
}

/** A column heading inside a card, as a table's. */
@Composable
private fun HeadCell(text: String, modifier: Modifier, end: Boolean = false) {
    Column(modifier, horizontalAlignment = if (end) Alignment.End else Alignment.Start) { Label(text) }
}
