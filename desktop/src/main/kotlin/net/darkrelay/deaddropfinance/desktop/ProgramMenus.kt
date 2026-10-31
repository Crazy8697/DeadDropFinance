package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import net.darkrelay.deaddropfinance.ui.Nav

/**
 * The program's menus (0.15.0), laid out as DarkRelay's other Windows programs lay theirs out: an icon on the left, the shortcut on the right, separators
 * between groups. File, View (each sidebar screen but Settings, in sidebar order, Ctrl+1 to Ctrl+8) and Help; no Edit
 * menu. One list makes both the menus and their shortcuts, so the two can't differ.
 */
object ProgramMenus {

    /** A shortcut: Ctrl and [key], shown as [text]. */
    class Shortcut(val key: Key, val text: String)

    /** One menu entry; null in a menu's list is a separator. */
    class Item(
        val label: String,
        val icon: ImageVector,
        val shortcut: Shortcut? = null,
        val enabled: (AppModel, Shell) -> Boolean = { _, _ -> true },
        val run: (AppModel, Shell) -> Unit,
    )

    class Menu(val title: String, val items: List<Item?>)

    private fun ctrl(key: Key, text: String) = Shortcut(key, "Ctrl+$text")
    private val budget: (AppModel, Shell) -> Boolean = { m, _ -> m.budget != null }

    private val about = Item("About Dead Drop Finance", MenuIcons.ABOUT, enabled = budget) { m, _ -> m.openPhone(Nav.About) }

    private val viewKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight)
    private val viewIcons = mapOf(
        Screen.BUDGET to MenuIcons.BUDGET, Screen.ACTIVITY to MenuIcons.ACTIVITY, Screen.ACCOUNTS to MenuIcons.ACCOUNTS,
        Screen.RECEIPTS to MenuIcons.RECEIPTS, Screen.BILLS to MenuIcons.BILLS, Screen.CATEGORIES to MenuIcons.CATEGORIES,
        Screen.REPORTS to MenuIcons.REPORTS, Screen.SYNC to MenuIcons.SYNC,
    )

    val menus: List<Menu> = listOf(
        Menu(
            "File",
            listOf(
                Item("Add transaction", MenuIcons.ADD, ctrl(Key.N, "N"), budget) { m, _ -> m.dialog = Dialog.AddTransaction(m.activityTab) },
                Item("Move money", MenuIcons.MOVE, ctrl(Key.M, "M"), budget) { m, _ -> m.dialog = Dialog.MoveMoney() },
                null,
                Item("Import CSV...", MenuIcons.IMPORT, ctrl(Key.I, "I"), budget) { m, _ -> m.openPhone(Nav.Import) },
                Item("Import setup...", MenuIcons.IMPORT_SETUP, enabled = budget) { m, _ -> m.openPhone(Nav.SetupImport) },
                Item("Export CSV...", MenuIcons.EXPORT, ctrl(Key.E, "E"), budget) { m, s -> m.exportCsv(s) },
                null,
                Item("Back up...", MenuIcons.BACKUP, ctrl(Key.B, "B"), budget) { m, _ -> m.startBackup() },
                Item("Restore...", MenuIcons.RESTORE, enabled = budget) { m, s -> s.pickBackupOpen()?.let { m.dialog = Dialog.Restore(it) } },
                null,
                Item("Settings...", MenuIcons.SETTINGS, ctrl(Key.Comma, ",")) { m, _ -> m.go(Screen.SETTINGS) },
                about,
                Item("Quit", MenuIcons.QUIT) { _, s -> s.onExit() },
            ),
        ),
        Menu(
            "View",
            Screen.entries.filter { it != Screen.SETTINGS }.mapIndexed { i, sc ->
                Item(sc.title, viewIcons.getValue(sc), ctrl(viewKeys[i], "${i + 1}")) { m, _ -> m.go(sc) }
            },
        ),
        Menu(
            "Help",
            listOf(
                Item("Check for updates...", MenuIcons.UPDATES) { _, s -> s.onCheckNow() },
                Item("Licenses...", MenuIcons.LICENSES, enabled = { _, s -> s.onLicenses != null }) { _, s -> s.onLicenses?.invoke() },
                Item("Privacy policy", MenuIcons.PRIVACY, enabled = budget) { m, _ -> m.openPhone(Nav.Document("PRIVACY.md")) },
                Item("End user agreement", MenuIcons.TERMS, enabled = budget) { m, _ -> m.openPhone(Nav.Document("TERMS.md")) },
                Item("Support DarkRelay", MenuIcons.SUPPORT) { _, s -> s.onOpenPage(net.darkrelay.deaddropfinance.ui.DarkRelayLinks.CHEESE) },
                null,
                about,
            ),
        ),
    )

    /**
     * A key pressed with Ctrl (no Alt): runs the entry with that shortcut when it's enabled. Not while a dialog is open:
     * its own fields and buttons have the keyboard then. -> whether it was taken.
     */
    fun onKey(key: Key, ctrl: Boolean, alt: Boolean, model: AppModel, shell: Shell): Boolean {
        if (!ctrl || alt || model.dialog != null) return false
        val item = menus.flatMap { it.items }.filterNotNull().firstOrNull { it.shortcut?.key == key } ?: return false
        if (!item.enabled(model, shell)) return false
        item.run(model, shell)
        return true
    }
}
