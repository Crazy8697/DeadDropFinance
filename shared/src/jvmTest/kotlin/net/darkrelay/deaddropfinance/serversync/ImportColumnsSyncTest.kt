package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.TransactionEntity
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A transaction's bank original description and import id (schema 15) beside devices that don't know them: a change
 * from a 1.9.6 phone or a 0.15.9 program (a renamed payee) leaves them as they are; on such a device (no columns) a
 * change carrying them writes everything else and drops just those. Additive only, no existing data changes.
 */
class ImportColumnsSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun aChangeWithoutTheNewColumnsKeepsThem() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val acct = o.store.write { put(null, net.darkrelay.deaddropfinance.data.AccountEntity(name = "Sample Checking", type = net.darkrelay.deaddropfinance.data.AccountType.BANK, startDate = LocalDate.of(2030, 1, 1), createdAt = now, updatedAt = now)) }
            val tx = o.store.write {
                put(null, TransactionEntity(accountId = acct.id, date = LocalDate.of(2030, 11, 3), payee = "QuikTrip", amount = -40_00,
                    originalDescription = "QUIKTRIP 0123 SAMPLE CITY", importId = "imp-1", createdAt = now, updatedAt = now))
            }
            val sync = ServerSync(o.store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { error("no network here") })
            // a 1.9.6 phone renames the payee: all its fields, but not the two new ones
            val old = JSONObject(tx.fields().filterKeys { it != "original_description" && it != "import_id" }.mapValues { it.value ?: JSONObject.NULL }).put("payee", "Quick Trip")
            sync.applyRemote(listOf(ServerChange(1, "c-old", "transaction", tx.id, "UPDATE", old, 5_000, "phone-old")))
            val kept = o.store.dao.transaction(tx.id)!!
            assertEquals("Quick Trip", kept.payee)
            assertEquals("QUIKTRIP 0123 SAMPLE CITY", kept.originalDescription, "the bank's description is kept")
            assertEquals("imp-1", kept.importId, "and the import id")
            assertTrue(o.store.dao.changes().any { it.entityId == tx.id && it.changedFields.contains("\"import_id\":\"imp-1\"") })
        } finally {
            scope.cancel()
            o.store.db.close()
        }
    }

    @Test fun onADeviceWithoutTheColumnsTheRestIsWritten() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        try {
            val tx = o.store.write { put(null, TransactionEntity(accountId = "a", date = LocalDate.of(2030, 11, 3), payee = "Sample", amount = -1_00, createdAt = now, updatedAt = now)) }
            o.store.db.rawTransaction { raw ->
                // the columns a schema 14 table has
                val cols = ServerSync.columns(raw, "txn") - "original_description" - "import_id"
                ServerSync.upsertRow(raw, "txn", cols, tx.id, JSONObject().put("payee", "Renamed").put("original_description", "X").put("import_id", "imp-2"), 7_000)
            }
            val e = o.store.dao.transaction(tx.id)!!
            assertEquals("Renamed", e.payee)
            assertNull(e.originalDescription)
            assertNull(e.importId)
        } finally {
            o.store.db.close()
        }
    }
}
