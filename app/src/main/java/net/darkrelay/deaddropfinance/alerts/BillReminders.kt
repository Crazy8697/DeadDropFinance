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
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Reminders
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

        /** Sends what's due now and remembers it. */
        suspend fun check(context: Context, store: net.darkrelay.deaddropfinance.data.BudgetStore, settings: SyncSettings, today: LocalDate) {
            val alerts = SpendAlerts(context)
            if (!alerts.allowed()) return
            val b = BudgetActions(store).snapshot()
            val sent = settings.reminded.toMutableSet()
            val lock = settings.alertAmountsOnLockScreen
            if (settings.alertBills) {
                for (r in Reminders.due(b, today)) {
                    if (r.key in sent) continue
                    val whenText = if (r.due == today) "today" else "on ${r.due}"
                    alerts.post(
                        SpendAlerts.CHANNEL_BILLS, ID_BILLS + (r.key.hashCode() and 0xFFF), "${r.name} is due $whenText",
                        "${Money.format(r.amount)} to pay${if (r.kind == Reminders.Kind.DEBT) " (debt payment)" else ""}.", "A bill is due soon", lock,
                        MainActivity.OPEN_BILLS,
                    )
                    sent += r.key
                    settings.reminded = sent      // saved as each goes, so a failure later never sends it again
                }
            }
            if (settings.alertAutopay) {
                val next = BillActions(store) { today }.prediction().effectiveNext(today)
                if (next != null) {
                    for (w in Reminders.autopayWarnings(b, today, next)) {
                        val key = "autopay:${w.cycle.bill.id}:${w.cycle.due}"
                        if (key in sent) continue
                        alerts.post(
                            SpendAlerts.CHANNEL_AUTOPAY, ID_AUTOPAY + (key.hashCode() and 0xFFF), "${w.cycle.bill.name} autopays before payday",
                            "Due ${w.cycle.due}: ${Money.format(w.needed)}. ${w.envelope?.name ?: "Its envelope"} has ${Money.format(w.envelopeHas)}, short ${Money.format(w.short)}.",
                            "An autopay is short", lock, MainActivity.OPEN_BILLS,
                        )
                        sent += key
                        settings.reminded = sent
                    }
                }
            }
            // keep the memory small: only this year's keys
            settings.reminded = sent.filter { k -> Regex("""\d{4}-\d{2}-\d{2}""").find(k)?.value?.let { LocalDate.parse(it).isAfter(today.minusDays(60)) } ?: false }.toSet()
        }

        // each kind has its own range (4096 each)
        private const val ID_BILLS = 10_000
        private const val ID_AUTOPAY = 20_000
    }
}
