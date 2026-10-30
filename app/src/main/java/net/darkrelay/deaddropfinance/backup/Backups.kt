package net.darkrelay.deaddropfinance.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ReceiptImages
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Backup and restore (FEATURES.md): one file, encrypted with the recovery key (BackupCrypto format 2, 1.6.0; older
 * backups were encrypted with a password the user picked, and still restore with it), holding the
 * whole budget (every budget table, the change log included), the receipt photos and the user's settings.
 *
 * Restore replaces everything the backup holds. The whole file is read and checked first (password, every table,
 * every photo); only then is the budget replaced, in one database transaction, and the photos swapped in. A bad file
 * or a wrong password changes nothing.
 */
class Backups(
    private val db: AppDatabase,
    /** The phone's receipt photos (null: none are carried). */
    private val photos: ReceiptImages?,
    /** Where a restore stages photos before swapping them in (it's emptied each time). */
    private val stagingDir: File,
    private val settings: SettingsTransfer?,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val sql get() = db.openHelper.writableDatabase

    private fun schema() = sql.version

    /** The tables a backup carries: every table but the database's own bookkeeping. */
    private fun tables(): List<String> {
        val out = mutableListOf<String>()
        sql.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out.filter { it !in LEFT_OUT && !it.startsWith("sqlite_") }
    }

    private fun columns(table: String): List<String> {
        val out = mutableListOf<String>()
        sql.query("PRAGMA table_info(`$table`)").use { c -> val i = c.getColumnIndexOrThrow("name"); while (c.moveToNext()) out += c.getString(i) }
        return out
    }

    private fun count(rows: Map<String, JSONArray>, table: String, live: Boolean = true): Int {
        val a = rows[table] ?: return 0
        return (0 until a.length()).count { i -> !live || a.getJSONObject(i).optInt("deleted", 0) == 0 }
    }

    /** Writes the backup to [out] (closed when done), encrypted under the recovery key (1.6.0). */
    fun write(out: OutputStream, recoveryKey: ByteArray): BackupSummary = write(out) { BackupCrypto.encryptingWithKey(it, recoveryKey) }

    /** A backup under a password, as before 1.6.0 (the tests of older backups). */
    fun write(out: OutputStream, passphrase: CharArray, rounds: Int = BackupCrypto.ITERATIONS): BackupSummary =
        write(out) { BackupCrypto.encrypting(it, passphrase, rounds) }

    private fun write(out: OutputStream, encrypting: (OutputStream) -> OutputStream): BackupSummary {
        val rows = mutableMapOf<String, JSONArray>()
        // one consistent picture of the budget, even if a background job writes meanwhile
        db.runInTransaction {
            for (t in tables()) {
                val a = JSONArray()
                sql.query("SELECT * FROM `$t`").use { c -> while (c.moveToNext()) a.put(row(c, t)) }
                rows[t] = a
            }
        }
        val pages = photos?.stored().orEmpty()
        ZipOutputStream(encrypting(out)).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT_NAME).put("version", 1).put("schema", schema()).put("app", appVersion).put("createdAt", clock())
                .put("tables", JSONArray(rows.keys.sorted())).put("photos", pages.size)
            zip.entry("manifest.json", manifest.toString().toByteArray())
            for ((t, a) in rows) zip.entry("tables/$t.json", a.toString().toByteArray())
            settings?.let { zip.entry("settings.json", it.export().toString().toByteArray()) }
            for ((id, page) in pages) {
                // the photo key is this phone's; in the backup a photo is sealed by the backup's own encryption
                val bytes = photos!!.read(id, page) ?: continue
                zip.entry("photos/$id/$page", bytes)
            }
        }
        return BackupSummary(count(rows, "account"), count(rows, "envelope"), count(rows, "txn"), count(rows, "receipt"), pages.size)
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun row(c: Cursor, table: String): JSONObject {
        val o = JSONObject()
        for (i in 0 until c.columnCount) {
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> o.put(c.getColumnName(i), JSONObject.NULL)
                Cursor.FIELD_TYPE_INTEGER -> o.put(c.getColumnName(i), c.getLong(i))
                Cursor.FIELD_TYPE_STRING -> o.put(c.getColumnName(i), c.getString(i))
                else -> error("$table.${c.getColumnName(i)} isn't a whole number or text")
            }
        }
        return o
    }

    /**
     * Replaces the budget, the receipt photos and the carried settings with the backup's. Reads and checks the whole
     * file before changing anything; throws BackupException (nothing changed) when it can't be used.
     */
    fun restore(input: InputStream, passphrase: CharArray): BackupSummary = restore(input, BackupSecret.OldPassword(passphrase))

    /** As above, opened with [secret]: the recovery key (1.6.0), or an older backup's password. */
    fun restore(input: InputStream, opener: BackupSecret): BackupSummary {
        stagingDir.deleteRecursively()
        val staged = photos?.staging(stagingDir)
        var manifest: JSONObject? = null
        val rows = mutableMapOf<String, JSONArray>()
        var settingsJson: JSONObject? = null
        var pageCount = 0
        try {
            val plain = BackupCrypto.decrypting(input, opener)
            ZipInputStream(plain).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    when {
                        e.name == "manifest.json" -> manifest = JSONObject(String(bytes))
                        e.name.startsWith("tables/") && e.name.endsWith(".json") -> rows[e.name.removePrefix("tables/").removeSuffix(".json")] = JSONArray(String(bytes))
                        e.name == "settings.json" -> settingsJson = JSONObject(String(bytes))
                        e.name.startsWith("photos/") -> {
                            val parts = e.name.removePrefix("photos/").split('/')
                            val page = parts.getOrNull(1)?.toIntOrNull()
                            if (parts.size != 2 || page == null || !ID.matches(parts[0])) throw BackupException("The backup file is damaged.")
                            staged?.write(parts[0], page, bytes)
                            pageCount++
                        }
                    }
                }
                // read to the end: the last chunk must be there, and nothing may follow it
                val rest = ByteArray(8192)
                while (plain.read(rest) != -1) { /* past the zip's end: still checked */ }
            }
            val m = manifest ?: throw BackupException("That isn't a Dead Drop Finance backup file.")
            if (m.optString("format") != FORMAT_NAME) throw BackupException("That isn't a Dead Drop Finance backup file.")
            if (m.optInt("schema") > schema()) throw BackupException("This backup was made by a newer version of the app. Update the app, then restore.")
            if (rows["account"] == null || rows["envelope"] == null || rows["txn"] == null) throw BackupException("The backup file is damaged.")
            replace(rows)
        } catch (e: BackupException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: JSONException) {
            stagingDir.deleteRecursively()
            throw BackupException("The backup file is damaged.")
        } catch (e: java.util.zip.ZipException) {
            stagingDir.deleteRecursively()
            throw BackupException("The backup file is damaged.")
        }
        // the budget is in; now the photos and settings
        photos?.replaceWith(stagingDir)
        settingsJson?.let { s -> settings?.import(s) }
        return BackupSummary(count(rows, "account"), count(rows, "envelope"), count(rows, "txn"), count(rows, "receipt"), pageCount)
    }

    /**
     * One transaction: every carried table emptied and filled from the backup (this phone's device id is kept). A
     * backup from before 1.3.0 may carry bank sync's data on its transactions: the bank's ids are dropped and a synced
     * row still pending becomes an ordinary posted one, as the 11 to 12 migration does.
     */
    private fun replace(rows: Map<String, JSONArray>) {
        val here = tables()
        try {
            db.runInTransaction {
                for (t in here) {
                    if (t == "meta") sql.execSQL("DELETE FROM meta WHERE `key` != ?", arrayOf(BudgetStore.META_DEVICE_ID))
                    else sql.execSQL("DELETE FROM `$t`")
                    val a = rows[t] ?: continue
                    val cols = columns(t).toSet()
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        if (t == "meta" && r.optString("key") == BudgetStore.META_DEVICE_ID) continue
                        val v = ContentValues()
                        for (k in r.keys()) {
                            if (k !in cols) continue
                            when (val x = r.get(k)) {
                                JSONObject.NULL -> v.putNull(k)
                                is String -> v.put(k, x)
                                is Int -> v.put(k, x.toLong())
                                is Long -> v.put(k, x)
                                is Boolean -> v.put(k, if (x) 1L else 0L)
                                else -> throw BackupException("The backup file is damaged.")
                            }
                        }
                        sql.insert(t, SQLiteDatabase.CONFLICT_REPLACE, v)
                    }
                }
                sql.execSQL("UPDATE txn SET pending = 0 WHERE source = 'SYNC' AND pending = 1")
                sql.execSQL("UPDATE txn SET external_id = NULL WHERE external_id IS NOT NULL")
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup couldn't be restored: it doesn't fit this version's budget. Nothing was changed.")
        }
        db.invalidationTracker.refreshVersionsAsync()
    }

    companion object {
        const val FORMAT_NAME = "deaddropfinance-backup"
        /**
         * The database's bookkeeping never travels (and bank sync's tables, gone since 1.3.0, never did). Nor does
         * `notif_app`, the bank notification reader's table (reader removed in 1.5.0): kept empty, so an older
         * backup's rows for it are left out of a restore.
         */
        val LEFT_OUT = setOf("connection", "linked_account", "sync_log", "notif_app", "room_master_table", "android_metadata")
        private val ID = Regex("[A-Za-z0-9-]{1,64}")

        /** A backup file's suggested name: deaddrop-yyyy-mm-dd.ddfbak. */
        fun fileName(date: java.time.LocalDate) = BackupNames.fileName(date)
    }
}
