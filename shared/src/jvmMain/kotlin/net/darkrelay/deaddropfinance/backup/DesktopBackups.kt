package net.darkrelay.deaddropfinance.backup

import androidx.room.Transactor
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteStatement
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Backup and restore for the desktop program (0.8.0), in the phone's backup format (app/.../backup/Backups.kt): one
 * file encrypted with [BackupCrypto] (AES-256-GCM; from 0.11.0 under the recovery key, before that under a password
 * whose backups still restore with it), holding a zip
 * with manifest.json and one tables/<table>.json per budget table. The desktop has no receipt photos and no phone
 * settings, so it writes neither and ignores both in a phone's backup. No key is stored anywhere: a lost password is a
 * lost backup.
 *
 * Restore reads and checks the whole file first (password, every table); a bad file or a wrong password changes
 * nothing. Then it keeps a copy of the current budget file beside it ([BEFORE_RESTORE], still encrypted), replaces
 * every table in one transaction, checks the rows that went in against the backup before committing, and only then
 * removes the copy. If anything fails the transaction rolls back and the budget is as it was.
 */
class DesktopBackups(
    private val db: AppDatabase,
    private val dbFile: File,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Summary(val accounts: Int, val envelopes: Int, val transactions: Int)

    /** Writes the backup to [out] (closed when done), encrypted under the recovery key (0.11.0). */
    suspend fun write(out: OutputStream, recoveryKey: ByteArray): Summary = write(out) { BackupCrypto.encryptingWithKey(it, recoveryKey) }

    /** A backup under a password, as before 0.11.0 (the tests of older backups). */
    suspend fun write(out: OutputStream, passphrase: CharArray, rounds: Int = BackupCrypto.ITERATIONS): Summary =
        write(out) { BackupCrypto.encrypting(it, passphrase, rounds) }

    private suspend fun write(out: OutputStream, encrypting: (OutputStream) -> OutputStream): Summary {
        // one consistent picture of the budget
        val (schema, rows) = db.useWriterConnection { c ->
            c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val v = usePrepared("PRAGMA user_version") { it.step(); it.getLong(0).toInt() }
                val names = usePrepared("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { s ->
                    buildList { while (s.step()) add(s.getText(0)) }
                }.filter { it !in LEFT_OUT && !it.startsWith("sqlite_") }
                v to names.associateWith { t -> usePrepared("SELECT * FROM `$t`") { s -> JSONArray().also { a -> while (s.step()) a.put(row(s, t)) } } }
            }
        }
        ZipOutputStream(encrypting(out)).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT_NAME).put("version", 1).put("schema", schema).put("app", appVersion).put("createdAt", clock())
                .put("tables", JSONArray(rows.keys.sorted())).put("photos", 0)
            zip.entry("manifest.json", manifest.toString().toByteArray())
            for ((t, a) in rows) zip.entry("tables/$t.json", a.toString().toByteArray())
        }
        return summary(rows)
    }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun row(s: SQLiteStatement, table: String): JSONObject {
        val o = JSONObject()
        for (i in 0 until s.getColumnCount()) {
            val name = s.getColumnName(i)
            when (s.getColumnType(i)) {
                SQLITE_NULL -> o.put(name, JSONObject.NULL)
                SQLITE_INTEGER -> o.put(name, s.getLong(i))
                SQLITE_TEXT -> o.put(name, s.getText(i))
                else -> error("$table.$name isn't a whole number or text")
            }
        }
        return o
    }

    private fun count(rows: Map<String, JSONArray>, table: String): Int {
        val a = rows[table] ?: return 0
        return (0 until a.length()).count { i -> a.getJSONObject(i).optInt("deleted", 0) == 0 }
    }

    private fun summary(rows: Map<String, JSONArray>) = Summary(count(rows, "account"), count(rows, "envelope"), count(rows, "txn"))

    fun read(input: InputStream, passphrase: CharArray, currentSchema: Int): Map<String, JSONArray> =
        read(input, BackupSecret.OldPassword(passphrase), currentSchema)

    /** Reads and checks a backup without changing anything (the recovery key, or an older backup's password). Throws BackupException when it can't be used. */
    fun read(input: InputStream, opener: BackupSecret, currentSchema: Int): Map<String, JSONArray> {
        var manifest: JSONObject? = null
        val rows = mutableMapOf<String, JSONArray>()
        try {
            val plain = BackupCrypto.decrypting(input, opener)
            ZipInputStream(plain).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val bytes = zip.readBytes()
                    when {
                        e.name == "manifest.json" -> manifest = JSONObject(String(bytes))
                        e.name.startsWith("tables/") && e.name.endsWith(".json") -> rows[e.name.removePrefix("tables/").removeSuffix(".json")] = JSONArray(String(bytes))
                        // a phone's backup also carries its settings and receipt photos: the desktop has neither
                        else -> {}
                    }
                }
                // read to the end: the last chunk must be there, and nothing may follow it
                val rest = ByteArray(8192)
                while (plain.read(rest) != -1) { /* past the zip's end: still checked */ }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: JSONException) {
            throw BackupException("The backup file is damaged.")
        } catch (e: java.util.zip.ZipException) {
            throw BackupException("The backup file is damaged.")
        } catch (e: java.io.IOException) {
            throw BackupException("The backup file can't be read.")
        }
        val m = manifest ?: throw BackupException("That isn't a Dead Drop Finance backup file.")
        if (m.optString("format") != FORMAT_NAME) throw BackupException("That isn't a Dead Drop Finance backup file.")
        if (m.optInt("schema") > currentSchema) throw BackupException("This backup was made by a newer version. Update the program, then restore.")
        if (rows["account"] == null || rows["envelope"] == null || rows["txn"] == null) throw BackupException("The backup file is damaged.")
        return rows
    }

    /**
     * Replaces the budget with the backup's. [failForTest]: fail after the rows went in, before the commit (tests of
     * the rollback only).
     */
    suspend fun restore(input: InputStream, passphrase: CharArray, failForTest: Boolean = false): Summary =
        restore(input, BackupSecret.OldPassword(passphrase), failForTest)

    /** Replaces the budget with the backup's, opened with [secret] (the recovery key, or an older backup's password). */
    suspend fun restore(input: InputStream, opener: BackupSecret, failForTest: Boolean = false): Summary {
        val schema = db.useWriterConnection { c -> c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0).toInt() } }
        val rows = read(input, opener, schema)
        // a copy of the current budget, kept until the restore is checked
        val copy = File(dbFile.absoluteFile.parentFile, dbFile.name + BEFORE_RESTORE)
        db.useWriterConnection { c -> c.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { while (it.step()) { } } }
        Files.copy(dbFile.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
        try {
            replace(rows, failForTest)
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup couldn't be restored: it doesn't fit this version's budget. Nothing was changed.")
        } finally {
            // checked and committed, or rolled back to exactly what was there: the copy isn't needed either way
            // (if the program stops mid-restore, it stays beside the budget)
            copy.delete()
        }
        return summary(rows)
    }

    /**
     * Purge data (desktop 0.13.0), as the phone's: every budget table emptied in one transaction, checked before it
     * commits (a restore of an empty budget); this computer's id stays.
     */
    suspend fun purge() = replace(emptyMap(), failForTest = false)

    private suspend fun replace(rows: Map<String, JSONArray>, failForTest: Boolean) {
        db.useWriterConnection { c ->
            c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val here = usePrepared("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { s ->
                    buildList { while (s.step()) add(s.getText(0)) }
                }.filter { it !in LEFT_OUT && !it.startsWith("sqlite_") }
                val expected = mutableMapOf<String, Int>()
                for (t in here) {
                    if (t == "meta") usePrepared("DELETE FROM meta WHERE `key` != ?") { it.bindText(1, BudgetStore.META_DEVICE_ID); it.step() }
                    else usePrepared("DELETE FROM `$t`") { it.step() }
                    val cols = usePrepared("PRAGMA table_info(`$t`)") { s ->
                        val i = (0 until s.getColumnCount()).first { s.getColumnName(it) == "name" }
                        buildSet { while (s.step()) add(s.getText(i)) }
                    }
                    val a = rows[t] ?: JSONArray()
                    var n = 0
                    for (i in 0 until a.length()) {
                        val r = a.getJSONObject(i)
                        if (t == "meta" && r.optString("key") == BudgetStore.META_DEVICE_ID) continue
                        val keys = r.keys().asSequence().filter { it in cols }.toList()
                        if (keys.isEmpty()) continue
                        val sql = "INSERT OR REPLACE INTO `$t` (${keys.joinToString(",") { "`$it`" }}) VALUES (${keys.joinToString(",") { "?" }})"
                        usePrepared(sql) { s ->
                            keys.forEachIndexed { k, key ->
                                when (val x = r.get(key)) {
                                    JSONObject.NULL -> s.bindNull(k + 1)
                                    is String -> s.bindText(k + 1, x)
                                    is Int -> s.bindLong(k + 1, x.toLong())
                                    is Long -> s.bindLong(k + 1, x)
                                    is Boolean -> s.bindLong(k + 1, if (x) 1L else 0L)
                                    else -> throw BackupException("The backup file is damaged.")
                                }
                            }
                            s.step()
                        }
                        n++
                    }
                    expected[t] = n
                }
                usePrepared("UPDATE txn SET pending = 0 WHERE source = 'SYNC' AND pending = 1") { it.step() }
                usePrepared("UPDATE txn SET external_id = NULL WHERE external_id IS NOT NULL") { it.step() }
                // check before committing: every table holds exactly the backup's rows (meta keeps this computer's id)
                for ((t, n) in expected) {
                    val have = usePrepared(if (t == "meta") "SELECT count(*) FROM meta WHERE `key` != ?" else "SELECT count(*) FROM `$t`") { s ->
                        if (t == "meta") s.bindText(1, BudgetStore.META_DEVICE_ID)
                        s.step(); s.getLong(0).toInt()
                    }
                    if (have != n) throw BackupException("The restored budget didn't check out. Nothing was changed.")
                }
                if (failForTest) throw BackupException("The restored budget didn't check out. Nothing was changed.")
            }
        }
    }

    companion object {
        /** The phone's (Backups.FORMAT_NAME): a backup from either opens on the other. */
        const val FORMAT_NAME = "deaddropfinance-backup"
        /** The database's bookkeeping never travels (as the phone's Backups.LEFT_OUT). */
        val LEFT_OUT = setOf("connection", "linked_account", "sync_log", "room_master_table", "android_metadata")
        const val BEFORE_RESTORE = ".before-restore"
        private const val SQLITE_INTEGER = 1
        private const val SQLITE_TEXT = 3
        private const val SQLITE_NULL = 5

        /** A backup file's suggested name, as the phone's: deaddrop-yyyy-mm-dd.ddfbak. */
        fun fileName(date: java.time.LocalDate) = "deaddrop-$date.ddfbak"
    }
}
