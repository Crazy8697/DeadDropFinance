package net.darkrelay.deaddropfinance.desktop

import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hide screen on the program (0.14.0): off by default, as on the phone; on, Windows keeps the window out of screen
 * captures (its display affinity is WDA_MONITOR, and a capture of it is black); off again, it's captured normally. The
 * test window opens on the rightmost monitor without taking focus.
 */
class HideScreenTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun itIsOffByDefaultAndPurgeTurnsItOffAgain() {
        val s = Settings(tmp.newFolder("data"))
        assertFalse(s.hideScreen)
        s.hideScreen = true
        assertTrue(Settings(s.dir).hideScreen, "kept in the settings file")
        s.clearScreenSettings()
        assertFalse(Settings(s.dir).hideScreen, "Purge data resets it, as the phone's settings go back to their defaults")
    }

    @Test fun onTheWindowIsKeptOutOfCapturesAndOffItIsCapturedAgain() {
        assumeTrue("Windows only", System.getProperty("os.name", "").startsWith("Windows"))
        assumeTrue("a screen", !GraphicsEnvironment.isHeadless())
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.maxBy { it.x }
        val colour = Color(0, 200, 120)
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait {
            frame = JFrame("Dead Drop Finance hide screen test").apply {
                isUndecorated = true
                focusableWindowState = false
                isAutoRequestFocus = false
                contentPane.background = colour
                bounds = Rectangle(screen.x + screen.width - 260, screen.y + screen.height - 260, 200, 200)
                isVisible = true
            }
        }
        try {
            Thread.sleep(400)
            assertEquals(HideScreen.WDA_NONE, HideScreen.affinity(frame))
            val robot = Robot()
            fun centre(): Color = robot.getPixelColor(frame.x + 100, frame.y + 100)
            // the window is captured as it is (unless something else covers it: then only the affinity is checked)
            val unobstructed = centre() == colour
            assertTrue(HideScreen.apply(frame, true))
            assertEquals(HideScreen.WDA_MONITOR, HideScreen.affinity(frame))
            Thread.sleep(400)
            if (unobstructed) assertEquals(Color.BLACK, centre(), "a capture shows it black")
            assertTrue(HideScreen.apply(frame, false))
            assertEquals(HideScreen.WDA_NONE, HideScreen.affinity(frame))
            Thread.sleep(400)
            if (unobstructed) assertEquals(colour, centre(), "captured again")
            println("hide screen capture check: ${if (unobstructed) "captured black while on" else "window covered, affinity only"}")
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }
}
