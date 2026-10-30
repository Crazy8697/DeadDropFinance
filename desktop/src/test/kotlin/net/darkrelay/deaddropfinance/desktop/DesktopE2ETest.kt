package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.serversync.Box
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.KeyVault
import net.darkrelay.deaddropfinance.serversync.PairingQr
import net.darkrelay.deaddropfinance.serversync.ServerClient
import net.darkrelay.deaddropfinance.serversync.ServerException
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncPrefs
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import net.darkrelay.deaddropfinance.serversync.Transport
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 26 end to end against a real, throwaway Dead Drop server (never the live one): the desktop program's own sync
 * (ServerModel, its settings file and key vault) and phones on invented budgets in temp folders, over real HTTP with
 * real envelopes. Skipped unless DDF_E2E_URL and DDF_E2E_CODE (that test server's setup code) are set; the server must
 * be new, since the first phone creates its one account.
 */
class DesktopE2ETest {

    @get:Rule val tmp = TemporaryFolder()

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)

    /** A transport that can be cut, as a device losing its connection. */
    class Cuttable(private val inner: Transport) : Transport {
        @Volatile var online = true
        override fun get(path: String) = if (online) inner.get(path) else throw IOException("cut")
        override fun post(path: String, body: ByteArray) = if (online) inner.post(path, body) else throw IOException("cut")
    }

    private object TestProtector : KeyProtector {
        override fun protect(plain: ByteArray) = "TEST".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unprotect(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    class MemoryPrefs : SyncPrefs {
        override var serverUrl: String? = null
        override var serverKey: String? = null
        override var deviceId: String? = null
        override var deviceName: String? = null
        override var username: String? = null
        override var phase: String? = null
        override var pushedSeq = 0L
        override var pulledSeq = 0L
        override var lastSyncedAt = 0L
        override var conflictsSeenAt = 0L
        override var revoked = false
        override var stayLoggedIn = true
        val pins = mutableMapOf<String, String>()
        override fun pinned(url: String) = pins[url]
        override fun pin(url: String, keyHex: String) { pins[url] = keyHex }
    }

    class MemoryVault : KeyVault {
        var key: ByteArray? = null
        override fun load() = key
        override fun save(key: ByteArray) { this.key = key.copyOf() }
        override fun delete() { key = null }
    }

    /** A phone: the shared sync on its own invented budget. */
    inner class Phone(val model: AppModel, val scope: CoroutineScope) {
        val store = (model.engine!!.opened as DesktopDatabase.Opened.Ok).store
        val prefs = MemoryPrefs()
        val vault = MemoryVault()
        val net = Cuttable(HttpTransport(url))
        var sync = start()
        fun start() = ServerSync(store, prefs, vault, scope, transportFor = { net }, kind = "phone", sendDelayMs = 60_000)
    }

    private fun until(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    /** A budget on the real clock (the server refuses change times far in the future, as the sample budget's 2030 is). */
    private fun model(dir: File, own: Boolean = false): AppModel = runBlocking {
        val m = AppModel(Engine.start(dir))
        if (own) m.actions!!.saveAccount(null, "Zzlocal Desk Checking", AccountType.BANK, 120_00, java.time.LocalDate.now().minusDays(2))
        m.refresh()
        m
    }

    private fun names(m: AppModel) = runBlocking { (m.engine!!.opened as DesktopDatabase.Opened.Ok).store.dao.accountsNow().map { it.name }.sorted() }
    private fun payees(m: AppModel) = runBlocking { (m.engine!!.opened as DesktopDatabase.Opened.Ok).store.dao.transactionsNow().map { it.payee }.sorted() }

    @Test fun theDesktopAndAPairedPhoneThroughARealServer() = runBlocking<Unit> {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val models = mutableListOf<AppModel>()
        try {
            // 1. a phone creates the account; its budget is the server's copy
            val a = Phone(model(tmp.newFolder("A")).also { models += it }, scope)
            a.model.actions!!.saveAccount(null, "E2E Phone Checking", AccountType.BANK, 700_00, java.time.LocalDate.now().minusDays(5))
            a.sync.createAccount(url, code, "e2e", pw, "E2E phone A")
            assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state, a.sync.status.value.message)
            a.sync.background()

            // 2. the desktop logs in: its own budget is backed up (account password), then replaced by the server's
            val dDir = tmp.newFolder("D")
            val d = model(dDir, own = true).also { models += it }
            val dNet = Cuttable(HttpTransport(url))
            val dSync = ServerSync((d.engine!!.opened as DesktopDatabase.Opened.Ok).store, DesktopSyncPrefs(dDir), DesktopKeyVault(dDir, TestProtector), scope,
                transportFor = { dNet }, kind = "desktop", sendDelayMs = 60_000,
                // 0.11.0: its own budget's backup before the login uses its recovery key (made and saved first)
                recovery = net.darkrelay.deaddropfinance.backup.RecoveryKeys(net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore())
                    .also { d.recoveryKeys = it; it.ensure(); it.confirmSaved() })
            val desk = ServerModel(dSync, d, scope, File(dDir, "Downloads"), defaultDeviceName = "E2E desktop").also { d.server = it }
            assertTrue(desk.connect(url), desk.error)
            assertEquals(a.sync.fingerprint, desk.contacted!!.second)
            assertFalse(desk.login("e2e", "wrong-password-e2e", "E2E desktop"), "a wrong password is refused")
            assertTrue(desk.login("E2E", pw, "E2E desktop"), desk.error)
            assertEquals(names(a.model), names(d), "the server's budget replaced the desktop's")
            // the backup comes before the login is tried (as on the phone), so the refused attempt made one too
            assertEquals(2, File(dDir, "Downloads/DeadDropFinance").listFiles()!!.size, "the desktop's own budget was backed up first")
            assertEquals("desktop", desk.devices.single { it.id == dSync.deviceId }.kind)

            // 3. Add a phone: the desktop's QR content pairs a new phone, once
            assertTrue(desk.newPairingCode(), desk.error)
            val qr = assertNotNull(desk.pairing).qr
            val p = Phone(model(tmp.newFolder("P")).also { models += it }, scope)
            assertNull(p.sync.pair(PairingQr.parse(qr)!!, "E2E phone P") { error("nothing to back up") })
            assertEquals(SyncStatus.State.SYNCED, p.sync.status.value.state, p.sync.status.value.message)
            assertEquals(names(d), names(p.model))
            val again = Phone(model(tmp.newFolder("Q")).also { models += it }, scope)
            assertEquals(ServerClient.PAIR_REFUSED, assertFailsWith<ServerException> { again.sync.pair(PairingQr.parse(qr)!!, "Again") { "" } }.message)
            val forged = PairingQr.parse(PairingQr.encode(url, Box.fingerprint(Box.keyPair().publicKey), PairingQr.parse(qr)!!.code))!!
            assertEquals(ServerClient.QR_MISMATCH, assertFailsWith<ServerException> { again.sync.pair(forged, "Forged") { "" } }.message)

            // 4. edits both ways: the desktop's window is open, so it edits; minimized, the phone does
            desk.shown()
            until("desktop holds the lock") { dSync.holdsEditLock }
            val checking = d.budget!!.accounts.first().id
            assertTrue(d.addTransaction(checking, java.time.LocalDate.now(), "E2E Desk Pay", 25_00, READY_TO_ASSIGN, ""), d.message?.first)
            dSync.syncNow()
            p.sync.syncNow()
            assertTrue(payees(p.model).contains("E2E Desk Pay"), "the desktop's edit reached the phone: desk ${dSync.status.value}")
            desk.hidden()
            until("desktop let go") { runBlocking { p.sync.foreground(); p.sync.status.value.readOnlyBy == null } }
            p.model.actions!!.saveAccount(null, "E2E Phone Savings", AccountType.BANK, 80_00, java.time.LocalDate.now())
            p.sync.syncNow()
            dSync.syncNow()
            assertTrue(names(d).contains("E2E Phone Savings"), "the phone's edit reached the desktop")

            // 5. offline and back: the phone's edit waits, survives a restart, then goes
            p.net.online = false
            p.model.actions!!.saveAccount(null, "E2E Offline Cash", AccountType.CASH, 15_00, java.time.LocalDate.now())
            p.sync.syncNow()
            assertEquals(SyncStatus.State.OFFLINE, p.sync.status.value.state)
            assertTrue(p.sync.status.value.queued > 0)
            p.sync = p.start()
            p.net.online = true
            p.sync.foreground()
            assertEquals(0, p.sync.status.value.queued)
            dSync.syncNow()
            assertTrue(names(d).contains("E2E Offline Cash"))

            // 6. lock handoff: the phone goes to the back, the desktop's window comes up and takes the lock
            p.sync.background()
            desk.shown()
            until("desktop took the lock") { dSync.holdsEditLock }
            p.sync.foreground()
            assertEquals("E2E desktop", p.sync.status.value.readOnlyBy)

            // 7. take over from the phone: the desktop turns read only, and its edit made before it heard waits there
            p.sync.takeOver()
            assertNull(p.sync.status.value.readOnlyBy)
            desk.hidden()          // stop the window's heartbeat loop, so the steps below run in order
            until("desktop loop stopped") { desk.status.state != SyncStatus.State.SYNCING }
            Thread.sleep(500)
            // an edit the desktop makes before it hears: kept on the desktop, not sent
            assertTrue(d.addTransaction(checking, java.time.LocalDate.now(), "E2E Desk Waiting", 2_00, READY_TO_ASSIGN, ""), d.message?.first)
            dSync.foreground()
            assertEquals("E2E phone P", dSync.status.value.readOnlyBy)
            assertEquals("E2E phone P", dSync.status.value.takenOverBy)
            assertTrue(dSync.status.value.queued > 0, "the desktop's edit waits")
            p.sync.syncNow()
            assertFalse(payees(p.model).contains("E2E Desk Waiting"), "not sent while taken over")
            assertFalse(d.addTransaction(checking, java.time.LocalDate.now(), "Blocked", 1_00, READY_TO_ASSIGN, ""))
            p.model.actions!!.saveAccount(null, "E2E After Takeover", AccountType.BANK, 5_00, java.time.LocalDate.now())
            p.sync.syncNow()
            dSync.syncNow()
            assertTrue(names(d).contains("E2E After Takeover"), "the new holder's edits still reach the read-only desktop")
            // ...and take over back from the desktop
            dSync.takeOver()
            assertNull(dSync.status.value.readOnlyBy)
            assertEquals(0, dSync.status.value.queued, "the waiting edit went once the desktop held the lock again")
            assertTrue(d.addTransaction(checking, java.time.LocalDate.now(), "E2E Desk Back", 3_00, READY_TO_ASSIGN, ""), d.message?.first)
            dSync.syncNow()
            p.sync.tick()
            assertEquals("E2E desktop", p.sync.status.value.takenOverBy)
            assertTrue(payees(p.model).containsAll(listOf("E2E Desk Back", "E2E Desk Waiting")))

            // 8. revoke: the desktop removes the phone, which is cut off
            val pId = assertNotNull(p.sync.deviceId)
            assertTrue(desk.revoke(pId), desk.error)
            p.sync.syncNow()
            assertEquals(SyncStatus.State.ERROR, p.sync.status.value.state)
            assertEquals(ServerClient.REVOKED, p.sync.status.value.message)

            // 9. the desktop logs out: lets go and leaves; its budget stays
            val before = names(d)
            assertTrue(desk.logout(), desk.error)
            assertFalse(dSync.connected)
            assertFalse(File(dDir, DesktopKeyVault.FILE).exists())
            assertEquals(before, names(d))
            a.sync.foreground()
            assertNull(a.sync.status.value.readOnlyBy, "the desktop let go of the lock")
            assertTrue(a.sync.devices().single { it.name == "E2E desktop" }.revokedAt != null)
            a.sync.logout()
        } finally {
            scope.cancel()
            models.forEach { runCatching { it.engine?.close() } }
        }
    }
}
