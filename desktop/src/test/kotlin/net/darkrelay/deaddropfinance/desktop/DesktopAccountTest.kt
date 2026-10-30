package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.serversync.FakeServer
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.hex
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The program 0.11.0: creating the account from this computer (the recovery key shown once), Stay logged in, password
 * change, the recovery key arriving from another device, the DPAPI-protected key file, and Windows Hello unlock with a
 * stand-in for Hello (the real prompt is never shown in a test). Against the shared tests' in-memory server: no network,
 * test keys, invented budgets in temp folders.
 */
class DesktopAccountTest {

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

    private object TestProtector : KeyProtector {
        override fun protect(plain: ByteArray) = "TEST".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unprotect(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    inner class Device(val kind: String = "desktop", filled: Boolean = true) {
        val dir: File = tmp.newFolder()
        val model: AppModel = (if (filled) SampleBudget.filled(dir).model else SampleBudget.empty(dir)).also { models += it }
        val prefs = DesktopSyncPrefs(dir)
        val vault = DesktopKeyVault(dir, TestProtector)
        val keys = RecoveryKeys(DesktopRecoveryStore(dir, TestProtector)).also { model.recoveryKeys = it }
        val store = (model.engine!!.opened as DesktopDatabase.Opened.Ok).store
        val sync = ServerSync(store, prefs, vault, scope, transportFor = { fake.transport() }, kind = kind, sendDelayMs = 60_000, recovery = keys)
        val server = ServerModel(sync, model, scope, File(dir, "Downloads"), defaultDeviceName = "Test $kind").also { model.server = it }

        fun connect() = runBlocking { assertTrue(server.connect("server.example"), server.error) }
    }

    private fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)

    // ---- creating the account here

    @Test fun thisComputerCreatesTheAccountAndShowsTheRecoveryKeyOnce() {
        val d = Device()
        d.connect()
        runBlocking {
            assertFalse(d.server.create("TEST-CODE", "tester", pw, "not the same", "Test desktop"))
            assertEquals("The two passwords don't match.", d.server.error)
            assertFalse(d.server.create("TEST-CODE", "tester", "short", "short", "Test desktop"))
            assertTrue(d.server.create("TEST-CODE", "tester", pw, pw, "Test desktop"), d.server.error)
        }
        assertTrue(d.sync.connected)
        assertEquals("desktop", fake.devices[d.prefs.deviceId]!!.kind)
        val dlg = assertIs<Dialog.ShowRecoveryKey>(d.model.dialog)
        assertNull(dlg.next)
        val key = assertNotNull(d.keys.toShow())
        assertEquals(RecoveryKey.id(key), fake.recoveryKeyId, "the account's key is this computer's")
        // the file holds it protected, never the key itself
        val raw = File(d.dir, DesktopRecoveryStore.FILE).readBytes()
        assertFalse(raw.hex().contains(key.hex()))
        assertFalse(String(raw, Charsets.ISO_8859_1).contains(key.hex()))
        d.keys.confirmSaved()
        assertNull(d.keys.toShow())
        // a fresh store on the same folder reads it back (the program reopened)
        assertTrue(RecoveryKeys(DesktopRecoveryStore(d.dir, TestProtector)).current()!!.contentEquals(key))
    }

    @Test fun theCreateFormIsOnTheServerPanel() {
        val d = Device()
        d.connect()
        compose.setContent { ServerPanel(d.model, d.server, Shell(run = { b -> runBlocking { b() } })) }
        compose.waitForIdle()
        tag("server.mode.create").performClick()
        compose.waitForIdle()
        tag("server.setupCode").performTextInput("TEST-CODE")
        tag("server.username").performTextInput("tester")
        tag("server.password").performTextInput(pw)
        tag("server.again").performTextInput(pw)
        tag("server.create").performClick()
        compose.waitForIdle()
        until("connected") { d.sync.connected }
        assertIs<Dialog.ShowRecoveryKey>(d.model.dialog)
    }

    // ---- Stay logged in

