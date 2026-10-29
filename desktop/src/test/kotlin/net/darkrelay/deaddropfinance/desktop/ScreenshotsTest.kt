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
        val shell = Shell(checkAtStartup = true)
        fun shot(name: String, setup: () -> Unit) {
            m.dialog = null
            m.drawerEnvelope = null
            m.message = null
            setup()
            val scene = ImageComposeScene(1400, 900, Density(1f)) { DesktopApp(m, shell) }
            try {
                scene.render(0)
                scene.render(500_000_000)
                val img = scene.render(1_000_000_000)
                File(dir, "$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            } finally {
                scene.close()
            }
        }
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
        made.model.engine?.close()
    }
}
