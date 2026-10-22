package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.UpdateCheck
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Check for updates on More (1.0.2): a found update shows the update bar right there, its buttons work from More, and
 * "You're on the latest version" or a failure shows on More too. The compose rule needs the debug build's test
 * activity, so release unit tests skip it (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h1400dp-xhdpi")
class MoreUpdateTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val release = UpdateCheck.Release("9.9.9", "https://example.com/notes")
    private val more = MoreState(false, true, 5, false, true, false, null, "1.0.1")

    @Test fun anUpdateFoundFromMoreShowsTheBarOnMoreWithAllFourButtonsWorking() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MoreScreen(
                more, {}, {}, {}, {}, {}, {}, {},
                updateBar = {
                    UpdateBar(
                        release, UpdateInstallState(), onNotes = { calls += "notes" }, onDownload = { calls += "download" },
                        onAllowInstalls = {}, onSkip = { calls += "skip" }, onLater = { calls += "later" },
                    )
                },
            )
        }
        compose.onNodeWithText("See Home", substring = true).assertDoesNotExist()
        for (label in listOf("Release notes", "Download and install", "Skip this version", "Later")) compose.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(listOf("notes", "download", "skip", "later"), calls)
    }

    @Test fun downloadAndInstallFromMoreShowsProgressAndTheMobileDataQuestion() {
        var downloaded = false
        compose.setContent {
            MoreScreen(
                more, {}, {}, {}, {}, {}, {}, {},
                updateBar = {
                    UpdateBar(release, UpdateInstallState(working = true, percent = 37), {}, {}, {}, {}, {})
                    MobileDataDialog(41_165_299L, onDownload = { downloaded = true }, onCancel = {})
                },
            )
        }
        compose.onNodeWithText("Downloading... 37%").assertExists()
        compose.onNodeWithText("You're on mobile data. This download is about 41 MB.").assertExists()
        compose.onNodeWithText("Download").performClick()
        assertEquals(true, downloaded)
    }

    @Test fun theLatestVersionAndFailureMessagesShowOnMore() {
        compose.setContent { MoreScreen(more.copy(updateMessage = "You're on the latest version"), {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("You're on the latest version").assertExists()
    }

    @Test fun aFailureMessageShowsOnMore() {
        compose.setContent { MoreScreen(more.copy(updateMessage = "Couldn't check for updates. Try again later."), {}, {}, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("Couldn't check for updates. Try again later.").assertExists()
    }
}
