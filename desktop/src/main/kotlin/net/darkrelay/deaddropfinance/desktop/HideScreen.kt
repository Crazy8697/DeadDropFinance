package net.darkrelay.deaddropfinance.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Hide screen on the program (0.14.0), the phone's setting (off by default, as on the phone): Windows' documented way
 * to keep a window out of screen captures, SetWindowDisplayAffinity with WDA_MONITOR. While it's on, the window shows
 * normally on the monitor, and screenshots, screen recording and screen sharing get a black window instead of its
 * contents, as the phone's screenshots go blank. Off Windows, or if Windows refuses, nothing changes.
 */
object HideScreen {
    private interface User32 : Library {
        fun SetWindowDisplayAffinity(hwnd: Pointer, affinity: Int): Boolean
        fun GetWindowDisplayAffinity(hwnd: Pointer, affinity: IntByReference): Boolean
    }

    const val WDA_NONE = 0
    const val WDA_MONITOR = 1

    private val user32: User32? by lazy {
        if (!System.getProperty("os.name", "").startsWith("Windows")) null else runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    }

    /** Hides [window] from captures, or shows it again. Call once it's on screen (it needs its native handle). True when Windows took it. */
    fun apply(window: java.awt.Window, on: Boolean): Boolean {
        val u = user32 ?: return false
        val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return false
        return runCatching { u.SetWindowDisplayAffinity(hwnd, if (on) WDA_MONITOR else WDA_NONE) }.getOrDefault(false)
    }

    /** The window's display affinity now (tests): WDA_MONITOR while hidden from captures; null if it can't be told. */
    fun affinity(window: java.awt.Window): Int? {
        val u = user32 ?: return null
        val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return null
        val v = IntByReference(-1)
        return if (runCatching { u.GetWindowDisplayAffinity(hwnd, v) }.getOrDefault(false)) v.value else null
    }
}
