package net.darkrelay.deaddropfinance.data

import androidx.room.Room
import androidx.room.useReaderConnection
import net.darkrelay.deaddropfinance.desktopdb.CipherDriver
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Opens the desktop program's own budget database: the phone's schema (AppDatabase, the same tables and version 12),
 * in a file of its own, never the phone's, encrypted like the phone's (SQLCipher 4 format with a raw 32-byte key) with
 * the key protected by Windows DPAPI for the current user ([DesktopKey], budget.key beside it in the data folder).
 *
 * Never deletes or recreates anything on failure. An encrypted budget whose key is missing or can't be unlocked is
 * reported, never replaced. A plain budget.db left by 0.6.0 is copied into a new encrypted file and checked, and only
 * then removed ([migratePlain]); if anything fails, the plain file stays exactly where it was and nothing is opened.
 */
object DesktopDatabase {

    const val FILE_NAME = AppDatabase.NAME
    const val KEY_FILE = "budget.key"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    sealed class Opened {
        class Ok(val store: BudgetStore, val file: File, val migrated: Boolean = false) : Opened()
        data class Unavailable(val reason: String) : Opened()
    }

    /** [dir]: the program's data folder (%APPDATA%\DeadDropFinance). The file and its key are made on first use. */
    fun open(dir: File, clock: () -> Long = System::currentTimeMillis, protector: KeyProtector = Dpapi): Opened = try {
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        val keys = DesktopKey(File(dir, KEY_FILE), protector)
        var migrated = false
        val keyResult = when {
            file.exists() && isPlain(file) -> {
                val r = if (keys.file.exists()) keys.load() else keys.create()
                if (r is DesktopKey.Result.Ok) {
                    val failed = migratePlain(file, r.key)
                    if (failed != null) {
                        r.key.fill(0)
                        return Opened.Unavailable(failed)
                    }
                    migrated = true
                }
                r
            }
            file.exists() -> keys.load()
            keys.file.exists() -> keys.load()
            else -> keys.create()
        }
        when (keyResult) {
            is DesktopKey.Result.Unavailable -> Opened.Unavailable(keyResult.reason)
            is DesktopKey.Result.Ok -> {
                val key = keyResult.key
                val db = build(file, key)
                key.fill(0)
                // open now, so a wrong key shows up here and not later
                val ok = try {
                    runBlocking { db.useReaderConnection { c -> c.usePrepared("SELECT count(*) FROM sqlite_master") { it.step() } } }
                } catch (e: Exception) {
                    false
                }
                if (!ok) {
                    db.close()
                    Opened.Unavailable("the budget can't be opened with this computer's key")
                } else {
                    Opened.Ok(BudgetStore(db, clock), file, migrated)
                }
            }
        }
    } catch (t: Throwable) {
        Opened.Unavailable("the budget database in ${dir.name} can't be opened")
    }

