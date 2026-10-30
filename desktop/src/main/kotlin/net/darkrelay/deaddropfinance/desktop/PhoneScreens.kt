package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.UpdateInstallEvent
import net.darkrelay.deaddropfinance.backup.BackupSecret
import net.darkrelay.deaddropfinance.backup.BackupSummary
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.Dpapi
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.ReceiptFiles
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.domain.SetupExample
import net.darkrelay.deaddropfinance.ui.AppRoot
import net.darkrelay.deaddropfinance.ui.AppSettings
import net.darkrelay.deaddropfinance.ui.DdfTheme
import net.darkrelay.deaddropfinance.ui.DeviceWords
import net.darkrelay.deaddropfinance.ui.LocalDeviceWords
import net.darkrelay.deaddropfinance.ui.MoreState
import net.darkrelay.deaddropfinance.ui.Nav
import net.darkrelay.deaddropfinance.ui.Platform
import net.darkrelay.deaddropfinance.ui.ProgramHost
import net.darkrelay.deaddropfinance.ui.budgetFlow

/**
 * The phone app's own screens in the program (0.13.0): Home, Bills, Categories, More, and the screens the program's
 * own screens open (To sort, a transaction's details, an envelope's details, the fill order). They are the phone's
 * code (the :ui module's AppRoot), on the program's budget, so the fields, confirms, wording and sync are the
 * phone's. What they need from the device comes from [ProgramPlatform] and [ProgramAppSettings].
 */
@Composable
fun PhoneScreens(model: AppModel, shell: Shell, start: Nav, onExit: () -> Unit = {}) {
    val ok = model.engine?.opened as? DesktopDatabase.Opened.Ok ?: return
    val platform = remember(ok) { ProgramPlatform(model, shell, ok.file.parentFile) }
    val settings = remember(ok) { ProgramAppSettings(shell.settings ?: Settings(ok.file.parentFile)) }
    // the program's own screens read the budget again after these screens change it
    LaunchedEffect(ok.store) { budgetFlow(ok.store).drop(1).collect { withContext(Dispatchers.IO) { model.refresh() } } }
    DdfTheme {
        CompositionLocalProvider(LocalDeviceWords provides PROGRAM_WORDS) {
            Box(Modifier.fillMaxSize().background(net.darkrelay.deaddropfinance.ui.DdfColors.Background).testTag("phone.${start.javaClass.simpleName}")) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxHeight().align(Alignment.TopCenter)) {
                    // keyed by where it opens: a new start is a new set of screens
                    androidx.compose.runtime.key(start, model.phoneGeneration) {
                        AppRoot(
                            ok.store, settings, platform, startupUpdateCheck = false, sync = model.server?.sync,
                            host = ProgramHost(start, onExit = onExit),
                        )
                    }
                }
            }
        }
    }
}

/** "computer" and "program", no app lock, no camera: the words and parts the phone's screens adapt to here. */
val PROGRAM_WORDS = DeviceWords(device = "computer", product = "program", appLock = false)

/** The settings the phone's screens keep, in the program's settings file (Settings). */
class ProgramAppSettings(private val s: Settings) : AppSettings {
    override var setupDone by s.flag("ui.setupDone", true)
    override var setupAfterPurge by s.flag("ui.setupAfterPurge", false)
    override var setupCardDismissed by s.flag("ui.setupCardDismissed", true)
    override var autoApprove by s.flag("ui.autoApprove", false)
    override var alertAmountsOnLockScreen by s.flag("ui.alertAmounts", false)
    override var alertsAsked by s.flag("ui.alertsAsked", true)
    override var alertBills by s.flag("ui.alertBills", true)
    override var alertAutopay by s.flag("ui.alertAutopay", true)
    override var lastBackupAt by s.number("ui.lastBackupAt", 0L)
    override var lastBackupName by s.text("ui.lastBackupName", "")
    override var reminded by s.keys("ui.reminded")
}

/** The receipt photo key, protected for this Windows user (DPAPI), as the budget's own key is. */
class ProtectorWrapper(private val p: KeyProtector = Dpapi) : KeyWrapper {
    override fun wrap(plain: ByteArray) = p.protect(plain)
    override fun unwrap(blob: ByteArray) = p.unprotect(blob)
}

/**
 * What the phone's screens ask of the device, on Windows: Open and Save As dialogs (the Shell's), the browser,
 * receipt photos kept encrypted in the data folder, and Windows notifications for bill reminders. Backups, the
 * server, updates and app lock are the program's own (Settings, Sync, the update bar), so those screens aren't shown.
 */
class ProgramPlatform(private val model: AppModel, private val shell: Shell, private val dir: File) : Platform {

    private val secrets by lazy { SecretStore(File(dir, "secrets.bin"), ProtectorWrapper(shell.keyProtector)) }
    val receiptImages by lazy { ReceiptImages(File(dir, "receipts"), { ReceiptImages.keyFrom(secrets) }) }

    override fun openUrl(url: String) = shell.onOpenPage(url)

