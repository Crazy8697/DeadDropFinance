package net.darkrelay.deaddropfinance.serversync

import android.database.Cursor
import androidx.room.withTransaction
import net.darkrelay.deaddropfinance.data.AppDatabase

/** libsodium on the phone: lazysodium's Android build, with the library for each ABI in its aar. */
internal fun newSodium(): com.goterl.lazysodium.Sodium = com.goterl.lazysodium.SodiumAndroid()

/** One database transaction with plain SQL, on the phone's SQLCipher database (as backup restore writes it). */
internal suspend fun <R> AppDatabase.rawTransaction(block: suspend (RawSql) -> R): R {
    val r = withTransaction {
        val s = openHelper.writableDatabase
        block(object : RawSql {
            override suspend fun exec(sql: String, args: List<Any?>) = s.execSQL(sql, args.map(::bindable).toTypedArray())
            override suspend fun query(sql: String, args: List<Any?>): List<List<Any?>> =
                s.query(sql, args.map(::bindable).toTypedArray()).use { c ->
                    buildList {
                        while (c.moveToNext()) add((0 until c.columnCount).map { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                                else -> c.getString(i)
                            }
                        })
                    }
                }
        })
    }
    invalidationTracker.refreshVersionsAsync()
    return r
}
