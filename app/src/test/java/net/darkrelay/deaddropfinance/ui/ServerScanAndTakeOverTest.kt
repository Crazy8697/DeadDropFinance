package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
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
    private var paired: String? = null
    private var loggedIn: List<Any>? = null
    private var newPassword: String? = null

    private fun show(connected: Boolean = false, stay: Boolean = true) = compose.setContent {
        ServerScreen(
            ui, SyncStatus(if (connected) SyncStatus.State.SYNCED else SyncStatus.State.NOT_CONNECTED),
            if (connected) ServerInfo("http://server.example:5090", "0a1b2c3d4e5f6a7b", "tester", "Test phone", "d1") else ServerInfo(null, null, null, null, null),
            connected = connected,
            onBack = {}, onContact = {}, onMode = { ui = ui.copy(createMode = it) }, onCreate = { _, _, _, _ -> },
            onLogin = { u, p, n, st -> loggedIn = listOf(u, p, n, st) },
            onSyncNow = {}, onRevoke = {}, onLogout = {},
            onScan = { scans++ }, onPair = { name -> paired = name }, onCancelScan = { ui = ui.copy(scanned = null) },
            onChangePassword = { newPassword = it }, stayLoggedIn = stay,
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
        assertEquals("Phone", paired)
    }

    @Test fun pairingOverABudgetOfItsOwnConfirmsAndAsksForNoPassword() {
        show()
        ui = ui.copy(scanned = qr, hasLocalData = true)
        compose.waitForIdle()
        // 1.6.0: no backup password; the backup uses the phone's recovery key
        compose.onNodeWithText("Backup password").assertDoesNotExist()
        compose.onNodeWithText("Pair this phone").performClick()
        assertNull("not before the confirmation", paired)
        compose.onNodeWithText("Back up and pair").performClick()
        assertEquals("Phone", paired)
    }

    @Test fun logInHasStayLoggedInOnByDefault() {
        show()
        ui = ui.copy(contactedUrl = "http://server.example:5090", contactedFingerprint = "0a1b2c3d4e5f6a7b", createMode = false)
        compose.waitForIdle()
        compose.onNodeWithText("Stay logged in").assertIsDisplayed()
        compose.onNodeWithText("Username").performTextInput("tester")
        compose.onNodeWithText("Password").performTextInput("test-pass-for-unit")
        compose.onAllNodesWithText("Log in").onLast().performClick()
        assertEquals(listOf("tester", "test-pass-for-unit", "Phone", true), loggedIn)
        // unticked, the login carries it
        compose.onNodeWithText("Stay logged in").performClick()
        compose.onAllNodesWithText("Log in").onLast().performClick()
        assertEquals(false, loggedIn!![3])
    }

    @Test fun createTheAccountHasNoStayLoggedInAndWarnsThereIsNoReset() {
        show()
        ui = ui.copy(contactedUrl = "http://server.example:5090", contactedFingerprint = "0a1b2c3d4e5f6a7b", createMode = true)
        compose.waitForIdle()
        compose.onNodeWithText("Stay logged in").assertDoesNotExist()
        compose.onNodeWithText("Setup code").assertIsDisplayed()
        compose.onNodeWithText("Password again").assertIsDisplayed()
    }

    @Test fun aLoggedInPhoneChangesThePasswordTypedTwice() {
        show(connected = true, stay = false)
        compose.onNodeWithText("No: closing the app logs out").assertExists()
        compose.onNodeWithText("New password").performTextInput("new-test-pass-1")
        compose.onNodeWithText("New password again").performTextInput("new-test-pass-2")
        compose.onNodeWithText("Change password").performClick()
        assertNull(newPassword)
        compose.onNodeWithText("The two passwords don't match.").assertExists()
        compose.onNodeWithText("New password again").performTextClearance()
        compose.onNodeWithText("New password again").performTextInput("new-test-pass-1")
        compose.onNodeWithText("Change password").performClick()
        assertEquals("new-test-pass-1", newPassword)
    }

    @Test fun theRecoveryKeyIsShownInGroupsWithCopyAndISavedIt() {
        val key = net.darkrelay.deaddropfinance.backup.RecoveryKey.generate()
        var copied: String? = null
        var saved = 0
        compose.setContent { RecoveryKeyDialog(key, onCopy = { copied = it }, onSaved = { saved++ }) }
        val text = net.darkrelay.deaddropfinance.backup.RecoveryKey.format(key)
        val groups = text.split("-")
        compose.onNodeWithText(groups.take(2).joinToString("  "), substring = true).assertExists()
        compose.onNodeWithText("This is the only time it is shown", substring = true).assertExists()
        compose.onNodeWithText("Copy").performClick()
        assertEquals(text, copied)
        compose.onNodeWithText("I saved it").performClick()
        assertEquals(1, saved)
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
