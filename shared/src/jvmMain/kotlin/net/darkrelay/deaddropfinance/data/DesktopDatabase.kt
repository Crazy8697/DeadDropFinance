package net.darkrelay.deaddropfinance.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers

/**
 * Opens the desktop program's own budget database: the phone's schema (AppDatabase, the same tables and version),
 * in a file of its own, never the phone's. Room runs on bundled SQLite here. Like the phone, it never deletes or
 * recreates anything on failure: no destructive migration is configured, and a file that can't be opened is reported
 * instead.
 */
object DesktopDatabase {

    const val FILE_NAME = AppDatabase.NAME

    sealed class Opened {
        class Ok(val store: BudgetStore, val file: File) : Opened()
        data class Unavailable(val reason: String) : Opened()
    }

    /** [dir]: the program's data folder (%APPDATA%\DeadDropFinance). The file is made on first use. */
    fun open(dir: File, clock: () -> Long = System::currentTimeMillis): Opened = try {
        dir.mkdirs()
        val file = File(dir, FILE_NAME)
        val db = Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        Opened.Ok(BudgetStore(db, clock), file)
    } catch (t: Throwable) {
        Opened.Unavailable("the budget database in ${dir.name} can't be opened")
    }
}
