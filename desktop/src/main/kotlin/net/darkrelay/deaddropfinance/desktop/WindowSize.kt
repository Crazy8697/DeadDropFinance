package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The program's window (0.14.2): it opens at [OPEN] and can be made larger or smaller, but never smaller than
 * [MINIMUM], the size every screen is laid out and checked for (the sidebar, the top bar and two cards a row fit).
 */
object WindowSize {
    val OPEN = DpSize(1400.dp, 900.dp)
    val MINIMUM = DpSize(1100.dp, 700.dp)

    /** Windows won't let [window] be made smaller than [MINIMUM] (AWT's minimum size, in the window's own units). */
    fun applyMinimum(window: java.awt.Window) {
        window.minimumSize = java.awt.Dimension(MINIMUM.width.value.toInt(), MINIMUM.height.value.toInt())
    }
}
