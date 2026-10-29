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

    @Test fun oneToLatestKeepsEveryRow() = runBlocking<Unit> {
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
            // and every later version's columns arrived with their safe defaults (latest: schema 5)
            assertNull(t.sortedBy)
            assertNull(t.returnOf)
            assertTrue(d.rulesNow().isEmpty())
            assertEquals(false, d.account("a1")!!.trackOnly)
            assertTrue(d.receiptsNow().isEmpty())
            assertTrue(d.receiptItemsNow().isEmpty())
            assertTrue(d.subscriptionsNow().isEmpty())
            assertEquals(0, d.notifAppCount())
            assertTrue(d.parkedBuysNow().isEmpty())
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
            assertEquals("synced and pending: an ordinary posted row since 1.3.0", false, t2.pending)
            assertNull(t2.externalId)
            assertEquals("e1", d.linesOf("t2").single().envelopeId)
            assertEquals(false, d.envelope("e1")!!.everyday)
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

    @Test fun sixToSevenKeepsBillsAndPaymentsWithSafeDefaults() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-6.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 6)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 0.9.x budget with the Stage 6 bill rows, a debt envelope and a rule
        raw.execSQL("INSERT INTO envelope (id, name, mode, required_payment, due_date, archived, sort_order, everyday, created_at, updated_at, deleted) VALUES ('d1', 'Sample Debt', 'DEBT', 5000, '2030-02-17', 0, 1, 0, 1, 1, 0)")
        raw.execSQL("INSERT INTO bill (id, name, envelope_id, amount_due, due_date, recurrence, autopay, reminder, created_at, updated_at, deleted) VALUES ('b1', 'Sample Bill', 'd1', 4200, '2030-02-05', 'MONTHLY', 1, 1, 1, 1, 0)")
        raw.execSQL("INSERT INTO bill_payment (id, bill_id, transaction_id, amount, date, created_at, updated_at, deleted) VALUES ('p1', 'b1', 't1', 1234, '2030-01-02', 1, 1, 0)")
        raw.execSQL("INSERT INTO rule (id, match_text, account_id, direction, min_amount, max_amount, action, splits, always_ask, priority, origin, acknowledged, created_at, updated_at, deleted) VALUES ('r1', 'sample payee', NULL, -1, NULL, NULL, 'ENVELOPE', 'd1:1', 0, 0, 'USER', 1, 1, 1, 0)")
        raw.execSQL("INSERT INTO receipt (id, transaction_id, store, total, kind, pages, low_confidence, raw_text, created_at, updated_at, deleted) VALUES ('rc1', 't1', 'Sample Shop', 1234, 'PURCHASE', 1, '', '', 1, 1, 0)")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            val b = d.bill("b1")!!
            assertEquals(4200L, b.amountDue)
            assertEquals(BillRecurrence.MONTHLY, b.recurrence)
            assertEquals(true, b.autopay)
            assertEquals(true, b.reminder)
            assertEquals(2, b.reminderDays)
            assertNull(b.matchText)
            assertEquals(false, b.anyAmount)
            assertEquals(false, b.varies)
            val p = d.billPayment("p1")!!
            assertEquals(PaymentMethod.BANK, p.method)
            assertNull(p.periodDue)
            assertEquals(false, d.envelope("d1")!!.reminder)
            assertEquals(2, d.envelope("d1")!!.reminderDays)
            assertNull(d.rulesNow().single().billId)
            assertEquals("t1", d.receiptsNow().single().transactionId)
            // the new tables work: a subscription through the store (change-logged); the notification app table is there
            // (empty: the reader that used it was removed in 1.5.0, but the schema keeps it)
            val store = BudgetStore(db)
            store.write { put(null, SubscriptionEntity(matchKey = "sample stream", createdAt = now, updatedAt = now)) }
            assertEquals(SubscriptionStatus.NEW, d.subscriptionsNow().single().status)
            assertEquals(0, d.notifAppCount())
            assertTrue(d.changes().any { it.entityType == "subscription" })
        } finally {
            db.close()
        }
    }

    @Test fun sevenToEightAddsParkedBuysWithEverythingKept() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-7.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 7)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 0.10.x budget: Rollover and an everyday envelope, a bill with a payment, a subscription and a notification app
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, everyday, reminder, reminder_days, created_at, updated_at, deleted) VALUES ('r1', 'Rollover', 'ROLLOVER', 0, 0, 0, 0, 2, 1, 1, 0)")
        raw.execSQL("INSERT INTO envelope (id, name, mode, target, archived, sort_order, everyday, reminder, reminder_days, created_at, updated_at, deleted) VALUES ('e1', 'Sample Groceries', 'FILL_TO_TARGET', 30000, 0, 1, 1, 0, 2, 1, 1, 0)")
        raw.execSQL("INSERT INTO bill (id, name, envelope_id, amount_due, due_date, recurrence, autopay, reminder, reminder_days, match_text, any_amount, varies, created_at, updated_at, deleted) VALUES ('b1', 'Sample Bill', NULL, 4200, '2030-02-05', 'MONTHLY', 0, 0, 2, 'sample bill', 0, 0, 1, 1, 0)")
        raw.execSQL("INSERT INTO bill_payment (id, bill_id, transaction_id, amount, date, period_due, method, created_at, updated_at, deleted) VALUES ('p1', 'b1', 't1', 1234, '2030-01-02', '2030-01-05', 'HAND', 1, 1, 0)")
        raw.execSQL("INSERT INTO subscription (id, name, match_key, status, bill_id, created_at, updated_at, deleted) VALUES ('s1', '', 'sample stream', 'CONFIRMED', NULL, 1, 1, 0)")
        raw.execSQL("INSERT INTO notif_app (package_name, label, account_id, enabled, templates, last_amount, last_payee, last_deposit, last_at) VALUES ('com.example.samplebank', 'Sample Bank', 'a1', 1, '', NULL, NULL, 0, NULL)")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            assertEquals(true, d.envelope("e1")!!.everyday)
            assertEquals(EnvelopeMode.ROLLOVER, d.envelope("r1")!!.mode)
            assertEquals("sample bill", d.bill("b1")!!.matchText)
            assertEquals(PaymentMethod.HAND, d.billPayment("p1")!!.method)
            assertEquals(SubscriptionStatus.CONFIRMED, d.subscriptionsNow().single().status)
            // the migration keeps the row; the upgrade cleanup (ReaderRemoval) empties the table at the first start
            assertEquals(1, d.notifAppCount())
            assertEquals(-1234L, d.transaction("t1")!!.amount)
            assertTrue(d.parkedBuysNow().isEmpty())
            // the new table works, through the store (change-logged)
            val store = BudgetStore(db)
            store.write { put(null, ParkedBuyEntity(name = "Sample Lamp", amount = 8999, waitDays = 1, parkedAt = 1, readyAt = 2, createdAt = now, updatedAt = now)) }
            val p = d.parkedBuysNow().single()
            assertEquals(ParkedStatus.WAITING, p.status)
            assertNull(p.envelopeId)
            assertTrue(d.changes().any { it.entityType == "parked_buy" })
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

    @Test fun eightToNineAddsCategoriesWithEveryPurchaseKeepingItsEnvelope() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-8.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 8)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 1.0.x budget: an envelope named like a default category, a purchase sorted into it, a rule, a bonus income
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, everyday, reminder, reminder_days, created_at, updated_at, deleted) VALUES ('e1', 'Groceries', 'FILL_TO_TARGET', 0, 1, 1, 0, 2, 1, 1, 0)")
        raw.execSQL(
            "INSERT INTO txn (id, account_id, kind, transfer_account_id, date, payee, memo, amount, pending, wash_group_id, is_payday, fee, fee_rebate, source, import_description, created_at, updated_at, deleted) " +
                "VALUES ('t2','a1','NORMAL',NULL,'2030-01-03','Sample Shop','',-560,0,NULL,0,0,0,'MANUAL',NULL,1,1,0)",
        )
        raw.execSQL("INSERT INTO txn_line (id, transaction_id, bucket, envelope_id, amount, sort_order, created_at, updated_at, deleted) VALUES ('l2','t2','ENVELOPE','e1',-560,0,1,1,0)")
        raw.execSQL(
            "INSERT INTO rule (id, match_text, direction, action, splits, always_ask, priority, origin, acknowledged, created_at, updated_at, deleted) " +
                "VALUES ('r1','sample shop',-1,'ENVELOPE','e1:1',0,0,'USER',1,1,1,0)",
        )
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            assertEquals("Sample Account", d.account("a1")!!.name)
            assertEquals(-560L, d.transaction("t2")!!.amount)
            val l = d.linesOf("t2").single()
            assertEquals("e1", l.envelopeId)
            assertNull("an old purchase has no category", l.categoryId)
            val r = d.rule("r1")!!
            assertEquals("e1:1", r.splits)
            assertNull("an old rule has no category", r.categoryId)
            assertEquals(false, d.envelope("e1")!!.unplanned)
            assertEquals("Groceries", d.envelope("e1")!!.name)
            assertTrue(d.categoriesNow().isEmpty())
            // the first start after the upgrade creates the default set and Unplanned, and touches nothing else
            val store = BudgetStore(db)
            assertEquals(false, store.ensureCategories())
            assertEquals(DefaultCategories.NAMES, d.categoriesNow().map { it.name })
            val unplanned = d.envelopesNow().single { it.unplanned }
            assertEquals("Unplanned", unplanned.name)
            assertEquals(true, unplanned.everyday)
            assertEquals("Groceries", d.envelope("e1")!!.name)
            assertEquals("e1", d.linesOf("t2").single().envelopeId)
            assertTrue("no envelope claims anything", d.categoriesNow().all { it.envelopeId == null })
            // the new table works, and the old change log row is still there
            db.dao().upsertCategory(CategoryEntity(name = "Sample", createdAt = 1, updatedAt = 1))
            assertEquals(18, d.categoriesNow().size)
            assertEquals(1, d.changes().filter { it.entityType == "account" }.size)
        } finally {
            db.close()
        }
    }

    @Test fun nineToTenTicksShowInActivityForEveryAccountAndKeepsEveryRow() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-9.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 9)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        // a 1.1.x budget: a second (archived, track only) account, a category with its claim, a categorized purchase
        raw.execSQL("INSERT INTO account (id, name, type, starting_balance, start_date, archived, sort_order, track_only, held_amount, created_at, updated_at, deleted) VALUES ('a2','Sample Wallet','PAYMENT_APP',2500,'2030-01-01',1,1,1,0,1,1,0)")
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, everyday, reminder, reminder_days, is_unplanned, created_at, updated_at, deleted) VALUES ('e1', 'Food', 'FILL_TO_TARGET', 0, 1, 1, 0, 2, 0, 1, 1, 0)")
        raw.execSQL("INSERT INTO category (id, name, envelope_id, sort_order, created_at, updated_at, deleted) VALUES ('c1', 'Groceries', 'e1', 0, 1, 1, 0)")
        raw.execSQL("UPDATE txn_line SET bucket = 'ENVELOPE', envelope_id = 'e1', category_id = 'c1' WHERE id = 'l1'")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            val a1 = d.account("a1")!!
            val a2 = d.account("a2")!!
            assertEquals("Sample Account", a1.name)
            assertTrue("ticked", a1.showInActivity)
            assertTrue("ticked, archived or not", a2.showInActivity)
            assertEquals(true, a2.trackOnly)
            assertEquals(2500L, a2.startingBalance)
            assertEquals(-1234L, d.transaction("t1")!!.amount)
            val l = d.linesOf("t1").single()
            assertEquals("c1", l.categoryId)
            assertEquals("e1", d.categoriesNow().single().envelopeId)
            assertEquals(1, d.changes().size)
        } finally {
            db.close()
        }
    }

    /**
     * 11 to 12 (1.3.0): bank sync removed. A 1.2.x budget with a SimpleFIN connection, a linked account with the sign
     * choice set, its sync log, the request count, synced transactions (one still pending) and a hand-entered one
     * migrates with every budget row and value kept; the bank's tables and data go; the account is a manual account; its
     * balance and Ready to Assign are what 1.2.x showed.
     */
    @Test fun elevenToTwelveDropsBankSyncAndKeepsEveryBudgetRow() = runBlocking<Unit> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test-11.db").apply { parentFile?.mkdirs(); delete() }
        createVersion(file, 11)
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
        raw.execSQL("INSERT INTO envelope (id, name, mode, archived, sort_order, everyday, reminder, reminder_days, is_unplanned, created_at, updated_at, deleted) VALUES ('e1', 'Food', 'FILL_TO_TARGET', 0, 1, 1, 0, 2, 0, 1, 1, 0)")
        raw.execSQL("INSERT INTO envelope_move (id, from_envelope_id, to_envelope_id, amount, date, kind, created_at, updated_at, deleted) VALUES ('m1', NULL, 'e1', 4000, '2030-01-02', 'ASSIGN', 1, 1, 0)")
        raw.execSQL(
            "INSERT INTO txn (id, account_id, kind, transfer_account_id, date, payee, memo, amount, pending, wash_group_id, is_payday, fee, fee_rebate, source, import_description, external_id, created_at, updated_at, deleted) " +
                "VALUES ('s1','a1','NORMAL',NULL,'2030-01-03','Sample Shop','',-560,0,NULL,0,0,0,'SYNC','SAMPLE SHOP','simplefin:ACT:S1',2,2,0)",
        )
        raw.execSQL(
            "INSERT INTO txn (id, account_id, kind, transfer_account_id, date, payee, memo, amount, pending, wash_group_id, is_payday, fee, fee_rebate, source, import_description, external_id, created_at, updated_at, deleted) " +
                "VALUES ('s2','a1','NORMAL',NULL,'2030-01-04','Sample Cafe','',-300,1,NULL,0,0,0,'SYNC','SAMPLE CAFE','simplefin:ACT:S2',3,3,0)",
        )
        raw.execSQL("INSERT INTO txn_line (id, transaction_id, bucket, envelope_id, amount, sort_order, created_at, updated_at, deleted) VALUES ('ls1','s1','ENVELOPE','e1',-560,0,2,2,0)")
        raw.execSQL("INSERT INTO txn_line (id, transaction_id, bucket, envelope_id, amount, sort_order, created_at, updated_at, deleted) VALUES ('ls2','s2','ENVELOPE','e1',-300,0,3,3,0)")
        raw.execSQL("INSERT INTO connection (id, provider, name, status, last_sync_at, created_at) VALUES ('c1', 'SIMPLEFIN', 'Sample', 'OK', 5, 1)")
        raw.execSQL("INSERT INTO linked_account (id, connection_id, provider_account_id, provider_name, mask, account_id, provider_balance, provider_balance_date, set_start_balance, balance_sign_flipped) VALUES ('l1', 'c1', 'ACT', 'Sample', '1234', 'a1', 8306, 1900000000, 0, 1)")
        raw.execSQL("INSERT INTO sync_log (id, connection_id, at, ok, added, updated, removed, message) VALUES ('g1', 'c1', 5, 1, 2, 0, 0, 'Sample result.')")
        raw.execSQL("INSERT INTO meta (`key`, value) VALUES ('simplefin.requests.c1', '1,2,3')")
        raw.execSQL("INSERT INTO meta (`key`, value) VALUES ('budget_start', '2030-01-01')")
        raw.close()
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, file.path).addMigrations(*Migrations.ALL).build()
        try {
            val d = db.dao()
            // every budget row and value is kept
            assertEquals("Sample Account", d.account("a1")!!.name)
            assertEquals(10000L, d.account("a1")!!.startingBalance)
            assertEquals(listOf("s1", "s2", "t1"), d.transactionsNow().map { it.id }.sorted())
            val s1 = d.transaction("s1")!!
            val s2 = d.transaction("s2")!!
            assertEquals(-560L, s1.amount); assertEquals(-300L, s2.amount); assertEquals(-1234L, d.transaction("t1")!!.amount)
            assertEquals("SAMPLE CAFE", s2.importDescription)
            assertEquals(TransactionSource.SYNC, s2.source)
            assertEquals("the pending synced row is an ordinary posted one", false, s2.pending)
            assertNull("the bank's ids go", s1.externalId)
            assertNull(s2.externalId)
            assertEquals("e1", d.linesOf("s2").single().envelopeId)
            assertEquals(4000L, d.movesNow().single().amount)
            assertEquals(1, d.changes().size)
            assertEquals("2030-01-01", d.meta("budget_start"))
            // the bank's tables and its request count are gone
            val tables = mutableListOf<String>()
            db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
            assertTrue(tables.toString(), tables.none { it in setOf("connection", "linked_account", "sync_log") })
            assertNull(d.meta("simplefin.requests.c1"))
            // the balance and Ready to Assign are what 1.2.x showed (the same rows, the pending one still pending)
            val after = net.darkrelay.deaddropfinance.domain.Budget(d.accountsNow(), d.envelopesNow(), d.transactionsNow(), d.linesNow(), d.movesNow())
            val before = net.darkrelay.deaddropfinance.domain.Budget(
                d.accountsNow(), d.envelopesNow(), d.transactionsNow().map { if (it.id == "s2") it.copy(pending = true) else it }, d.linesNow(), d.movesNow(),
            )
            assertEquals(before.accountBalance("a1"), after.accountBalance("a1"))
            assertEquals(10000L - 1234 - 560 - 300, after.accountBalance("a1"))
            assertEquals(before.readyToAssign, after.readyToAssign)
            assertEquals(before.envelopeBalance("e1"), after.envelopeBalance("e1"))
            assertTrue(d.account("a1")!!.showInActivity)
        } finally {
            db.close()
        }
    }
}
