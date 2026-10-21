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

    @Test fun oneToTwoKeepsEveryRowAndAddsTheSyncTables() = runBlocking<Unit> {
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
