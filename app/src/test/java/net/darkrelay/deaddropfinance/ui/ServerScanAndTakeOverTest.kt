package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.serversync.PairingQr
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
 * Scan QR code and Take over editing on the phone's screens (1.5.0): the camera asked for only on Scan, a refusal
 * falling back to the typed fields with a note, a scanned code's pairing form, and the banner's confirmation. Invented
 * data. Debug build only (the compose rule's test activity), like the other screen tests; the sync itself is tested in
 * the shared module's ServerSyncTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w400dp-h4000dp-xhdpi")
class ServerScanAndTakeOverTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val qr = PairingQr.Info("http://server.example:5090", "0a1b2c3d4e5f6a7b", "K7QM-4XTP")
    private var ui by mutableStateOf(ServerUi())
    private var scans = 0
    private var paired: Pair<String, String?>? = null

    private fun show() = compose.setContent {
        ServerScreen(
            ui, SyncStatus(SyncStatus.State.NOT_CONNECTED), ServerInfo(null, null, null, null, null), connected = false,
            onBack = {}, onContact = {}, onMode = {}, onCreate = { _, _, _, _ -> }, onLogin = { _, _, _ -> },
            onSyncNow = {}, onRevoke = {}, onLogout = {},
            onScan = { scans++ }, onPair = { name, pass -> paired = name to pass }, onCancelScan = { ui = ui.copy(scanned = null) },
        )
    }

    @Test fun scanIsOfferedNextToTheTypedAddressAndAsksNothingUntilTapped() {
        show()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
        compose.onNodeWithText("Server address").assertIsDisplayed()
        assertEquals("nothing is asked for until Scan is tapped", 0, scans)
        compose.onNodeWithText("Scan QR code").performClick()
        assertEquals(1, scans)
    }

    @Test fun aRefusedCameraFallsBackToTheTypedFieldsWithANote() {
        show()
        // what the app does when the camera is refused (AppRoot: QrScan.CameraDenied)
        ui = ui.copy(scanNote = CAMERA_DENIED_NOTE)
        compose.waitForIdle()
        compose.onNodeWithText(CAMERA_DENIED_NOTE).assertIsDisplayed()
        compose.onNodeWithText("Server address").assertIsDisplayed()
        compose.onNodeWithText("Connect").assertIsDisplayed()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
    }

    @Test fun aScannedCodeShowsItsServerAndPairs() {
        show()
        ui = ui.copy(scanned = qr)
        compose.waitForIdle()
        compose.onNodeWithText("Pair with the scanned code").assertIsDisplayed()
        compose.onNodeWithText("http://server.example:5090").assertIsDisplayed()
        compose.onNodeWithText("0a1b 2c3d 4e5f 6a7b").assertIsDisplayed()
        compose.onNodeWithText("Pair this phone").performClick()
        assertEquals("Phone" to null, paired)
    }

    @Test fun pairingOverABudgetOfItsOwnAsksForABackupPasswordFirst() {
        show()
        ui = ui.copy(scanned = qr, hasLocalData = true)
        compose.waitForIdle()
        compose.onNodeWithText("Pair this phone").performClick()
        assertNull("not without a backup password", paired)
        compose.onNodeWithText("Backup password").performTextInput("backup-pass-test")
        compose.onNodeWithText("Backup password again").performTextInput("backup-pass-test")
        compose.onNodeWithText("Pair this phone").performClick()
        compose.onNodeWithText("Back up and pair").performClick()
        assertEquals("Phone" to "backup-pass-test", paired)
    }

    @Test fun cancelGoesBackToConnect() {
        show()
        ui = ui.copy(scanned = qr)
        compose.waitForIdle()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
    }

    @Test fun theReadOnlyBannerTakesOverAfterAConfirmationNamingTheOtherDevice() {
        var took = 0
        var status by mutableStateOf(SyncStatus(SyncStatus.State.SYNCED, readOnlyBy = "Test desktop"))
        compose.setContent { ReadOnlyBanner(status) { took++ } }
        compose.onNodeWithText("Read only: Test desktop is editing the budget. You can make changes here once it closes.").assertIsDisplayed()
        compose.onNodeWithText("Take over editing").performClick()
        compose.onNodeWithText("Take over editing?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, took)
        compose.onNodeWithText("Take over editing").performClick()
        compose.onNodeWithText("Take over").performClick()
        assertEquals(1, took)
        // the device it was taken from: who took over, and what waits here
        status = SyncStatus(SyncStatus.State.SYNCED, queued = 2, readOnlyBy = "Test desktop", takenOverBy = "Test desktop")
        compose.waitForIdle()
        compose.onNodeWithText("Test desktop took over editing. This phone is read only; its 2 waiting changes are sent once it can edit again.").assertIsDisplayed()
        assertTrue(readOnlyText(status).contains("took over"))
    }
}
