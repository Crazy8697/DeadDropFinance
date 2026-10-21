package net.darkrelay.deaddropfinance

import android.app.Application
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.data.KeystoreWrapper
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.lock.AppLock
import net.darkrelay.deaddropfinance.sync.RemovedProviderCleanup
import net.darkrelay.deaddropfinance.sync.SyncScheduler
import net.darkrelay.deaddropfinance.sync.SyncService
import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Holds what lives as long as the process: the app lock (it watches the whole app go to the background), the one
 * open database (the screens and background sync share it, so screens see sync's writes at once) and bank sync.
 */
class DdfApp : Application() {
    lateinit var lock: AppLock
        private set

    private val openLock = Mutex()
    @Volatile private var opened: DatabaseOpener.Opened? = null
    @Volatile private var service: SyncService? = null

    override fun onCreate() {
        super.onCreate()
        lock = AppLock(this).also { it.attach() }
    }

    /** Opens the database once. Call off the main thread. */
    suspend fun open(): DatabaseOpener.Opened = openLock.withLock {
        opened ?: DatabaseOpener.open(this).also { opened = it }
    }

    /** Bank sync, once the database is open; null if the budget can't be opened on this phone. */
    suspend fun syncService(): SyncService? {
        service?.let { return it }
        val o = open() as? DatabaseOpener.Opened.Ok ?: return null
        return openLock.withLock {
            service ?: run {
                val secrets = SecretStore(File(File(filesDir, "keys"), SecretStore.FILE_NAME), KeystoreWrapper(SECRETS_KEY_ALIAS))
                val settings = SyncSettings(this)
                // before anything reads the connection table: clear what the removed Teller provider left (0.7.0 installs)
                runCatching { RemovedProviderCleanup(o.store, secrets, settings) { has -> SyncScheduler.apply(this, settings, has) }.run() }
                SyncService(o.store, secrets, settings).also { service = it }
            }
        }
    }

    companion object {
        /** Its own Keystore key, separate from the database key's (KeystoreWrapper.ALIAS). */
        const val SECRETS_KEY_ALIAS = "ddf_secrets_key_wrap"
    }
}
