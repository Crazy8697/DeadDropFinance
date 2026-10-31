package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money

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
