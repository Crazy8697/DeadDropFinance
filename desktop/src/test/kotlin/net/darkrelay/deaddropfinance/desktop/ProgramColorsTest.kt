package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.14.1: every screen of the program uses the one set of colors, so a screen can't have a background or card of its
 * own again without this failing. The program's colors are the shared ProgramPalette; no program source sets a color
 * outside Theme.kt's C, nor reaches for the phone's colors; and drawn, every sidebar screen and the app's screens the
 * program opens show the program's window background (on an empty budget, where a wrong one shows most).
 */
class ProgramColorsTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var model: AppModel? = null

    @After fun close() { model?.engine?.close() }

    private val main = listOf(File("src/main/kotlin"), File("desktop/src/main/kotlin")).first { it.isDirectory }

    @Test fun theProgramsColorsAreTheSharedPalette() {
        assertEquals(ProgramPalette.background, C.Bg)
        assertEquals(ProgramPalette.surface, C.Panel)
        assertEquals(ProgramPalette.outline, C.Line)
        assertEquals(ProgramPalette.outlineHigh, C.Line2)
        assertEquals(ProgramPalette.text, C.Text)
        assertEquals(ProgramPalette.muted, C.Dim)
        assertEquals(ProgramPalette.cyan, C.Cyan)
        assertEquals(ProgramPalette.teal, C.Teal)
        assertEquals(ProgramPalette.warning, C.Warn)
        assertEquals(ProgramPalette.error, C.Bad)
    }

    @Test fun noScreenSetsAColorOfItsOwn() {
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 10)
        val literals = files.filter { it.name != "Theme.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> l.contains("Color(0x") }.map { (i, _) -> "${f.name}:${i + 1}" }
        }
        assertEquals(emptyList(), literals, "a color literal outside Theme.kt's C")
        val phoneColors = files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> l.contains("DdfColors.") && !l.contains("DdfColors.palette") }.map { (i, _) -> "${f.name}:${i + 1}" }
        }
        assertEquals(emptyList(), phoneColors, "the phone's colors used directly in the program")
    }

    @Test fun everyScreenShowsTheProgramsBackground() {
        val m = SampleBudget.empty(tmp.newFolder("DeadDropFinance")).also { model = it }
        compose.setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder("settings")))) }
        fun backgroundAtTheRightEdge(what: String) {
            compose.waitForIdle()
            val px = compose.onRoot().captureToImage().toPixelMap()
            // the right margin of the content area, beside the cards: the window background
            val c = px[px.width - 4, px.height / 2]
            assertEquals(C.Bg, c, "$what: background")
        }
        for (s in Screen.entries) {
            m.phoneNav = null
            m.go(s)
            backgroundAtTheRightEdge(s.name)
        }
        for (n in listOf(Nav.Rules, Nav.Scan(null), Nav.ManageCategories, Nav.About, Nav.Subscriptions)) {
            m.go(Screen.BUDGET)
            m.openPhone(n)
            backgroundAtTheRightEdge(n.toString())
            assertEquals(ProgramPalette, DdfColors.palette, "the app's screens in the program use the program's colors")
        }
    }
}
