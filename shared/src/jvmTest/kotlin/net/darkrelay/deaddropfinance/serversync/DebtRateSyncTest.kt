package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A Debt envelope's interest rate (schema 13) beside devices that don't know it: a change from a 1.8.x phone or a
 * 0.14.x program carries every envelope column but the rate, and leaves the rate as it is; on such a device (no column)
 * a change carrying the rate writes everything else and drops just the rate. No existing data changes.
 */
class DebtRateSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun aChangeWithoutTheRateKeepsIt() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val a = BudgetActions(o.store)
            val id = a.saveEnvelope(null, EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00, interestRate = 2499), LocalDate.of(2030, 5, 1))
            val sync = ServerSync(o.store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { error("no network here") })
            // what a 1.8.x device sends for the envelope: all its fields, renamed, but no interest_rate
            val old = JSONObject(o.store.dao.envelope(id)!!.fields().filterKeys { it != "interest_rate" }.mapValues { it.value ?: JSONObject.NULL })
                .put("name", "Sample Car Loan")
            sync.applyRemote(listOf(ServerChange(1, "c-old", "envelope", id, "UPDATE", old, 5_000, "phone-old")))
            val e = o.store.dao.envelope(id)!!
            assertEquals("Sample Car Loan", e.name)
            assertEquals(2499L, e.interestRate, "the rate is kept")
            assertEquals(1200_00L, e.debtOwed)
            // a 1.9.0 device's change carries it
            sync.applyRemote(listOf(ServerChange(2, "c-new", "envelope", id, "UPDATE", JSONObject().put("interest_rate", 1999), 6_000, "phone-new")))
            assertEquals(1999L, o.store.dao.envelope(id)!!.interestRate)
        } finally {
            scope.cancel()
            o.store.db.close()
        }
    }

    @Test fun onADeviceWithoutTheColumnTheRestIsWritten() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        try {
            val id = BudgetActions(o.store).saveEnvelope(null, EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, debtOwed = 500_00), LocalDate.of(2030, 5, 1))
            o.store.db.rawTransaction { raw ->
                // the columns a schema 12 table has
                val cols = ServerSync.columns(raw, "envelope") - "interest_rate"
                ServerSync.upsertRow(raw, "envelope", cols, id, JSONObject().put("name", "Sample Loan 2").put("interest_rate", 999), 7_000)
            }
            val e = o.store.dao.envelope(id)!!
            assertEquals("Sample Loan 2", e.name)
            assertEquals(null, e.interestRate)
        } finally {
            o.store.db.close()
        }
    }
}
