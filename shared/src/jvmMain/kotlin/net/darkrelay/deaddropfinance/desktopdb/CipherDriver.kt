package net.darkrelay.deaddropfinance.desktopdb

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import org.sqlite.SQLiteConfig
import org.sqlite.mc.SQLiteMCSqlCipherConfig

/**
 * Room's SQLite driver for the desktop's encrypted budget: SQLite3 Multiple Ciphers (through its JDBC driver) in its
 * SQLCipher 4 mode, with the 32-byte database key used as a raw key, the same form the phone gives SQLCipher
 * (DbKeys.rawKeyPassword: x'<64 hex>', no key derivation). [key] hands out a fresh copy of the key for each
 * connection; it is wiped once the connection is keyed. A null [key] opens plain, unencrypted SQLite: only for reading
 * a 0.6.0 budget and for tests, never for a budget the program keeps.
 *
 * JDBC keeps its own idea of a transaction, so the transaction statements Room sends (BEGIN, COMMIT or END, ROLLBACK)
 * go through JDBC's own calls; savepoints and everything else run as they are.
 */
class CipherDriver(private val key: (() -> ByteArray)?) : SQLiteDriver {

    override fun open(fileName: String): SQLiteConnection {
        val k = key?.invoke()
        try {
            val url = "jdbc:sqlite:$fileName"
            val conn = if (k == null) java.sql.DriverManager.getConnection(url)
            else SQLiteMCSqlCipherConfig.getV4Defaults().withRawUnsaltedKey(k).build().createConnection(url)
            return JdbcConnection(conn)
        } catch (e: SQLException) {
            throw SQLiteException("can't open the database: ${e.message}")
        } finally {
            k?.fill(0)
        }
    }

    private class JdbcConnection(val c: Connection) : SQLiteConnection {
        override fun inTransaction(): Boolean = !c.autoCommit
        override fun prepare(sql: String): SQLiteStatement {
            val head = sql.trimStart().take(40).uppercase()
            return when {
                head.startsWith("BEGIN") -> TxStatement(this) {
                    val mode = when {
                        head.startsWith("BEGIN IMMEDIATE") -> SQLiteConfig.TransactionMode.IMMEDIATE
                        head.startsWith("BEGIN EXCLUSIVE") -> SQLiteConfig.TransactionMode.EXCLUSIVE
                        else -> SQLiteConfig.TransactionMode.DEFERRED
                    }
                    (c as org.sqlite.SQLiteConnection).setCurrentTransactionMode(mode)
                    c.autoCommit = false
                }
                head.startsWith("COMMIT") || head.startsWith("END") -> TxStatement(this) { c.autoCommit = true }
                head.startsWith("ROLLBACK") && !head.startsWith("ROLLBACK TO") -> TxStatement(this) {
                    c.rollback()
                    c.autoCommit = true
                }
                else -> try {
                    JdbcStatement(c.prepareStatement(sql))
                } catch (e: SQLException) {
                    throw SQLiteException("${e.message} (in: ${sql.take(60)})")
                }
            }
        }

        override fun close() {
            try {
                c.close()
            } catch (e: SQLException) {
                throw SQLiteException(e.message ?: "close failed")
            }
        }
    }

    /** BEGIN, COMMIT or ROLLBACK: one action on step, no rows, no parameters. */
    private class TxStatement(val conn: JdbcConnection, val action: () -> Unit) : SQLiteStatement {
        override fun step(): Boolean {
            try {
                action()
            } catch (e: SQLException) {
                throw SQLiteException(e.message ?: "transaction failed")
            }
            return false
        }
        override fun bindBlob(index: Int, value: ByteArray) = noParams()
        override fun bindDouble(index: Int, value: Double) = noParams()
        override fun bindLong(index: Int, value: Long) = noParams()
        override fun bindText(index: Int, value: String) = noParams()
        override fun bindNull(index: Int) = noParams()
        override fun getBlob(index: Int): ByteArray = noRow()
        override fun getDouble(index: Int): Double = noRow()
        override fun getLong(index: Int): Long = noRow()
        override fun getText(index: Int): String = noRow()
        override fun isNull(index: Int): Boolean = noRow()
        override fun getColumnCount() = 0
        override fun getColumnName(index: Int): String = noRow()
        override fun getColumnType(index: Int): Int = noRow()
        override fun reset() {}
        override fun clearBindings() {}
        override fun close() {}
        private fun noParams(): Nothing = throw SQLiteException("a transaction statement takes no parameters")
        private fun noRow(): Nothing = throw SQLiteException("a transaction statement has no rows")
    }

