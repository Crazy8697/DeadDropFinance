package net.darkrelay.deaddropfinance.desktop

import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * Windows notifications (0.13.0) for what the phone sends as notifications: bill reminders and autopay warnings, under
 * the same settings (More, Alerts). A notification in the corner of the screen: it never takes focus from what the
 * user is doing.
 */
interface Notices {
    fun allowed(): Boolean
    fun post(title: String, text: String)

    object None : Notices {
        override fun allowed() = false
        override fun post(title: String, text: String) {}
    }
}

/** The program's notification area icon, shown only while a notification is up. */
class TrayNotices(private val icon: java.awt.Image) : Notices {
    private var tray: TrayIcon? = null

    override fun allowed() = SystemTray.isSupported()

    @Synchronized
    override fun post(title: String, text: String) {
        if (!SystemTray.isSupported()) return
        val t = tray ?: TrayIcon(icon, PROGRAM_NAME).also { it.isImageAutoSize = true; SystemTray.getSystemTray().add(it); tray = it }
        t.displayMessage(title, text, TrayIcon.MessageType.NONE)
    }

    @Synchronized
    fun close() {
        tray?.let { SystemTray.getSystemTray().remove(it) }
        tray = null
    }
}

/** The program's bill reminders and autopay warnings (0.13.0): the phone's BillAlerts, posted as Windows notifications. */
object ProgramAlerts {
    suspend fun check(store: net.darkrelay.deaddropfinance.data.BudgetStore, settings: Settings, notices: Notices, today: java.time.LocalDate) {
        if (!notices.allowed()) return
        net.darkrelay.deaddropfinance.ui.BillAlerts.check(store, ProgramAppSettings(settings), today) { a -> notices.post(a.title, a.text) }
    }
}
