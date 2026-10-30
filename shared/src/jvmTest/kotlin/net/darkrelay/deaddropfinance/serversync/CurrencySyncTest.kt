package net.darkrelay.deaddropfinance.serversync

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.BackupCrypto
import net.darkrelay.deaddropfinance.backup.DesktopBackups
import net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.backup.RecoveryKeys
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.Money
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * 1.7.0: the budget's currency is one setting for the whole account, synced as a meta change like the budget start;
 * an existing budget stays USD; switching changes no stored amount. And restore's saved keys: the account's key first,
 * then this device's older ones. Against FakeServer: no network, test keys, invented budgets, temp folders.
 */
class CurrencySyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "test-pass-for-unit"
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<BudgetStore>()

    @After fun done() { scope.cancel(); opened.forEach { it.db.close() }; Money.use(null) }

    inner class Device(name: String, val kind: String = "phone") {
        val dir: File = tmp.newFolder(name)
        val ok = runBlocking { assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(dir)) }
        val store: BudgetStore = ok.store.also { runBlocking { it.ensureSystemEnvelopes(); it.ensureCategories() }; opened += it }
        val keys = RecoveryKeys(MemoryRecoveryKeyStore())
        val sync = ServerSync(store, ServerSyncTest.MemoryPrefs(), ServerSyncTest.MemoryVault(), scope, transportFor = { server.transport() },
            kind = kind, sendDelayMs = 60_000, recovery = keys)
        fun create() = runBlocking { sync.createAccount("server.example", "test-code", "tester", pw, "Test $kind") }
        fun login() = runBlocking { keys.ensure(); keys.confirmSaved(); sync.login("server.example", "tester", pw, "Test $kind 2") { "saved" } }
    }

    @Test fun anExistingBudgetStaysUsd() = runBlocking<Unit> {
        val a = Device("A")
        assertEquals("USD", a.store.currency())
        assertEquals("USD", a.store.applyCurrency())
        assertEquals("$1,234.56", Money.format(123456))
    }

    @Test fun switchingTheCurrencyChangesNoStoredAmount() = runBlocking<Unit> {
        val a = Device("A")
        val acct = BudgetActions(a.store).saveAccount(null, "Sample Checking", AccountType.BANK, 1234_56, day)
        val before = a.store.dao.accountsNow().map { it.id to it.startingBalance } to a.store.dao.transactionsNow().map { it.id to it.amount }
        a.store.setCurrency("JPY")
        a.store.setCurrency("EUR")
        val after = a.store.dao.accountsNow().map { it.id to it.startingBalance } to a.store.dao.transactionsNow().map { it.id to it.amount }
        assertEquals(before, after)
        assertEquals(123456L, a.store.dao.accountsNow().single { it.id == acct }.startingBalance)
        assertEquals("EUR", a.store.currency())
        assertEquals("EUR", Money.style.code)
        assertFailsWith<IllegalArgumentException> { a.store.setCurrency("NOPE") }
    }

    @Test fun theCurrencySyncsToTheOtherDevicesAsAMetaChange() = runBlocking<Unit> {
        val a = Device("A"); a.create()
        val b = Device("B", "desktop"); b.login()
        assertEquals("USD", b.store.currency())
        a.store.setCurrency("EUR")
        a.sync.syncNow()
        val change = server.changes.last().change
        assertEquals(BudgetStore.META_ENTITY to BudgetStore.META_CURRENCY, change.getString("entityType") to change.getString("entityId"))
        Money.use(null)
        b.sync.syncNow()
        assertEquals("EUR", b.store.currency())
        assertEquals("EUR", Money.style.code, "the other device shows amounts in it from then on")
        // a device logging in later takes it with the rest of the budget
        val c = Device("C"); c.login()
        assertEquals("EUR", c.store.currency())
    }

    @Test fun theCreatingDevicesCurrencyGoesUpWithItsBudget() = runBlocking<Unit> {
        val a = Device("A")
        a.store.setCurrency("GBP")
        a.create()
        val b = Device("B"); b.login()
        assertEquals("GBP", b.store.currency())
    }

    @Test fun restoreFindsTheSavedKeyTheAccountsFirstThenOlderOnes() = runBlocking<Unit> {
        val a = Device("A")
        BudgetActions(a.store).saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day)
        val own = a.keys.ensure(); a.keys.confirmSaved()
        val backups = DesktopBackups(a.store.db, a.ok.file, "test")
        val withOwn = ByteArrayOutputStream().also { backups.write(it, own) }.toByteArray()
        val account = RecoveryKey.generate()
        a.keys.adopt(account)
        val withAccount = ByteArrayOutputStream().also { backups.write(it, account) }.toByteArray()
        fun head(b: ByteArray) = BackupCrypto.keyIdOf(b.copyOf(BackupCrypto.HEAD_BYTES))
        assertContentEquals(account, a.keys.savedKeyFor(head(withAccount)), "the account's key")
        assertContentEquals(own, a.keys.savedKeyFor(head(withOwn)), "an older key of this device")
        val other = ByteArrayOutputStream().also { backups.write(it, RecoveryKey.generate()) }.toByteArray()
        assertNull(a.keys.savedKeyFor(head(other)), "no saved key opens it: the key is typed")
        val old = ByteArrayOutputStream().also { backups.write(it, "older pass".toCharArray(), rounds = 1_000) }.toByteArray()
        assertNull(BackupCrypto.keyIdOf(old.copyOf(BackupCrypto.HEAD_BYTES)), "an old password backup has no key id: its password is asked for")
    }
}
