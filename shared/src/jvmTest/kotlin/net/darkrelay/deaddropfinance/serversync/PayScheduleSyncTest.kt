package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.PaySchedule
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pay schedule (1.9.8, program 0.15.11) as a synced setting: saving it logs a change of the meta entity
 * pay_schedule, another device's change sets it here, a damaged one is left out, and the changes an older device
 * sends (which never name it) leave it as it is. An older device skips the key itself, as it skips any meta key it
 * doesn't know (its SYNCED_META has no pay_schedule), so it keeps working.
 */
class PayScheduleSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val firstMinus2 = PaySchedule(PaySchedule.Kind.MONTHLY, dayOfMonth = 1, daysEarly = 2)

    @Test fun savedLoggedAndBroughtByAnotherDevice() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bills = BillActions(o.store, today = { LocalDate.of(2026, 11, 27) })
            assertNull(bills.paySchedule())
            bills.savePaySchedule(firstMinus2)
            assertEquals(firstMinus2, bills.paySchedule())
            val logged = o.store.dao.changes().last()
            assertEquals(BudgetStore.META_ENTITY to BudgetStore.META_PAY_SCHEDULE, logged.entityType to logged.entityId)
            assertEquals(firstMinus2, PaySchedule.fromJson(JSONObject(logged.changedFields).getString("value")))
            assertEquals(LocalDate.of(2026, 11, 27), bills.prediction().next)

            val sync = ServerSync(o.store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { error("no network here") })
            // another device saves every two weeks
            val other = PaySchedule(PaySchedule.Kind.BIWEEKLY, anchor = LocalDate.of(2026, 11, 13))
            sync.applyRemote(listOf(ServerChange(1, "c-1", "meta", BudgetStore.META_PAY_SCHEDULE, "UPDATE", JSONObject().put("value", other.toJson()), 5_000, "phone")))
            assertEquals(other, bills.paySchedule())
            // a damaged one changes nothing
            sync.applyRemote(listOf(ServerChange(2, "c-2", "meta", BudgetStore.META_PAY_SCHEDULE, "UPDATE", JSONObject().put("value", "{oops"), 6_000, "phone")))
            assertEquals(other, bills.paySchedule())
            // an older device's changes (a budget start, a key it made up) never touch it
            sync.applyRemote(listOf(
                ServerChange(3, "c-3", "meta", BudgetStore.META_BUDGET_START, "UPDATE", JSONObject().put("value", "2026-10-01"), 7_000, "phone-old"),
                ServerChange(4, "c-4", "meta", "not_a_setting", "UPDATE", JSONObject().put("value", "x"), 7_000, "phone-old"),
            ))
            assertEquals(other, bills.paySchedule())
            assertEquals(LocalDate.of(2026, 10, 1), o.store.budgetStart())
            assertNull(o.store.dao.meta("not_a_setting"))
        } finally {
            scope.cancel()
            o.store.db.close()
        }
    }

    @Test fun itSyncsAndTheSameScheduleAgainLogsNothing() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        try {
            assertTrue(BudgetStore.META_PAY_SCHEDULE in BudgetStore.SYNCED_META)
            o.store.setPaySchedule(firstMinus2.toJson())
            // saving the same again logs nothing more
            val n = o.store.dao.changes().size
            o.store.setPaySchedule(firstMinus2.toJson())
            assertEquals(n, o.store.dao.changes().size)
        } finally {
            o.store.db.close()
        }
    }
}