    private class JdbcStatement(val ps: PreparedStatement) : SQLiteStatement {
        private var rs: ResultSet? = null
        private var executed = false
        private var row: Array<Any?>? = null

        private inline fun <T> sql(block: () -> T): T = try {
            block()
        } catch (e: SQLException) {
            throw SQLiteException(e.message ?: "SQLite error")
        }

        override fun step(): Boolean = sql {
            if (!executed) {
                executed = true
                rs = if (ps.execute()) ps.resultSet else null
            }
            val r = rs ?: return@sql false
            if (!r.next()) {
                row = null
                return@sql false
            }
            // read the whole row now: JDBC reads columns by their declared type, SQLite by the value's own type
            row = Array(r.metaData.columnCount) { r.getObject(it + 1) }
            true
        }

        private fun value(index: Int): Any? {
            val r = row ?: throw SQLiteException("no row: step() first")
            if (index < 0 || index >= r.size) throw SQLiteException("column $index out of range")
            return r[index]
        }

        override fun getLong(index: Int): Long = when (val v = value(index)) {
            null -> 0L
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: v.toDoubleOrNull()?.toLong() ?: 0L
            is ByteArray -> 0L
            else -> 0L
        }

        override fun getDouble(index: Int): Double = when (val v = value(index)) {
            null -> 0.0
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        override fun getText(index: Int): String = when (val v = value(index)) {
            null -> ""
            is ByteArray -> String(v, Charsets.UTF_8)
            is Double -> v.toString()
            else -> v.toString()
        }

        override fun getBlob(index: Int): ByteArray = when (val v = value(index)) {
            null -> ByteArray(0)
            is ByteArray -> v
            else -> v.toString().toByteArray(Charsets.UTF_8)
        }

        override fun isNull(index: Int): Boolean = value(index) == null

        override fun getColumnType(index: Int): Int = when (value(index)) {
            null -> 5                       // SQLITE_NULL
            is Int, is Long, is Short, is Byte -> 1   // SQLITE_INTEGER
            is Float, is Double -> 2        // SQLITE_FLOAT
            is ByteArray -> 4               // SQLITE_BLOB
            else -> 3                       // SQLITE_TEXT
        }

        // the statement's result columns, read once from the prepared statement (they never change for its SQL)
        private val names: List<String> by lazy {
            try {
                val md = ps.metaData ?: return@lazy emptyList()
                (1..md.columnCount).map { md.getColumnName(it) }
            } catch (e: SQLException) {
                emptyList()
            }
        }

        override fun getColumnCount(): Int = row?.size ?: names.size
        override fun getColumnName(index: Int): String = names.getOrNull(index) ?: throw SQLiteException("column $index out of range")

        override fun bindBlob(index: Int, value: ByteArray) = sql { ps.setBytes(index, value) }
        override fun bindDouble(index: Int, value: Double) = sql { ps.setDouble(index, value) }
        override fun bindLong(index: Int, value: Long) = sql { ps.setLong(index, value) }
        override fun bindText(index: Int, value: String) = sql { ps.setString(index, value) }
        override fun bindNull(index: Int) = sql { ps.setNull(index, Types.NULL) }

        override fun reset() {
            sql { rs?.close() }
            rs = null
            row = null
            executed = false
        }

        override fun clearBindings() = sql { ps.clearParameters() }

        override fun close() {
            sql {
                rs?.close()
                ps.close()
            }
        }
    }
}
