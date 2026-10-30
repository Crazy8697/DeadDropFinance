package net.darkrelay.deaddropfinance.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
 * change, the recovery key arriving from another device, and the DPAPI-protected key file. Against the shared tests' in-memory server: no network,
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

    // ---- 0.12.0: the currency, restore with a saved key, no Windows Hello

    @Test fun theCurrencyPickerChangesHowAmountsShowAndNoAmount() {
        val made = SampleBudget.filled(tmp.newFolder()).also { models += it.model }
        val m = made.model
        val before = m.budget!!.accounts.map { it.id to m.budget!!.accountBalance(it.id) }
        try {
            compose.setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } })) }
            compose.waitForIdle()
            tag("nav.SETTINGS").performClick()
            compose.waitForIdle()
            tag("currency").assertExists()
            assertEquals("USD", m.currency, "a budget stays USD until one is chosen")
            tag("currency.change").performClick()
            compose.waitForIdle()
            tag("currency.USD").assertExists()
            tag("currency.search").performTextInput("euro")
            compose.waitForIdle()
            tag("currency.EUR").performClick()
            compose.waitForIdle()
            assertEquals("EUR", m.currency)
            assertNull(m.dialog)
            assertEquals(before, m.budget!!.accounts.map { it.id to m.budget!!.accountBalance(it.id) }, "no amount changes")
            assertEquals("1.234,56 €", money(123456).replace(' ', ' '))
            assertEquals(123456L, parseAmount("1.234,56"))
        } finally {
            net.darkrelay.deaddropfinance.domain.Money.use(null)
        }
    }

    @Test fun restoreUsesTheKeySavedHereAndAsksOnlyWhenNoneOpensIt() {
        val made = SampleBudget.filled(tmp.newFolder()).also { models += it.model }
        val m = made.model
        val keys = RecoveryKeys(net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore()).also { m.recoveryKeys = it }
        val own = keys.ensure(); keys.confirmSaved()
        val ok = m.engine!!.opened as DesktopDatabase.Opened.Ok
        val withSaved = File(tmp.root, "saved.ddfbak").also { f -> runBlocking { f.outputStream().use { net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "t").write(it, own) } } }
        val withOther = File(tmp.root, "other.ddfbak").also { f -> runBlocking { f.outputStream().use { net.darkrelay.deaddropfinance.backup.DesktopBackups(ok.store.db, ok.file, "t").write(it, RecoveryKey.generate()) } } }
        var dialog by androidx.compose.runtime.mutableStateOf<Dialog.Restore>(Dialog.Restore(withSaved))
        compose.setContent { RestoreDialog(m, Shell(run = { b -> runBlocking { b() } }), dialog) }
        compose.waitForIdle()
        // a key saved here opens it: straight to the confirmation, nothing typed
        tag("dialog.confirmRestore").assertExists()
        tag("restore.savedKey").assertExists()
        tag("f.key").assertDoesNotExist()
        dialog = Dialog.Restore(withOther)
        compose.waitForIdle()
        tag("f.key").assertExists()
        assertNull(m.savedKeyFor(withOther))
    }

    @Test fun noWindowsHelloIsLeftInTheProgram() {
        val src = listOf(File("src/main"), File("desktop/src/main")).first { it.isDirectory }
        val hits = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { it.contains("Hello", ignoreCase = false) || it.contains("UserConsentVerifier") || it.contains("hello_unlock") } }
            .map { it.name }.toList()
        assertEquals(emptyList(), hits)
    }
}
