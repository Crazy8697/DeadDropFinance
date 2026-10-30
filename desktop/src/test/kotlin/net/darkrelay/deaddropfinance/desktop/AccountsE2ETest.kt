package net.darkrelay.deaddropfinance.desktop

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.BackupSecret
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.KeyProtector
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.PairingQr
import net.darkrelay.deaddropfinance.serversync.ServerException
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.6.0 and 0.11.0 end to end against a real, throwaway Dead Drop server 0.4.0 (never the live one), over real HTTP
 * with real envelopes: invented budgets in temp folders, test keys. Skipped unless DDF_E2E_URL, DDF_E2E_CODE (that
 * server's setup code) and DDF_E2E_SCENARIO are set; the server must be new, since the first device creates its one
 * account.
 *  - DESKTOP_FIRST: the program creates the account and shows the recovery key; a phone pairs by its QR code and gets
 *    the key; a backup from the phone restores on the program; the phone changes the password while the program stays
 *    in; a second program logs in with Stay logged in off and closing logs it out.
 *  - PHONE_FIRST: a phone creates the account and the program logs in, then gets the account's key.
 */
class AccountsE2ETest {

    @get:Rule val tmp = TemporaryFolder()

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val scenario = System.getenv("DDF_E2E_SCENARIO")
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = mutableListOf<AppModel>()

    private object TestProtector : KeyProtector {
        override fun protect(plain: ByteArray) = "TEST".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unprotect(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    /** A budget on the real clock (the server refuses change times far in the future). */
    private fun model(dir: File, account: String? = null): AppModel = runBlocking {
        val m = AppModel(Engine.start(dir))
        if (account != null) m.actions!!.saveAccount(null, account, AccountType.BANK, 120_00, java.time.LocalDate.now().minusDays(2))
        m.refresh()
        m.also { models += it }
    }

    /** The program: ServerModel, its own settings file, DPAPI-style key files (a stand-in protector), recovery keys. */
    inner class Program(name: String, account: String? = null) {
        val dir: File = tmp.newFolder(name)
        val model = model(dir, account)
        val keys = RecoveryKeys(DesktopRecoveryStore(dir, TestProtector)).also { model.recoveryKeys = it }
        val sync = ServerSync((model.engine!!.opened as DesktopDatabase.Opened.Ok).store, DesktopSyncPrefs(dir), DesktopKeyVault(dir, TestProtector), scope,
            transportFor = { HttpTransport(it) }, kind = "desktop", sendDelayMs = 60_000, recovery = keys)
        val server = ServerModel(sync, model, scope, File(dir, "Downloads"), defaultDeviceName = "E2E $name").also { model.server = it }
    }

    /** A phone: the shared sync on its own budget, with its recovery keys (the phone's SecretStore stands in as memory). */
    inner class Phone(name: String, account: String? = null) {
        val dir: File = tmp.newFolder(name)
        val model = model(dir, account)
        val ok = model.engine!!.opened as DesktopDatabase.Opened.Ok
        val keys = RecoveryKeys(MemoryRecoveryKeyStore())
        val sync = ServerSync(ok.store, DesktopE2ETest.MemoryPrefs(), DesktopE2ETest.MemoryVault(), scope,
            transportFor = { HttpTransport(it) }, kind = "phone", sendDelayMs = 60_000, recovery = keys)
        /** The phone's backup format (the program reads the same one), under its recovery key. */
        fun backup(): File = File(dir, "phone-backup.ddfbak").also { f -> runBlocking { f.outputStream().use { DesktopBackups(ok.store.db, ok.file, "e2e-phone").write(it, keys.current()!!) } } }
    }

    private fun until(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private fun names(m: AppModel) = runBlocking { (m.engine!!.opened as DesktopDatabase.Opened.Ok).store.dao.accountsNow().map { it.name }.sorted() }

    private fun ready(which: String) {
        assumeTrue("set DDF_E2E_URL, DDF_E2E_CODE and DDF_E2E_SCENARIO=$which to run against a fresh test server",
            !url.isNullOrBlank() && !code.isNullOrBlank() && scenario == which)
    }

    @Test fun theProgramCreatesTheAccountAndAPhonePairsByQr() = runBlocking<Unit> {
        ready("DESKTOP_FIRST")
        try {
            // 1. the program creates the account; the recovery key is made and shown once
            val desk = Program("D", account = "E2E Desk Checking")
            assertTrue(desk.server.connect(url), desk.server.error)
            assertTrue(desk.server.create(code, "e2e", pw, pw, "E2E desktop"), desk.server.error)
            assertTrue(desk.sync.connected)
            assertIs<Dialog.ShowRecoveryKey>(desk.model.dialog)
            val accountKey = assertNotNull(desk.keys.toShow())
            desk.keys.confirmSaved()
            desk.model.dialog = null
            assertNull(desk.keys.toShow(), "shown once")
            desk.server.shown()
            until("desktop holds the lock") { desk.sync.holdsEditLock }

            // 2. a phone pairs by the program's QR code and gets the account's key, sealed through the server
            assertTrue(desk.server.newPairingCode(), desk.server.error)
            val qr = assertNotNull(PairingQr.parse(assertNotNull(desk.server.pairing).qr))
            val phone = Phone("P")
            assertNull(phone.sync.pair(qr, "E2E phone") { error("nothing of its own to back up") })
            assertEquals(names(desk.model), names(phone.model))
            assertNull(phone.keys.current(), "no key until a device holding it sends it")
            desk.sync.syncNow()
            phone.sync.syncNow()
            assertContentEquals(accountKey, phone.keys.current(), "the phone got the account's key")
            assertTrue(phone.sync.recoveryKeyArrived.value)

            // 3. the phone's change syncs, then the phone changes the password while the program stays logged in
            desk.server.hidden()
            until("lock free") { !desk.sync.holdsEditLock }
            phone.sync.foreground()
            phone.model.actions!!.saveAccount(null, "E2E Phone Savings", AccountType.BANK, 50_00, java.time.LocalDate.now().minusDays(1))
            phone.sync.syncNow()
            val newPw = pw + "-new"
            phone.sync.changePassword(newPw)
            desk.sync.syncNow()
            assertEquals(SyncStatus.State.SYNCED, desk.sync.status.value.state, desk.sync.status.value.message)
            assertTrue(desk.sync.connected, "the program stays logged in")
            assertTrue(names(desk.model).contains("E2E Phone Savings"))

            // 4. a second program: the old password is refused, the new one works; Stay logged in off logs out on close
            val other = Program("O")
            assertTrue(other.server.connect(url), other.server.error)
            assertFalse(other.server.login("e2e", pw, "E2E second desktop", stay = false), "the old password no longer works")
            assertTrue(other.server.login("e2e", newPw, "E2E second desktop", stay = false), other.server.error)
            val otherId = other.sync.deviceId!!
            other.server.shown()
            other.server.closing()
            assertFalse(other.sync.connected, "closing logged it out")
            assertFalse(File(other.dir, DesktopKeyVault.FILE).exists(), "its device key is gone")
            assertTrue(desk.sync.devices().single { it.id == otherId }.revokedAt != null, "and the server removed it")

            // 5. a backup made on the phone restores on the program, with the program's own (the account's) key
            val file = phone.backup()
            desk.server.logout()
            assertTrue(desk.model.restore(file, BackupSecret.Key(desk.keys.current()!!)), desk.model.message?.first)
            assertEquals(names(phone.model), names(desk.model))
            // and a key that isn't the account's opens nothing and changes nothing
            assertFalse(desk.model.restore(file, BackupSecret.Key(RecoveryKey.generate())))
            assertTrue(desk.model.message!!.first.contains("different key"))
        } finally {
            scope.cancel()
            models.forEach { it.engine?.close() }
        }
    }

    @Test fun aPhoneCreatesTheAccountAndTheProgramLogsIn() = runBlocking<Unit> {
        ready("PHONE_FIRST")
        try {
            val phone = Phone("P", account = "E2E Phone Checking")
            phone.sync.createAccount(url, code, "e2e", pw, "E2E phone")
            assertEquals(SyncStatus.State.SYNCED, phone.sync.status.value.state, phone.sync.status.value.message)
            val accountKey = assertNotNull(phone.keys.toShow(), "the account's key, made with it")
            phone.keys.confirmSaved()
            phone.sync.background()
            // the program logs in with username and password (never a code); its own budget gets a key and a backup first
            val desk = Program("D", account = "Zzlocal Desk Checking")
            assertTrue(desk.server.connect(url), desk.server.error)
            desk.server.startLogin("e2e", pw, "E2E desktop", true)
            val show = assertIs<Dialog.ShowRecoveryKey>(desk.model.dialog)
            val own = assertNotNull(desk.keys.toShow(), "the program's own key, shown before its backup")
            desk.keys.confirmSaved()
            assertEquals(ServerException.Kind.REFUSED, assertFailsWith<ServerException> {
                desk.sync.login(url, "e2e", "wrong-password-e2e", "E2E desktop") { "not-written" }
            }.kind)
            val confirm = assertIs<Dialog.ConfirmLogin>(show.next)
            assertTrue(desk.server.login(confirm.username, confirm.pass, confirm.name, confirm.stay), desk.server.error)
            assertEquals(names(phone.model), names(desk.model), "the server's budget replaced the program's")
            val saved = File(desk.dir, "Downloads/DeadDropFinance").listFiles()!!.single()
            // the program's own budget's backup opens with its own key
            val check = model(tmp.newFolder("C"))
            val ok = check.engine!!.opened as DesktopDatabase.Opened.Ok
            saved.inputStream().use { DesktopBackups(ok.store.db, ok.file, "e2e").restore(it, BackupSecret.Key(own)) }
            assertTrue(names(check).contains("Zzlocal Desk Checking"))
            // then the account's key arrives from the phone; the program's own is kept for that backup
            phone.sync.foreground(); phone.sync.background()
            desk.sync.syncNow()
            assertContentEquals(accountKey, desk.keys.current())
            assertTrue(desk.keys.old().any { it.contentEquals(own) })
        } finally {
            scope.cancel()
            models.forEach { it.engine?.close() }
        }
    }

    /**
     * 1.7.0 / 0.12.0 (DDF_E2E_SCENARIO=CURRENCY): the phone picks EUR and the program shows amounts in it; a backup made
     * on the phone restores on the program with the key saved there, nothing typed.
     */
    @Test fun theCurrencyAndASavedKeyRestoreThroughARealServer() = runBlocking<Unit> {
        ready("CURRENCY")
        try {
            val phone = Phone("P", account = "E2E Phone Checking")
            phone.sync.createAccount(url, code, "e2e", pw, "E2E phone")
            phone.keys.confirmSaved()
            phone.sync.background()
            val desk = Program("D")
            assertTrue(desk.server.connect(url), desk.server.error)
            assertTrue(desk.server.login("e2e", pw, "E2E desktop"), desk.server.error)
            assertEquals("USD", desk.model.currency)
            // the phone picks EUR; the program shows it after the next sync
            phone.sync.foreground()
            phone.ok.store.setCurrency("EUR")
            phone.sync.syncNow()
            phone.sync.background()
            net.darkrelay.deaddropfinance.domain.Money.use(null)
            desk.sync.syncNow()
            desk.model.refresh()
            assertEquals("EUR", desk.model.currency)
            assertEquals("1.234,56 €", money(123456).replace(' ', ' '))
            // the account's key reached the program; a phone backup restores there with it, nothing typed
            assertContentEquals(phone.keys.current(), desk.keys.current())
            val file = phone.backup()
            desk.server.logout()
            val saved = assertNotNull(desk.model.savedKeyFor(file), "a key saved on the program opens it")
            assertTrue(desk.model.restore(file, BackupSecret.Key(saved)), desk.model.message?.first)
            assertEquals(names(phone.model), names(desk.model))
            assertEquals("EUR", desk.model.currency, "the backup carries the currency too")
        } finally {
            net.darkrelay.deaddropfinance.domain.Money.use(null)
            scope.cancel()
            models.forEach { it.engine?.close() }
        }
    }
}
