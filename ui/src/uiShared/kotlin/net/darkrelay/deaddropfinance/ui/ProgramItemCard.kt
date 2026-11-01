package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.ReceiptTax

/** The item card's measures (dp), as the approved mockup draws them. */
object ItemCardSizes {
    val Qty = 90.dp
    val Price = 120.dp
    val WithTax = 120.dp
    val Gap = 10.dp
    /** Item's right edge is With tax's: Qty + Price + With tax and the gaps between them. */
    val Item = Qty + Price + WithTax + Gap * 2
    val Envelope = 200.dp
    val Button = 80.dp
    /** Twice the text's height, padding included: 13sp text in a 26dp box. */
    val Box = 26.dp
    /** The left block's width: Row 2 through Checked and Remove (slider 36 + label after it, then the buttons). */
    val Left = 636.dp
    /** The room the side list needs; less than this and it goes under the left block instead. */
    val ReasonsMin = 280.dp
    val ReasonsGap = 40.dp
}

/**
 * One item on the program's check screen (0.15.8): row 1 Item and Envelope; row 2 Qty, Price, With tax, the Taxed
 * slider, Checked and Remove; the quantity line under it. A field the reader wasn't sure of has a yellow border and
 * label (the label stays plain); why is listed in the card's empty right side, one line each starting with "Check".
 */
@Composable
internal fun ProgramItemCard(
    form: ReceiptForm, i: Int, r: ItemRow, work: ReceiptTax.Result, misreadTo: Long?, showEnvelope: Boolean, envelopeOptions: List<Pair<String?, String>>,
) {
    fun warn(field: String): String? = if (r.checked) null else r.unsure[field]
    val taxedNow = form.taxedOf(i, work)
    // the price the gap points at says which price would add up, before the reader's own doubt about it
    val priceWarn = if (r.checked) null else if (misreadTo != null) "price may be misread: ${Money.format(misreadTo)} would make it add up" else r.unsure["price"]
    val withTaxWarn = if (!r.checked && misreadTo != null) "it does not add up" else null
    val taxWarn = warn("taxed") ?: if (!r.checked && taxedNow == null) "not sure whether it's taxed" else null
    val nameWarn = warn("name"); val qtyWarn = warn("qty"); val envWarn = warn("envelope")
    val reasons = listOfNotNull(
        nameWarn?.let { checkLine("item name", it, "name") },
        qtyWarn?.let { checkLine("quantity", it, "quantity") },
        priceWarn?.let { checkLine("price", it, "price") },
        withTaxWarn?.let { checkLine("with tax", it, "with tax") },
        taxWarn?.let { checkLine("taxed", it, "tax mark", "taxed") },
        envWarn?.let { checkLine("envelope", it, "envelope") },
    )
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        Modifier.padding(bottom = 10.dp).fillMaxWidth().alpha(if (r.checked) 0.6f else 1f).clip(shape)
            .background(DdfColors.Surface).border(1.dp, DdfColors.Outline, shape).testTag("item.card.$i"),
    ) {
        val inner = maxWidth - 32.dp
        val side = inner - ItemCardSizes.Left - ItemCardSizes.ReasonsGap >= ItemCardSizes.ReasonsMin
        @Composable fun sideBlock(modifier: Modifier) {
            Column(modifier.testTag("item.reasons.$i"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.checked) Text("✓ Checked", color = DdfColors.Cyan, style = programText(12), modifier = Modifier.testTag("item.checkedMark.$i"))
                reasons.forEachIndexed { n, line ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(DdfColors.Warning, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Text(line, color = DdfColors.Warning, style = programText(12), modifier = Modifier.testTag("item.reason.$i.$n"))
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.width(ItemCardSizes.Left)) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(ItemCardSizes.Gap)) {
                        CompactField("Item", r.text, { v -> form.editItem(i, "name") { it.copy(text = v) } }, ItemCardSizes.Item, nameWarn != null, "item.name.$i")
                        if (showEnvelope) EnvelopeField(r.envelopeId, envelopeOptions, { v -> form.editItem(i, "envelope") { it.copy(envelopeId = v) } }, envWarn != null, "item.envelope.$i")
                    }
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(ItemCardSizes.Gap), verticalAlignment = Alignment.CenterVertically) {
                        CompactField("Qty", r.qty, { v -> form.editItem(i, "qty") { it.copy(qty = v) } }, ItemCardSizes.Qty, qtyWarn != null, "item.qty.$i")
                        CompactField("Price", r.amount, { v -> form.editItem(i, "price") { it.copy(amount = v) } }, ItemCardSizes.Price, priceWarn != null, "item.price.$i", decimal = true)
                        CompactField("With tax", Money.plain(form.withTaxOf(i, work)), null, ItemCardSizes.WithTax, withTaxWarn != null, "item.withTax.$i")
                        Row(Modifier.padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(ItemCardSizes.Gap), verticalAlignment = Alignment.CenterVertically) {
                            TaxedSlider(taxedNow == true, { form.editItem(i, "taxed") { it.copy(taxed = taxedNow != true) } }, taxWarn != null, i)
                            CardButton(if (r.checked) "Not checked" else "Checked", { form.items[i] = r.copy(checked = !r.checked) }, "item.checked.$i")
                            CardButton("Remove", { form.items.removeAt(i) }, "item.remove.$i")
                        }
                    }
                    qtyLine(r)?.let { Text(it, color = DdfColors.Muted, style = programText(11), modifier = Modifier.padding(top = 8.dp, start = 0.dp).testTag("item.qtyLine.$i")) }
                    if (!side && (reasons.isNotEmpty() || r.checked)) sideBlock(Modifier.padding(top = 10.dp))
                }
                if (side && (reasons.isNotEmpty() || r.checked)) sideBlock(Modifier.padding(start = ItemCardSizes.ReasonsGap, top = 36.dp).weight(1f))
            }
        }
    }
}

