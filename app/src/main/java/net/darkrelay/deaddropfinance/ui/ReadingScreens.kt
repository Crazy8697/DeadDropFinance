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
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.data.NotifAppEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.NotifParse

/** What the notification reading screen shows. [pending]: picked apps' notifications waiting to be checked (memory only). */
data class ReadingState(
    val on: Boolean,
    val granted: Boolean,
    val apps: List<NotifAppEntity>,
    val launcher: List<Pair<String, String>>,
    val pending: Map<String, String>,
)

/**
 * More, Alerts, Read bank notifications: on and off, Android's notification access (with the restricted settings
 * steps a downloaded app needs), which apps, the account each logs to, checking how a notification is read, and what
 * was read last.
 */
@Composable
fun NotifReadingScreen(
    s: ReadingState, budget: Budget,
    onBack: () -> Unit, onToggle: (Boolean) -> Unit, onOpenAccess: () -> Unit, onOpenAppInfo: () -> Unit,
    onPick: (String, String, String?) -> Unit, onDrop: (String) -> Unit, onTeach: (String, NotifParse.Template) -> Unit, onForget: (String) -> Unit, onRefresh: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        ScreenTitle("Read bank notifications", onBack)
        Text(
            "Spending shows up the moment your bank or wallet app notifies you, not hours later with bank sync. Only the apps you pick are read. " +
                "The notification is read on this phone and only the amount and payee are kept; its text is never stored or sent. Bank sync later " +
                "matches the real record to it, so nothing is counted twice.",
            color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
        )
        SettingRow("Read notifications from picked apps", if (s.on) "On" else "Off: nothing is read", s.on, true, onToggle)
        if (!s.on) { Gap(24); return@Column }
        if (!s.granted) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Allow notification access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android asks you to allow this yourself. Because this app is installed from a download (not an app store), Android first " +
                            "blocks it as a \"restricted setting\". Three steps:",
                        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Text("1. Tap Open notification access and try to turn on Dead Drop Finance. If Android says it's a restricted setting, tap OK.", style = MaterialTheme.typography.bodySmall)
                    Text("2. Tap Open app info, then the three dots at the top right, then Allow restricted settings, and confirm with your fingerprint or PIN.", style = MaterialTheme.typography.bodySmall)
                    Text("3. Tap Open notification access again and turn it on.", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onOpenAccess) { Text("Open notification access") }
                        TextButton(onClick = onOpenAppInfo) { Text("Open app info") }
                    }
                }
            }
        } else {
            Text("Notification access is on.", color = DdfColors.Cyan)
        }
        SectionTitle("Apps") { TextButton(onClick = { picking = !picking }) { Text(if (picking) "Done" else "Add an app") } }
        if (s.apps.isEmpty() && !picking) Text("Pick your bank's or wallet's app.", color = DdfColors.Muted)
        val accounts: List<Pair<String?, String>> = budget.accounts.filter { !it.archived }.map { it.id to it.name }
        s.apps.forEach { a ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { onDrop(a.packageName) }) { Text("Remove") }
                    }
                    Picker("Its spending goes to account", accounts, a.accountId, { onPick(a.packageName, a.label, it) })
                    val learned = NotifParse.decode(a.templates)
                    Text(
                        if (learned.isEmpty()) "Not checked yet: the next notification from it asks you once how to read it."
                        else "Reads " + learned.joinToString(" and ") { if (it.deposit) "deposits" else "spending" } + ".",
                        color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            a.lastAt?.let { "Last read: ${if (a.lastDeposit) "+" else "-"}${Money.format(a.lastAmount ?: 0)}, ${a.lastPayee ?: ""}, ${syncTime(it)}" } ?: "Nothing read from it yet.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onRefresh) { Text("Test") }
                    }
                    if (learned.isNotEmpty()) TextButton(onClick = { onForget(a.packageName) }) { Text("Check how it's read again") }
                    s.pending[a.packageName]?.let { text -> TeachCard(a.label, text) { t -> onTeach(a.packageName, t) } }
                }
            }
        }
        if (picking) {
            val picked = s.apps.map { it.packageName }.toSet()
            s.launcher.filter { it.first !in picked }.forEach { (pkg, label) ->
                ListRow(onClick = { onPick(pkg, label, accounts.firstOrNull()?.first); picking = false }) { Text(label) }
            }
        }
        Gap(24)
    }
}

/**
 * The once-per-app check: the notification as it came, the amounts in it to pick from, the payee (first guess filled
 * in), and money in or out. Saving teaches the app's template and logs this one too.
 */
@Composable
fun TeachCard(label: String, text: String, onSave: (NotifParse.Template) -> Unit) {
    val amounts = remember(text) { NotifParse.amounts(text) }
    val guess = remember(text) { NotifParse.guess(text) }
    var pick by remember { mutableIntStateOf(0) }
    var payee by remember { mutableStateOf(guess?.payee ?: "") }
    var deposit by remember { mutableStateOf(guess?.deposit ?: false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Check how $label is read", style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
            if (amounts.isEmpty()) { Text("There's no amount in it.", color = DdfColors.Muted); return@Column }
            Text("The amount", color = DdfColors.Muted, style = MaterialTheme.typography.labelMedium)
            Row { amounts.forEachIndexed { i, a -> FilterChip(selected = pick == i, onClick = { pick = i }, label = { Text(Money.format(a.cents)) }); HGap(4) } }
            TextFieldRow("Payee", payee, { payee = it })
            Row {
                FilterChip(selected = !deposit, onClick = { deposit = false }, label = { Text("Money out") })
                HGap()
                FilterChip(selected = deposit, onClick = { deposit = true }, label = { Text("Money in") })
            }
            Gap()
            Button(onClick = { onSave(NotifParse.learn(text, amounts[pick], payee, deposit)) }) { Text("That's right") }
            Text("After this, $label's notifications like it are read by themselves.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Activity: entries logged from a notification that bank sync hasn't brought in a week. */
@Composable
fun StaleNotificationEntries(list: List<net.darkrelay.deaddropfinance.data.TransactionEntity>, onKeep: (String) -> Unit, onDelete: (String) -> Unit) {
    if (list.isEmpty()) return
    SectionTitle("Not in bank sync after a week")
    Text("These were logged from a bank notification, but the bank's record never came in. Keep them if they're real, or delete them.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
    list.forEach { t ->
        ActivityRowLines(name = t.payee.ifBlank { "Notification entry" }, info = t.date.toString(), chips = if (t.pending) listOf("pending") else emptyList(), amount = t.amount, onClick = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onKeep(t.id) }) { Text("Keep") }
                OutlinedButton(onClick = { onDelete(t.id) }) { Text("Delete") }
            }
        }
    }
}
