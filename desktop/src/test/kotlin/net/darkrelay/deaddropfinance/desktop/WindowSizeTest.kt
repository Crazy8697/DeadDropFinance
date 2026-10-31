package net.darkrelay.deaddropfinance.desktop

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.14.2: the program's window opens at 1400 x 900 and can't be made smaller than 1100 x 700. A real window, on the
 * rightmost monitor, never focused: made smaller, Windows keeps it at the minimum; made larger, it grows.
 */
class WindowSizeTest {

    @Test fun theSizes() {
        assertEquals(1400f, WindowSize.OPEN.width.value); assertEquals(900f, WindowSize.OPEN.height.value)
        assertEquals(1100f, WindowSize.MINIMUM.width.value); assertEquals(700f, WindowSize.MINIMUM.height.value)
    }

    @Test fun theWindowCantBeMadeSmallerThanTheMinimum() {
        assumeTrue("a screen", !GraphicsEnvironment.isHeadless())
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }.maxBy { it.x }
        lateinit var f: JFrame
        SwingUtilities.invokeAndWait {
            f = JFrame("Dead Drop Finance window size test").apply {
                focusableWindowState = false
                isAutoRequestFocus = false
                bounds = Rectangle(screen.x + 10, screen.y + 10, 1400, 900)
                WindowSize.applyMinimum(this)
                isVisible = true
            }
        }
        try {
            SwingUtilities.invokeAndWait { f.setSize(800, 500) }
            Thread.sleep(300)
            var size = java.awt.Dimension()
            SwingUtilities.invokeAndWait { size = f.size }
            assertTrue(size.width >= 1100 && size.height >= 700, "made smaller: $size")
            SwingUtilities.invokeAndWait { f.setSize(1250, 820) }
            Thread.sleep(300)
            SwingUtilities.invokeAndWait { size = f.size }
            assertEquals(java.awt.Dimension(1250, 820), size, "made larger")
        } finally {
            SwingUtilities.invokeAndWait { f.dispose() }
        }
    }
}
