package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the window around the screens needs from the program: updates and the settings that aren't the budget's. */
class Shell(
    val update: UpdateState = UpdateState.Hidden,
    val canInstall: Boolean = false,
    val installedVersion: String = BuildInfo.VERSION,
    val checkAtStartup: Boolean = true,
    val dataFolder: String = "%APPDATA%\\DeadDropFinance",
    val onInstall: () -> Unit = {},
    val onOpenPage: (String) -> Unit = {},
    val onDismissUpdate: () -> Unit = {},
    val onCheckNow: () -> Unit = {},
    val onCheckAtStartup: (Boolean) -> Unit = {},
    val onOpenDataFolder: (() -> Unit)? = null,
    val onLicenses: (() -> Unit)? = null,
    val onExit: () -> Unit = {},
    /** Runs a model action off the UI thread, then refreshes. */
    val run: (suspend () -> Unit) -> Unit = {},
    /** Asks for a CSV file; null when cancelled. */
    val pickCsv: () -> java.io.File? = { null },
    /** Asks where to save a backup ([suggested]: the file name offered); null when cancelled. */
    val pickBackupSave: (suggested: String) -> java.io.File? = { null },
    /** Asks for a backup file to restore; null when cancelled. */
    val pickBackupOpen: () -> java.io.File? = { null },
)

@Composable
fun DesktopApp(model: AppModel, shell: Shell) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Cyan, background = C.Bg, surface = C.Panel)) {
        Column(Modifier.fillMaxSize().background(C.Bg)) {
            MenuStrip(model, shell)
            UpdateStrip(shell)
            Row(Modifier.fillMaxSize()) {
                Sidebar(model)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.fillMaxSize()) {
                        TopBar(model)
                        model.server?.let { SyncBanners(model, it) }
                        model.message?.let { (text, error) -> MessageLine(text, error) { model.message = null } }
                        val unavailable = model.unavailable
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                model.screen == Screen.SETTINGS -> SettingsScreen(model, shell)
                                model.screen == Screen.SYNC -> SyncScreen(model, model.server)
                                model.screen == Screen.REPORTS -> ReportsScreen()
                                unavailable != null -> UnavailableScreen(unavailable)
                                model.budget == null -> Box(Modifier.fillMaxSize().padding(18.dp)) { T("Opening your budget...", color = C.Dim) }
                                model.screen == Screen.BUDGET -> BudgetScreen(model, shell)
                                model.screen == Screen.ACTIVITY -> ActivityScreen(model, shell)
                                model.screen == Screen.ACCOUNTS -> AccountsScreen(model, shell)
                            }
                        }
                    }
                    model.drawerEnvelope?.let { id -> model.budget?.let { b -> EnvelopeDrawer(model, b, id) } }
                }
            }
        }
        model.dialog?.let { d ->
            val server = model.server
            when (d) {
                is Dialog.ServerDialog -> if (server != null) ServerDialog(model, server, shell, d)
                is Dialog.Backup -> BackupDialog(model, shell)
                is Dialog.Restore -> RestoreDialog(model, shell, d)
                is Dialog.ShowRecoveryKey -> RecoveryKeyDialog(model, d)
                is Dialog.CurrencyPicker -> CurrencyDialog(model, shell)
                else -> model.budget?.let { b -> DialogHost(model, shell, b, d) }
            }
        }
    }
}

// ---- the menu strip (File, Edit, View, Help) -------------------------------------------------------------------

@Composable
private fun MenuStrip(model: AppModel, shell: Shell) {
    Row(Modifier.fillMaxWidth().height(26.dp).background(Color(0xFF0D1217)).bottomLine().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Menu("File") { close ->
            MenuItem("Back up...", enabled = model.budget != null) { close(); model.startBackup() }
            MenuItem("Restore...", enabled = model.budget != null) { close(); shell.pickBackupOpen()?.let { model.dialog = Dialog.Restore(it) } }
            MenuItem("Exit") { close(); shell.onExit() }
        }
        Menu("Edit") { close ->
            MenuItem("Add transaction", enabled = model.budget != null) { close(); model.dialog = Dialog.AddTransaction(model.activityTab) }
            MenuItem("Move money", enabled = model.budget != null) { close(); model.dialog = Dialog.MoveMoney() }
        }
        Menu("View") { close -> Screen.entries.forEach { s -> MenuItem(s.title) { close(); model.go(s) } } }
        Menu("Help") { close ->
            MenuItem("Check for updates") { close(); shell.onCheckNow() }
            MenuItem((if (shell.checkAtStartup) "✓ " else "    ") + "Check for updates at startup") { close(); shell.onCheckAtStartup(!shell.checkAtStartup) }
        }
    }
}

