package net.darkrelay.deaddropfinance.serversync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.BackupCrypto
import net.darkrelay.deaddropfinance.backup.BackupException
import net.darkrelay.deaddropfinance.backup.BackupSecret
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.6.0 (program 0.11.0): the recovery key (its format, backups with it, old password backups), its handoff between an
 * account's devices through the server (sealed copies only), password change from a logged-in device, and Stay logged
 * in. Against FakeServer: no network, test keys, invented budgets, temp folders.
 */
class RecoveryAndAccountTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "test-pass-for-unit"
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<BudgetStore>()

    @After fun done() { scope.cancel(); opened.forEach { it.db.close() } }

    inner class Device(name: String, val kind: String = "phone") {
        val dir: File = tmp.newFolder(name)
        val ok = runBlocking { assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(dir)) }
        val store: BudgetStore = ok.store.also { runBlocking { it.ensureSystemEnvelopes(); it.ensureCategories() }; opened += it }
        val prefs = ServerSyncTest.MemoryPrefs()
        val vault = ServerSyncTest.MemoryVault()
        val keys = RecoveryKeys(MemoryRecoveryKeyStore())
        val backups = DesktopBackups(store.db, ok.file, "test")
        val sync = ServerSync(store, prefs, vault, scope, transportFor = { server.transport() }, kind = kind, sendDelayMs = 60_000, recovery = keys)

        fun create() = runBlocking { sync.createAccount("server.example", "test-code", "tester", pw, "Test $kind") }

        fun login(stay: Boolean = true, pass: String = pw) = runBlocking {
            if (sync.needsRecoveryKeyFirst()) keys.ensure()
            sync.login("server.example", "tester", pass, "Test $kind 2", stay) { k -> "made-with-" + RecoveryKey.id(k) }
        }

        fun fill(label: String) = runBlocking {
            BudgetActions(store).saveAccount(null, "$label Checking", AccountType.BANK, 500_00, day)
            store.setBudgetStart(day)
        }

        fun accountNames() = runBlocking { store.dao.accountsNow().map { it.name }.sorted() }

        fun backup(): ByteArray = ByteArrayOutputStream().also { runBlocking { backups.write(it, keys.ensure()) } }.toByteArray()
    }

    // ---- the key itself

    @Test fun theKeyIsShownAs14GroupsOf4AndReadsBack() {
        val k = RecoveryKey.generate()
        assertEquals(32, k.size)
        val shown = RecoveryKey.format(k)
        val groups = shown.split("-")
        assertEquals(14, groups.size)
        assertTrue(groups.all { it.length == 4 && it.none { c -> c in "ILOU" } }, shown)
        assertContentEquals(k, RecoveryKey.parse(shown))
        // typed loosely: spaces, lower case, O for 0 and l for 1
        val loose = shown.lowercase().replace("-", " ").replace('0', 'o').replace('1', 'l')
        assertContentEquals(k, RecoveryKey.parse(" $loose "))
        assertNotEquals(RecoveryKey.format(k), RecoveryKey.format(RecoveryKey.generate()))
    }

    @Test fun aTypoOrAWrongLengthIsCaught() {
        val shown = RecoveryKey.format(RecoveryKey.generate())
        val i = shown.indexOfFirst { it.isLetterOrDigit() }
        val typo = shown.substring(0, i) + (if (shown[i] == '2') '3' else '2') + shown.substring(i + 1)
        assertTrue(assertFailsWith<IllegalArgumentException> { RecoveryKey.parse(typo) }.message!!.contains("typo"))
        assertTrue(assertFailsWith<IllegalArgumentException> { RecoveryKey.parse(shown.dropLast(2)) }.message!!.contains("14 groups of 4"))
        assertFailsWith<IllegalArgumentException> { RecoveryKey.parse("") }
    }

    @Test fun aKeyIsShownUntilItsSavedThenNeverAgain() {
        val keys = RecoveryKeys(MemoryRecoveryKeyStore())
        assertNull(keys.toShow())
        val k = keys.ensure()
        assertContentEquals(k, keys.toShow(), "made here: shown until I saved it is tapped")
        assertContentEquals(k, keys.ensure(), "made once")
        keys.confirmSaved()
        assertNull(keys.toShow(), "never shown again")
        val account = RecoveryKey.generate()
        keys.adopt(account)
        assertContentEquals(account, keys.current())
        assertContentEquals(k, keys.old().single(), "the old key is kept for the older backups")
        assertNull(keys.toShow(), "the account's key was shown where it was made")
    }

    // ---- backups with it

    @Test fun aBackupOpensWithItsKeyAndAWrongKeyChangesNothing() {
        val a = Device("A"); a.fill("Sample")
        val file = a.backup()
        assertEquals(BackupSecret.Key::class.java, BackupCrypto.kindOf(file.copyOf(7)))
        a.fill("Zzlater")
        val wrong = assertFailsWith<BackupException> {
            runBlocking { a.backups.restore(ByteArrayInputStream(file), BackupSecret.Key(RecoveryKey.generate())) }
        }
        assertTrue(wrong.message!!.contains("different key"), wrong.message)
        assertEquals(listOf("Sample Checking", "Zzlater Checking"), a.accountNames(), "a wrong key changes nothing")
        assertFailsWith<BackupException> { runBlocking { a.backups.restore(ByteArrayInputStream(file), BackupSecret.OldPassword("a-password".toCharArray())) } }
        runBlocking { a.backups.restore(ByteArrayInputStream(file), BackupSecret.Key(a.keys.current()!!)) }
        assertEquals(listOf("Sample Checking"), a.accountNames())
    }

    @Test fun aBackupFromBefore160StillRestoresWithItsPassword() {
        val a = Device("A"); a.fill("Older")
        val old = ByteArrayOutputStream().also { runBlocking { a.backups.write(it, "old-backup-pass".toCharArray(), rounds = 1_000) } }.toByteArray()
        assertEquals(BackupSecret.OldPassword::class.java, BackupCrypto.kindOf(old.copyOf(7)))
        a.fill("Zznewer")
        assertFailsWith<BackupException> { runBlocking { a.backups.restore(ByteArrayInputStream(old), BackupSecret.Key(a.keys.ensure())) } }
        assertEquals(listOf("Older Checking", "Zznewer Checking"), a.accountNames())
        runBlocking { a.backups.restore(ByteArrayInputStream(old), BackupSecret.OldPassword("old-backup-pass".toCharArray())) }
        assertEquals(listOf("Older Checking"), a.accountNames())
    }

    // ---- the account's key

    @Test fun creatingTheAccountMakesTheKeyAndShowsItOnce() {
        val a = Device("A", "desktop"); a.fill("Sample"); a.create()
        val k = assertNotNull(a.keys.toShow(), "made with the account, to show")
        assertEquals(RecoveryKey.id(k), server.recoveryKeyId)
        a.keys.confirmSaved()
        assertNull(a.keys.toShow())
    }

    @Test fun aDevicesOwnKeyBecomesTheAccountsKey() {
        val a = Device("A"); a.fill("Sample")
        val own = a.keys.ensure(); a.keys.confirmSaved()
        a.create()
        assertEquals(RecoveryKey.id(own), server.recoveryKeyId)
        assertNull(a.keys.toShow(), "already shown when it was made")
    }

    @Test fun theKeyReachesTheOtherDevicesSealedAndABackupOpensOnEither() = runBlocking<Unit> {
        val desk = Device("D", "desktop"); desk.fill("Sample"); desk.create(); desk.keys.confirmSaved()
        val accountKey = desk.keys.current()!!
        val phone = Device("P"); phone.fill("Zzlocal")
        val saved = phone.login()
        val own = phone.keys.old().singleOrNull() ?: phone.keys.current()!!
        assertEquals("made-with-" + RecoveryKey.id(own), saved, "the backup before the login used the phone's own key")
        assertFalse(phone.keys.current()?.contentEquals(accountKey) == true, "not until a device holding it sends it")
        desk.sync.syncNow()
        val copy = server.devices[phone.prefs.deviceId]!!.sealed
        assertNotNull(copy)
        assertEquals(RecoveryKey.id(accountKey), copy.first)
        assertFalse(copy.second.contains(accountKey.hex()), "the server holds only a sealed copy")
        phone.sync.syncNow()
        assertContentEquals(accountKey, phone.keys.current(), "the phone took the account's key")
        assertTrue(phone.sync.recoveryKeyArrived.value)
        assertTrue(phone.keys.old().any { it.contentEquals(own) }, "its own key is kept for its older backup")
        // a backup made on the phone opens on the desktop with the desktop's key, and the other way round
        val fromPhone = phone.backup()
        desk.backups.restore(ByteArrayInputStream(fromPhone), BackupSecret.Key(desk.keys.current()!!))
        val fromDesk = desk.backup()
        phone.backups.restore(ByteArrayInputStream(fromDesk), BackupSecret.Key(phone.keys.current()!!))
    }

    @Test fun anAccountWithoutAKeyTakesTheFirstOneOffered() = runBlocking<Unit> {
        val a = Device("A"); a.create()
        server.recoveryKeyId = null
        a.sync.syncNow()
        assertEquals(a.keys.currentId(), server.recoveryKeyId)
    }

    // ---- password change

    @Test fun aLoggedInDeviceSetsANewPasswordAndTheOthersStayIn() = runBlocking<Unit> {
        val a = Device("A"); a.fill("Sample"); a.create()
        val b = Device("B"); b.login()
        b.sync.changePassword("a-new-test-pass")
        assertTrue("account.password" in server.ops)
        a.sync.syncNow()
        assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state, "the other device stays logged in")
        assertEquals(ServerException.Kind.REFUSED, assertFailsWith<ServerException> { Device("C").login(pass = pw) }.kind, "the old password no longer works")
        Device("E").login(pass = "a-new-test-pass")
        assertFailsWith<IllegalArgumentException> { b.sync.changePassword("short") }
    }

    // ---- Stay logged in

    @Test fun stayLoggedInOffLogsOutOnCloseInOneCall() = runBlocking<Unit> {
        val a = Device("A"); a.fill("Sample"); a.create()
        val b = Device("B"); b.login(stay = false)
        assertFalse(b.sync.stayLoggedIn)
        val id = b.prefs.deviceId!!
        assertTrue(b.sync.closing())
        assertFalse(b.sync.connected)
        assertNull(b.vault.key, "the device key is removed")
        assertNotNull(server.devices[id]!!.revokedAt, "the device is removed on the server")
        assertTrue("device.logout" in server.ops)
        assertTrue(b.sync.stayLoggedIn, "back to the default for the next login")
    }

    @Test fun aCloseThatDidNotFinishIsCaughtOnTheNextOpen() = runBlocking<Unit> {
        val a = Device("A"); a.fill("Sample"); a.create()
        val b = Device("B"); b.login(stay = false)
        // the app was stopped before it could log out: the next open does it before showing anything
        assertTrue(b.sync.openingCheck())
        assertFalse(b.sync.connected)
    }

    @Test fun stayLoggedInOnKeepsTheDeviceIn() = runBlocking<Unit> {
        val a = Device("A"); a.fill("Sample"); a.create()
        val b = Device("B"); b.login(stay = true)
        assertFalse(b.sync.closing())
        assertFalse(b.sync.openingCheck())
        assertTrue(b.sync.connected)
        assertTrue(a.sync.stayLoggedIn, "creating the account stays logged in")
    }
}
