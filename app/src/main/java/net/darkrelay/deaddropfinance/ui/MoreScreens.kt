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
import androidx.compose.material3.AlertDialog
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
    onBankSync: () -> Unit = {},
    onSetup: () -> Unit = {},
    syncLine: String? = null,
    onErase: () -> Unit = {},
    /** Sorting (rules, auto-approve) and Alerts sections. */
    sortAlerts: (@Composable () -> Unit)? = null,
    onReceipts: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("More", onBack = null)
        ListRow(onAccounts) { Text("Accounts") }
        ListRow(onBankSync) {
            Column {
                Text("Bank sync")
                Text(syncLine ?: "SimpleFIN, from this phone", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        ListRow(onImport) { Column { Text("Import CSV"); Text("Old School: a bank or payment app export", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        if (onReceipts != null) {
            ListRow(onReceipts) { Column { Text("Receipts"); Text("Scanned receipts, and the ones waiting for their charge", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        }
        sortAlerts?.invoke()
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
        ListRow(onSetup) { Column { Text("Setup"); Text("Run the setup wizard again. Nothing you have is changed.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall) } }
        ListRow(onErase) {
            Column {
                Text("Erase everything and start over", color = DdfColors.Error)
                Text("Deletes all your data from this phone", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        SectionTitle("About")
        Text("Dead Drop Finance, version ${s.version}", color = DdfColors.Muted)
        Gap(24)
    }
}

@Composable
internal fun SettingRow(title: String, help: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
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
                        Row { Text(a.name); if (a.archived) Pill("archived") else if (a.trackOnly) Pill("track only") }
                        Text(accountTypeName(a.type), color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    MoneyText(budget.accountBalance(a.id))
                }
            }
        }
        Gap(24)
    }
}

/** What the account screen saves. */
data class AccountForm(val name: String, val type: AccountType, val startingBalance: Long, val startDate: LocalDate, val trackOnly: Boolean, val heldAmount: Long)

@Composable
fun AccountEditScreen(
    existing: AccountEntity?, error: String?, onBack: () -> Unit, onSave: (AccountForm) -> Unit, onArchive: (Boolean) -> Unit,
    syncSection: (@Composable () -> Unit)? = null,
    budget: Budget? = null,
    /** Deleting this account takes [deleteCount] transactions with it. Null hides the button. */
    onDelete: (() -> Unit)? = null,
    deleteCount: Int = 0,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: AccountType.BANK) }
    var start by remember { mutableStateOf(existing?.startingBalance?.let { Money.plain(it) } ?: "") }
    var date by remember { mutableStateOf((existing?.startDate ?: LocalDate.now()).toString()) }
    var trackOnly by remember { mutableStateOf(existing?.trackOnly ?: false) }
    var held by remember { mutableStateOf(existing?.heldAmount?.takeIf { it > 0 }?.let { Money.plain(it) } ?: "") }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val balance = existing?.let { budget?.accountBalance(it.id) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle(if (existing == null) "New account" else "Edit account", onBack)
        if (existing != null && balance != null && budget != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Balance ", color = DdfColors.Muted)
                MoneyText(balance, bold = true)
                if (existing.archived) Pill("archived, not in the budget") else if (existing.trackOnly) Pill("track only")
            }
            val h = budget.held[existing.id]
            if (h != null && h > 0) Text("Held: ${Money.format(h)}", color = DdfColors.Muted)
            if (budget.heldShort(existing.id)) {
                Text(
                    "The balance is below the held amount of ${Money.format(existing.heldAmount)}. Only the actual balance is held until it's back up.",
                    color = DdfColors.Warning, style = MaterialTheme.typography.bodySmall,
                )
            }
            Gap()
        }
        TextFieldRow("Name", name, { name = it })
        Picker("Type", AccountType.entries.map { it to accountTypeName(it) }, type, { type = it })
        AmountField("Starting balance", start, { start = it })
        DateField("Balance as of", date, { date = it })
        Text(
            if (trackOnly) "This account is outside the budget, so its starting balance doesn't go to Ready to Assign."
            else "The starting balance goes to Ready to Assign.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        SettingRow(
            "Track only, not in the budget",
            "The balance shows and syncs, but never counts in Ready to Assign, and its transactions don't go to To sort.",
            trackOnly, true, { trackOnly = it },
        )
        if (!trackOnly) {
            AmountField("Held amount (optional)", held, { held = it })
            Text(
                "Money that has to stay in this account, like a required minimum. Ready to Assign counts the balance minus this.",
                color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
        }
        ErrorLine(error)
        Gap()
        Button(
            onClick = {
                onSave(
                    AccountForm(
                        name, type, Money.parse(start) ?: 0, parseDateOrNull(date) ?: LocalDate.now(), trackOnly,
                        if (trackOnly) 0 else Money.parse(held) ?: 0,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        if (existing != null) {
            Gap()
            OutlinedButton(onClick = { confirmArchive = true }) { Text(if (existing.archived) "Unarchive" else "Archive") }
        }
        syncSection?.invoke()
        if (existing != null && onDelete != null) {
            Gap(16)
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete account", color = DdfColors.Error) }
        }
        Gap(24)
    }
    if (confirmDelete && existing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = {
                Text(
                    "This deletes the account and its " + (if (deleteCount == 1) "1 transaction" else "$deleteCount transactions") +
                        " for good. Envelope balances and Ready to Assign change to match. A transfer with another account stays there as an " +
                        "entry to sort. Any bank link for this account is removed. This can't be undone.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete for good", color = DdfColors.Error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmArchive && existing != null) {
        val amount = balance?.let { Money.format(it) }
        AlertDialog(
            onDismissRequest = { confirmArchive = false },
            title = { Text(if (existing.archived) "Unarchive ${existing.name}?" else "Archive ${existing.name}?") },
            text = {
                Text(
                    when {
                        existing.archived && existing.trackOnly -> "It comes back to the account list. It stays track only, so its balance still doesn't count in Ready to Assign."
                        existing.archived -> "Its balance${amount?.let { " ($it)" } ?: ""} goes back into Ready to Assign, and the account is back in the budget."
                        existing.trackOnly -> "It leaves the account list. Its transactions stay as history."
                        else -> "This takes its balance${amount?.let { " ($it)" } ?: ""} out of Ready to Assign, as if the account was never in the budget. Its transactions stay as history."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { confirmArchive = false; onArchive(!existing.archived) }) { Text(if (existing.archived) "Unarchive" else "Archive") } },
            dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Cancel") } },
        )
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
