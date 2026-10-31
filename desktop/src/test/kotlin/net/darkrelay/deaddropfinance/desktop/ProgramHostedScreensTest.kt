package net.darkrelay.deaddropfinance.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.PhonePalette
import net.darkrelay.deaddropfinance.ui.ProgramPalette
import net.darkrelay.deaddropfinance.ui.ProgramType
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.1: the app's screens inside the program use the program's text sizes (its body 13.5 sp, the sidebar's), and a
 * sub-screen's title and Back are in the top bar, once ("Reports > Price history"); the sidebar shows the real icon; the
 * menus' highlight is the selected sidebar item's dark teal. At 1400 x 900 and 1100 x 700. Invented data only.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramHostedScreensTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private fun model() = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }

    private fun SemanticsNodeInteractionsProvider.fontSizeOf(text: String): Float {
        val node = onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first { it.config.getOrNull(SemanticsProperties.Text) != null }
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.first().layoutInput.style.fontSize.value
    }

    @Test fun theProgramsTextSizesInTheSharedThemeAndThePhonesUntouched() = runDesktopComposeUiTest(400, 300) {
        var program = 0f; var phone = 0f; var title = 0f
        DdfColors.palette = ProgramPalette
        setContent { DdfTheme { program = MaterialTheme.typography.bodyLarge.fontSize.value; title = MaterialTheme.typography.titleLarge.fontSize.value } }
        waitForIdle()
        assertEquals(13.5f, program, "the program's body text, as its sidebar")
        assertEquals(16f, title, "the program's top bar title")
        DdfColors.palette = PhonePalette
        setContent { DdfTheme { phone = MaterialTheme.typography.bodyLarge.fontSize.value } }
        waitForIdle()
        assertEquals(16f, phone, "the phone's own, Material's")
    }

    private fun hosted(w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val m = model()
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        // a report's sub-screen: its list text at the program's size, its name once (in the top bar), Back there
        m.go(Screen.REPORTS)
        // the app's screens wait for the budget to come from the database, which waitForIdle doesn't cover
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("report.PRICE_HISTORY").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("report.PRICE_HISTORY").performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("title.sub", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Price history", onNodeWithTag("title.sub", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text })
        assertEquals(1, onAllNodesWithText("Price history", useUnmergedTree = true).fetchSemanticsNodes().size, "no second title in the page")
        onNodeWithTag("back", useUnmergedTree = true).assertDoesNotExist()
        // the sample has no scanned receipts: the empty list's words, drawn at the program's body size, in its font
        assertEquals(13.5f, fontSizeOf(net.darkrelay.deaddropfinance.ui.NEEDS_RECEIPTS))
        assertEquals(Segoe, ProgramType.family)
        onNodeWithTag("top.back").performClick()
        waitForIdle()
        onNodeWithTag("title.sub", useUnmergedTree = true).assertDoesNotExist()
        // the app's screens the program opens from its own: one title each, in the top bar, and Back returns
        for (n in listOf(Nav.Rules, Nav.Subscriptions, Nav.ManageCategories, Nav.About)) {
            m.go(Screen.BUDGET)
            m.openPhone(n)
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("title.sub", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val title =onNodeWithTag("title.sub", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            assertEquals(1, onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().size, "$n at $w x $h: '$title' shows once")
            onNodeWithTag("back", useUnmergedTree = true).assertDoesNotExist()
            onNodeWithTag("top.back").performClick()
            waitForIdle()
            assertEquals(null, m.phoneNav, "$n: Back returns to the program's screen")
        }
    }

    @Test fun subScreenTitlesAreInTheTopBarAtTheOpeningSize() = hosted(1400, 900)

    @Test fun subScreenTitlesAreInTheTopBarAtTheMinimumSize() = hosted(1100, 700)

    @Test fun hostedListTextIsTheProgramsSize() = runDesktopComposeUiTest(1400, 900) {
        val m = model()
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        m.go(Screen.BUDGET)
        m.openPhone(Nav.Rules)
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithTag("title.sub", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // the screen's own text (not the top bar): its explanation, the body text the program uses
        val texts = onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
            .filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null && it.boundsInRoot.top > 60f && it.boundsInRoot.left > 210f }
        assertTrue(texts.isNotEmpty())
        val sizes = texts.map { n -> mutableListOf<TextLayoutResult>().also { n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(it) }.first().layoutInput.style.fontSize.value }
        assertTrue(sizes.all { it <= 16f }, "nothing bigger than the program's top bar title: $sizes")
        assertTrue(sizes.none { it == 22f || it == 28f }, "no phone-sized titles: $sizes")
    }

    @Test fun theSidebarShowsTheRealIcon() = runDesktopComposeUiTest(1400, 900) {
        val m = model()
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        val px = onNodeWithTag("sidebar.icon").captureToImage().toPixelMap()
        val colors = (0 until px.width).flatMap { x -> (0 until px.height).map { y -> px[x, y] } }.toSet()
        assertTrue(colors.size > 30, "a picture, not a plain square: ${colors.size} colors")
        // the icon's own dark tile (#0b131b) is in it
        assertTrue(colors.any { it.red < 0.08f && it.green < 0.1f && it.blue < 0.13f && it.alpha > 0.9f }, "its dark tile")
    }

    @Test fun theMenuHighlightIsTheSelectedSidebarItemsTeal() = runDesktopComposeUiTest(1400, 900) {
        assertEquals(C.Cyan.copy(alpha = 0.12f).compositeOver(C.Panel), C.MenuSelected)
        assertEquals(C.Cyan, C.MenuSelectedText)
        val m = model()
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()))) }
        waitForIdle()
        onNodeWithTag("menu.File").performClick()
        waitForIdle()
        onNodeWithTag("menuItem.Move money").performMouseInput { moveTo(center) }
        waitForIdle()
        val px = onNodeWithTag("menuItem.Move money").captureToImage().toPixelMap()
        assertEquals(C.MenuSelected, px[px.width - 3, 2], "the hovered entry")
        // and the selected sidebar item, drawn, is the same color
        val nav = onNodeWithTag("nav.BUDGET").captureToImage().toPixelMap()
        assertEquals(C.MenuSelected, nav[nav.width - 3, 2], "the selected sidebar item")
    }
}
