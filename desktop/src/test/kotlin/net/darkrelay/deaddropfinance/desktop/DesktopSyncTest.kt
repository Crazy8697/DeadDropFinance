package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        val sync = ServerSync(store, prefs, vault, scope, transportFor = { fake.transport() }, kind = kind, sendDelayMs = 60_000)
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
            assertTrue(d.server.login("tester", pw, "Test desktop"), d.server.error)
        }
        return p to d
    }

    @Test fun loggingInBacksUpThisComputersBudgetThenTakesTheServersCopy() {
        val (p, d) = loggedIn()
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        assertEquals(accounts(p), accounts(d), "the server's budget replaced this one")
        assertFalse(accounts(d).contains("Checking"))
        // the backup: Downloads\DeadDropFinance, the shared format, opened with the account password
        val backup = d.downloads.resolve("DeadDropFinance").listFiles()!!.single()
        assertTrue(backup.name.endsWith(".ddfbak"))
        assertTrue(d.model.message!!.first.contains(backup.absolutePath))
        val check = SampleBudget.empty(tmp.newFolder()).also { models += it }
        val ok = check.engine!!.opened as DesktopDatabase.Opened.Ok
        val s = runBlocking { backup.inputStream().use { DesktopBackups(ok.store.db, ok.file, "test").restore(it, pw.toCharArray()) } }
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

    @Test fun restoreWaitsForALogOutWhileConnected() {
        val (_, d) = loggedIn()
        assertFalse(d.model.canRestore())
        assertTrue(d.model.message!!.first.startsWith("Log out of the server"))
        assertFalse(runBlocking { d.model.restore(File(d.dir, "none.ddfbak"), "whatever") })
        runBlocking { d.server.logout() }
        assertTrue(d.model.canRestore())
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
        runBlocking { phone2.sync.pair(info, "Test tablet", null) { error("nothing to back up") } }
        assertTrue(phone2.sync.connected)
    }

    // ---- the screens

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)

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
        runBlocking { d.server.login("tester", pw, "Test desktop") }
        until("synced") { d.server.status.state == SyncStatus.State.SYNCED }
        compose.waitForIdle()
        tag("server.addPhone").performClick()
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
