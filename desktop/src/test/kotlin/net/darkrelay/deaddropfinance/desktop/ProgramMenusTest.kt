package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The program's menus and colors (0.15.0), as DarkRelay's other Windows programs have them: the entries, their order and shortcuts, each with an
 * icon; the shortcuts work from the keyboard (not while a dialog is open); no Edit menu, and Settings isn't in View.
 * The text colors are theirs. Invented data only.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramMenusTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() } }

    private fun entries(title: String) = ProgramMenus.menus.first { it.title == title }.items.map { it?.label ?: "-" }
    private fun keys(title: String) = ProgramMenus.menus.first { it.title == title }.items.filterNotNull().associate { it.label to it.shortcut?.text }

    @Test fun theMenusAreFileViewAndHelpInThatOrder() {
        assertEquals(listOf("File", "View", "Help"), ProgramMenus.menus.map { it.title })
        assertEquals(
            listOf("Add transaction", "Move money", "-", "Import CSV...", "Import setup...", "Export CSV...", "-", "Back up...", "Restore...", "-", "Settings...", "About Dead Drop Finance", "Quit"),
            entries("File"),
        )
        assertEquals(listOf("Budget", "Activity", "Accounts", "Receipts", "Bills", "Categories", "Reports", "Sync"), entries("View"))
        assertEquals(listOf("Check for updates...", "Licenses...", "Privacy policy", "End user agreement", "Support DarkRelay", "-", "About Dead Drop Finance"), entries("Help"))
    }

    @Test fun eachEntryHasItsShortcut() {
        assertEquals(
            mapOf(
                "Add transaction" to "Ctrl+N", "Move money" to "Ctrl+M", "Import CSV..." to "Ctrl+I", "Import setup..." to null, "Export CSV..." to "Ctrl+E",
                "Back up..." to "Ctrl+B", "Restore..." to null, "Settings..." to "Ctrl+,", "About Dead Drop Finance" to null, "Quit" to null,
            ),
            keys("File"),
        )
        assertEquals((1..8).map { "Ctrl+$it" }, keys("View").values.toList())
        assertTrue(keys("Help").values.all { it == null })
        // no two entries share a key, and every entry has an icon
        val all = ProgramMenus.menus.flatMap { it.items }.filterNotNull()
        val withKeys = all.mapNotNull { it.shortcut?.key }
        assertEquals(withKeys.size, withKeys.toSet().size)
        assertTrue(all.all { it.icon.root.size > 0 })
    }

    @Test fun theShortcutsWorkFromTheKeyboard() {
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        var quit = false
        var saved: String? = null
        val shell = Shell(run = { b -> runBlocking { b() } }, onExit = { quit = true }, pickSave = { name -> saved = name; null })
        fun press(key: Key, ctrl: Boolean = true, alt: Boolean = false) = ProgramMenus.onKey(key, ctrl, alt, m, shell)
        assertTrue(press(Key.Three)); assertEquals(Screen.ACCOUNTS, m.screen)
        assertTrue(press(Key.Eight)); assertEquals(Screen.SYNC, m.screen)
        assertTrue(press(Key.Comma)); assertEquals(Screen.SETTINGS, m.screen)
        assertTrue(press(Key.I)); assertEquals(Nav.Import, m.phoneNav)
        assertTrue(press(Key.One)); assertEquals(Screen.BUDGET, m.screen); assertNull(m.phoneNav)
        assertTrue(press(Key.E)); assertTrue(saved!!.endsWith(".csv"), saved)
        assertTrue(press(Key.N)); assertIs<Dialog.AddTransaction>(m.dialog)
        // a dialog has the keyboard: Ctrl+M doesn't swap it for another
        assertFalse(press(Key.M)); assertIs<Dialog.AddTransaction>(m.dialog)
        m.dialog = null
        assertTrue(press(Key.M)); assertIs<Dialog.MoveMoney>(m.dialog)
        m.dialog = null
        // without Ctrl, or with Alt, nothing
        assertFalse(press(Key.N, ctrl = false)); assertNull(m.dialog)
        assertFalse(press(Key.N, alt = true)); assertNull(m.dialog)
        assertFalse(press(Key.Q))
        assertFalse(quit)
    }

    @Test fun entriesThatNeedABudgetWaitForOne() {
        val m = AppModel(null).also { models += it }
        val shell = Shell(run = { b -> runBlocking { b() } })
        val file = ProgramMenus.menus.first { it.title == "File" }.items.filterNotNull().associate { it.label to it.enabled(m, shell) }
        assertFalse(file.getValue("Add transaction")); assertFalse(file.getValue("Back up..."))
        assertTrue(file.getValue("Settings...")); assertTrue(file.getValue("Quit"))
        assertFalse(ProgramMenus.onKey(Key.N, true, false, m, shell))
    }

    @Test fun theMenusOpenWithIconsAndShortcuts() = runDesktopComposeUiTest(1400, 900) {
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        onNodeWithTag("menu.Edit").assertDoesNotExist()
        onNodeWithTag("menu.File").performClick()
        waitForIdle()
        onNodeWithTag("menuItem.Add transaction").assertExists()
        onNodeWithTag("shortcut.Add transaction", useUnmergedTree = true).assertExists()
        onNodeWithTag("menuItem.Settings...").performClick()
        waitForIdle()
        assertEquals(Screen.SETTINGS, m.screen)
        onNodeWithTag("menu.View").performClick()
        waitForIdle()
        onNodeWithTag("menuItem.Settings").assertDoesNotExist()
        onNodeWithTag("menuItem.Reports").performClick()
        waitForIdle()
        assertEquals(Screen.REPORTS, m.screen)
    }

    /** Each menu open, for the round's screenshots (only with -Pddf.screens and round 0.15.0). */
    @Test fun screenshotsOfTheOpenMenus() {
        val dir = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.0" }
        assumeTrue(dir != null)
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        for (menu in listOf("File", "View", "Help")) runDesktopComposeUiTest(1400, 900) {
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()), onLicenses = {})) }
            waitForIdle()
            onNodeWithTag("menu.$menu").performClick()
            waitForIdle()
            // the open menu is a popup, a second root: the first one's picture is the whole window, popup and all
            val img = onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureToImage().toAwtImage()
            javax.imageio.ImageIO.write(img, "png", File(dir!!, "menu-${menu.lowercase()}-1400x900.png"))
        }
    }

    // ---- colors -----------------------------------------------------------------------------------------------------

    /** DarkRelay's other Windows programs' values: their text, muted text and menu colors. */
    private val houseText = Color(0xFFE6E6E6)
    private val houseMuted = Color(0xFF9AA0A6)

    @Test fun theProgramsTextColorsAreTheOtherProgramsOnes() {
        assertEquals(houseText, C.Text)
        assertEquals(houseMuted, C.Dim)
        assertEquals(houseText, ProgramPalette.text)
        assertEquals(houseMuted, ProgramPalette.muted)
        assertEquals(Color(0xFF16181D), C.MenuBar)
        assertEquals(Color(0xFF1E2128), C.MenuPanel)
        assertEquals(Color(0xFF3A4150), C.MenuBorder)
        assertEquals(Color(0xFF2D6CDF), C.MenuSelected)
        assertEquals(Color(0xFF5C6370), C.MenuDisabled)
    }
}
