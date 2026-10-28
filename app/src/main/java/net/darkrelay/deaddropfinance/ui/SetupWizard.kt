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
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The setup wizard's pages, in order. CONNECT is Old School: CSV import or entering things by hand (1.3.0: the only way in). */
enum class WizardPage { WELCOME, LOCK, CONNECT, STARTER, FINISH }

/**
 * Starter envelopes: names only, no dollar amounts (FEATURES.md, Envelopes). Nothing is made unless the user ticks
 * it; "Start blank" makes none.
 */
val STARTER_ENVELOPES = listOf(
    "Groceries", "Gas", "Rent or mortgage", "Electric", "Water", "Phone", "Internet", "Insurance",
    "Car upkeep", "Household", "Eating out", "Gifts",
)

/** The frame every page sits in: title, the page, then Back and the page's own forward button. */
@Composable
fun WizardFrame(
    page: WizardPage,
    title: String,
    onBack: (() -> Unit)?,
    next: Pair<String, () -> Unit>?,
    nextEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Setup, step ${page.ordinal + 1} of ${WizardPage.entries.size}", color = DdfColors.Muted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
            Gap(16)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) OutlinedButton(onClick = onBack) { Text("Back") } else Text("")
            if (next != null) Button(onClick = next.second, enabled = nextEnabled) { Text(next.first) }
        }
    }
}

@Composable
fun WelcomePage() {
    Text("Dead Drop Finance is an envelope budget that lives on this phone. Your budget is encrypted here and nothing about it is sent anywhere.")
    Gap()
    Text("Setup takes a few steps: app lock, how your transactions get in, and optional starter envelopes. You can rerun it any time from More, Setup.", color = DdfColors.Muted)
}

@Composable
fun LockPage(lockOn: Boolean, deviceSecure: Boolean, onLock: (Boolean) -> Unit) {
    if (!deviceSecure) {
        Text("This phone has no screen lock (PIN, pattern, password or fingerprint), so the app can't lock itself. Set one in the phone's settings and app lock turns on.", color = DdfColors.Warning)
        return
    }
    Text("The app locks with your fingerprint or the phone's PIN when you open it and after 5 minutes away. You can change the time in More.")
    SettingRow("App lock", if (lockOn) "On" else "Off", lockOn, true, onLock)
}

@Composable
fun StarterPage(selected: Set<String>, existing: Set<String>, onToggle: (String) -> Unit, onBlank: () -> Unit) {
    Text("Pick any to start with. They're names only: you set amounts yourself, and the app never suggests how much to spend.", color = DdfColors.Muted)
    Gap()
    for (n in STARTER_ENVELOPES) {
        val have = existing.any { it.equals(n, ignoreCase = true) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = have || n in selected, onCheckedChange = { onToggle(n) }, enabled = !have)
            Text(if (have) "$n (already there)" else n)
        }
    }
    Gap()
    OutlinedButton(onClick = onBlank) { Text("Start blank") }
}

@Composable
fun FinishPage(lines: List<String>) {
    Text("You're set.")
    Gap()
    for (l in lines) Text(l, color = DdfColors.Muted, modifier = Modifier.padding(vertical = 2.dp))
}

