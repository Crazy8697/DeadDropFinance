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
}
