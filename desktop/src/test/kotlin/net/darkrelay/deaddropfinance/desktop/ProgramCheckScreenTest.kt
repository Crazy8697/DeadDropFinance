package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.4 (phone 1.9.5): the check screen in the program, at the size its window opens and at its minimum, with the
 * real Food Lion photo read through the Scan button's own path: item cards (one Checked), the totals breakdown, the Taxed
 * toggles, the store and date in yellow; then Clear's question, where Cancel keeps it.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramCheckScreenTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.4" }?.let { File(it).also { d -> d.mkdirs() } }

    private fun run(w: Int, h: Int) = runDesktopComposeUiTest(w, h) {
        val m = SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { models += it }
        val photo = listOf(File("../shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp"), File("shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp"))
            .first { it.isFile }.copyTo(File(tmp.root, "food-lion-receipt.webp"))
        setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()), pickReceipt = { photo })) }
        waitForIdle()
        fun shot(name: String) = shots?.let { dir ->
            // a dialog is a window of its own over the screen: drawn over it, as it shows
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val img = roots[0].captureToImage().toAwtImage()
            for (k in 1 until roots.fetchSemanticsNodes().size) img.createGraphics().apply { drawImage(roots[k].captureToImage().toAwtImage(), 0, 0, null); dispose() }
            javax.imageio.ImageIO.write(img, "png", File(dir, "$name-${w}x$h.png"))
        }
        // what Receipts' Scan button does
        m.go(Screen.RECEIPTS)
        m.openPhone(Nav.Scan(null))
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Choose an image or PDF").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText("Choose an image or PDF").performClick()
        waitUntil(timeoutMillis = 120_000) { onAllNodesWithTag("receipt.save", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(21, (0 until 30).count { onAllNodesWithTag("item.card.$it", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        onNodeWithTag("item.checked.1", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        onNodeWithTag("receipt.store", useUnmergedTree = true).performScrollTo()
        waitForIdle()
        assertTrue(onAllNodesWithText("store wasn't on the photo", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("program-check-top")
        onNodeWithTag("item.card.3", useUnmergedTree = true).performScrollTo()
        waitForIdle()
        shot("program-check-cards")
        onNodeWithTag("breakdown.total", useUnmergedTree = true).performScrollTo()
        waitForIdle()
        shot("program-check-totals")
        onNodeWithTag("receipt.clear", useUnmergedTree = true).performScrollTo().performClick()
        waitForIdle()
        assertTrue(onAllNodesWithText("Clear this receipt?", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        shot("program-clear-confirm")
        onNodeWithTag("clear.cancel", useUnmergedTree = true).performClick()
        waitForIdle()
        assertTrue(onAllNodesWithTag("item.card.0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "Cancel keeps it")
    }

    @Test fun atTheOpeningSize() = run(1400, 900)

    @Test fun atTheMinimumSize() = run(1100, 700)
}