/** Runs the pages. Back steps a page; the last page has only Back and Finish. */
@Composable
fun SetupWizardHost(
    page: WizardPage,
    forced: Boolean,
    budget: net.darkrelay.deaddropfinance.domain.Budget,
    starters: Set<String>,
    more: MoreState,
    hasAccounts: Boolean,
    onPage: (WizardPage?) -> Unit,
    onLock: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onImport: () -> Unit,
    onToggleStarter: (String) -> Unit,
    onAddStarters: () -> Unit,
    onFinish: () -> Unit,
) {
    when (page) {
        WizardPage.WELCOME -> WizardFrame(page, "Welcome", onBack = if (forced) null else { { onPage(null) } }, next = "Start" to { onPage(WizardPage.LOCK) }) { WelcomePage() }
        WizardPage.LOCK -> WizardFrame(page, "App lock", { onPage(WizardPage.WELCOME) }, "Next" to { onPage(WizardPage.CONNECT) }) {
            LockPage(more.lockEnabled && more.lockAvailable, more.lockAvailable, onLock)
        }
        WizardPage.CONNECT -> WizardFrame(page, "Old School", { onPage(WizardPage.LOCK) }, null) {
            OldSchoolDoor(hasAccounts, onAddAccount, onImport) { onPage(WizardPage.STARTER) }
        }
        WizardPage.STARTER -> WizardFrame(
            page, "Starter envelopes", { onPage(WizardPage.CONNECT) },
            (if (starters.isEmpty()) "Next" else "Add ${starters.size}") to { if (starters.isEmpty()) onPage(WizardPage.FINISH) else onAddStarters() },
        ) {
            StarterPage(starters, budget.envelopes.map { it.name }.toSet(), onToggleStarter) { onPage(WizardPage.FINISH) }
        }
        WizardPage.FINISH -> WizardFrame(page, "Finish", { onPage(WizardPage.STARTER) }, "Finish" to onFinish) {
            val lines = mutableListOf<String>()
            lines += if (more.lockEnabled && more.lockAvailable) "App lock is on." else "App lock is off."
            lines += "Import a CSV or add transactions any time."
            lines += "${budget.accounts.count { !it.archived }} account(s), ${budget.envelopes.count { !it.archived }} envelope(s) including Rollover."
            lines += "Rerun setup from More, Setup."
            FinishPage(lines)
        }
    }
}

/** Old School: CSV import, or plain manual entry. */
@Composable
fun OldSchoolDoor(hasAccounts: Boolean, onAddAccount: () -> Unit, onImport: () -> Unit, onManual: () -> Unit) {
    Text("No bank connection. Download a CSV file from your bank or payment app and import it, or enter things yourself.", color = DdfColors.Muted)
    Gap()
    if (!hasAccounts) {
        Text("Add the account first, with its current balance.")
        OutlinedButton(onClick = onAddAccount) { Text("Add an account") }
        Gap()
    }
    Button(onClick = onImport, enabled = hasAccounts, modifier = Modifier.fillMaxWidth()) { Text("Import a CSV file") }
    Gap()
    OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("I'll enter things myself") }
}

/** Home card for installs that came from 0.6.x with accounts already in them. */
@Composable
fun FinishSetupCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Finish setup", style = MaterialTheme.typography.titleMedium)
            Text("App lock and starter envelopes, or keep going as you are. Nothing you already have is changed.", color = DdfColors.Muted, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onOpen) { Text("Open setup") }
                TextButton(onClick = onDismiss) { Text("Not now") }
            }
        }
    }
}

private val whenFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")

/** A moment as "Mar 4, 3:05 PM" in the phone's time zone, or "never". */
fun whenText(at: Long?, zone: ZoneId = ZoneId.systemDefault()) = at?.let { Instant.ofEpochMilli(it).atZone(zone).format(whenFormat) } ?: "never"

/** What a launch shows for setup. */
enum class SetupMode { WIZARD, CARD, NONE }

/**
 * Setup is required on a fresh install. A quick check of what's already there decides: an install updated from
 * 0.6.x with accounts in it is a working setup, so it gets a dismissible Home card instead. The card is gone for good
 * once the wizard is finished or the user taps Not now.
 */
fun setupMode(setupDone: Boolean, cardDismissed: Boolean, hasAccounts: Boolean, afterPurge: Boolean = false): SetupMode = when {
    // after Purge data: a fresh install's wizard
    afterPurge -> SetupMode.WIZARD
    setupDone -> SetupMode.NONE
    !hasAccounts -> SetupMode.WIZARD
    cardDismissed -> SetupMode.NONE
    else -> SetupMode.CARD
}