@Composable
private fun Menu(title: String, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    Box {
        Box(
            Modifier.clip(RoundedCornerShape(4.dp)).background(if (open || hovered(src)) C.NavHover else Color.Transparent).hoverable(src)
                .clickable { open = true }.padding(horizontal = 9.dp, vertical = 3.dp).testTag("menu.$title"),
        ) { T(title, 12.sp, if (open || hovered(src)) C.Text else C.Dim) }
        DropdownMenu(open, { open = false }, containerColor = C.Panel2, modifier = Modifier.border(1.dp, C.Line2)) { items { open = false } }
    }
}

@Composable
private fun MenuItem(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(text = { T(text, 13.sp, if (enabled) C.Text else C.Dim2) }, onClick = onClick, enabled = enabled, modifier = Modifier.testTag("menuItem.$text"))
}

// ---- the update bar ---------------------------------------------------------------------------------------------

@Composable
private fun UpdateStrip(shell: Shell) {
    val u = shell.update
    if (u is UpdateState.Hidden) return
    val error = u is UpdateState.Message && u.error
    Row(
        Modifier.fillMaxWidth().background(C.Cyan.copy(alpha = 0.08f)).bottomLine(C.Cyan.copy(alpha = 0.3f)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("updateBar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val text = when (u) {
            is UpdateState.Checking -> "Checking for updates..."
            is UpdateState.Available -> "Dead Drop Finance ${u.release.version} is available. You are on ${shell.installedVersion}."
            is UpdateState.Downloading -> "Downloading Dead Drop Finance ${u.release.version}..." + if (u.total > 0) " ${u.done * 100 / u.total}%" else ""
            is UpdateState.Installing -> "Starting Setup for Dead Drop Finance ${u.release.version}..."
            is UpdateState.Message -> u.text
            UpdateState.Hidden -> ""
        }
        T(text, 13.sp, if (error) C.Bad else C.Cyan, modifier = Modifier.weight(1f).testTag("updateText"))
        if (u is UpdateState.Available) {
            if (shell.canInstall) Btn("Install and restart", shell.onInstall, small = true, tag = "install")
            else Btn("Open the release page", { shell.onOpenPage(u.release.page) }, small = true, ghost = true, tag = "releasePage")
        }
        if (u is UpdateState.Available || u is UpdateState.Message) {
            Box(Modifier.clickable(onClick = shell.onDismissUpdate).padding(horizontal = 4.dp).testTag("dismiss")) { T("✕", 13.sp, C.Dim) }
        }
    }
}

// ---- sidebar and top bar ----------------------------------------------------------------------------------------

private val navIcons = mapOf(
    Screen.BUDGET to "▮", Screen.ACTIVITY to "⋮", Screen.ACCOUNTS to "▣",
    Screen.REPORTS to "▲", Screen.SYNC to "⇄", Screen.SETTINGS to "⚙",
)

@Composable
private fun Sidebar(model: AppModel) {
    Column(Modifier.width(208.dp).fillMaxHeight().background(C.Panel).rightLine().padding(vertical = 12.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Glow(26.dp, 7.dp)
            Spacer(Modifier.width(9.dp))
            Column {
                T("Dead Drop", 14.sp, weight = FontWeight.Bold, spacing = 0.2.sp)
                T("FINANCE", 10.sp, C.Dim2, spacing = 0.6.sp)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Screen.entries.forEach { s -> NavButton(s, model.screen == s) { model.go(s) } }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().topLine().padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
            T("Version ${BuildInfo.VERSION}", 11.sp, C.Dim2)
            T(model.server?.status?.let { ServerModel.pillText(it) } ?: "Not connected", 11.sp, C.Dim2, modifier = Modifier.testTag("sideSync"))
        }
    }
}

@Composable
private fun NavButton(s: Screen, on: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover = hovered(src)
    val shape = RoundedCornerShape(7.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) C.Cyan.copy(alpha = 0.12f) else if (hover) C.NavHover else Color.Transparent)
            .drawBehind { if (on) drawRect(C.Cyan, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height)) }
            .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("nav.${s.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (on) C.Cyan else if (hover) C.Text else C.Dim
        Box(Modifier.width(16.dp), contentAlignment = Alignment.Center) { T(navIcons.getValue(s), 12.sp, color) }
        Spacer(Modifier.width(10.dp))
        T(s.title, 13.5.sp, color)
    }
}