    private fun build(file: File, key: ByteArray): AppDatabase {
        val copy = key.copyOf()
        return Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(CipherDriver { copy.copyOf() })
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    fun isPlain(file: File): Boolean = try {
        file.inputStream().use { s -> s.readNBytes(PLAIN_HEADER.size).contentEquals(PLAIN_HEADER) }
    } catch (e: Exception) {
        false
    }

    /**
     * Copies the plain budget [file] into a new encrypted file, checks the copy holds exactly the same rows, then puts
     * the encrypted copy in its place and deletes the plain file. -> null when done, else the reason; on failure the
     * plain file is untouched (it is only ever read) and the half-made copy is removed.
     */
    internal fun migratePlain(file: File, key: ByteArray, failAfterCopy: Boolean = false): String? {
        val dir = file.absoluteFile.parentFile
        val tmp = File(dir, "$FILE_NAME.encrypting")
        val old = File(dir, "$FILE_NAME.plain")
        fun cleanTmp() = listOf(tmp, File(tmp.path + "-wal"), File(tmp.path + "-shm"), File(tmp.path + "-journal")).forEach { it.delete() }
        cleanTmp()
        try {
            // 1. an exact plain copy (VACUUM INTO reads the WAL too and keeps user_version), then encrypt that copy
            plain(file).use { c -> c.createStatement().use { it.execute("VACUUM INTO '${tmp.absolutePath.replace("'", "''")}'") } }
            plain(tmp).use { c ->
                c.createStatement().use { s ->
                    s.execute("PRAGMA cipher='sqlcipher'")
                    s.execute("PRAGMA legacy=4")
                    s.executeQuery("PRAGMA rekey=\"${DesktopKey.rawKeyText(key)}\"").close()
                }
            }
            if (isPlain(tmp)) return fail(tmp, "the copy of your budget didn't encrypt", ::cleanTmp)
            // 2. the encrypted copy holds exactly what the plain file holds
            val want = plain(file).use { fingerprint(it) }
            val got = encrypted(tmp, key).use { c ->
                val ok = c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { r -> r.next() && r.getString(1) == "ok" } }
                if (!ok) return fail(tmp, "the encrypted copy of your budget failed its check", ::cleanTmp)
                fingerprint(c)
            }
            if (failAfterCopy || want != got) return fail(tmp, "the encrypted copy of your budget didn't match it", ::cleanTmp)
            // 3. swap: the plain file steps aside, the encrypted copy takes its name, then the plain file goes
            listOf("-wal", "-shm").forEach { File(file.path + it).let { f -> if (f.exists()) return fail(tmp, "the budget is still in use", ::cleanTmp) } }
            if (old.exists()) old.delete()
            if (!file.renameTo(old)) return fail(tmp, "the budget file couldn't be moved aside", ::cleanTmp)
            if (!tmp.renameTo(file)) {
                old.renameTo(file)
                return fail(tmp, "the encrypted budget couldn't be put in place", ::cleanTmp)
            }
            old.delete()
            return null
        } catch (e: Exception) {
            if (!file.exists() && old.exists()) old.renameTo(file)
            cleanTmp()
            return "your budget couldn't be encrypted (${e.javaClass.simpleName}); the unencrypted file is kept as it was"
        }
    }

    private inline fun fail(@Suppress("UNUSED_PARAMETER") tmp: File, reason: String, clean: () -> Unit): String {
        clean()
        return "$reason; the unencrypted file is kept as it was"
    }

    private fun plain(f: File): Connection = DriverManager.getConnection("jdbc:sqlite:${f.absolutePath}")

    private fun encrypted(f: File, key: ByteArray): Connection {
        val k = key.copyOf()
        try {
            return org.sqlite.mc.SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(k).build().createConnection("jdbc:sqlite:${f.absolutePath}")
        } finally {
            k.fill(0)
        }
    }

    /** user_version, the schema, and every table's rows (sorted, so row order doesn't matter), as one SHA-256. */
    private fun fingerprint(c: Connection): String {
        val md = MessageDigest.getInstance("SHA-256")
        c.createStatement().use { s ->
            s.executeQuery("PRAGMA user_version").use { r -> r.next(); md.update("uv=${r.getInt(1)};".toByteArray()) }
            val tables = s.executeQuery("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name").use { r ->
                buildList { while (r.next()) { md.update("${r.getString(1)}|${r.getString(2)}|${r.getString(3)};".toByteArray()); if (r.getString(1) == "table") add(r.getString(2)) } }
            }
            for (t in tables) {
                val rows = s.executeQuery("SELECT * FROM \"${t.replace("\"", "\"\"")}\"").use { r ->
                    val n = r.metaData.columnCount
                    buildList {
                        while (r.next()) add((1..n).joinToString("\u0001") { i -> r.getObject(i)?.let { v -> if (v is ByteArray) v.joinToString(",") else "${v.javaClass.simpleName}:$v" } ?: "NULL" })
                    }
                }
                md.update("$t:${rows.size};".toByteArray())
                rows.sorted().forEach { md.update(it.toByteArray()); md.update(0) }
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
