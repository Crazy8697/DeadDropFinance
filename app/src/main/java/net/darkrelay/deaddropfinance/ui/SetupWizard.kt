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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The setup wizard's pages, in order. Link is skipped when no bank was connected. */
enum class WizardPage { WELCOME, LOCK, DOOR, CONNECT, LINK, STARTER, FINISH }

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
    Text("Setup takes a few steps: app lock, how your bank transactions get in, and optional starter envelopes. You can rerun it any time from More, Setup.", color = DdfColors.Muted)
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
    connections: List<net.darkrelay.deaddropfinance.data.ConnectionEntity>,
    links: List<net.darkrelay.deaddropfinance.data.LinkedAccountEntity>,
    ui: ConnectUi,
    picks: Map<String, LinkPick>,
    linkBusy: Boolean,
    syncMessage: String?,
    starters: Set<String>,
    more: MoreState,
    hasAccounts: Boolean,
    onPage: (WizardPage?) -> Unit,
    onDoor: (Door) -> Unit,
    door: Door?,
    onConnected: (String) -> Unit,
    wizardConn: String?,
    onSimpleFin: () -> Unit,
    onApplyLinks: (String) -> Unit,
    onPick: (String, LinkPick) -> Unit,
    onLock: (Boolean) -> Unit,
    onAddAccount: () -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onToggleStarter: (String) -> Unit,
    onAddStarters: () -> Unit,
    onFinish: () -> Unit,
) {
    when (page) {
        WizardPage.WELCOME -> WizardFrame(page, "Welcome", onBack = if (forced) null else { { onPage(null) } }, next = "Start" to { onPage(WizardPage.LOCK) }) { WelcomePage() }
        WizardPage.LOCK -> WizardFrame(page, "App lock", { onPage(WizardPage.WELCOME) }, "Next" to { onPage(WizardPage.DOOR) }) {
            LockPage(more.lockEnabled && more.lockAvailable, more.lockAvailable, onLock)
        }
        WizardPage.DOOR -> WizardFrame(page, "Pick your door", { onPage(WizardPage.LOCK) }, null) {
            Text("How do your bank transactions get in? You can add another way later from More, Bank sync.", color = DdfColors.Muted)
            Gap()
            DoorList(onPick = onDoor)
        }
        WizardPage.CONNECT -> {
            val title = when (door) { Door.EASY -> "Connect with SimpleFIN"; else -> "Old School" }
            val skip = if (door == Door.OLD_SCHOOL) null else "Skip for now" to { onPage(WizardPage.STARTER) }
            WizardFrame(page, title, { onPage(WizardPage.DOOR) }, skip) {
                when (door) {
                    Door.EASY -> SimpleFinDoor(ui, onOpen, onSimpleFin)
                    else -> OldSchoolDoor(hasAccounts, onAddAccount, onImport) { onPage(WizardPage.STARTER) }
                }
            }
        }
        WizardPage.LINK -> {
            val id = wizardConn
            val mine = links.filter { it.connectionId == id }
            WizardFrame(page, "Link accounts", { onPage(WizardPage.CONNECT) }, if (id == null) null else "Link and sync" to { onApplyLinks(id) }, nextEnabled = !linkBusy) {
                connections.firstOrNull { it.id == id }?.let { Text("Connected: ${it.name}", color = DdfColors.Cyan) }
                LinkPanel(mine, budget.accounts, picks, onPick, linkBusy, null, onApply = null)
                syncMessage?.let { Text(it) }
            }
        }
        WizardPage.STARTER -> WizardFrame(
            page, "Starter envelopes", { onPage(if (wizardConn != null) WizardPage.LINK else WizardPage.CONNECT) },
            (if (starters.isEmpty()) "Next" else "Add ${starters.size}") to { if (starters.isEmpty()) onPage(WizardPage.FINISH) else onAddStarters() },
        ) {
            syncMessage?.let { Text(it, color = DdfColors.Cyan); Gap() }
            StarterPage(starters, budget.envelopes.map { it.name }.toSet(), onToggleStarter) { onPage(WizardPage.FINISH) }
        }
        WizardPage.FINISH -> WizardFrame(page, "Finish", { onPage(WizardPage.STARTER) }, "Finish" to onFinish) {
            val lines = mutableListOf<String>()
            lines += if (more.lockEnabled && more.lockAvailable) "App lock is on." else "App lock is off."
            lines += if (connections.isEmpty()) "No bank connection. Import a CSV or add transactions any time." else
                connections.joinToString("\n") { "Connected: ${it.name}" }
            lines += "${budget.accounts.count { !it.archived }} account(s), ${budget.envelopes.count { !it.archived }} envelope(s) including Rollover."
            lines += "Rerun setup from More, Setup. Add or change bank connections from More, Bank sync."
            FinishPage(lines)
        }
    }
}

/** What a launch shows for setup. */
enum class SetupMode { WIZARD, CARD, NONE }

/**
 * Setup is required on a fresh install. A quick check of what's already there decides: an install updated from
 * 0.6.x with accounts or a connection in it is a working setup, so it gets a dismissible Home card instead.
 */
fun setupMode(setupDone: Boolean, cardDismissed: Boolean, hasAccounts: Boolean, hasConnections: Boolean): SetupMode = when {
    setupDone -> SetupMode.NONE
    !hasAccounts && !hasConnections -> SetupMode.WIZARD
    cardDismissed -> SetupMode.NONE
    else -> SetupMode.CARD
}
