package net.darkrelay.deaddropfinance

import android.app.Application
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.darkrelay.deaddropfinance.data.BankSyncCleanup
import net.darkrelay.deaddropfinance.data.DatabaseOpener
import net.darkrelay.deaddropfinance.data.Eraser
import net.darkrelay.deaddropfinance.data.KeystoreWrapper
import net.darkrelay.deaddropfinance.data.ReaderRemoval
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.lock.AppLock
import net.darkrelay.deaddropfinance.sync.SyncSettings

/**
 * Holds what lives as long as the process: the app lock (it watches the whole app go to the background) and the one
 * open database (the screens and the background jobs share it, so screens see their writes at once).
 */
class DdfApp : Application() {
    lateinit var lock: AppLock
        private set

    private val openLock = Mutex()
    @Volatile private var opened: DatabaseOpener.Opened? = null
    @Volatile private var cleaned = false
    @Volatile private var secretStore: SecretStore? = null

    /** The receipt photo key, wrapped by its own Keystore key. */
    fun secrets(): SecretStore = secretStore ?: synchronized(this) {
        secretStore ?: SecretStore(File(File(filesDir, "keys"), SecretStore.FILE_NAME), KeystoreWrapper(SECRETS_KEY_ALIAS)).also { secretStore = it }
    }

    /** Server sync (1.4.0): one for the process, made once the budget is open; its lock follows the app in front. */
    @Volatile private var serverSync: net.darkrelay.deaddropfinance.serversync.ServerSync? = null
    private var syncLifecycle: net.darkrelay.deaddropfinance.serversync.SyncLifecycle? = null
    private val syncScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /**
     * Stay logged in off (1.6.0): the app is closing (Back out of it, or it was swiped away and Android said so), so it
     * logs out, with a few seconds for the server. If Android stops the app without a word, [openingCheck] finishes it
     * on the next open.
     */
    fun closingSync() {
        val s = serverSync ?: return
        syncScope.launch { kotlinx.coroutines.withTimeoutOrNull(6_000) { s.closing() } }
    }

    /** On opening, before anything is shown: a log out the last close didn't finish (Stay logged in off). */
    suspend fun openingCheck(store: net.darkrelay.deaddropfinance.data.BudgetStore) {
        val s = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { serverSync(store) }
        kotlinx.coroutines.withTimeoutOrNull(6_000) { s.openingCheck() }
    }

    /** This phone's recovery keys (1.6.0): every backup's key, in SecretStore. */
    val recoveryKeys: net.darkrelay.deaddropfinance.backup.RecoveryKeys by lazy {
        net.darkrelay.deaddropfinance.backup.RecoveryKeys(net.darkrelay.deaddropfinance.serversync.PhoneRecoveryStore { secrets() })
    }

    /** Call on the main thread (it watches the app come and go). */
    fun serverSync(store: net.darkrelay.deaddropfinance.data.BudgetStore): net.darkrelay.deaddropfinance.serversync.ServerSync =
        serverSync ?: synchronized(this) {
            serverSync ?: net.darkrelay.deaddropfinance.serversync.ServerSync(
                store, net.darkrelay.deaddropfinance.serversync.ServerPrefs(this),
                net.darkrelay.deaddropfinance.serversync.ServerKeyVault { secrets() }, syncScope,
                transportFor = { url ->
                    net.darkrelay.deaddropfinance.serversync.LocalNetworkTransport(net.darkrelay.deaddropfinance.serversync.HttpTransport(url)) {
                        net.darkrelay.deaddropfinance.serversync.LocalNetwork.allowed(this)
                    }
                },
                recovery = recoveryKeys,
            ).also { s ->
                serverSync = s
                syncLifecycle = net.darkrelay.deaddropfinance.serversync.SyncLifecycle(s, syncScope).also { it.attach() }
            }
        }

