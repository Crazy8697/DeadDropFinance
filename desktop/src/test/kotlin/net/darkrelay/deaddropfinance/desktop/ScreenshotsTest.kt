package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Screenshots of every screen, the envelope drawer and both dialogs, at 1400 x 900, from the invented sample budget.
 * Rendered off screen (no window opens). Only when asked for: -Pddf.screens=<folder>.
 */
class ScreenshotsTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun everyScreen() {
        val out = System.getProperty("ddf.screens").orEmpty()
        assumeTrue(out.isNotBlank())
        val dir = File(out).also { it.mkdirs() }
        val made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        val m = made.model
        val shell = Shell(checkAtStartup = true, settings = Settings(tmp.newFolder("settings")))
        fun shot(name: String, on: AppModel = m, setup: () -> Unit) {
            on.dialog = null
            on.drawerEnvelope = null
            on.message = null
            on.phoneNav = null
            setup()
            val scene = ImageComposeScene(1400, 900, Density(1f)) { DesktopApp(on, shell) }
            try {
                scene.render(0)
                // what's worked out off the UI thread (the payday plan) is in before the last frame
                Thread.sleep(400)
                scene.render(500_000_000)
                Thread.sleep(200)
                val img = scene.render(1_000_000_000)
                File(dir, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally {
                scene.close()
            }
        }
        val backupFile = File(tmp.root, "deaddrop-2030-10-29.ddfbak")
        if (System.getProperty("ddf.screensRound") == "0.14.1") {
            // 0.14.1: every sidebar screen, on the sample budget and on an empty one (where a wrong background shows), and the
            // app's screens the program opens, all in the program's colors and buttons
            val empty = SampleBudget.empty(tmp.newFolder("Empty"))
            Screen.entries.forEachIndexed { i, s ->
                shot("%02d-%s".format(i + 1, s.name.lowercase())) { m.go(s) }
                shot("%02d-%s-empty".format(i + 1, s.name.lowercase()), empty) { empty.go(s) }
            }

            val hosted = listOf(
                "scan" to net.darkrelay.deaddropfinance.ui.Nav.Scan(null), "rules" to net.darkrelay.deaddropfinance.ui.Nav.Rules, "subscriptions" to net.darkrelay.deaddropfinance.ui.Nav.Subscriptions, "manage-categories" to net.darkrelay.deaddropfinance.ui.Nav.ManageCategories,
                "import-csv" to net.darkrelay.deaddropfinance.ui.Nav.Import, "import-setup" to net.darkrelay.deaddropfinance.ui.Nav.SetupImport, "about" to net.darkrelay.deaddropfinance.ui.Nav.About, "purge" to net.darkrelay.deaddropfinance.ui.Nav.Purge, "erase" to net.darkrelay.deaddropfinance.ui.Nav.Erase,
                "to-sort" to net.darkrelay.deaddropfinance.ui.Nav.Activity, "fill-order" to net.darkrelay.deaddropfinance.ui.Nav.Envelopes, "forecast" to net.darkrelay.deaddropfinance.ui.Nav.Forecast, "safe-to-spend" to net.darkrelay.deaddropfinance.ui.Nav.SafeToSpend,
                "add-bill" to net.darkrelay.deaddropfinance.ui.Nav.BillEdit(null), "envelope-details" to net.darkrelay.deaddropfinance.ui.Nav.Envelope(made.ids.getValue("Groceries")),
            )
            hosted.forEachIndexed { i, (name, nav) -> shot("%02d-opened-%s".format(30 + i, name)) { m.go(Screen.BUDGET); m.openPhone(nav) } }
            empty.engine?.close()
        }
        if (System.getProperty("ddf.screensRound") == "0.14.0") {
            // 0.14.0: one sidebar; Budget with what Home showed; Receipts; Bills and Categories in the program's layout; Settings
            shot("01-budget") { m.go(Screen.BUDGET) }
            shot("02-receipts") { m.go(Screen.RECEIPTS) }
            shot("03-bills") { m.go(Screen.BILLS) }
            shot("04-bills-next-payday") { m.go(Screen.BILLS); m.dialog = Dialog.NextPayday }
            shot("05-categories") { m.go(Screen.CATEGORIES) }
            shot("06-settings") { m.go(Screen.SETTINGS) }
            val move = m.budget!!.moves.first { net.darkrelay.deaddropfinance.domain.MoveRules.editable(it) }
            shot("07-edit-move") { m.go(Screen.ACTIVITY); m.activityTab = null; m.dialog = Dialog.MoveMoney(editId = move.id) }
            shot("08-scan-receipt") { m.go(Screen.RECEIPTS); m.openPhone(net.darkrelay.deaddropfinance.ui.Nav.Scan(null)) }
            shot("09-sync") { m.go(Screen.SYNC) }
        }
        if (System.getProperty("ddf.screensRound") == "0.8.0") {
            // 0.8.0: the new and changed screens and dialogs
            shot("01-add-transaction-category") { m.go(Screen.BUDGET); m.dialog = Dialog.AddTransaction(made.ids["Checking"], made.ids["Groceries"]) }
            shot("02-add-transaction-payday-fill") { m.go(Screen.BUDGET); m.dialog = Dialog.AddTransaction(made.ids["Checking"], READY_TO_ASSIGN, payday = true, fill = true) }
            shot("03-add-transaction-payday-only") { m.go(Screen.BUDGET); m.dialog = Dialog.AddTransaction(made.ids["Checking"], READY_TO_ASSIGN, payday = true) }
            shot("04-add-transaction-split") { m.go(Screen.BUDGET); m.dialog = Dialog.AddTransaction(made.ids["Checking"], made.ids["Groceries"], split = true) }
            shot("05-settings-backup") { m.go(Screen.SETTINGS) }
            shot("06-backup") { m.go(Screen.SETTINGS); m.dialog = Dialog.Backup }
            shot("07-restore") { m.go(Screen.SETTINGS); m.dialog = Dialog.Restore(backupFile) }
            shot("08-restore-confirm") { m.go(Screen.SETTINGS); m.dialog = Dialog.Restore(backupFile, confirm = true) }
            shot("09-budget") { m.go(Screen.BUDGET) }
        } else if (System.getProperty("ddf.screensRound").isNullOrBlank()) {
            // every screen (no round named)
            shot("01-budget") { m.go(Screen.BUDGET) }
            shot("02-envelope-drawer") { m.go(Screen.BUDGET); m.drawerEnvelope = made.ids["Groceries"] }
            shot("03-activity-all") { m.go(Screen.ACTIVITY); m.activityTab = null }
            shot("04-activity-account") { m.go(Screen.ACTIVITY); m.activityTab = made.ids["Checking"] }
            shot("05-accounts") { m.go(Screen.ACCOUNTS) }
            shot("06-reports") { m.go(Screen.REPORTS) }
            shot("07-sync") { m.go(Screen.SYNC) }
            shot("08-settings") { m.go(Screen.SETTINGS) }
            shot("09-add-transaction") { m.go(Screen.BUDGET); m.dialog = Dialog.AddTransaction(made.ids["Checking"], made.ids["Groceries"]) }
            shot("10-move-money") { m.go(Screen.BUDGET); m.dialog = Dialog.MoveMoney(READY_TO_ASSIGN, made.ids["Groceries"]) }
        }
        made.model.engine?.close()
    }
}
