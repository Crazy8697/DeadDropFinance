package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate

/**
 * Bill reminders (FEATURES.md, Bills): off unless turned on for a bill, or for a Debt envelope with a required payment
 * and due date. A reminder is due from [BillEntity.reminderDays] days before the due date through the due date, while
 * that cycle is unpaid. Each cycle is reminded once (the caller remembers which). Pure.
 */
object Reminders {

    enum class Kind { BILL, DEBT }

    data class Due(val kind: Kind, val id: String, val name: String, val due: LocalDate, val amount: Long) {
        /** Remembered once sent, so a cycle is reminded once. */
        val key get() = "$kind:$id:$due"
    }

    fun due(b: Budget, today: LocalDate): List<Due> {
        val out = mutableListOf<Due>()
        for (bill in b.bills.filter { it.reminder && !it.deleted }) {
            for (c in Bills.cycles(bill, b.billPayments, today.plusDays(bill.reminderDays.toLong())).filter { !it.isPaid }) {
                if (!today.isBefore(c.due.minusDays(bill.reminderDays.toLong())) && !today.isAfter(c.due)) {
                    out += Due(Kind.BILL, bill.id, bill.name, c.due, c.remaining)
                }
            }
        }
        for (e in b.envelopes.filter { it.reminder && !it.archived }) {
            val due = Bills.nextDebtDue(b, e, today) ?: continue
            if (!today.isBefore(due.minusDays(e.reminderDays.toLong())) && !today.isAfter(due)) {
                out += Due(Kind.DEBT, e.id, e.name, due, e.requiredPayment ?: 0)
            }
        }
        return out.sortedBy { it.due }
    }

    /** Autopays due before the next payday whose envelope is short, as a warning to send once per cycle. */
    fun autopayWarnings(b: Budget, today: LocalDate, nextPayday: LocalDate): List<Bills.AutopayCheck> =
        Bills.autopayChecks(b, b.billPayments, today, nextPayday).filter { it.short > 0 }
}