@Composable
private fun TopBar(model: AppModel) {
    val budgety = model.screen in setOf(Screen.BUDGET, Screen.ACTIVITY, Screen.ACCOUNTS, Screen.REPORTS)
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(C.Panel).bottomLine().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        T(model.screen.title, 16.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("title"))
        if (budgety && model.budget != null) MonthPicker(model)
        Spacer(Modifier.weight(1f))
        SyncPill(model.server) { model.go(Screen.SYNC) }
        if (budgety && model.budget != null) {
            Btn("Move money", { model.dialog = Dialog.MoveMoney() }, ghost = true, tag = "top.move")
            Btn("Add transaction", { model.dialog = Dialog.AddTransaction(model.activityTab.takeIf { model.screen == Screen.ACTIVITY }) }, tag = "top.add")
        }
    }
}

@Composable
private fun MonthPicker(model: AppModel) {
    val months = model.months
    val shape = RoundedCornerShape(7.dp)
    Row(Modifier.clip(shape).background(C.Panel2).border(1.dp, C.Line2, shape).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val older = model.monthIndex < months.size - 1
        val newer = model.monthIndex > 0
        Box(Modifier.clickable(enabled = older) { model.monthIndex++ }.padding(horizontal = 6.dp).testTag("month.older")) { T("◀", 13.sp, if (older) C.Dim else C.Line2) }
        T(monthLabel(model.month), 13.sp, modifier = Modifier.padding(horizontal = 4.dp).testTag("month.label"))
        Box(Modifier.clickable(enabled = newer) { model.monthIndex-- }.padding(horizontal = 6.dp).testTag("month.newer")) { T("▶", 13.sp, if (newer) C.Dim else C.Line2) }
    }
}

@Composable
private fun MessageLine(text: String, error: Boolean, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(if (error) C.Bad.copy(alpha = 0.08f) else C.Cyan.copy(alpha = 0.06f)).bottomLine().padding(horizontal = 18.dp, vertical = 7.dp).testTag("message"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(text, 13.sp, if (error) C.Bad else C.Cyan, modifier = Modifier.weight(1f).testTag("messageText"))
        Box(Modifier.clickable(onClick = onClose).padding(horizontal = 4.dp)) { T("✕", 12.sp, C.Dim) }
    }
}

@Composable
private fun UnavailableScreen(reason: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Frame(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp).testTag("unavailable")) {
                T("Your budget can't be opened", 14.sp, C.Bad, FontWeight.SemiBold)
                T("Dead Drop Finance stopped: ${reason.replaceFirstChar { it.uppercase() }}.", 13.sp, modifier = Modifier.padding(top = 6.dp))
                T("Nothing was deleted or replaced. Your files are in %APPDATA%\\DeadDropFinance.", 12.sp, C.Dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** A budget month in words, as the phone's Categories shows it. */
fun monthLabel(m: net.darkrelay.deaddropfinance.domain.Budget.Month): String {
    val f = java.time.format.DateTimeFormatter.ofPattern("MMM d")
    val s = m.start
    val e = m.end
    return when {
        s == null && e == null -> "All activity (no payday marked yet)"
        s == null -> "Before ${e!!.format(f)}"
        e == null -> "This budget month, since ${s.format(f)}"
        else -> "${s.format(f)} to ${e.minusDays(1).format(f)}"
    }
}

fun Modifier.bottomLine(color: Color = C.Line) = drawBehind { drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }
fun Modifier.topLine(color: Color = C.Line) = drawBehind { drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx()) }
fun Modifier.rightLine(color: Color = C.Line) = drawBehind { drawLine(color, Offset(size.width - 0.5f, 0f), Offset(size.width - 0.5f, size.height), 1.dp.toPx()) }