    /**
     * Before a login or QR pairing replaces this phone's budget with the server's: an encrypted backup of it (the
     * phone's backup format, opened with this phone's recovery key, 1.6.0) in Downloads/DeadDropFinance. Returns where it went.
     */
    suspend fun backupBeforeServer(store: net.darkrelay.deaddropfinance.data.BudgetStore, key: ByteArray): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val name = "deaddrop-before-server-${java.time.LocalDate.now()}-${System.currentTimeMillis() % 100000}.ddfbak"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/DeadDropFinance")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("The backup couldn't be saved to Downloads, so nothing was replaced.")
            try {
                (contentResolver.openOutputStream(uri) ?: throw java.io.IOException("The backup couldn't be saved to Downloads, so nothing was replaced."))
                    .use { backups(store).write(it, key) }
                contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
            "Downloads/DeadDropFinance/$name"
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
        opened ?: DatabaseOpener.open(this).also { o ->
            opened = o
            // amounts show in the budget's currency (1.7.0), in the app and in its reminders alike
            if (o is DatabaseOpener.Opened.Ok) o.store.applyCurrency()
        }
    }

    /**
     * The app's settings, once the database is open; null if the budget can't be opened on this phone. The first call
     * removes what bank sync (removed in 1.3.0) left outside the database: its saved login, settings and background job;
     * and what the bank notification reader (removed in 1.5.0) left: its settings, picked apps and notification channels.
     */
    suspend fun settings(): SyncSettings? {
        val ok = open() as? DatabaseOpener.Opened.Ok ?: return null
        val settings = SyncSettings(this)
        if (!cleaned) openLock.withLock {
            if (!cleaned) {
                runCatching { BankSyncCleanup(secrets(), settings) { androidx.work.WorkManager.getInstance(this).cancelUniqueWork(it) }.run() }
                runCatching {
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    ReaderRemoval(ok.store.dao, settings) { nm.deleteNotificationChannel(it) }.run()
                }
                cleaned = true
            }
        }
        return settings
    }

    /**
     * Erase everything and start over: closes the database, then removes the database, the keys folder,
     * both Keystore keys, every settings file and scheduled work. The next open is a fresh install's. Returns what
     * couldn't be removed (empty when all went).
     */
    suspend fun eraseEverything(): List<String> = openLock.withLock {
        // off the server first, while the key is still here: the lock goes and this phone leaves the device list
        serverSync?.let { s -> runCatching { s.logout() } }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { syncLifecycle?.detach() }
        syncLifecycle = null
        serverSync = null
        (opened as? DatabaseOpener.Opened.Ok)?.store?.db?.close()
        opened = null
        val left = Eraser(
            databaseFile = getDatabasePath(net.darkrelay.deaddropfinance.data.AppDatabase.NAME),
            keysDir = File(filesDir, "keys"),
            keystoreAliases = listOf(KeystoreWrapper.ALIAS, SECRETS_KEY_ALIAS),
            deleteKeystoreKey = { alias -> java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) },
            settings = listOf("lock", "sync", "updates", net.darkrelay.deaddropfinance.serversync.ServerPrefs.FILE).map { getSharedPreferences(it, MODE_PRIVATE) },
            cancelScheduledWork = { androidx.work.WorkManager.getInstance(this).cancelAllWork() },
            extraDirs = listOf(receiptDir(), net.darkrelay.deaddropfinance.scan.TesseractReader.ocrDir(this), captureDir(), File(cacheDir, "restore")),
        ).erase()
        secretStore = null
        left
    }

    /** Purge data (FEATURES.md). The database stays open; the next start of the screens opens the setup wizard. */
    suspend fun purgeData() = openLock.withLock {
        val o = opened as? DatabaseOpener.Opened.Ok ?: return@withLock
        net.darkrelay.deaddropfinance.data.Purger(
            o.store.db, secrets(), listOf("lock", "sync", "updates").map { getSharedPreferences(it, MODE_PRIVATE) },
            listOf(receiptDir(), captureDir(), File(cacheDir, "restore")),
        ).purge()
        SyncSettings(this).setupAfterPurge = true
    }

    companion object {
        /** Its own Keystore key, separate from the database key's (KeystoreWrapper.ALIAS). */
        const val SECRETS_KEY_ALIAS = "ddf_secrets_key_wrap"
    }
}