    private fun accountByPhone(): Device = Device("phone", filled = false).also {
        it.connect()
        runBlocking { it.sync.createAccount("server.example", "TEST-CODE", "tester", pw, "Test phone"); it.sync.background() }
    }

    @Test fun stayLoggedInIsOnByDefaultAndOffLogsOutOnClose() {
        accountByPhone()
        val d = Device()
        d.connect()
        compose.setContent { ServerPanel(d.model, d.server, Shell(run = { b -> runBlocking { b() } })) }
        compose.waitForIdle()
        tag("server.stay").assertIsOn()
        tag("server.stay").performClick()
        compose.waitForIdle()
        tag("server.stay").assertIsOff()
        tag("server.username").performTextInput("tester")
        tag("server.password").performTextInput(pw)
        tag("server.login").performClick()
        compose.waitForIdle()
        // this computer has a budget and no key: the key comes first, then the confirmation, then the login
        val show = assertIs<Dialog.ShowRecoveryKey>(d.model.dialog)
        val confirm = assertIs<Dialog.ConfirmLogin>(show.next)
        assertFalse(confirm.stay)
        d.keys.confirmSaved()
        runBlocking { assertTrue(d.server.login(confirm.username, confirm.pass, confirm.name, confirm.stay), d.server.error) }
        assertFalse(d.sync.stayLoggedIn)
        val me = d.prefs.deviceId!!
        d.server.shown()
        until("lock taken") { fake.lockHolder == me }
        runBlocking { d.server.closing() }
        assertFalse(d.sync.connected, "closing logged out")
        assertNull(fake.lockHolder)
        assertNotNull(fake.devices[me]!!.revokedAt)
        assertFalse(File(d.dir, DesktopKeyVault.FILE).exists())
    }

    @Test fun stayLoggedInOnKeepsThisComputerInAcrossAClose() {
        accountByPhone()
        val d = Device(filled = false)
        d.connect()
        runBlocking { assertTrue(d.server.login("tester", pw, "Test desktop", stay = true), d.server.error) }
        runBlocking { d.server.closing() }
        assertTrue(d.sync.connected)
        runBlocking { d.server.openingCheck() }
        assertTrue(d.sync.connected)
    }

    @Test fun aCloseThatDidNotLogOutIsFinishedOnTheNextOpen() {
        accountByPhone()
        val d = Device(filled = false)
        d.connect()
        runBlocking { assertTrue(d.server.login("tester", pw, "Test desktop", stay = false), d.server.error) }
        // the program was stopped without closing: on the next open, the same folder's settings say log out
        assertFalse(DesktopSyncPrefs(d.dir).stayLoggedIn)
        runBlocking { d.server.openingCheck() }
        assertFalse(d.sync.connected)
    }

    // ---- password change

    @Test fun changePasswordFromThisComputer() {
        val phone = accountByPhone()
        val d = Device(filled = false)
        d.connect()
        runBlocking { assertTrue(d.server.login("tester", pw, "Test desktop"), d.server.error) }
        compose.setContent { ServerDialog(d.model, d.server, Shell(run = { b -> runBlocking { b() } }), Dialog.ChangePassword) }
        compose.waitForIdle()
        tag("pw.new").performTextInput("new-test-pass-1")
        tag("pw.again").performTextInput("new-test-pass-2")
        tag("dialog.confirm").performClick()
        compose.waitForIdle()
        assertEquals("The two passwords don't match.", d.server.error)
        runBlocking { assertTrue(d.server.changePassword("new-test-pass-1", "new-test-pass-1"), d.server.error) }
        assertEquals("new-test-pass-1", fake.account!!.second)
        runBlocking { phone.sync.syncNow() }
        assertTrue(phone.sync.connected, "the other device stays logged in")
        assertTrue(d.model.message!!.first.startsWith("Password changed"))
    }

    // ---- the account's key arriving here

