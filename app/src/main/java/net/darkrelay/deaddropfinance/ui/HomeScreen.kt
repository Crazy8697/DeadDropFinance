package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money

/** The update bar (same behavior as 0.5.0): never a popup. */
@Composable
fun UpdateBar(release: UpdateCheck.Release, onNotes: () -> Unit, onDownload: () -> Unit, onSkip: () -> Unit, onLater: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Version ${release.version} is available", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onNotes) { Text("Release notes") }
                TextButton(onClick = onDownload) { Text("Download") }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onSkip) { Text("Skip this version") }
                TextButton(onClick = onLater) { Text("Later") }
            }
        }
    }
}

/**
 * Home for this stage: Ready to Assign with Assign, the update bar, To sort count, and the envelope list. No Safe
 * to Spend card yet (Stage 11); Rollover sits in the list like any envelope, with no callouts.
 */
@Composable
fun HomeScreen(
    budget: Budget,
    update: (@Composable () -> Unit)?,
    onAssign: () -> Unit,
    onToSort: () -> Unit,
    onEnvelope: (String) -> Unit,
    onAddEnvelope: () -> Unit,
    /** Under Ready to Assign: the payday checklist. */
    belowReady: (@Composable () -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        item { update?.invoke() }
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(10.dp)).background(DdfColors.Teal).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ready to assign", style = MaterialTheme.typography.labelMedium, color = DdfColors.Text)
                    Text(Money.format(budget.readyToAssign), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium,
                        color = if (budget.readyToAssign < 0) DdfColors.Warning else DdfColors.Text)
                }
                Button(onClick = onAssign) { Text("Assign") }
            }
        }
        if (belowReady != null) item { belowReady() }
        if (budget.toSort.isNotEmpty()) item {
            ListRow(onClick = onToSort) {
                Row { Text("${budget.toSort.size} to sort", modifier = Modifier.weight(1f), color = DdfColors.Cyan); Text("Open") }
            }
        }
        item { SectionTitle("Envelopes") { TextButton(onClick = onAddEnvelope) { Text("Add") } } }
        val list = budget.fillOrder
        if (list.size <= 1 && list.all { it.mode == EnvelopeMode.ROLLOVER } && budget.envelopeBalances.values.all { it == 0L }) {
            item { EmptyState("No envelopes yet besides Rollover. Add one to give your money a job.") }
        }
        items(list, key = { it.id }) { e -> EnvelopeRow(e, budget, onClick = { onEnvelope(e.id) }) }
        item { Gap(24) }
    }
}

@Composable
fun EnvelopeRow(e: EnvelopeEntity, budget: Budget, onClick: () -> Unit, handle: (@Composable () -> Unit)? = null) {
    val bal = budget.envelopeBalance(e.id)
    val goal = when (e.mode) {
        EnvelopeMode.FILL_TO_TARGET -> e.target
        EnvelopeMode.GOAL -> e.goalAmount
        else -> null
    }
    ListRow(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            handle?.invoke()
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(e.name)
                modeTag(e)?.let { Pill(it) }
            }
            MoneyText(bal)
            if (goal != null) Text(" / ${Money.format(goal)}", color = DdfColors.Muted)
        }
        if (goal != null && goal > 0) {
            Gap(4)
            Progress(if (bal <= 0) 0f else (bal.toFloat() / goal.toFloat()), warn = bal < 0)
        } else if (bal < 0) {
            Gap(4)
            Progress(1f, warn = true)
        }
    }
}

fun modeTag(e: EnvelopeEntity): String? = when (e.mode) {
    EnvelopeMode.FILL_TO_TARGET -> null
    EnvelopeMode.ADD_EVERY_PAYDAY -> "every payday"
    EnvelopeMode.GOAL -> "goal"
    EnvelopeMode.SEASONAL -> "seasonal"
    EnvelopeMode.DEBT -> "debt"
    EnvelopeMode.CASH -> "cash"
    EnvelopeMode.ROLLOVER -> null
}

fun modeName(m: EnvelopeMode): String = when (m) {
    EnvelopeMode.FILL_TO_TARGET -> "Fill to target"
    EnvelopeMode.ADD_EVERY_PAYDAY -> "Add every payday"
    EnvelopeMode.GOAL -> "Goal"
    EnvelopeMode.SEASONAL -> "Seasonal"
    EnvelopeMode.DEBT -> "Debt"
    EnvelopeMode.CASH -> "Cash"
    EnvelopeMode.ROLLOVER -> "Rollover"
}
