package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Hide screen in the program's Settings, Privacy (0.14.0): off at first, switched on and off, kept in the settings file. */
class ProgramHideScreenTest : ProgramScreensBase() {

    @Test fun hideScreenIsInSettingsPrivacyOffAtFirstAndKept() {
        show()
        var hidden by mutableStateOf(settings.hideScreen)
        compose.setContent {
            DesktopApp(model, Shell(run = { b -> runBlocking { b() } }, settings = settings, keyProtector = TestProtector,
                hideScreen = hidden, onHideScreen = { hidden = it; settings.hideScreen = it }))
        }
        compose.waitForIdle()
        click("nav.SETTINGS")
        until("Privacy")
        assertFalse(hidden, "off by default, as on the phone")
        click("setting.hideScreen")
        assertTrue(hidden)
        assertTrue(Settings(settings.dir).hideScreen)
        click("setting.hideScreen")
        assertFalse(hidden)
        assertFalse(Settings(settings.dir).hideScreen)
    }
}
