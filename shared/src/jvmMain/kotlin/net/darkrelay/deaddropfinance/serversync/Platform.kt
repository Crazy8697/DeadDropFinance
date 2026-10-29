package net.darkrelay.deaddropfinance.serversync

import androidx.room.Transactor
import androidx.room.useWriterConnection
import net.darkrelay.deaddropfinance.data.AppDatabase

/** libsodium on the desktop's JVM (tests only for now: the desktop program doesn't sync yet and doesn't ship it). */
internal fun newSodium(): com.goterl.lazysodium.Sodium = com.goterl.lazysodium.SodiumJava()

/** One database transaction with plain SQL, on the writer connection (as the desktop's backup restore writes). */
internal suspend fun <R> AppDatabase.rawTransaction(block: suspend (RawSql) -> R): R {
    val r = useWriterConnection { c ->
        c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val tx = this
            block(object : RawSql {
                override suspend fun exec(sql: String, args: List<Any?>) {
                    tx.usePrepared(sql) { s -> bind(s, args); while (s.step()) { } }
                }

                override suspend fun query(sql: String, args: List<Any?>): List<List<Any?>> = tx.usePrepared(sql) { s ->
                    bind(s, args)
                    buildList {
                        while (s.step()) add((0 until s.getColumnCount()).map { i ->
                            when {
                                s.isNull(i) -> null
                                s.getColumnType(i) == SQLITE_INTEGER -> s.getLong(i)
                                else -> s.getText(i)
                            }
                        })
                    }
                }
            })
        }
    }
    invalidationTracker.refreshAsync()
    return r
}

private const val SQLITE_INTEGER = 1

private fun bind(s: androidx.sqlite.SQLiteStatement, args: List<Any?>) {
    args.forEachIndexed { i, a ->
        when (val v = bindable(a)) {
            null -> s.bindNull(i + 1)
            is Long -> s.bindLong(i + 1, v)
            else -> s.bindText(i + 1, v.toString())
        }
    }
}
