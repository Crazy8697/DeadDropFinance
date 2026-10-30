package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0: About in the program (the phone's: its links, Licenses, the privacy policy and the end user agreement),
 * and the program's update bar with Release notes and Skip this version, as on the phone (the phone's UpdateBarTest
 * and MoreUpdateTest cases where they apply).
 */
class ProgramAboutTest : ProgramScreensBase() {

    @Test fun aboutHasTheSiteAndSupportLinksAndThePrivacyPolicy() {
        show()
        click("nav.MORE")
        assertTrue(!shows("Show amounts on the lock screen"), "the phone's lock screen setting isn't shown")
        clickText("credits, licenses")
        until("darkrelay.net")
        clickText("darkrelay.net", exact = true)
        assertTrue(opened.any { it.contains("darkrelay.net") }, opened.toString())
        clickText("Support DarkRelay")
        assertEquals(2, opened.size)
        clickText("Privacy policy", exact = true)
        // the program carries PRIVACY.md, as the phone does
        until("Effective with version")
    }
}

class ProgramUpdatesTest {

    @get:Rule val compose = createComposeRule()

    private val rel = Updater.Release("0.13.1", "desktop-v0.13.1", "Fixes.", "https://example.invalid/release")

    @Test fun skipThisVersionHidesItAndTheStartupCheckDoesNotOfferItAgain() {
        var skipped: String? = null
        val m = UpdateModel("0.13.0", check = { Updater.Result.Newer(rel) }, canInstall = true, skipped = { skipped }, onSkip = { skipped = it })
        m.runCheck(manual = false)
        assertTrue(m.state is UpdateState.Available)
        m.skip()
        assertEquals("0.13.1", skipped)
        assertEquals(UpdateState.Hidden, m.state)
        // the startup check stays quiet for the skipped version
        m.runCheck(manual = false)
        assertEquals(UpdateState.Hidden, m.state)
        // Check for updates still offers it
        m.runCheck(manual = true)
        assertTrue(m.state is UpdateState.Available)
        // a newer one than the skipped version is offered at startup again
        val newer = UpdateModel("0.13.0", check = { Updater.Result.Newer(rel.copy(version = "0.13.2")) }, skipped = { skipped })
        newer.runCheck(manual = false)
        assertTrue(newer.state is UpdateState.Available)
    }

    @Test fun theUpdateBarHasReleaseNotesAndSkipThisVersion() {
        val pages = mutableListOf<String>()
        var skips = 0
        val m = AppModel(null)
        compose.setContent {
            DesktopApp(m, Shell(update = UpdateState.Available(rel), canInstall = true, onOpenPage = { pages += it }, onSkipUpdate = { skips++ }, run = { b -> runBlocking { b() } }))
        }
        compose.onNodeWithTag("releaseNotes", useUnmergedTree = true).performClick()
        assertEquals(listOf(rel.page), pages)
        compose.onNodeWithTag("skipVersion", useUnmergedTree = true).performClick()
        assertEquals(1, skips)
    }
}
