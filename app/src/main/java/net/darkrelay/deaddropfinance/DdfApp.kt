package net.darkrelay.deaddropfinance

import android.app.Application
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.data.Eraser
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
    @Volatile private var secretStore: SecretStore? = null

    /** Bank credentials and the receipt photo key, each wrapped by its own Keystore key. */
    fun secrets(): SecretStore = secretStore ?: synchronized(this) {
        secretStore ?: SecretStore(File(File(filesDir, "keys"), SecretStore.FILE_NAME), KeystoreWrapper(SECRETS_KEY_ALIAS)).also { secretStore = it }
    }

    /** Receipt photos, encrypted in the app's private files. */
    val receiptImages: net.darkrelay.deaddropfinance.data.ReceiptImages by lazy {
        net.darkrelay.deaddropfinance.data.ReceiptImages(receiptDir()) { net.darkrelay.deaddropfinance.data.ReceiptImages.keyFrom(secrets()) }
    }

    fun receiptDir() = File(filesDir, "receipts")

    /** Backup and restore over the open budget; a restore stages photos in the cache first. */
    fun backups(store: net.darkrelay.deaddropfinance.data.BudgetStore) = net.darkrelay.deaddropfinance.backup.Backups(
        store.db, receiptImages, File(cacheDir, "restore"),
        net.darkrelay.deaddropfinance.backup.SettingsTransfer(listOf("sync", "lock", "updates").associateWith { getSharedPreferences(it, MODE_PRIVATE) }),
        BuildConfig.VERSION_NAME,
    )

    /** The camera writes its photo here (cache), and the app deletes it as soon as it's read. */
    fun captureDir() = File(cacheDir, "scan")

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
                val secrets = secrets()
                val settings = SyncSettings(this)
                // before anything reads the connection table: clear what the removed Teller provider left (0.7.0 installs)
                runCatching { RemovedProviderCleanup(o.store, secrets, settings) { has -> SyncScheduler.apply(this, settings, has) }.run() }
                SyncService(o.store, secrets, settings).also { service = it }
            }
        }
    }

    /**
     * Erase everything and start over: stops sync, closes the database, then removes the database, the keys folder,
     * both Keystore keys, every settings file and scheduled work. The next open is a fresh install's. Returns what
     * couldn't be removed (empty when all went).
     */
    suspend fun eraseEverything(): List<String> = openLock.withLock {
        service?.shutdown()
        service = null
        // stop reading bank notifications (the listener is switched off, not just its setting)
        runCatching { net.darkrelay.deaddropfinance.alerts.BankNotificationListener.setEnabled(this, false) }
        (opened as? DatabaseOpener.Opened.Ok)?.store?.db?.close()
        opened = null
        val left = Eraser(
            databaseFile = getDatabasePath(net.darkrelay.deaddropfinance.data.AppDatabase.NAME),
            keysDir = File(filesDir, "keys"),
            keystoreAliases = listOf(KeystoreWrapper.ALIAS, SECRETS_KEY_ALIAS),
            deleteKeystoreKey = { alias -> java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) },
            settings = listOf("lock", "sync", "updates").map { getSharedPreferences(it, MODE_PRIVATE) },
            cancelScheduledWork = { androidx.work.WorkManager.getInstance(this).cancelAllWork() },
            extraDirs = listOf(receiptDir(), net.darkrelay.deaddropfinance.scan.TesseractReader.ocrDir(this), captureDir(), File(cacheDir, "restore")),
        ).erase()
        secretStore = null
        left
    }

    /**
     * Purge data: everything but the SimpleFIN connection (FEATURES.md). The database stays open; the next start of
     * the screens opens the setup wizard with the connection kept.
     */
    suspend fun purgeData() = openLock.withLock {
        val o = opened as? DatabaseOpener.Opened.Ok ?: return@withLock
        // no sync may write while the budget is emptied: the running one stops, and a fresh one starts after
        service?.shutdown()
        service = null
        runCatching { net.darkrelay.deaddropfinance.alerts.BankNotificationListener.setEnabled(this, false) }
        net.darkrelay.deaddropfinance.data.Purger(
            o.store.db, secrets(), listOf("lock", "sync", "updates").map { getSharedPreferences(it, MODE_PRIVATE) },
            listOf(receiptDir(), captureDir(), File(cacheDir, "restore")),
        ).purge()
        val settings = SyncSettings(this)
        settings.setupAfterPurge = true
        SyncScheduler.apply(this, settings, o.store.sync.connectionsNow().isNotEmpty())
    }

    companion object {
        /** Its own Keystore key, separate from the database key's (KeystoreWrapper.ALIAS). */
        const val SECRETS_KEY_ALIAS = "ddf_secrets_key_wrap"
    }
}
