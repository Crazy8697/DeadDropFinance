package net.darkrelay.deaddropfinance.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * The window's own Windows title bar, dark to match the program (0.9.0): Windows' immersive dark mode attribute, set
 * with DwmSetWindowAttribute. The title bar stays Windows' own (no custom one). Off Windows, or on a Windows too old
 * for the attribute, nothing changes.
 */
object TitleBar {
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
        fun DwmGetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    /** DWMWA_USE_IMMERSIVE_DARK_MODE: 20 on Windows 10 20H1 and later, 19 before that. */
    private val ATTRIBUTES = listOf(20, 19)

    private val dwm: Dwm? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) null else runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    }

    /** Makes [window]'s title bar dark. Call once it's on screen (it needs its native handle). True when Windows took it. */
    fun dark(window: java.awt.Window): Boolean {
        val d = dwm ?: return false
        val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return false
        return ATTRIBUTES.any { a -> runCatching { d.DwmSetWindowAttribute(hwnd, a, IntByReference(1), 4) == 0 }.getOrDefault(false) }
    }

    /** Whether [window]'s title bar is dark now (tests). */
    fun isDark(window: java.awt.Window): Boolean {
        val d = dwm ?: return false
        val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return false
        return ATTRIBUTES.any { a ->
            val v = IntByReference(0)
            runCatching { d.DwmGetWindowAttribute(hwnd, a, v, 4) == 0 && v.value != 0 }.getOrDefault(false)
        }
    }
}
