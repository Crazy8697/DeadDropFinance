package net.darkrelay.deaddropfinance.alerts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import net.darkrelay.deaddropfinance.DdfApp
import net.darkrelay.deaddropfinance.MainActivity
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Bill reminders and autopay warnings. A WorkManager job checks a few times a day (WorkManager keeps it across
 * reboots, and it needs no exact-alarm permission: a reminder some time on its day is what's wanted). Each reminder
 * and warning goes once per bill cycle. Nothing is shown for bills with reminders off.
 */
class BillReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? DdfApp ?: return Result.success()
        val o = app.open() as? DatabaseOpener.Opened.Ok ?: return Result.success()
        runCatching { check(app, o.store, SyncSettings(app), LocalDate.now()) }
        return Result.success()
    }

    companion object {
        private const val NAME = "ddf-bill-reminders"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BillReminderWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Sends what's due now and remembers it (BillAlerts, shared with the program). */
        suspend fun check(context: Context, store: net.darkrelay.deaddropfinance.data.BudgetStore, settings: SyncSettings, today: LocalDate) {
            val alerts = SpendAlerts(context)
            if (!alerts.allowed()) return
            val lock = settings.alertAmountsOnLockScreen
            net.darkrelay.deaddropfinance.ui.BillAlerts.check(store, settings, today) { a ->
                when (a.kind) {
                    net.darkrelay.deaddropfinance.ui.BillAlerts.Kind.BILL ->
                        alerts.post(SpendAlerts.CHANNEL_BILLS, ID_BILLS + (a.key.hashCode() and 0xFFF), a.title, a.text, a.publicText, lock, MainActivity.OPEN_BILLS)
                    net.darkrelay.deaddropfinance.ui.BillAlerts.Kind.AUTOPAY ->
                        alerts.post(SpendAlerts.CHANNEL_AUTOPAY, ID_AUTOPAY + (a.key.hashCode() and 0xFFF), a.title, a.text, a.publicText, lock, MainActivity.OPEN_BILLS)
                }
            }
        }

        // each kind has its own range (4096 each)
        private const val ID_BILLS = 10_000
        private const val ID_AUTOPAY = 20_000
    }
}
