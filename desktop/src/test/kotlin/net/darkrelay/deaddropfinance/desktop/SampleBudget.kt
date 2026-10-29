package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft

/** An invented budget for the screen tests and screenshots: made-up names, round numbers, dates in 2030. */
object SampleBudget {
    val today: LocalDate = LocalDate.of(2030, 10, 29)

    class Made(val model: AppModel, val ids: Map<String, String>)

    /** An empty budget (built-in rows only) in [dir]. */
    fun empty(dir: File): AppModel = runBlocking {
        val clock = today.atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        AppModel(Engine.start(dir) { clock }) { today }.also { it.refresh() }
    }

    /** A filled budget like the mockup's, in [dir]. */
    fun filled(dir: File): Made = runBlocking {
        var tick = today.minusDays(40).atTime(9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        val engine = Engine.start(dir) { tick += 60_000; tick }
        val m = AppModel(engine) { today }
        val a = m.actions!!
        val ids = mutableMapOf<String, String>()
        val start = today.minusDays(35)
        ids["Checking"] = a.saveAccount(null, "Checking", AccountType.BANK, 1240_00, start)
        ids["Savings"] = a.saveAccount(null, "Savings", AccountType.BANK, 1042_10, start)
        ids["Cash"] = a.saveAccount(null, "Cash", AccountType.CASH, 60_00, start, showInActivity = false)
        ids["Credit Card"] = a.saveAccount(null, "Credit Card", AccountType.BANK, -290_46, start, trackOnly = true)
        suspend fun env(name: String, mode: EnvelopeMode, amount: Long, everyday: Boolean = false) {
            ids[name] = when (mode) {
                EnvelopeMode.GOAL -> a.saveEnvelope(null, EnvelopeDraft(name, mode, goalAmount = amount, goalDate = today.plusDays(180), everyday = everyday), start)
                EnvelopeMode.FILL_TO_TARGET -> a.saveEnvelope(null, EnvelopeDraft(name, mode, target = amount, everyday = everyday), start)
                else -> a.saveEnvelope(null, EnvelopeDraft(name, mode, perPayday = amount, everyday = everyday), start)
            }
        }
        env("Rent", EnvelopeMode.FILL_TO_TARGET, 1200_00)
        env("Electric", EnvelopeMode.FILL_TO_TARGET, 140_00)
        env("Internet", EnvelopeMode.FILL_TO_TARGET, 70_00)
        env("Groceries", EnvelopeMode.FILL_TO_TARGET, 600_00, everyday = true)
        env("Gas", EnvelopeMode.FILL_TO_TARGET, 160_00, everyday = true)
        env("Dining out", EnvelopeMode.FILL_TO_TARGET, 80_00, everyday = true)
        env("Emergency fund", EnvelopeMode.GOAL, 2000_00)
        env("Gifts", EnvelopeMode.ADD_EVERY_PAYDAY, 40_00)
        val payday = today.minusDays(3)
        val pay = a.saveTransaction(null, TransactionDraft(ids["Checking"]!!, payday, "Sample Payroll Co", "Income", 1850_00, listOf(LineDraft(null, 1850_00, toReadyToAssign = true))))
        a.markPayday(pay, payday)
        a.move(null, ids["Emergency fund"]!!, 350_00, date = today.minusDays(2))
        a.move(null, ids["Gifts"]!!, 60_00, date = today.minusDays(2))
        suspend fun spend(account: String, days: Long, payee: String, cents: Long, envelope: String, memo: String = "") =
            a.saveTransaction(null, TransactionDraft(ids[account]!!, today.minusDays(days), payee, memo, -cents, listOf(LineDraft(ids[envelope], -cents))))
        spend("Checking", 6, "Power Company", 58_00, "Electric", "Autopay")
        spend("Cash", 5, "Noodle House", 18_25, "Dining out")
        spend("Checking", 1, "Fuel Stop 12", 40_00, "Gas", "Half tank")
        spend("Checking", 0, "Corner Market", 43_12, "Groceries", "Weekly run")
        a.move(ids["Dining out"], ids["Groceries"], 20_00, date = today.minusDays(1))
        m.refresh()
        Made(m, ids)
    }

    /** A bank export with invented rows, for the import test. */
    val csv = """
        Date,Description,Amount
        2030-10-27,Sample Bookshop,-12.50
        2030-10-28,Sample Pharmacy,-8.20
        2030-10-28,Interest Paid,0.42
    """.trimIndent()
}
