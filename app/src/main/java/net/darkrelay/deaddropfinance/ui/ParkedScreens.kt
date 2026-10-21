package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ParkedBuyEntity
import net.darkrelay.deaddropfinance.data.ParkedStatus
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.ParkedBuys

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** When a parked buy is ready: "today, 9:40 PM", "tomorrow, 9:40 PM", or "Mar 18, 9:40 PM". */
fun readyText(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (t.toLocalDate()) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> dayText(t.toLocalDate())
    }
    return "$day, ${t.format(timeFormat)}"
}

/**
 * Parked buys (FEATURES.md): park something with a name and a price, and after the wait it says whether Rollover
 * covers it. Buy it (from Rollover, or another envelope picked here), keep waiting, or drop it.
 */
@Composable
fun ParkedBuysScreen(
    all: List<ParkedBuyEntity>,
    budget: Budget,
    now: Long,
    defaultWait: Int,
    error: String?,
    onBack: () -> Unit,
    onPark: (name: String, amount: Long, waitDays: Int) -> Unit,
    onBuy: (id: String, envelopeId: String) -> Unit,
    onKeep: (String) -> Unit,
    onDrop: (String) -> Unit,
    /** Screenshots only: open with the Park something form showing. */
    startAdding: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    var adding by remember { mutableStateOf(startAdding) }
    val dropped = ParkedBuys.droppedTotal(all)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Parked buys", onBack)
        Text("Want something? Park it. After the wait, it shows whether there's room.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        if (dropped > 0) Row { Text("Dropped so far: ", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall); MoneyText(dropped, color = DdfColors.Cyan, small = true) }
        Gap()
        ErrorLine(error)
        val waiting = ParkedBuys.waiting(all)
        if (waiting.isEmpty() && !adding) EmptyState("Nothing parked. Park something you want, and decide after the wait.")
        waiting.forEach { p -> ParkedCard(p, all, budget, now, zone, onBuy, onKeep, onDrop) }
        if (adding) ParkForm(defaultWait, onPark = { n, a, w -> onPark(n, a, w); adding = false }, onCancel = { adding = false })
        else Button(onClick = { adding = true }) { Text("Park something") }
        val bought = all.filter { it.status == ParkedStatus.BOUGHT && it.transactionId == null }
        if (bought.isNotEmpty()) {
            SectionTitle("Bought")
            bought.forEach { p ->
                ListRow(null) {
                    Row { Text(p.name, Modifier.weight(1f)); MoneyText(p.amount) }
                    val env = budget.envelopes.firstOrNull { it.id == p.envelopeId }?.name ?: "an envelope"
                    Text("From $env. Its charge is sorted there when it comes in.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Gap(24)
    }
}

@Composable
private fun ParkedCard(
    p: ParkedBuyEntity, all: List<ParkedBuyEntity>, budget: Budget, now: Long, zone: ZoneId,
    onBuy: (String, String) -> Unit, onKeep: (String) -> Unit, onDrop: (String) -> Unit,
) {
    var picking by remember(p.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row { Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); MoneyText(p.amount) }
            Text("Parked ${dayText(Instant.ofEpochMilli(p.parkedAt).atZone(zone).toLocalDate())}, waits ${ParkedBuys.waitLabel(p.waitDays)}", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            if (!ParkedBuys.isReady(p, now)) {
                Text("Waiting, ready ${readyText(p.readyAt, now, zone)}", color = DdfColors.Muted, modifier = Modifier.padding(top = 4.dp))
                TextButton(onClick = { onDrop(p.id) }) { Text("Drop it") }
                return@Column
            }
            val v = ParkedBuys.verdict(p, budget, all)
            if (v.fits) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = DdfColors.Cyan)) { append("There's room. ") }
                        append("Rollover has ${Money.format(v.rollover)}, leaving ${Money.format(v.left)}.")
                    },
                    color = DdfColors.Text, modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = DdfColors.Warning)) { append("Not right now. ") }
                        append("Rollover has ${Money.format(v.rollover)}. It would have to come out of another envelope.")
                    },
                    color = DdfColors.Text, modifier = Modifier.padding(top = 4.dp),
                )
            }
            val roll = budget.rollover
            Gap(4)
            if (v.fits && roll != null) Button(onClick = { onBuy(p.id, roll.id) }) { Text("Buy from Rollover") }
            else OutlinedButton(onClick = { picking = !picking }) { Text("Buy from another envelope") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (v.fits) TextButton(onClick = { picking = !picking }) { Text("Other envelope") }
                TextButton(onClick = { onKeep(p.id) }) { Text("Keep waiting") }
                TextButton(onClick = { onDrop(p.id) }) { Text("Drop it") }
            }
            if (picking) {
                Text("Buy it from:", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                budget.fillOrder.filter { it.mode != EnvelopeMode.ROLLOVER }.forEach { e ->
                    ListRow({ onBuy(p.id, e.id) }) {
                        Row { Text(e.name, Modifier.weight(1f)); MoneyText(budget.envelopeBalance(e.id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParkForm(defaultWait: Int, onPark: (String, Long, Int) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var wait by remember { mutableIntStateOf(defaultWait) }
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Park something", style = MaterialTheme.typography.titleMedium)
            TextFieldRow("What is it?", name, { name = it })
            AmountField("Price", price, { price = it })
            Text("Wait before deciding", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            WaitChips(wait) { wait = it }
            Row {
                Button(onClick = { onPark(name, Money.parse(price) ?: 0L, wait) }) { Text("Park it") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** 1 day, 2 days, 1 week. */
@Composable
fun WaitChips(selected: Int, onSelect: (Int) -> Unit) {
    Row {
        ParkedBuys.WAITS.forEach { d ->
            FilterChip(selected = selected == d, onClick = { onSelect(d) }, label = { Text(ParkedBuys.waitLabel(d)) })
            HGap(6)
        }
    }
}

/**
 * Which envelopes Safe to Spend counts: the ones marked Everyday spending, switched on or off here (or on each
 * envelope's edit screen). Rollover never counts, so it isn't listed.
 */
@Composable
fun SafeToSpendScreen(budget: Budget, error: String?, onBack: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Safe to Spend", onBack)
        Text("Counts what's left in the envelopes switched on here, until next payday. Bills and set-asides live in their own envelopes, so they're already out of it.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        Gap()
        Row { Text("Safe to spend", Modifier.weight(1f)); budget.safeToSpend?.let { MoneyText(it, bold = true) } ?: Text("Nothing counted yet", color = DdfColors.Muted) }
        ErrorLine(error)
        budget.fillOrder.filter { it.mode != EnvelopeMode.ROLLOVER }.forEach { e ->
            SettingRow(e.name, "Has ${Money.format(budget.envelopeBalance(e.id))}", e.everyday, true) { onToggle(e.id, it) }
        }
        Gap(24)
    }
}
