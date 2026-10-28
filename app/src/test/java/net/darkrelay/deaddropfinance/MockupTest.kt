package net.darkrelay.deaddropfinance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The private clickable mockup (docs/mockups/app-prototype.html, 1.2.3) stays whole and self-contained: every bottom
 * tab, the Activity tabs and Show in Activity are in it, it loads nothing from the network, and it has no long dash.
 * Opening it with no console errors is checked in a browser each round that changes a screen.
 */
class MockupTest {

    // the mockup is private (export-ignored): a copy of the public source has no docs/mockups, and skips these
    private val html: String by lazy {
        val f = listOf(File("../docs/mockups/app-prototype.html"), File("docs/mockups/app-prototype.html")).firstOrNull { it.isFile }
        assumeTrue("the private mockup isn't in this copy", f != null)
        f!!.readText()
    }

    @Test fun everyTabAndTheActivityTabsAreThere() {
        for (t in listOf("Home", "Envelopes", "Activity", "Bills", "Categories", "More")) assertTrue(t, html.contains(t))
        for (t in listOf("actTab-", "Current balance", "Manual account", "Starting balance", "Show in Activity", "Budget start date", "Bonus income", "About", "It's actually negative (overdrawn)", "function row3", "Adjust the starting balance?", "Payday auto-fill", "function mvRow")) {
            assertTrue(t, html.contains(t))
        }
        assertFalse("no Help screen", html.contains(">Help<"))
    }

    @Test fun itIsSelfContained() {
        assertFalse("no network address", Regex("https?://").containsMatchIn(html))
        assertFalse("no outside script", html.contains("<script src"))
        assertFalse("no fetch", html.contains("fetch("))
        assertFalse("no long dash", html.contains(0x2014.toChar()))
    }
}
