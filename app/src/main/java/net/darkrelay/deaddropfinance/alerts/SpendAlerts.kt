package net.darkrelay.deaddropfinance.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.DdfApp
import net.darkrelay.deaddropfinance.MainActivity
import net.darkrelay.deaddropfinance.R
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.domain.Alerts
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Sorting
import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Posts the spend alerts Alerts.build works out, after a bank notification is read. Two notifications at most: new spending
 * (one, however many charges) and envelope warnings. Until the user opens the app, a later one adds to what's
 * showing instead of replacing it. The lock screen gets the public version (a count only) unless the user allows
 * amounts there. Approve needs the phone unlocked.
 */
class SpendAlerts(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && nm.areNotificationsEnabled()

    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SPENDING, "New spending", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "New spending from your bank app's notifications, with its suggested envelope"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, "Envelope warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An envelope low or overspent, or Safe to Spend going negative"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BILLS, "Bill reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A bill due soon, for the bills you turned reminders on for"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_AUTOPAY, "Autopay warnings", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An autopay due before payday with its envelope short"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_READING, "Notification reading", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Setting up how a bank app's notifications are read"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * A plain notification on [channel] that opens the app at [open] (MainActivity.OPEN_*). The lock screen shows
     * only [publicText] unless amounts are allowed there.
     */
    fun post(channel: String, id: Int, title: String, text: String, publicText: String, showOnLockScreen: Boolean, open: String = MainActivity.OPEN_ACTIVITY) {
        if (!allowed()) return
        ensureChannels()
        val intent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, open).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_alert).setContentTitle(Alerts.PUBLIC_TITLE).setContentText(publicText).build()
        nm.notify(
            id,
            Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_stat_alert).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(intent).setAutoCancel(true)
                .setVisibility(if (showOnLockScreen) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .build(),
        )
    }

    /** After new transactions [newIds] came in: works out and posts the alerts, if the user allows them. */
    suspend fun afterSync(store: BudgetStore, settings: SyncSettings, newIds: List<String>) {
        if (newIds.isEmpty() || !allowed()) return
        val b = BudgetActions(store).snapshot()
        val live = b.transactions.map { it.id }.toSet()
        val ids = (settings.alertIds + newIds).filter { it in live }
        val notices = Alerts.build(b, store.dao.rulesNow(), ids, settings.alertSettings())
        settings.alertIds = ids.toSet()
        ensureChannels()
        for (n in notices) nm.notify(idOf(n.kind), notification(n))
    }

    /** The app was opened: what's showing has been seen. */
    fun clear(settings: SyncSettings) {
        nm.cancel(ID_SPENDING)
        nm.cancel(ID_WARNINGS)
        settings.alertIds = emptySet()
    }

    internal fun notification(n: Alerts.Notice): Notification {
        val channel = if (n.kind == Alerts.Kind.SPENDING) CHANNEL_SPENDING else CHANNEL_WARNINGS
        val open = PendingIntent.getActivity(
            context, REQUEST_OPEN,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(n.publicTitle)
            .setContentText(n.publicText)
            .build()
        val b = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_alert)
            .setContentTitle(n.title)
            .setContentText(n.lines.firstOrNull() ?: "")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(if (n.showOnLockScreen) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
        b.setStyle(
            if (n.lines.size > 1) Notification.InboxStyle().also { st -> n.lines.forEach { st.addLine(it) } }
            else Notification.BigTextStyle().bigText(n.text),
        )
        if (n.approveIds.isNotEmpty()) {
            val approve = PendingIntent.getBroadcast(
                context, REQUEST_APPROVE,
                Intent(context, AlertActionReceiver::class.java).setAction(ACTION_APPROVE).putExtra(EXTRA_IDS, n.approveIds.toTypedArray()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val label = if (n.approveIds.size == 1) "Approve" else "Approve ${n.approveIds.size}"
            b.addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_alert), label, approve)
                    .setAuthenticationRequired(true).build(),
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_alert), "Change", open).build())
        }
        return b.build()
    }

    companion object {
        const val CHANNEL_SPENDING = "spending"
        const val CHANNEL_WARNINGS = "warnings"
        const val CHANNEL_BILLS = "bills"
        const val CHANNEL_AUTOPAY = "autopay"
        const val CHANNEL_READING = "reading"
        const val ID_SPENDING = 8001
        const val ID_WARNINGS = 8002
        const val ACTION_APPROVE = "net.darkrelay.deaddropfinance.APPROVE"
        const val EXTRA_IDS = "ids"
        private const val REQUEST_OPEN = 1
        private const val REQUEST_APPROVE = 2

        fun idOf(k: Alerts.Kind) = if (k == Alerts.Kind.SPENDING) ID_SPENDING else ID_WARNINGS
    }
}

/** The Approve button on a spend alert: approves those suggestions, in the background, and clears the alert. */
class AlertActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SpendAlerts.ACTION_APPROVE) return
        val ids = intent.getStringArrayExtra(SpendAlerts.EXTRA_IDS)?.toList().orEmpty()
        val app = context.applicationContext as? DdfApp ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val o = app.open() as? DatabaseOpener.Opened.Ok
                if (o != null && ids.isNotEmpty()) Sorting(o.store).approveAll(ids)
                val settings = SyncSettings(context)
                settings.alertIds = settings.alertIds - ids.toSet()
                context.getSystemService(NotificationManager::class.java).cancel(SpendAlerts.ID_SPENDING)
            } catch (t: Throwable) {
                // nothing to show from a notification button; the transactions stay in To sort
            } finally {
                done.finish()
            }
        }
    }
}