    override fun pickCsv(onPicked: (name: String, text: String?) -> Unit) {
        val f = shell.pickCsv() ?: return
        onPicked(f.name, runCatching { f.readText(Charsets.UTF_8) }.getOrNull())
    }

    override fun pickJson(onPicked: (name: String, text: String?) -> Unit) {
        val f = shell.pickJson() ?: return
        onPicked(f.name, runCatching { f.readText(Charsets.UTF_8) }.getOrNull())
    }

    override fun saveCsv(name: String, text: String, done: (Boolean) -> Unit) {
        val f = shell.pickSave(name) ?: return done(false)
        done(runCatching { f.writeText(text, Charsets.UTF_8) }.isSuccess)
    }

    override fun exportSetupExample(done: (Boolean) -> Unit) {
        val f = shell.pickSave("deaddropfinance-setup-example.json") ?: return done(false)
        val doc = javaClass.getResourceAsStream("/docs/IMPORT.md")?.use { String(it.readBytes(), Charsets.UTF_8) }
        val json = doc?.let { SetupExample.extract(it) }
        done(json != null && runCatching { f.writeText(json, Charsets.UTF_8) }.isSuccess)
    }

    // the program has its own update bar and checks (Help, Check for updates); the phone's are never shown here
    override fun downloadAndInstallUpdate(onEvent: (UpdateInstallEvent) -> Unit) = onEvent(UpdateInstallEvent.Done(false, null))
    override fun isMeteredNetwork() = false
    override fun updateDownloadSize(onSize: (Long?) -> Unit) = onSize(null)
    override fun openInstallPermissionSettings() {}
    override fun checkForUpdates(manual: Boolean, done: (UpdateCheck.Release?, String?) -> Unit) = done(null, null)
    override fun skipVersion(version: String) {}

    override fun eraseEverything() = shell.onErase()
    override fun purgeData() = shell.onPurge()

    override val more: MoreState
        get() = MoreState(
            lockEnabled = false, lockAvailable = false, timeoutMinutes = 5, hideScreen = false,
            checkAtStartup = shell.checkAtStartup, checking = false, updateMessage = null, version = BuildInfo.VERSION,
        )
    override fun setLock(on: Boolean) {}
    override fun setTimeout(minutes: Int) {}
    override fun setHide(on: Boolean) {}
    override fun setCheckAtStartup(on: Boolean) = shell.onCheckAtStartup(on)

    override fun notificationsAllowed() = shell.notices.allowed()
    override fun requestNotifications(done: (Boolean) -> Unit) = done(shell.notices.allowed())

    // no camera on the program: a receipt comes from a file
    override fun takeReceiptPhoto(done: (File?) -> Unit) = done(null)
    override fun pickReceiptFile(done: (File?) -> Unit) = done(shell.pickReceipt())

    /**
     * Keeps the picked image as the receipt's next page, encrypted. The program has no text recognition (the phone's
     * reader is Android only), so nothing is read from it: the form opens for the store, total and items to be typed.
     */
    override suspend fun scanReceipt(uri: File, receiptId: String, firstPage: Int): List<List<ReceiptParser.Line>> = withContext(Dispatchers.IO) {
        val bytes = uri.readBytes()
        javax.imageio.ImageIO.read(bytes.inputStream()) ?: throw java.io.IOException("not an image")
        receiptImages.write(receiptId, firstPage, bytes)
        listOf(emptyList())
    }

    override suspend fun receiptPage(receiptId: String, page: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val bytes = receiptImages.read(receiptId, page) ?: return@withContext null
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

    override val receiptFiles: ReceiptFiles get() = receiptImages
    override fun sweepReceiptFiles(keep: Set<String>) { runCatching { receiptImages.sweep(keep) } }

    // About's Licenses: the notices the installed program carries
    override fun docs(): List<String> = shell.docs()
    override fun readDoc(name: String): String? {
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        // the privacy policy and the end user agreement are in the program itself, as in the phone's
        return shell.readDoc(name) ?: if (name in setOf("PRIVACY.md", "TERMS.md"))
            javaClass.getResourceAsStream("/docs/$name")?.use { String(it.readBytes(), Charsets.UTF_8) } else null
    }

    // backups and the server are the program's own (File, Back up; Sync): never reached from these screens
    override fun createBackupFile(name: String, done: (File?) -> Unit) = done(null)
    override fun pickBackupFile(done: (File?) -> Unit) = done(null)
    override fun displayName(uri: File): String = uri.name
    override val recoveryKeys get() = model.recoveryKeys
    override suspend fun writeBackup(uri: File): BackupSummary = throw UnsupportedOperationException("the program's own backup")
    override suspend fun restoreBackup(file: File, opener: BackupSecret): BackupSummary = throw UnsupportedOperationException("the program's own restore")
    override suspend fun backupBeforeServer(key: ByteArray): String = throw UnsupportedOperationException("the program's own sync")

    @Composable
    override fun appIcon() = androidx.compose.ui.res.painterResource("deaddropfinance-256.png")
}