    @Test fun theAccountsKeyArrivesFromTheOtherDevice() {
        val phone = accountByPhone()
        phone.keys.confirmSaved()
        val d = Device(filled = false)
        d.connect()
        runBlocking { assertTrue(d.server.login("tester", pw, "Test desktop"), d.server.error) }
        assertNull(d.keys.current(), "nothing of its own and nothing sent yet")
        runBlocking { phone.sync.syncNow(); d.sync.syncNow() }
        until("key arrived") { d.keys.current() != null }
        assertTrue(d.keys.current()!!.contentEquals(phone.keys.current()!!))
        until("told") { d.model.message?.first?.contains("recovery key reached this computer") == true }
        assertNull(d.keys.toShow(), "not shown here: it was shown where it was made")
    }

    // ---- Windows Hello unlock (a stand-in for Hello)

    private class FakeHello(var availability: HelloVerifier.Availability = HelloVerifier.Availability.AVAILABLE,
                            var result: HelloVerifier.Result = HelloVerifier.Result.VERIFIED) : HelloVerifier {
        var asked = 0
        override fun availability() = availability
        override fun verify(message: String, hwnd: Long): HelloVerifier.Result { asked++; return result }
    }

    @Test fun helloIsOffByDefaultAndTurnsOnOnlyAfterACheck() {
        val settings = Settings(tmp.newFolder())
        val hello = FakeHello()
        val lock = HelloLock(settings, hello)
        assertFalse(lock.enabled)
        assertFalse(lock.locked)
        hello.result = HelloVerifier.Result.CANCELED
        lock.set(true, 0)
        assertFalse(lock.enabled, "a cancelled check leaves it off")
        assertFalse(settings.helloUnlock)
        hello.result = HelloVerifier.Result.VERIFIED
        lock.set(true, 0)
        assertTrue(lock.enabled)
        assertTrue(Settings(settings.dir).helloUnlock, "kept for the next open")
        assertEquals(2, hello.asked)
        lock.set(false, 0)
        assertFalse(Settings(settings.dir).helloUnlock)
    }

    @Test fun withoutHelloSetUpItSaysSoAndStaysOff() {
        val settings = Settings(tmp.newFolder())
        val hello = FakeHello(HelloVerifier.Availability.NOT_SET_UP)
        val lock = HelloLock(settings, hello)
        lock.set(true, 0)
        assertFalse(lock.enabled)
        assertEquals(HelloLock.NOT_SET_UP, lock.message)
        assertEquals(0, hello.asked, "no prompt when Hello isn't set up")
    }

    @Test fun whenOnTheProgramOpensLockedUntilHelloPasses() {
        val settings = Settings(tmp.newFolder()).also { it.helloUnlock = true }
        val hello = FakeHello(result = HelloVerifier.Result.FAILED)
        val lock = HelloLock(settings, hello)
        assertTrue(lock.locked)
        compose.setContent { if (lock.locked) HelloLockScreen(lock, onUnlock = { lock.unlock(0) }, onExit = {}) else androidx.compose.material3.Text("budget") }
        compose.waitForIdle()
        tag("helloLock").assertExists()
        tag("hello.unlock").performClick()
        compose.waitForIdle()
        assertTrue(lock.locked, "a failed check keeps it locked")
        tag("hello.lockMessage").assertExists()
        hello.result = HelloVerifier.Result.VERIFIED
        tag("hello.unlock").performClick()
        compose.waitForIdle()
        assertFalse(lock.locked)
        tag("helloLock").assertDoesNotExist()
    }

    @Test fun theSettingsToggleUsesTheCheck() {
        val made = SampleBudget.filled(tmp.newFolder()).also { models += it.model }
        val settings = Settings(tmp.newFolder())
        val hello = FakeHello()
        val lock = HelloLock(settings, hello)
        compose.setContent { DesktopApp(made.model, Shell(run = { b -> runBlocking { b() } }, hello = lock)) }
        compose.waitForIdle()
        tag("nav.SETTINGS").performClick()
        compose.waitForIdle()
        assertFalse(lock.enabled)
        tag("setting.hello").performClick()
        compose.waitForIdle()
        assertTrue(lock.enabled)
        tag("hello.message").assertExists()
        assertEquals(1, hello.asked)
        assertTrue(settings.helloUnlock)
    }
}
