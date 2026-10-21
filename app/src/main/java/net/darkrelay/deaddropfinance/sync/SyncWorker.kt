package net.darkrelay.deaddropfinance.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import net.darkrelay.deaddropfinance.DdfApp

/**
 * Background bank sync. It never shows the app or any of its data: no notification, no activity, nothing logged.
 * It only writes to the encrypted database, the same way a sync from the screen does.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DdfApp ?: return Result.success()
        val service = app.syncService() ?: return Result.success()
        service.syncAll(SyncService.Trigger.BACKGROUND)
        return Result.success()
    }
}

/** Keeps the periodic job in line with the settings: on with the chosen interval, or off. */
object SyncScheduler {
    private const val NAME = "ddf-bank-sync"

    fun apply(context: Context, settings: SyncSettings, hasConnections: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!settings.backgroundEnabled || !hasConnections) {
            wm.cancelUniqueWork(NAME)
            return
        }
        val hours = settings.intervalHours.toLong()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(hours, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
