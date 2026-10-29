package net.darkrelay.deaddropfinance.serversync

import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReadOnlyException
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End to end against a real, throwaway Dead Drop server (never the live one): two devices on invented budgets in temp
 * folders, over real HTTP with real envelopes. Skipped unless DDF_E2E_URL and DDF_E2E_CODE (that test server's setup
 * code) are set. The server must be new: this creates its one account.
 */
class ServerE2ETest {

    @get:Rule val tmp = TemporaryFolder()

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)

    /** A transport that can be cut, as a phone losing its connection. */
    class Cuttable(private val inner: Transport) : Transport {
        @Volatile var online = true
        override fun get(path: String) = if (online) inner.get(path) else throw IOException("cut")
        override fun post(path: String, body: ByteArray) = if (online) inner.post(path, body) else throw IOException("cut")
    }

    inner class Device(name: String, val scope: CoroutineScope) {
        val store: BudgetStore = runBlocking {
            val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(name)))
            o.store.ensureSystemEnvelopes(); o.store.ensureCategories(); o.store
        }
        val actions = BudgetActions(store)
        val prefs = ServerSyncTest.MemoryPrefs()
        val vault = ServerSyncTest.MemoryVault()
        val net = Cuttable(HttpTransport(url))
        var sync = start()
        val backups = mutableListOf<String>()
        fun start() = ServerSync(store, prefs, vault, scope, transportFor = { net }, sendDelayMs = 60_000)
        suspend fun picture(): String {
            val b = actions.snapshot()
            return "accounts=${b.accounts.map { it.name to b.accountBalance(it.id) }.sortedBy { it.first }} " +
                "envelopes=${b.fillOrder.map { it.name to b.envelopeBalance(it.id) }} txs=${b.transactions.map { it.payee to it.amount }.sortedBy { it.first }} " +
                "lines=${b.lines.size} moves=${b.moves.size} rta=${b.readyToAssign} start=${store.budgetStart()}"
        }
    }

    @Test fun twoDevicesThroughARealServer() = runBlocking<Unit> {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = mutableListOf<Device>()
        try {
            // 1. the first device: its budget becomes the server's copy
            val a = Device("A", scope).also { opened += it }
            val acct = a.actions.saveAccount(null, "E2E Checking", AccountType.BANK, 900_00, day)
            val food = a.actions.saveEnvelope(null, EnvelopeDraft("E2E Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
            a.actions.move(null, food, 120_00, date = day)
            val cat = a.actions.snapshot().categories.first { it.name == "Groceries" }.id
            a.actions.saveTransaction(null, TransactionDraft(acct, day, "E2E Market", "", -35_00, listOf(LineDraft(food, -35_00, categoryId = cat))))
            a.store.setBudgetStart(day)
            val (_, key) = a.sync.contact(url)
            a.sync.createAccount(url, code, "e2e", pw, "E2E phone A")
            assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state, a.sync.status.value.message)
            assertEquals(key.fingerprint, a.sync.fingerprint, "pinned on first contact")
            // the setup code works once
            val late = Device("Late", scope).also { opened += it }
            assertEquals(ServerException.Kind.REFUSED, assertFailsWith<ServerException> { late.sync.createAccount(url, code, "e2e2", pw, "Late") }.kind)

            // 2. a second device with a budget of its own: backed up (with the password), then replaced by the server's
            val b = Device("B", scope).also { opened += it }
            b.actions.saveAccount(null, "Zzlocal Savings", AccountType.BANK, 5_00, day)
            assertEquals(ServerException.Kind.REFUSED, assertFailsWith<ServerException> { b.sync.login(url, "e2e", "wrong-password-e2e", "E2E desktop B") { "" } }.kind)
            val saved = b.sync.login(url, "E2E", pw, "E2E desktop B") { p -> b.backups += String(p); "test-backup" }
            assertEquals("test-backup", saved)
            assertEquals(listOf(pw), b.backups)
            assertEquals(a.picture(), b.picture())
            assertFalse(b.picture().contains("Zzlocal"))

            // 3. the lock: A in front holds it, B in front is read only
            a.sync.foreground()
            b.sync.foreground()
            assertEquals("E2E phone A", b.sync.status.value.readOnlyBy)
            assertFailsWith<ReadOnlyException> { b.actions.saveAccount(null, "Blocked", AccountType.BANK, 1_00, day) }
            a.actions.move(null, food, 30_00, date = day)
            a.sync.syncNow()
            b.sync.syncNow()
            assertEquals(a.picture(), b.picture(), "A's edit reached B")
            // A goes to the back: the lock goes, and B takes it on its next heartbeat
            a.sync.background()
            b.sync.tick()
            assertNull(b.sync.status.value.readOnlyBy)
            b.actions.saveAccount(null, "E2E Savings", AccountType.BANK, 250_00, day)
            b.sync.syncNow()
            a.sync.syncNow()
            assertEquals(b.picture(), a.picture(), "B's edit reached A")

            // 4. offline: B's edit waits, survives a restart, then goes
            b.net.online = false
            b.actions.saveAccount(null, "E2E Offline Cash", AccountType.CASH, 40_00, day)
            b.sync.syncNow()
            assertEquals(SyncStatus.State.OFFLINE, b.sync.status.value.state)
            assertTrue(b.sync.status.value.queued > 0)
            b.sync = b.start()          // the app closed and opened again
            b.net.online = true
            b.sync.foreground()
            assertEquals(0, b.sync.status.value.queued)
            a.sync.syncNow()
            assertTrue(a.picture().contains("E2E Offline Cash"))

            // 5. last write wins: A renames Food offline, B renames it later; B's stays and A is told
            a.net.online = false
            a.actions.saveEnvelope(food, EnvelopeDraft("Food by A", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
            Thread.sleep(50)
            b.actions.saveEnvelope(food, EnvelopeDraft("Food by B", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
            b.sync.syncNow()
            a.net.online = true
            a.sync.syncNow()
            assertTrue(a.picture().contains("Food by B"), a.picture())
            assertFalse(a.picture().contains("Food by A"))
            assertTrue(a.sync.notices.value.any { it.contains("E2E desktop B") }, "${a.sync.notices.value}")

            // 6. revoke: A removes B, and B is cut off
            val bId = assertNotNull(b.sync.deviceId)
            assertTrue(a.sync.devices().any { it.id == bId && it.revokedAt == null })
            a.sync.revoke(bId)
            b.sync.syncNow()
            assertEquals(SyncStatus.State.ERROR, b.sync.status.value.state)
            assertEquals(ServerClient.REVOKED, b.sync.status.value.message)

            // 7. log out: A lets go and leaves; its budget stays
            a.sync.foreground()
            val pic = a.picture()
            a.sync.logout()
            assertFalse(a.sync.connected)
            assertEquals(pic, a.picture())
            val c = Device("C", scope).also { opened += it }
            c.sync.login(url, "e2e", pw, "E2E phone C") { "" }
            c.sync.foreground()
            assertNull(c.sync.status.value.readOnlyBy, "A let go of the lock")
            assertEquals(pic, c.picture())
            c.sync.logout()
        } finally {
            scope.cancel()
            opened.forEach { runCatching { it.store.db.close() } }
        }
    }
}
