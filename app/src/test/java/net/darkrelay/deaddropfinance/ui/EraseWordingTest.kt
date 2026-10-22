package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import net.darkrelay.deaddropfinance.BuildConfig
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Erase everything's wording (0.15.1): the "no backup yet" line is gone, replaced by a reminder to back up first;
 * "Everything is erased" shows only when nothing was left behind, and what's left is named otherwise. The compose
 * rule needs the debug build's test activity, so release unit tests skip it (see ui/PurgeWizardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EraseWordingTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    @Test fun eraseScreenPointsToBackupInsteadOfSayingThereIsNone() {
        compose.setContent { EraseScreen(onBack = {}, working = false, onErase = {}) }
        compose.onNodeWithText("There is no backup yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("make a backup first", substring = true).assertExists()
    }

    @Test fun erasedScreenSaysEverythingIsErasedOnlyWhenNothingIsLeft() {
        compose.setContent { ErasedScreen(emptyList(), onOpen = {}, onStartOver = {}) }
        compose.onNodeWithText("Everything is erased").assertExists()
    }

    @Test fun erasedScreenNamesWhatCouldNotBeRemoved() {
        compose.setContent { ErasedScreen(listOf("keys folder"), onOpen = {}, onStartOver = {}) }
        compose.onNodeWithText("Everything is erased").assertDoesNotExist()
        compose.onNodeWithText("something is left", substring = true).assertExists()
        compose.onNodeWithText("keys folder", substring = true).assertExists()
    }
}
