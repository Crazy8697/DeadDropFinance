package net.darkrelay.deaddropfinance.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every schema version migrates with its data intact. A version 1 database is built from the exported 1.json (as
 * 0.6.x made it), filled with synthetic rows, then opened by today's Room with the real migrations: Room checks the
 * migrated schema against version 2's, and the rows must all still be there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MigrationTest {

    private val schemas = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }.resolve("net.darkrelay.deaddropfinance.data.AppDatabase")

    private fun createVersion(file: File, version: Int) {
        val json = JSONObject(schemas.resolve("$version.json").readText()).getJSONObject("database")
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = json.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val idx = e.optJSONArray("indices")
            if (idx != null) for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
        val setup = json.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        // synthetic rows, as 0.6.x wrote them
        db.execSQL("INSERT INTO account (id, name, type, starting_balance, start_date, archived, sort_order, created_at, updated_at, deleted) VALUES ('a1','Sample Account','BANK',10000,'2030-01-01',0,0,1,1,0)")
        db.execSQL(
            "INSERT INTO txn (id, account_id, kind, transfer_account_id, date, payee, memo, amount, pending, wash_group_id, is_payday, fee, fee_rebate, source, import_description, created_at, updated_at, deleted) " +
                "VALUES ('t1','a1','NORMAL',NULL,'2030-01-02','Sample Payee','',-1234,0,NULL,0,0,0,'CSV','SAMPLE PAYEE',1,1,0)",
        )
        db.execSQL("INSERT INTO txn_line (id, transaction_id, bucket, envelope_id, amount, sort_order, created_at, updated_at, deleted) VALUES ('l1','t1','UNSORTED',NULL,-1234,0,1,1,0)")
        db.execSQL("INSERT INTO change_log (change_id, entity_type, entity_id, operation, changed_fields, timestamp, device_id, seq) VALUES ('ch1','account','a1','INSERT','{}',1,'d1',1)")
        db.close()
    }

    @Test fun oneToLatestKeepsEveryRowAndAddsTheSyncTables() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 1)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            assertEquals("Sample Account", d.account("a1")!!.name)
            val t = d.transaction("t1")!!
            assertEquals(-1234L, t.amount)
            assertEquals("SAMPLE PAYEE", t.importDescription)
            assertNull(t.externalId)
            assertEquals(1, d.linesOf("t1").size)
            assertEquals(1, d.changes().size)
            assertTrue(db.syncDao().connectionsNow().isEmpty())
            // the new tables work
            db.syncDao().upsertConnection(ConnectionEntity(provider = Provider.SIMPLEFIN, name = "Sample", createdAt = 1))
            assertEquals(1, db.syncDao().connectionsNow().size)
            // and every later version's columns arrived with their safe defaults (latest: schema 5)
            assertNull(t.sortedBy)
            assertNull(t.returnOf)
            assertTrue(d.rulesNow().isEmpty())
            assertEquals(false, d.account("a1")!!.trackOnly)
            assertTrue(d.receiptsNow().isEmpty())
            assertTrue(d.receiptItemsNow().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun twoToThreeAddsTrackOnlyAndHeldWithSafeDefaults() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-2.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 2)
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val a = db.dao().account("a1")!!
            assertEquals("Sample Account", a.name)
            assertEquals(false, a.trackOnly)
            assertEquals(0L, a.heldAmount)
            assertTrue(a.onBudget)
            assertEquals(-1234L, db.dao().transaction("t1")!!.amount)
            assertEquals(1, db.dao().changes().size)
        } finally {
            db.close()
        }
    }

    @Test fun threeToFourAddsTheBankBalanceDate() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-3.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 3)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("INSERT INTO connection (id, provider, name, status, created_at) VALUES ('c1', 'SIMPLEFIN', 'Sample', 'OK', 1)")
        raw.execSQL("INSERT INTO linked_account (id, connection_id, provider_account_id, provider_name, mask, account_id, provider_balance, set_start_balance) VALUES ('l1', 'c1', 'ACT', 'Sample', '', 'a1', -4107, 0)")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val l = db.syncDao().linkedNow().single()
            assertEquals(-4107L, l.providerBalance)
            assertEquals(null, l.providerBalanceDate)
            assertEquals("Sample Account", db.dao().account("a1")!!.name)
        } finally {
            db.close()
        }
    }

    @Test fun fourToFiveAddsRulesAndSortingWithEverythingKept() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-4.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 4)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 0.7.x budget: an envelope, a second transaction already sorted into it, a bank link with its balance date
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, created_at, updated_at, deleted) VALUES ('e1', 'Sample Envelope', 'FILL_TO_TARGET', 0, 1, 1, 1, 0)")
        raw.execSQL(
            "INSERT INTO txn (id, account_id, kind, transfer_account_id, date, payee, memo, amount, pending, wash_group_id, is_payday, fee, fee_rebate, source, import_description, external_id, created_at, updated_at, deleted) " +
                "VALUES ('t2','a1','NORMAL',NULL,'2030-01-03','Sample Shop','',-560,1,NULL,0,0,0,'SYNC','SAMPLE SHOP','simplefin:ACT:T2',1,1,0)",
        )
        raw.execSQL("INSERT INTO txn_line (id, transaction_id, bucket, envelope_id, amount, sort_order, created_at, updated_at, deleted) VALUES ('l2','t2','ENVELOPE','e1',-560,0,1,1,0)")
        raw.execSQL("INSERT INTO connection (id, provider, name, status, created_at) VALUES ('c1', 'SIMPLEFIN', 'Sample', 'OK', 1)")
        raw.execSQL("INSERT INTO linked_account (id, connection_id, provider_account_id, provider_name, mask, account_id, provider_balance, provider_balance_date, set_start_balance) VALUES ('l1', 'c1', 'ACT', 'Sample', '', 'a1', 9440, 1900000000, 0)")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            val t1 = d.transaction("t1")!!
            val t2 = d.transaction("t2")!!
            assertNull(t1.sortedBy); assertNull(t1.sortedAt); assertNull(t1.ruleId); assertNull(t1.returnOf)
            assertNull(t2.sortedBy)
            assertEquals(true, t2.pending)
            assertEquals("e1", d.linesOf("t2").single().envelopeId)
            assertEquals(false, d.envelope("e1")!!.everyday)
            assertEquals(1900000000L, db.syncDao().linkedNow().single().providerBalanceDate)
            assertEquals(1, d.changes().size)
            // the rule table works, through the store (with its change log row)
            val store = BudgetStore(db)
            store.write { put(null, RuleEntity(matchText = "sample shop", splits = "e1:1", createdAt = now, updatedAt = now)) }
            assertEquals("sample shop", d.rulesNow().single().matchText)
            assertTrue(d.changes().any { it.entityType == "rule" && it.operation == ChangeOperation.INSERT })
        } finally {
            db.close()
        }
    }

    @Test fun fiveToSixAddsReceiptsWithEverythingKept() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-5.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 5)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 0.8.x budget: a rule, and the transaction approved by it
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, everyday, created_at, updated_at, deleted) VALUES ('e1', 'Sample Envelope', 'FILL_TO_TARGET', 0, 1, 1, 1, 1, 0)")
        raw.execSQL("INSERT INTO rule (id, match_text, account_id, direction, min_amount, max_amount, action, splits, always_ask, priority, origin, acknowledged, created_at, updated_at, deleted) VALUES ('r1', 'sample payee', NULL, -1, NULL, NULL, 'ENVELOPE', 'e1:1', 0, 0, 'USER', 1, 1, 1, 0)")
        raw.execSQL("UPDATE txn SET sorted_by = 'APPROVED', sorted_at = 5, rule_id = 'r1' WHERE id = 't1'")
        raw.execSQL("UPDATE txn_line SET bucket = 'ENVELOPE', envelope_id = 'e1' WHERE id = 'l1'")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            val t = d.transaction("t1")!!
            assertEquals(SortedBy.APPROVED, t.sortedBy)
            assertEquals("r1", t.ruleId)
            assertEquals("e1", d.linesOf("t1").single().envelopeId)
            assertEquals("sample payee", d.rulesNow().single().matchText)
            assertEquals(true, d.envelope("e1")!!.everyday)
            assertTrue(d.receiptsNow().isEmpty())
            // the receipt tables work, through the store (with change log rows)
            val store = BudgetStore(db)
            store.write {
                val r = put(null, ReceiptEntity(transactionId = "t1", store = "Sample Shop", total = 1234, pages = 1, createdAt = now, updatedAt = now))
                val i = put(null, ReceiptItemEntity(receiptId = r.id, text = "SAMPLE THING", name = "sample thing", amount = 1234, createdAt = now, updatedAt = now))
                put(null, ItemReturnEntity(itemId = i.id, transactionId = "t1", quantityMilli = 1000, amount = 1234, createdAt = now, updatedAt = now))
            }
            assertEquals("t1", d.receiptsNow().single().transactionId)
            assertEquals(1, d.itemReturnsNow().size)
            assertTrue(listOf("receipt", "receipt_item", "item_return").all { k -> d.changes().any { it.entityType == k } })
        } finally {
            db.close()
        }
    }

    @Test fun everyVersionHasAnExportedSchemaAndAMigration() {
        val versions = schemas.listFiles()!!.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }.sorted()
        assertEquals((1..versions.last()).toList(), versions)
        assertEquals(versions.last() - 1, Migrations.ALL.size)
        Migrations.ALL.forEachIndexed { i, m -> assertEquals(i + 1, m.startVersion); assertEquals(i + 2, m.endVersion) }
    }
}
