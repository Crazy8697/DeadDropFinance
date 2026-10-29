package net.darkrelay.deaddropfinance.data

import androidx.room.Transactor
import androidx.room.useWriterConnection

/**
 * One database transaction around [block]. On the desktop Room has no withTransaction: the writer connection's
 * immediate transaction is its equivalent, and the DAO calls inside [block] run on that same connection.
 */
internal suspend fun <R> AppDatabase.atomically(block: suspend () -> R): R =
    useWriterConnection { it.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() } }
