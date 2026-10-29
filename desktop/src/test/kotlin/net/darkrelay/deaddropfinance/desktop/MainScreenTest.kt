package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class MainScreenTest {

    @get:Rule val compose = createComposeRule()

    private val rel = Updater.Release("0.6.0", "desktop-v0.6.0", "", "https://github.com/Crazy8697/DeadDropFinance/releases/tag/desktop-v0.6.0")

    @Test fun showsTheNameAndVersionAndNoBar() {
        compose.setContent { MainScreen(UpdateState.Hidden, true, {}, {}, {}) }
        compose.onNodeWithTag("name").assertTextEquals("Dead Drop Finance")
        compose.onNodeWithTag("version").assertTextEquals("Version ${BuildInfo.VERSION}")
        compose.onNodeWithTag("updateBar").assertDoesNotExist()
    }

    @Test fun theBarOffersTheUpdateAndItsButtonsWork() {
        var installs = 0
        var opened: String? = null
        var state by mutableStateOf<UpdateState>(UpdateState.Available(rel))
        compose.setContent { MainScreen(state, true, { installs++ }, { opened = it }, { state = UpdateState.Hidden }) }
        compose.onNodeWithTag("updateText").assertTextEquals("Dead Drop Finance 0.6.0 is available.")
        compose.onNodeWithTag("install").performClick()
        assertEquals(1, installs)
        compose.onNodeWithText("What's new").performClick()
        assertEquals(rel.page, opened)
        compose.onNodeWithTag("dismiss").performClick()
        compose.onNodeWithTag("updateBar").assertDoesNotExist()
    }

    @Test fun fromSourceTheBarOnlyPointsAtTheReleasePage() {
        compose.setContent { MainScreen(UpdateState.Available(rel), false, {}, {}, {}) }
        compose.onNodeWithTag("install").assertDoesNotExist()
        compose.onNodeWithText("Open the release page").assertIsDisplayed()
    }

    @Test fun progressAndMessages() {
        var state by mutableStateOf<UpdateState>(UpdateState.Downloading(rel, 50, 200))
        compose.setContent { MainScreen(state, true, {}, {}, {}) }
        compose.onNodeWithTag("updateText").assertTextEquals("Downloading Dead Drop Finance 0.6.0... 25%")
        state = UpdateState.Message("Couldn't check for updates. GitHub didn't answer in time.", true)
        compose.onNodeWithTag("updateText").assertTextEquals("Couldn't check for updates. GitHub didn't answer in time.")
        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}
