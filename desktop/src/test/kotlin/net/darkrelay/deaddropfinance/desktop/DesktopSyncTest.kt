package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.serversync.FakeServer
import net.darkrelay.deaddropfinance.serversync.PairingQr
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import net.darkrelay.deaddropfinance.serversync.hex
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The program's server sync (0.10.0) against the shared tests' in-memory server: no network, test keys, invented
 * budgets in temp folders, and a stand-in for DPAPI. Nothing of the real %APPDATA% or Downloads is touched.
 */
class DesktopSyncTest {

    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val compose = createComposeRule()

    private val pw = "test-pass-for-unit"
    private val fake = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<AppModel>()

    @After fun done() {
        scope.cancel()
        models.forEach { it.engine?.close() }
    }

    /** DPAPI's stand-in: the stored bytes are never the key itself. */
    private object TestProtector : KeyProtector {
        override fun protect(plain: ByteArray) = "TEST".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unprotect(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    inner class Device(val dir: File, val model: AppModel, val kind: String) {
        val prefs = DesktopSyncPrefs(dir)
        val vault = DesktopKeyVault(dir, TestProtector)
        val store = (model.engine!!.opened as DesktopDatabase.Opened.Ok).store
        val keys = net.darkrelay.deaddropfinance.backup.RecoveryKeys(DesktopRecoveryStore(dir, TestProtector)).also { model.recoveryKeys = it }
        val sync = ServerSync(store, prefs, vault, scope, transportFor = { fake.transport() }, kind = kind, sendDelayMs = 60_000, recovery = keys)
        val downloads: File = File(dir, "Downloads")
        val server = ServerModel(sync, model, scope, downloads, defaultDeviceName = "Test desktop").also { model.server = it }
    }

    private fun desktop(filled: Boolean = true): Device {
        val dir = tmp.newFolder()
        val m = if (filled) SampleBudget.filled(dir).model else SampleBudget.empty(dir)
        models += m
        return Device(dir, m, "desktop")
    }

    /** The phone that made the account: its own small budget, uploaded as the master copy. */
    private fun phone(): Device {
        val dir = tmp.newFolder()
        val m = SampleBudget.empty(dir).also { models += it }
        runBlocking { m.actions!!.saveAccount(null, "Phone Checking", AccountType.BANK, 300_00, SampleBudget.today.minusDays(3)) }
        return Device(dir, m, "phone").also { runBlocking { it.sync.createAccount("server.example", "TEST-CODE", "tester", pw, "Test phone") } }
    }

    private fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun accounts(d: Device) = runBlocking { d.store.dao.accountsNow().map { it.name }.sorted() }

    private fun loggedIn(): Pair<Device, Device> {
        val p = phone()
        runBlocking { p.sync.background() }
        val d = desktop()
        runBlocking {
            assertTrue(d.server.connect("server.example"), d.server.error)
            // Log in's button: this computer has a budget and no recovery key, so the key is made and shown first
            d.server.startLogin("tester", pw, "Test desktop", true)
            assertTrue(d.model.dialog is Dialog.ShowRecoveryKey, d.model.dialog.toString())
            d.keys.confirmSaved()
            d.model.dialog = null
            assertTrue(d.server.login("tester", pw, "Test desktop"), d.server.error)
        }
        return p to d
    }

    @Test fun loggingInBacksUpThisComputersBudgetThenTakesTheServersCopy() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        assertEquals(accounts(p), accounts(d), "the server's budget replaced this one")
        assertFalse(accounts(d).contains("Checking"))
        // the backup: Downloads\DeadDropFinance, the shared format, opened with this computer's own recovery key
        val backup = d.downloads.resolve("DeadDropFinance").listFiles()!!.single()
        assertTrue(backup.name.endsWith(".ddfbak"))
        assertTrue(d.model.message!!.first.contains(backup.absolutePath))
        val check = SampleBudget.empty(tmp.newFolder()).also { models += it }
        val ok = check.engine!!.opened as DesktopDatabase.Opened.Ok
        val own = d.keys.old().singleOrNull() ?: d.keys.current()!!
        val s = runBlocking { backup.inputStream().use { DesktopBackups(ok.store.db, ok.file, "test").restore(it, net.darkrelay.deaddropfinance.backup.BackupSecret.Key(own)) } }
        assertTrue(s.accounts >= 4, "the backup holds this computer's own accounts")
        runBlocking { check.refresh() }
        assertTrue(check.budget!!.accounts.any { it.name == "Checking" })
    }

