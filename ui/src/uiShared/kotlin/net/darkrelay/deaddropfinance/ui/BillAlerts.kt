package net.darkrelay.deaddropfinance.ui

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import net.darkrelay.deaddropfinance.domain.Reminders

/**
 * Bill reminders and autopay warnings: which ones are due now, each sent once per bill cycle, under the settings in
 * More, Alerts. The phone sends them as Android notifications (BillReminderWorker), the program as Windows
 * notifications (0.13.0); this decides what goes and remembers it in [AppSettings.reminded].
 */
object BillAlerts {
    enum class Kind { BILL, AUTOPAY }

    /** One notification: [publicText] is what the phone's lock screen shows when amounts are hidden there. */
    data class Alert(val kind: Kind, val key: String, val title: String, val text: String, val publicText: String)

    /** Sends what's due now through [send] and remembers it. */
    suspend fun check(store: BudgetStore, settings: AppSettings, today: LocalDate, send: (Alert) -> Unit) {
        val b = BudgetActions(store).snapshot()
        val sent = settings.reminded.toMutableSet()
        if (settings.alertBills) {
            for (r in Reminders.due(b, today)) {
                if (r.key in sent) continue
                val whenText = if (r.due == today) "today" else "on ${r.due}"
                send(Alert(Kind.BILL, r.key, "${r.name} is due $whenText",
                    "${Money.format(r.amount)} to pay${if (r.kind == Reminders.Kind.DEBT) " (debt payment)" else ""}.", "A bill is due soon"))
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
                    send(Alert(Kind.AUTOPAY, key, "${w.cycle.bill.name} autopays before payday",
                        "Due ${w.cycle.due}: ${Money.format(w.needed)}. ${w.envelope?.name ?: "Its envelope"} has ${Money.format(w.envelopeHas)}, short ${Money.format(w.short)}.",
                        "An autopay is short"))
                    sent += key
                    settings.reminded = sent
                }
            }
        }
        // keep the memory small: only this year's keys
        settings.reminded = sent.filter { k -> Regex("""\d{4}-\d{2}-\d{2}""").find(k)?.value?.let { LocalDate.parse(it).isAfter(today.minusDays(60)) } ?: false }.toSet()
    }
}
