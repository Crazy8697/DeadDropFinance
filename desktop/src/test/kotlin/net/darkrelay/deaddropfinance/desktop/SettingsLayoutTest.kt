package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * 0.14.2: Settings in an even grid, at the size the window opens (1400 x 900) and at its minimum (1100 x 700): the
 * cards in each row share one width, one height and one top; nothing in a card spills out of it or past the window.
 */
class SettingsLayoutTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private var model: AppModel? = null

    @After fun close() { runCatching { compose.setContent {}; compose.waitForIdle() }; model?.engine?.close() }

    private val rows = listOf(
        listOf("settings.card.General", "settings.card.Privacy", "settings.card.Backup"),
        listOf("settings.rulesPanel", "settings.alertsPanel", "settings.importPanel"),
        listOf("settings.card.Budget", "settings.data", "settings.card.About"),
    )
    private val buttons = mapOf(
        "settings.card.Backup" to listOf("settings.backup", "settings.restore"),
        "settings.rulesPanel" to listOf("settings.rules", "setting.autoApprove"),
        "settings.importPanel" to listOf("settings.importCsv", "settings.importSetup"),
        "settings.card.Budget" to listOf("budgetStart.change", "currency.change", "budgetStart", "currency"),
        "settings.data" to listOf("settings.setup", "settings.purge", "settings.erase"),
        "settings.card.About" to listOf("about.check", "settings.about"),
    )

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun close(a: Float, b: Float) = abs(a - b) <= 1f

    private fun checkAt(w: Int, h: Int) {
        val m = model ?: SampleBudget.filled(tmp.newFolder("DeadDropFinance")).model.also { model = it }
        m.go(Screen.SETTINGS)
        compose.setContent {
            Box(Modifier.requiredSize(w.dp, h.dp)) { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder("s$w")))) }
        }
        compose.waitForIdle()
        // Settings scrolls: each row is brought into view before it's measured
        fun show(tag: String) { compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo(); compose.waitForIdle() }
        for (row in rows) {
            show(row[0])
            val r = row.map { bounds(it) }
            assertTrue(r.all { close(it.width, r[0].width) }, "$w x $h: widths in $row: ${r.map { it.width }}")
            assertTrue(r.all { close(it.height, r[0].height) }, "$w x $h: heights in $row: ${r.map { it.height }}")
            assertTrue(r.all { close(it.top, r[0].top) }, "$w x $h: tops in $row: ${r.map { it.top }}")
            assertTrue(r.all { it.right <= w + 0.5f }, "$w x $h: past the window: ${r.map { it.right }}")
        }
        for ((card, inside) in buttons) {
            show(card)
            val c = bounds(card)
            for (t in inside) {
                val b = bounds(t)
                assertTrue(b.left >= c.left - 0.5f && b.right <= c.right + 0.5f && b.top >= c.top - 0.5f && b.bottom <= c.bottom + 0.5f, "$w x $h: $t spills out of $card: $b in $c")
            }
        }
        // three rows of three, then Server across the window: no small card stretched alone
        val grid = rows.flatten().map { show(it); bounds(it) }
        show("serverPanel")
        assertTrue(grid.all { it.width < bounds("serverPanel").width / 2 }, "$w x $h: every grid card is a third of the row")
    }

    @Test fun theRowsAreEvenAtTheOpeningSize() = checkAt(1400, 900)

    @Test fun theRowsAreEvenAtTheMinimumSize() = checkAt(1100, 700)
}
