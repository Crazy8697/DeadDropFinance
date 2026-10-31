package net.darkrelay.deaddropfinance.serversync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A receipt item's tax letter and price with tax (schema 14) beside devices that don't know them: a change from a
 * 1.9.2 phone or a 0.15.1 program carries every item column but those two and leaves them as they are; on such a device
 * (no columns) a change carrying them writes everything else and drops just those. Additive only, no existing data changes.
 */
class TaxLetterSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun aChangeWithoutTheTaxColumnsKeepsThem() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val item = o.store.write {
                val r = put(null, ReceiptEntity(store = "Sample Mart", total = 7_96, tax = 47, createdAt = now, updatedAt = now))
                put(null, ReceiptItemEntity(receiptId = r.id, text = "MILK", name = "milk", amount = 3_00, taxLetter = "A", priceWithTax = 3_28, createdAt = now, updatedAt = now))
            }
            val sync = ServerSync(o.store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { error("no network here") })
            // what a 1.9.2 phone sends when it renames the item: all its fields, but not the two new ones
            val old = JSONObject(item.fields().filterKeys { it != "tax_letter" && it != "price_with_tax" }.mapValues { it.value ?: JSONObject.NULL }).put("text", "MILK 2%")
            sync.applyRemote(listOf(ServerChange(1, "c-old", "receipt_item", item.id, "UPDATE", old, 5_000, "phone-old")))
            val kept = o.store.dao.itemsOf(item.receiptId).single()
            assertEquals("MILK 2%", kept.text)
            assertEquals("A", kept.taxLetter, "the letter is kept")
            assertEquals(3_28L, kept.priceWithTax, "and the price with tax")
            // a 1.9.3 device's change carries them
            sync.applyRemote(listOf(ServerChange(2, "c-new", "receipt_item", item.id, "UPDATE", JSONObject().put("tax_letter", "B").put("price_with_tax", 3_30), 6_000, "phone-new")))
            assertEquals("B" to 3_30L, o.store.dao.itemsOf(item.receiptId).single().let { it.taxLetter to it.priceWithTax })
            // the change this device logged names both
            assertTrue(o.store.dao.changes().any { it.entityId == item.id && it.changedFields.contains("\"tax_letter\":\"A\"") })
        } finally {
            scope.cancel()
            o.store.db.close()
        }
    }

    @Test fun onADeviceWithoutTheColumnsTheRestIsWritten() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(), clock = { 1_000L }))
        try {
            val item = o.store.write {
                val r = put(null, ReceiptEntity(store = "Sample Mart", createdAt = now, updatedAt = now))
                put(null, ReceiptItemEntity(receiptId = r.id, text = "MILK", name = "milk", amount = 3_00, createdAt = now, updatedAt = now))
            }
            o.store.db.rawTransaction { raw ->
                // the columns a schema 13 table has
                val cols = ServerSync.columns(raw, "receipt_item") - "tax_letter" - "price_with_tax"
                ServerSync.upsertRow(raw, "receipt_item", cols, item.id, JSONObject().put("text", "MILK 2%").put("tax_letter", "A").put("price_with_tax", 3_18), 7_000)
            }
            val e = o.store.dao.itemsOf(item.receiptId).single()
            assertEquals("MILK 2%", e.text)
            assertEquals("", e.taxLetter)
            assertEquals(null, e.priceWithTax)
        } finally {
            o.store.db.close()
        }
    }
}
