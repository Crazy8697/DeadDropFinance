package net.darkrelay.deaddropfinance.desktop

import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The dark native title bar (0.9.0), on a throwaway window far off screen that never takes focus. */
class TitleBarTest {

    @Test fun aWindowsTitleBarTurnsDark() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"))
        assumeFalse(GraphicsEnvironment.isHeadless())
        var frame: JFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = JFrame("Dead Drop Finance title bar test").apply {
                    focusableWindowState = false
                    isAutoRequestFocus = false
                    setBounds(-32000, -32000, 300, 200)
                    isVisible = true
                }
            }
            val f = frame!!
            assertFalse(TitleBar.isDark(f), "a new window starts light")
            assertTrue(TitleBar.dark(f), "Windows took the dark mode attribute")
            assertTrue(TitleBar.isDark(f))
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    @Test fun aWindowWithNoNativeHandleIsLeftAlone() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val f = JFrame("never shown")
        try {
            assertFalse(TitleBar.dark(f))
        } finally {
            f.dispose()
        }
    }
}
