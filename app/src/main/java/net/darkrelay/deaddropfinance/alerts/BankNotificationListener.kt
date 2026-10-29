package net.darkrelay.deaddropfinance.alerts

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.DdfApp
import net.darkrelay.deaddropfinance.MainActivity
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.domain.NotifLog
import net.darkrelay.deaddropfinance.domain.NotifParse
import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Reads the notifications of the bank and wallet apps the user picked (FEATURES.md, Live spend alerts), only while
 * reading is on. Every other app's notification is dropped before anything in it is looked at. A picked app's text is
 * read in memory, parsed on the phone, and only the parsed amount and payee are kept (as a pending transaction);
 * the text itself is never written anywhere, logged or sent.
 */
class BankNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val settings = SyncSettings(this)
        if (!settings.notifReading) return
        val pkg = sbn.packageName
        if (pkg == packageName || pkg !in settings.notifPackages) return
        val text = textOf(sbn.notification) ?: return
        val app = applicationContext as? DdfApp ?: return
        // the app's own write: it goes in even while another device holds the server's edit lock (an offline change)
        scope.launch { runCatching { kotlinx.coroutines.withContext(net.darkrelay.deaddropfinance.data.BackgroundWrite) { handle(app, settings, pkg, text) } } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun handle(app: DdfApp, settings: SyncSettings, pkg: String, text: String) {
        val o = app.open() as? DatabaseOpener.Opened.Ok ?: return
        val dao = o.store.dao
        val a = dao.notifApp(pkg)?.takeIf { it.enabled } ?: return
        val templates = NotifParse.decode(a.templates)
        val parsed = NotifParse.parse(text, templates)
        if (parsed == null) {
            // not read yet: if it looks like money, the user is asked once to check how to read it (text kept in memory only)
            if (NotifParse.guess(text) != null) {
                Inbox.put(pkg, text)
                if (templates.isEmpty()) {
                    SpendAlerts(app).post(
                        SpendAlerts.CHANNEL_READING, ID_TEACH + (pkg.hashCode() and 0xFF), "Check how ${a.label} is read",
                        "Open Dead Drop Finance to confirm the amount and payee in ${a.label}'s notification. After that it's automatic.",
                        "A notification needs checking", false, MainActivity.OPEN_READING,
                    )
                }
            }
            return
        }
        val logged = NotifLog(o.store).log(pkg, parsed)
        val id = logged.txId ?: return
        if (!parsed.deposit) {
            if (settings.autoApprove) net.darkrelay.deaddropfinance.domain.Sorting(o.store).autoApprove(listOf(id))
            SpendAlerts(app).afterSync(o.store, settings, listOf(id))
        }
    }

    /**
     * Notifications from picked apps that couldn't be read yet, for the teach screen. In memory only: gone when the
     * app's process ends, when it's taught, or when reading is turned off.
     */
    object Inbox {
        private val texts = ConcurrentHashMap<String, String>()
        fun put(pkg: String, text: String) { texts[pkg] = text }
        fun get(pkg: String): String? = texts[pkg]
        fun clear(pkg: String? = null) { if (pkg == null) texts.clear() else texts.remove(pkg) }
        fun packages(): Set<String> = texts.keys.toSet()
    }

    companion object {
        private const val ID_TEACH = 30_000

        /** Title, text and the long text of a notification, joined. */
        fun textOf(n: Notification): String? {
            val e = n.extras ?: return null
            val parts = listOfNotNull(
                e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT), e.getCharSequence(Notification.EXTRA_BIG_TEXT),
            ).map { it.toString().trim() }.filter { it.isNotEmpty() }.distinct()
            return parts.joinToString(". ").takeIf { it.isNotBlank() }
        }

        fun component(context: Context) = ComponentName(context, BankNotificationListener::class.java)

        /** Turns the listener on or off: when off, Android neither binds it nor lists it, so nothing is read at all. */
        fun setEnabled(context: Context, on: Boolean) {
            context.packageManager.setComponentEnabledSetting(
                component(context),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (!on) Inbox.clear()
        }

        fun accessGranted(context: Context): Boolean =
            context.getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    }
}
