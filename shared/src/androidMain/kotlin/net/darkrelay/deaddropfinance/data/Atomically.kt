package net.darkrelay.deaddropfinance.data

import androidx.room.withTransaction

/** One database transaction around [block]. On the phone this is Room's withTransaction, exactly as before. */
internal suspend fun <R> AppDatabase.atomically(block: suspend () -> R): R = withTransaction { block() }
