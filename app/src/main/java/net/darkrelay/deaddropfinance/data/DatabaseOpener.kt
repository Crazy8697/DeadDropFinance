package net.darkrelay.deaddropfinance.data

import android.content.Context
import androidx.room.Room
import java.io.File
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the encrypted budget database (Room on SQLCipher, keyed with the database key from DbKeys). Never deletes
 * or recreates anything on failure: the app shows that the data can't be opened instead.
 */
object DatabaseOpener {

    sealed class Opened {
        class Ok(val store: BudgetStore) : Opened()
        data class Unavailable(val reason: String) : Opened()
    }

    fun open(context: Context, wrapper: KeyWrapper = KeystoreWrapper()): Opened {
        val app = context.applicationContext
        val dbFile = app.getDatabasePath(AppDatabase.NAME)
        val keys = DbKeys(File(app.filesDir, "keys"), wrapper)
        val key = when (val r = keys.loadOrCreate(databaseExists = dbFile.exists())) {
            is DbKeys.Result.Unavailable -> return Opened.Unavailable(r.reason)
            is DbKeys.Result.Ok -> r.key
        }
        return try {
            System.loadLibrary("sqlcipher")
            val password = DbKeys.rawKeyPassword(key)
            key.fill(0)
            val db = Room.databaseBuilder(app, AppDatabase::class.java, AppDatabase.NAME)
                .openHelperFactory(SupportOpenHelperFactory(password))
                .addMigrations(*Migrations.ALL)
                .build()
            db.openHelper.writableDatabase      // open now, so a wrong key shows up here and not later
            Opened.Ok(BudgetStore(db))
        } catch (t: Throwable) {
            key.fill(0)
            Opened.Unavailable("the database can't be opened with this phone's key")
        }
    }
}
