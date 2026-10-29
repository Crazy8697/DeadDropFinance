package net.darkrelay.deaddropfinance.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import net.darkrelay.deaddropfinance.MainActivity
import net.darkrelay.deaddropfinance.R

/**
 * Posts the app's notifications: bill reminders and autopay warnings (BillReminders). The lock screen gets the
 * public version (no payee or amount) unless the user allows amounts there. The name is from when it also posted
 * the spend alerts of the bank notification reader (removed in 1.5.0; ReaderRemoval deletes their channels).
 */
class SpendAlerts(private val context: Context) {

    private val nm = context.getSystemService(NotificationManager::class.java)

    fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && nm.areNotificationsEnabled()

    fun ensureChannels() {
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
        val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_stat_alert).setContentTitle(PUBLIC_TITLE).setContentText(publicText).build()
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

    companion object {
        const val CHANNEL_BILLS = "bills"
        const val CHANNEL_AUTOPAY = "autopay"
        /** The lock screen's title for every notification: the app's name only. */
        const val PUBLIC_TITLE = "Dead Drop Finance"
    }
}