    @Test fun theSettingsAndKeyAreKeptAndNeitherHoldsThePassword() {
        val (_, d) = loggedIn()
        val again = DesktopSyncPrefs(d.dir)
        assertEquals(d.prefs.deviceId, again.deviceId)
        assertEquals("http://server.example:5090", again.serverUrl)
        assertEquals(fake.keys.publicKey.hex(), again.pinned("http://server.example:5090"))
        assertFalse(File(d.dir, DesktopSyncPrefs.FILE).readText().contains(pw))
        val keyFile = File(d.dir, DesktopKeyVault.FILE)
        val key = assertNotNull(d.vault.load())
        assertFalse(keyFile.readBytes().hex().contains(key.hex()), "the key file is protected, not the key itself")
        assertFalse(String(keyFile.readBytes(), Charsets.ISO_8859_1).contains(pw))
        assertEquals("desktop", fake.devices[d.prefs.deviceId]!!.kind)
    }

    @Test fun theEditLockFollowsTheWindow() {
        val (_, d) = loggedIn()
        val me = d.prefs.deviceId
        d.server.shown()
        until("lock taken") { fake.lockHolder == me && fake.lockExpires > System.currentTimeMillis() }
        d.server.hidden()
        until("lock let go when minimized") { fake.lockHolder == null }
        d.server.shown()
        until("lock taken again") { fake.lockHolder == me }
        runBlocking { d.server.closing() }
        assertNull(fake.lockHolder, "closing lets go")
    }

    @Test fun readOnlyWhileThePhoneEditsThenTakeOver() {
        val (p, d) = loggedIn()
        runBlocking { p.sync.foreground() }
        d.server.shown()
        until("read only") { d.server.status.readOnlyBy == "Test phone" }
        val acct = d.model.budget!!.accounts.first().id
        assertFalse(runBlocking { d.model.addTransaction(acct, SampleBudget.today, "Blocked", 5_00, READY_TO_ASSIGN, "") })
        assertTrue(d.model.message!!.first.contains("Read only: Test phone"))
        runBlocking { d.server.takeOver() }
        until("taken over") { fake.lockHolder == d.prefs.deviceId && d.server.status.readOnlyBy == null }
        assertTrue(runBlocking { d.model.addTransaction(acct, SampleBudget.today, "Desk Pay", 5_00, READY_TO_ASSIGN, "") }, d.model.message?.first)
        runBlocking { p.sync.tick() }
        assertEquals("Test desktop", p.sync.status.value.takenOverBy, "the phone turns read only and is told who took over")
        assertEquals("Test desktop", p.sync.status.value.readOnlyBy)
    }

    // ---- restore while logged in (0.12.2): the restored budget becomes the server's master copy

    /** The backup this computer's login made of its own budget (the sample one), and the key that opens it. */
    private fun ownBackup(d: Device): Pair<File, net.darkrelay.deaddropfinance.backup.BackupSecret> {
        val file = d.downloads.resolve("DeadDropFinance").listFiles()!!.single()
        return file to net.darkrelay.deaddropfinance.backup.BackupSecret.Key(assertNotNull(d.model.savedKeyFor(file)))
    }

