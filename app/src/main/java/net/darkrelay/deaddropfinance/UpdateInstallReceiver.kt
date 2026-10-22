package net.darkrelay.deaddropfinance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Where the PackageInstaller reports back after Download and install commits its session. For a normal update
 * Android answers STATUS_PENDING_USER_ACTION with the confirmation screen's intent, and nothing opens it unless the
 * app does: this receiver starts it. (0.15.1 and 0.15.2 sent the status to the activity, which ignored it, so no
 * install screen ever appeared.) Any other status needs nothing here.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val confirm = confirmIntent(intent) ?: return
        runCatching { context.startActivity(confirm) }
    }

    companion object {
        /** The install confirmation to open for a status broadcast, or null when there is nothing to open. */
        fun confirmIntent(status: Intent): Intent? {
            if (status.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE) != PackageInstaller.STATUS_PENDING_USER_ACTION) return null
            val confirm = status.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return null
            return Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
