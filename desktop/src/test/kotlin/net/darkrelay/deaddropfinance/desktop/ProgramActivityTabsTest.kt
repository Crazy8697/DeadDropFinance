package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.onRoot
import org.junit.Test
import kotlin.test.assertTrue

/** 0.14.1: the app's Activity in the program (To sort): its tab row spreads across the window, as the program does. */
class ProgramActivityTabsTest : ProgramScreensBase() {

    @Test fun theTabRowSpreadsAcrossTheWindow() {
        show()
        click("nav.ACTIVITY")
        click("toSort")
        until("To sort")
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        val tabs = tag("activityTabs").fetchSemanticsNode().boundsInRoot
        val content = tag("phone.Activity").fetchSemanticsNode().boundsInRoot
        // the content area beside the sidebar, less the program's margins
        assertTrue(tabs.width >= content.width - 40f, "tabs ${tabs.width} of ${content.width} (window $window)")
        // the tabs themselves share that width: the last one ends near the right edge, not after a few tab widths
        val each = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Tab), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue(each.size >= 2, "the tabs: $each")
        assertTrue(each.maxOf { it.right } >= content.right - 40f, "last tab ends at ${each.maxOf { it.right }} of ${content.right}")
    }
}