    @Test fun restoringWhileLoggedInMakesTheBackupTheServersBudgetAndThePhoneTakesIt() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, key) = ownBackup(d)
        assertFalse(accounts(d).contains("Checking"), "this computer has the phone's budget now")
        val before = fake.serverBackups
        assertTrue(runBlocking { d.model.restore(file, key) }, d.model.message?.first)
        assertFalse(d.model.message!!.second, d.model.message?.first)
        assertTrue(d.model.message!!.first.contains("The server's budget is now this backup"))
        assertTrue(accounts(d).contains("Checking"), "the restored budget is here")
        assertTrue(fake.serverBackups > before, "the server backed up its master first")
        assertTrue(fake.records.values.any { it.data.optString("name") == "Checking" }, "and the restored budget is the server's master")
        assertFalse(fake.records.values.any { it.data.optString("name") == "Phone Checking" }, "the phone's old budget is gone from it")
        // the phone takes it at its next sync (nothing is waiting there, so it needs no backup of its own)
        runBlocking { p.sync.syncNow() }
        assertEquals(SyncStatus.State.SYNCED, p.sync.status.value.state, p.sync.status.value.message)
        assertEquals(accounts(d), accounts(p))
        assertTrue(p.sync.notices.value.single().contains("replaced by a backup restored on another device"))
    }

    @Test fun anOlderPasswordBackupRestoresWhileLoggedInToo() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val old = tmp.newFile("older-backup.ddfbak")
        // an older backup of a different budget (made before 0.11.0, so opened with its password)
        val other = SampleBudget.filled(tmp.newFolder()).also { models.add(it.model) }
        val ok = other.model.engine!!.opened as DesktopDatabase.Opened.Ok
        runBlocking { old.outputStream().use { DesktopBackups(ok.store.db, ok.file, "test").write(it, "older pass 1".toCharArray(), rounds = 1_000) } }
        assertFalse(runBlocking { d.model.restore(old, net.darkrelay.deaddropfinance.backup.BackupSecret.OldPassword("wrong password".toCharArray())) })
        assertTrue(d.model.message!!.second)
        assertFalse(fake.records.values.any { it.data.optString("name") == "Checking" }, "a wrong password changes nothing, here or on the server")
        assertTrue(runBlocking { d.model.restore(old, net.darkrelay.deaddropfinance.backup.BackupSecret.OldPassword("older pass 1".toCharArray())) }, d.model.message?.first)
        assertTrue(fake.records.values.any { it.data.optString("name") == "Checking" })
        runBlocking { p.sync.syncNow() }
        assertTrue(accounts(p).contains("Checking"))
    }

    @Test fun anotherDeviceEditingStopsTheRestoreUntilThisComputerTakesOver() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, key) = ownBackup(d)
        runBlocking { p.sync.foreground() }
        val before = accounts(d)
        assertFalse(runBlocking { d.model.restore(file, key) })
        assertTrue(d.model.message!!.second)
        assertTrue(d.model.message!!.first.contains("Test phone is editing") && d.model.message!!.first.contains("Take over editing"), d.model.message?.first)
        assertEquals(before, accounts(d), "nothing changed here")
        assertFalse(fake.records.values.any { it.data.optString("name") == "Checking" }, "nor on the server")
        d.server.shown()                                     // the window is in front, so the lock stays with this computer
        runBlocking { d.server.takeOver() }
        until("taken over") { fake.lockHolder == d.prefs.deviceId }
        assertTrue(runBlocking { d.model.restore(file, key) }, d.model.message?.first)
        assertTrue(fake.records.values.any { it.data.optString("name") == "Checking" })
    }

    @Test fun aServerThatCantBackUpRefusesTheRestoreAndNothingChanges() {
        val (_, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, key) = ownBackup(d)
        val before = accounts(d)
        fake.backupWorks = false
        assertFalse(runBlocking { d.model.restore(file, key) })
        assertTrue(d.model.message!!.second)
        assertTrue(d.model.message!!.first.contains("Nothing was changed"), d.model.message?.first)
        assertEquals(before, accounts(d))
        assertFalse(fake.records.values.any { it.data.optString("name") == "Checking" })
    }

    @Test fun loggedOutARestoreStaysLocalAsBefore() {
        val (_, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, key) = ownBackup(d)
        runBlocking { d.server.logout() }
        val ops = fake.ops.size
        assertTrue(runBlocking { d.model.restore(file, key) }, d.model.message?.first)
        assertFalse(d.model.message!!.first.contains("server's budget"))
        assertTrue(accounts(d).contains("Checking"))
        assertEquals(ops, fake.ops.size, "no call to the server")
    }

    @Test fun theConfirmScreenWarnsAndCancelChangesNothing() {
        val (_, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, _) = ownBackup(d)
        val before = accounts(d)
        d.model.dialog = Dialog.Restore(file, confirm = true)
        compose.setContent { DesktopApp(d.model, Shell(run = { block -> runBlocking { block() } })) }
        tag("dialog.confirmRestore").assertExists()
        assertTrue(textOf("restore.serverWarning").startsWith("This replaces the budget on your server. Every device on this account will get this backup instead of what it has now."))
        assertTrue(textOf("restore.serverWarning").contains("The server keeps a backup of the current budget first."))
        compose.onNodeWithTag("dialog.confirm").assertTextEquals("Replace server budget")
        compose.onNodeWithTag("dialog.cancel").assertTextEquals("Cancel")
        tag("dialog.cancel").performClick()
        compose.waitForIdle()
        assertNull(d.model.dialog)
        assertEquals(before, accounts(d))
        assertEquals(0, fake.serverBackups, "nothing was asked of the server")
        assertFalse(fake.ops.contains("master.check") || fake.ops.contains("master.replace"))
    }

    @Test fun replaceServerBudgetRestoresAndTheLockHolderMustBeTakenOverFirst() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        val (file, _) = ownBackup(d)
        runBlocking { p.sync.foreground() }
        d.server.shown()
        until("read only") { d.server.status.readOnlyBy == "Test phone" }
        d.model.dialog = Dialog.Restore(file, confirm = true)
        compose.setContent { DesktopApp(d.model, Shell(run = { block -> runBlocking { block() } })) }
        tag("restore.lockedBy").assertExists()
        tag("dialog.confirm").assertHasNoClickAction()          // a disabled button has no click action
        tag("restore.takeOver").performClick()
        until("taken over") { d.server.status.readOnlyBy == null && fake.lockHolder == d.prefs.deviceId }
        compose.waitForIdle()
        tag("dialog.confirm").assertHasClickAction()
        tag("dialog.confirm").performClick()
        until("restored") { fake.records.values.any { it.data.optString("name") == "Checking" } }
        assertTrue(accounts(d).contains("Checking"))
    }

    @Test fun loggingOutLetsGoRemovesThisComputerAndDeletesTheKey() {
        val (_, d) = loggedIn()
        val me = d.prefs.deviceId!!
        d.server.shown()
        until("lock taken") { fake.lockHolder == me }
        val before = accounts(d)
        runBlocking { d.server.logout() }
        assertNull(fake.lockHolder)
        assertNotNull(fake.devices[me]!!.revokedAt)
        assertFalse(File(d.dir, DesktopKeyVault.FILE).exists())
        assertFalse(d.server.connected)
        assertEquals(before, accounts(d), "the budget stays")
    }

    @Test fun addAPhoneShowsAOneTimeQrCode() {
        val (_, d) = loggedIn()
        runBlocking { assertTrue(d.server.newPairingCode(), d.server.error) }
        val p = assertNotNull(d.server.pairing)
        val info = assertNotNull(PairingQr.parse(p.qr))
        assertEquals(p.code.code, info.code)
        assertEquals("http://server.example:5090", info.serverUrl)
        assertEquals(d.sync.fingerprint, info.fingerprint)
        assertTrue(p.expiresAt - System.currentTimeMillis() in 590_000..600_000)
        assertFalse(p.qr.contains(pw))
        assertFalse(p.qr.contains(fake.keys.publicKey.hex()))
        // the drawn code reads back as exactly that content
        val m = qrMatrix(p.qr)
        val scale = 6
        val w = m.width * scale
        val px = IntArray(w * w) { i -> if (m[i % w / scale, i / w / scale]) 0xFF000000.toInt() else -1 }
        assertEquals(p.qr, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, px)))).text)
        // a phone redeems it once
        val phone2 = desktop(filled = false).let { Device(it.dir, it.model, "phone") }
        runBlocking { phone2.sync.pair(info, "Test tablet") { error("nothing to back up") } }
        assertTrue(phone2.sync.connected)
    }

    // ---- the screens

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun textOf(t: String): String =
        tag(t).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }

    @Test fun theServerPanelLogsInAndShowsAddAPhoneWithItsCountdown() {
        val p = phone()
        runBlocking { p.sync.background() }
        val d = desktop()
        val shell = Shell(run = { block -> runBlocking { block() } })
        d.model.go(Screen.SETTINGS)
        compose.setContent { DesktopApp(d.model, shell) }
        tag("serverPanel").assertExists()
        tag("syncPillText").assertExists()
        runBlocking { d.server.connect("server.example") }
        compose.waitForIdle()
        tag("server.fingerprint").assertExists()
        compose.waitUntil(10_000) { runCatching { tag("server.localData").assertExists() }.isSuccess }
        // the recovery key, made and saved first (the panel's Log in shows it; here it's done directly)
        d.keys.ensure(); d.keys.confirmSaved()
        runBlocking { d.server.login("tester", pw, "Test desktop") }
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        compose.waitForIdle()
        // Settings is taller since the currency row (0.12.0): scroll the button into the window first
        tag("server.addPhone").performScrollTo().performClick()
        compose.waitUntil(10_000) { d.server.pairing != null }
        compose.waitForIdle()
        tag("pair.code").assertExists()
        tag("qr").assertExists()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("pair.expiry", useUnmergedTree = true).assertExists() }.isSuccess }
        val expiry = compose.onNodeWithTag("pair.expiry", useUnmergedTree = true).fetchSemanticsNode().config
            .first { it.key.name == "Text" }.value.toString()
        assertTrue(Regex("Expires in (10:00|9:[0-5]\\d)").containsMatchIn(expiry), expiry)
    }

    @Test fun theReadOnlyBannerOffersTakeOverWithAConfirmation() {
        val (p, d) = loggedIn()
        runBlocking { p.sync.foreground() }
        d.server.shown()
        until("read only") { d.server.status.readOnlyBy == "Test phone" }
        compose.setContent { DesktopApp(d.model, Shell(run = { block -> runBlocking { block() } })) }
        tag("readOnly").assertExists()
        tag("takeOver").performClick()
        compose.waitForIdle()
        tag("dialog.takeOver").assertExists()
        tag("dialog.confirm").performClick()
        compose.waitUntil(10_000) { fake.lockHolder == d.prefs.deviceId && d.server.status.readOnlyBy == null }
        compose.waitForIdle()
        tag("readOnly").assertDoesNotExist()
    }
}
