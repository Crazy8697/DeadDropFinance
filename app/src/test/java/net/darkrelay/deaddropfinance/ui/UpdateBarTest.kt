package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.UpdateCheck
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update bar's Download and install flow (0.15.1): while not allowed to install, it shows the permission
 * message and routes "Allow installs" to the Install unknown apps screen; progress and the final message show;
 * Download and install is disabled while working. The compose rule needs the debug build's test activity, so
 * release unit tests skip it (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateBarTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)
    private val release = UpdateCheck.Release("9.9.9", "https://example.com/notes")

    @Test fun needsInstallPermissionRoutesToAllowInstalls() {
        var opened = false
        compose.setContent {
            UpdateBar(
                release, UpdateInstallState(needsPermission = true, message = "Allow installs, then come back here to continue."),
                onNotes = {}, onDownload = {}, onAllowInstalls = { opened = true }, onSkip = {}, onLater = {},
            )
        }
        compose.onNodeWithText("needs permission to install updates", substring = true).assertExists()
        compose.onNodeWithText("Allow installs").performClick()
        assert(opened) { "Allow installs didn't call onAllowInstalls" }
    }

    @Test fun downloadingShowsProgressAndDisablesTheButton() {
        var tapped = false
        compose.setContent {
            UpdateBar(release, UpdateInstallState(working = true, percent = 37), onNotes = {}, onDownload = { tapped = true }, onAllowInstalls = {}, onSkip = {}, onLater = {})
        }
        compose.onNodeWithText("Downloading... 37%").assertExists()
        compose.onNodeWithText("Download and install").performClick()
        assert(!tapped) { "Download and install fired while already working" }
    }

    @Test fun aFailedInstallShowsItsMessage() {
        compose.setContent {
            UpdateBar(release, UpdateInstallState(message = "The install couldn't be started. Try again.", failed = true), onNotes = {}, onDownload = {}, onAllowInstalls = {}, onSkip = {}, onLater = {})
        }
        compose.onNodeWithText("The install couldn't be started. Try again.").assertExists()
    }

    @Test fun mobileDataDialogAsksWithTheSizeAndRoutesBothButtons() {
        var downloaded = false
        var cancelled = false
        compose.setContent { MobileDataDialog(41_165_299L, onDownload = { downloaded = true }, onCancel = { cancelled = true }) }
        compose.onNodeWithText("You're on mobile data. This download is about 41 MB.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assert(cancelled && !downloaded)
        compose.onNodeWithText("Download").performClick()
        assert(downloaded)
    }
}
