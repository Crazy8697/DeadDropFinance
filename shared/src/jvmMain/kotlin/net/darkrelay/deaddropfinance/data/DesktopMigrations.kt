package net.darkrelay.deaddropfinance.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * The program's database migrations (0.15.0): the phone's schema changes ([SchemaChanges]) from version 12, the
 * schema the program's budgets were made with, run on the program's driver. Each only adds; nothing existing changes.
 */
object DesktopMigrations {
    val V12_V13 = object : Migration(12, 13) {
        override fun migrate(connection: SQLiteConnection) {
            SchemaChanges.V12_V13.forEach { connection.execSQL(it) }
        }
    }

    val ALL = arrayOf<Migration>(V12_V13)
}
