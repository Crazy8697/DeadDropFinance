package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.domain.Money
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.7.0 on the phone's screens: the currency setting and its picker (common currencies first, searchable, display
 * only), and Restore using the recovery key saved on this phone without asking for it. Invented data. Debug build only
 * (the compose rule's test activity), like the other screen tests; the currency's formats and sync are tested in the
 * shared module's CurrencyTest and CurrencySyncTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CurrencyAndRestoreTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    @After fun backToUsd() = Money.use(null)

    @Test fun theSettingShowsTheCurrencyAndThePickerSearches() {
        var picked: String? = null
        compose.setContent { CurrencySetting("USD") { picked = it } }
        compose.onNodeWithText("USD (US Dollar)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("Euro").assertIsDisplayed()
        compose.onNodeWithText("Search by code or name").performTextInput("pound")
        compose.waitForIdle()
        compose.onNodeWithText("Euro").assertDoesNotExist()
        compose.onNodeWithText("British Pound").performClick()
        assertEquals("GBP", picked)
    }

    /** 1.7.1: a currency whose convention isn't two decimals isn't offered, but a budget that holds one still shows it. */
    @Test fun aHiddenCurrencyStillShowsButIsNotOffered() {
        var picked: String? = null
        compose.setContent { CurrencySetting("JPY") { picked = it } }
        compose.onNodeWithText("JPY (Japanese Yen)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change").performClick()
        compose.onNodeWithText("Search by code or name").performTextInput("yen")
        compose.waitForIdle()
        compose.onNodeWithText("Japanese Yen").assertDoesNotExist()
        compose.onNodeWithText("No currency matches that.").assertExists()
        assertNull(picked)
    }

    @Test fun aSavedKeyRestoresWithoutTypingAndOtherwiseItIsAsked() {
        var typed: String? = null
        val state = BackupState(restoreName = "deaddrop-2030-05-01.ddfbak", restoreWithKey = true, restoreSavedKey = true)
        compose.setContent { BackupScreen(state, {}, {}, {}, onRestore = { typed = it }, onCancelRestore = {}) }
        compose.onNodeWithText("It opens with the recovery key saved on this phone", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Recovery key").assertDoesNotExist()
        compose.onNodeWithText("Replace everything and restore").performScrollTo().performClick()
        assertEquals("", typed)
    }

    @Test fun withNoSavedKeyTheKeyIsTyped() {
        var typed: String? = null
        val state = BackupState(restoreName = "deaddrop-2030-05-01.ddfbak", restoreWithKey = true, restoreSavedKey = false)
        compose.setContent { BackupScreen(state, {}, {}, {}, onRestore = { typed = it }, onCancelRestore = {}) }
        compose.onNodeWithText("Recovery key").assertIsDisplayed()
        compose.onNodeWithText("Replace everything and restore").performScrollTo().performClick()
        assertNull("nothing typed, nothing restored", typed)
        compose.onNode(hasSetTextAction()).performTextInput("ABCD")
        compose.waitForIdle()
        compose.onNodeWithText("Replace everything and restore").performScrollTo().performClick()
        assertEquals("ABCD", typed)
    }

    // ---- 1.7.2: restore while logged in replaces the server's budget, after its own warning

    private val picked = BackupState(restoreName = "deaddrop-2030-05-01.ddfbak", restoreWithKey = true, restoreSavedKey = true)

    @Test fun loggedInTheWarningHasItsOwnScreenWithCancelAsTheDefault() {
        var cancelled = 0; var replaced = 0
        val state = picked.copy(confirmServerReplace = true)
        compose.setContent { BackupScreen(state, {}, {}, {}, {}, {}, onCancelServerReplace = { cancelled++ }, onConfirmServerReplace = { replaced++ }) }
        compose.onNodeWithText("Replace the server's budget?").assertIsDisplayed()
        compose.onNodeWithText("This replaces the budget on your server. Every device on this account will get this backup instead of what it has now. " +
            "The server keeps a backup of the current budget first.").assertIsDisplayed()
        compose.onNodeWithText("Replace everything and restore").assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, cancelled)
        assertEquals("Cancel changes nothing", 0, replaced)
        compose.onNodeWithText("Replace server budget").performClick()
        assertEquals(1, replaced)
    }

    @Test fun anotherDeviceEditingMustBeTakenOverBeforeTheReplaceCanGo() {
        var replaced = 0; var tookOver = 0
        val state = picked.copy(confirmServerReplace = true, lockedBy = "Test desktop")
        compose.setContent { BackupScreen(state, {}, {}, {}, {}, {}, onConfirmServerReplace = { replaced++ }, onTakeOver = { tookOver++ }) }
        compose.onNodeWithText("Test desktop is editing the budget right now. Take over editing to restore.").assertIsDisplayed()
        compose.onNodeWithText("Replace server budget").performClick()
        assertEquals("disabled while another device edits", 0, replaced)
        compose.onNodeWithText("Take over editing").performClick()
        assertEquals(1, tookOver)
    }

    @Test fun loggedOutThereIsNoServerWarning() {
        var typed: String? = null
        compose.setContent { BackupScreen(picked, {}, {}, {}, onRestore = { typed = it }, onCancelRestore = {}) }
        compose.onNodeWithText("Replace the server's budget?").assertDoesNotExist()
        compose.onNodeWithText("Replace everything and restore").performScrollTo().performClick()
        assertEquals("", typed)
    }

    @Test fun aRefusalIsShownOnTheWarningScreenAndNothingElseChanges() {
        val state = picked.copy(confirmServerReplace = true, error = "The server couldn't back up its current budget first, so it did not replace it. Nothing was changed.")
        compose.setContent { BackupScreen(state, {}, {}, {}, {}, {}) }
        compose.onNodeWithText("The server couldn't back up its current budget first", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Replace server budget").assertIsDisplayed()
    }
}