private fun programText(sp: Int) = TextStyle(fontFamily = ProgramType.family, fontSize = sp.sp, lineHeight = (sp + 4).sp)

/** "Check item name", "Check quantity, read from a smudged line": what the reader said, after its own field word is dropped. */
internal fun checkLine(phrase: String, reason: String, vararg own: String): String {
    var rest = reason.trim()
    for (w in own) if (rest.startsWith("$w ", true) && rest.drop(w.length + 1).trimStart().let { it.startsWith("read") || it.startsWith("may") || it.startsWith("couldn't") }) {
        rest = rest.drop(w.length).trim(); break
    }
    if (rest.equals(reason.trim(), true) && own.any { reason.trim().equals(it, true) }) rest = ""
    return if (rest.isEmpty()) "Check $phrase" else "Check $phrase, $rest"
}

/** A field box: 26dp (13sp text), its label on the border; a yellow border and label when [flagged], the label's words never change. */
@Composable
private fun CompactField(label: String, value: String, onChange: ((String) -> Unit)?, width: Dp, flagged: Boolean, tag: String, decimal: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    val line = if (flagged) DdfColors.Warning else DdfColors.Outline
    Box(Modifier.width(width).height(ItemCardSizes.Box)) {
        Box(Modifier.matchParentSize().clip(shape).border(1.dp, line, shape).testTag("$tag.box"), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value, { onChange?.invoke(it) }, readOnly = onChange == null, singleLine = true,
                textStyle = programText(13).copy(color = DdfColors.Text), cursorBrush = SolidColor(DdfColors.Cyan),
                keyboardOptions = if (decimal) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp).testTag(tag),
            )
        }
        FieldLabel(label, if (flagged) DdfColors.Warning else DdfColors.Muted)
    }
}

/** The label sitting on the box's top border, the card's color behind it so the border breaks around it. */
@Composable
private fun FieldLabel(text: String, color: Color) {
    Text(
        text, color = color, style = programText(10).copy(lineHeight = 12.sp),
        modifier = Modifier.offset(x = 7.dp, y = (-6).dp).background(DdfColors.Surface).padding(horizontal = 3.dp),
    )
}

@Composable
private fun EnvelopeField(selected: String?, options: List<Pair<String?, String>>, onSelect: (String?) -> Unit, flagged: Boolean, tag: String) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val line = if (flagged) DdfColors.Warning else DdfColors.Outline
    Box(Modifier.width(ItemCardSizes.Envelope).height(ItemCardSizes.Box)) {
        Box(
            Modifier.matchParentSize().clip(shape).border(1.dp, line, shape)
                .clickable(role = Role.DropdownList) { open = true }.testTag(tag),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "Choose", color = DdfColors.Text, style = programText(13), maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 28.dp))
            val chevron = DdfColors.Muted
            Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(10.dp, 6.dp)) {
                val w = 1.4.dp.toPx()
                drawLine(chevron, Offset(0f, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
                drawLine(chevron, Offset(size.width / 2, size.height), Offset(size.width, 0f), w, StrokeCap.Round)
            }
        }
        FieldLabel("Envelope", if (flagged) DdfColors.Warning else DdfColors.Muted)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { open = false; onSelect(v) }) }
        }
    }
}

/** Taxed: one slider switch with its label; off grey, on teal; yellow outline and label while the reader isn't sure. */
@Composable
private fun TaxedSlider(on: Boolean, onToggle: () -> Unit, flagged: Boolean, i: Int) {
    val track = RoundedCornerShape(10.dp)
    Row(
        Modifier.width(94.dp).clickable(role = Role.Switch, onClick = onToggle).semantics { selected = on }.testTag("item.taxed.$i"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp, 20.dp).clip(track).background(if (on) DdfColors.Teal else DdfColors.Outline)
                .then(if (flagged) Modifier.border(1.dp, DdfColors.Warning, track) else Modifier),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = if (on) 19.dp else 3.dp).size(14.dp)
                    .background(if (on) DdfColors.Text else DdfColors.Muted, CircleShape),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text("Taxed", color = if (flagged) DdfColors.Warning else if (on) DdfColors.Text else DdfColors.Muted, style = programText(12))
    }
}

/** Checked and Remove: 80 by 26, outlined, 12sp. */
@Composable
private fun CardButton(label: String, onClick: () -> Unit, tag: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.width(ItemCardSizes.Button).height(ItemCardSizes.Box).clip(shape).border(1.dp, DdfColors.Outline, shape)
            .clickable(role = Role.Button, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = DdfColors.Text, style = programText(12), maxLines = 1) }
}
