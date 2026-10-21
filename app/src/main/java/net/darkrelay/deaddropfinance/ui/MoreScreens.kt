package net.darkrelay.deaddropfinance.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.lock.LockPolicy

data class MoreState(
    val lockEnabled: Boolean,
    val lockAvailable: Boolean,
    val timeoutMinutes: Int,
    val hideScreen: Boolean,
    val checkAtStartup: Boolean,
    val checking: Boolean,
    val updateMessage: String?,
    val version: String,
)

@Composable
fun MoreScreen(
    s: MoreState,
    onAccounts: () -> Unit,
    onImport: () -> Unit,
    onLock: (Boolean) -> Unit,
    onTimeout: (Int) -> Unit,
    onHide: (Boolean) -> Unit,
    onCheckAtStartup: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("More", onBack = null)
        ListRow(onAccounts) { Text("Accounts") }
        ListRow(onImport) { Column { Text("Import CSV"); Text("Old School: a bank or payment app export", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        SectionTitle("Privacy")
        SettingRow(
            "App lock",
            if (s.lockAvailable) "Fingerprint or the phone's PIN, after time away" else "Off: this phone has no screen lock, so app lock can't work.",
            checked = s.lockEnabled && s.lockAvailable, enabled = s.lockAvailable, onChange = onLock,
        )
        if (s.lockEnabled && s.lockAvailable) {
            Picker(
                "Lock after",
                LockPolicy.TIMEOUTS.map { it to if (it == 0) "Immediately" else "$it minute${if (it == 1) "" else "s"} away" },
                s.timeoutMinutes, onTimeout,
            )
        }
        SettingRow("Hide screen", "Blank in recent apps. Blocks screenshots and screen mirroring tools.", s.hideScreen, true, onHide)
        SectionTitle("Updates")
        SettingRow("Check for updates at startup", "Asks GitHub for a newer version. Nothing about you is sent.", s.checkAtStartup, true, onCheckAtStartup)
        OutlinedButton(onClick = onCheckNow, enabled = !s.checking) { Text(if (s.checking) "Checking..." else "Check for updates") }
        if (s.updateMessage != null) Text(s.updateMessage, modifier = Modifier.padding(vertical = 6.dp))
        SectionTitle("About")
        Text("Dead Drop Finance, version ${s.version}", color = DdfColors.Muted)
        Gap(24)
    }
}

@Composable
private fun SettingRow(title: String, help: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title); Text(help, color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

fun accountTypeName(t: AccountType) = when (t) {
    AccountType.BANK -> "Bank account"
    AccountType.PAYMENT_APP -> "Payment app balance"
    AccountType.CASH -> "Cash"
}

@Composable
fun AccountsScreen(budget: Budget, onBack: () -> Unit, onAccount: (String) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Accounts", onBack) { TextButton(onClick = onAdd) { Text("Add") } }
        if (budget.accounts.isEmpty()) EmptyState("No accounts yet. Add one to get started.")
        budget.accounts.forEach { a ->
            ListRow(onClick = { onAccount(a.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row { Text(a.name); if (a.archived) Pill("archived") }
                        Text(accountTypeName(a.type), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    MoneyText(budget.accountBalance(a.id))
                }
            }
        }
        Gap(24)
    }
}

@Composable
fun AccountEditScreen(existing: AccountEntity?, error: String?, onBack: () -> Unit, onSave: (String, AccountType, Long, LocalDate) -> Unit, onArchive: (Boolean) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.BANK) }
    var start by remember { mutableStateOf(existing?.startingBalance?.let { Money.plain(it) } ?: "") }
    var date by remember { mutableStateOf((existing?.startDate ?: LocalDate.now()).toString()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New account" else "Edit account", onBack)
        TextFieldRow("Name", name, { name = it })
        Picker("Type", AccountType.entries.map { it to accountTypeName(it) }, type, { type = it })
        AmountField("Starting balance", start, { start = it })
        DateField("Balance as of", date, { date = it })
        Text("The starting balance goes to Ready to Assign.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        ErrorLine(error)
        Gap()
        Button(onClick = { onSave(name, type, Money.parse(start) ?: 0, parseDateOrNull(date) ?: LocalDate.now()) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        if (existing != null) {
            Gap()
            OutlinedButton(onClick = { onArchive(!existing.archived) }) { Text(if (existing.archived) "Unarchive" else "Archive") }
        }
        Gap(24)
    }
}

/** Shown while the app is locked: nothing of the budget is composed behind it. */
@Composable
fun LockScreen(onUnlock: () -> Unit, message: String?) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Dead Drop Finance", style = MaterialTheme.typography.headlineSmall, color = DdfColors.Cyan)
        Gap()
        Text("Locked", color = DdfColors.Muted)
        Gap(16)
        Button(onClick = onUnlock) { Text("Unlock") }
        if (message != null) { Gap(); Text(message, color = DdfColors.Muted, textAlign = TextAlign.Center) }
    }
}

/** The database can't be opened on this phone (the key is gone or doesn't fit). Nothing was deleted. */
@Composable
fun DataUnavailableScreen(reason: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your budget can't be opened on this phone", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Gap()
        Text(
            "The key that unlocks it isn't available ($reason). Nothing has been deleted. Restoring from a backup will be possible once backups exist (a later version).",
            color = DdfColors.Muted, textAlign = TextAlign.Center,
        )
    }
}
