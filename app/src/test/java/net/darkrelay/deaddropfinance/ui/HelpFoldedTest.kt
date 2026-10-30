package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import net.darkrelay.deaddropfinance.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Help is folded into About (1.1.0): no Help screen and no Help entry, and About keeps Licenses, the privacy policy,
 * the end user agreement, Support DarkRelay and darkrelay.net. Check for updates stays on More. The compose rule needs
 * the debug build's test activity, so release unit tests skip those (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h1400dp-xhdpi")
class HelpFoldedTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val more = MoreState(false, true, 5, false, true, false, null, "1.1.0")

    @Test fun thereIsNoHelpScreenAndNoHelpNavigationTarget() {
        val screens = Class.forName("net.darkrelay.deaddropfinance.ui.AboutScreensKt").declaredMethods.map { it.name }
        assertFalse("HelpScreen is gone", screens.any { it.contains("HelpScreen") })
        assertTrue("no Help in Nav", runCatching { Class.forName("net.darkrelay.deaddropfinance.ui.Nav\$Help") }.isFailure)
    }

    @Test fun moreHasNoHelpEntryButStillHasAboutAndCheckForUpdates() {
        compose.setContent { MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, onAbout = {}) }
        compose.onNodeWithText("Help").assertDoesNotExist()
        compose.onNodeWithText("Version 1.1.0, credits, licenses, support DarkRelay, darkrelay.net", substring = true).assertExists()
        compose.onNodeWithText("Check for updates").assertExists()
    }

    @Test fun aboutStillHasLicensesLegalSupportAndTheSite() {
        compose.setContent { AboutScreen("1.1.0", null, {}, {}, {}, {}) }
        for (t in listOf("Licenses", "Privacy policy", "End user agreement", "darkrelay.net")) compose.onNodeWithText(t).performScrollTo().assertExists()
        compose.onNodeWithText("Support DarkRelay", substring = true).assertExists()
    }
}
