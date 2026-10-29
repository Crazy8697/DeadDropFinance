package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BackgroundWrite
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.ReadOnlyException
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.json.JSONObject
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Server sync against FakeServer: no network, test keys, invented budgets, temp folders. */
class ServerSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "test-pass-for-unit"
    private val server = FakeServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<BudgetStore>()

    @After fun done() { scope.cancel(); opened.forEach { it.db.close() } }

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
        val pins = mutableMapOf<String, String>()
        override fun pinned(url: String) = pins[url]
        override fun pin(url: String, keyHex: String) { pins[url] = keyHex }
    }

    class MemoryVault : KeyVault {
        var key: ByteArray? = null
        override fun load() = key
        override fun save(bytes: ByteArray) { key = bytes.copyOf() }
        override fun delete() { key = null }
    }

    inner class Phone(name: String, var fake: FakeServer = server) {
        val store: BudgetStore = runBlocking {
            val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(name)))
            o.store.ensureSystemEnvelopes(); o.store.ensureCategories(); opened += o.store; o.store
        }
        val actions = BudgetActions(store)
        val prefs = MemoryPrefs()
        val vault = MemoryVault()
        var backups = mutableListOf<String>()
        var sync = newSync()
        fun newSync() = ServerSync(store, prefs, vault, scope, transportFor = { fake.transport() }, sendDelayMs = 60_000)
        fun create() = runBlocking { sync.createAccount("server.example", "test-code", "tester", pw, "Test phone") }
        fun login(name: String = "Test phone 2") = runBlocking {
            sync.login("server.example", "tester", pw, name) { p -> backups += String(p); "Downloads/test-backup.ddfbak" }
        }
    }

    private suspend fun Phone.fill(label: String): String {
        val acct = actions.saveAccount(null, "$label Checking", AccountType.BANK, 500_00, day)
        val food = actions.saveEnvelope(null, EnvelopeDraft("$label Food", EnvelopeMode.FILL_TO_TARGET, target = 200_00), day)
        actions.move(null, food, 150_00, date = day)
        val cat = actions.snapshot().categories.first { it.name == "Groceries" }.id
        actions.saveTransaction(null, TransactionDraft(acct, day, "Sample Market", "", -40_00, listOf(LineDraft(food, -40_00, categoryId = cat))))
        store.setBudgetStart(day)
        return food
    }

    private suspend fun Phone.picture(): String {
        val b = actions.snapshot()
        return "accounts=${b.accounts.map { it.name to b.accountBalance(it.id) }} envelopes=${b.fillOrder.map { it.name to b.envelopeBalance(it.id) }} " +
            "txs=${b.transactions.map { it.payee to it.amount }} lines=${b.lines.size} moves=${b.moves.size} categories=${b.categories.map { it.name }.sorted()} " +
            "rta=${b.readyToAssign} start=${store.budgetStart()}"
    }

    private fun queued(p: Phone) = p.sync.status.value.queued

    // ---- step 2: connect, create, login, pinning, first upload, takeover

    @Test fun creatingTheAccountUploadsTheWholeBudgetAsTheMasterCopy() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample")
        a.create()
        assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state, a.sync.status.value.message)
        assertEquals(ServerSync.PHASE_READY, a.prefs.phase)
        assertNull(server.setupCode, "the setup code is used up")
        val types = server.records.keys.map { it.substringBefore("\u0000") }.toSet()
        assertTrue(types.containsAll(setOf("account", "envelope", "transaction", "transaction_line", "envelope_move", "category", "meta")), "$types")
        assertEquals(a.prefs.pins["http://server.example:5090"], server.keys.publicKey.hex(), "the server key is pinned on first contact")
        assertEquals(0, queued(a))
    }

    @Test fun aDeviceThatLogsInTakesTheServersCopy() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val b = Phone("B")
        assertNotEquals(a.picture(), b.picture())
        assertNull(b.login(), "nothing of its own, so no backup")
        assertEquals(SyncStatus.State.SYNCED, b.sync.status.value.state, b.sync.status.value.message)
        assertEquals(a.picture(), b.picture())
        assertTrue(b.backups.isEmpty())
    }

    @Test fun localDataIsBackedUpThenReplaced() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val b = Phone("B"); b.fill("Zzlocal")
        val where = b.login()
        assertEquals("Downloads/test-backup.ddfbak", where)
        assertEquals(listOf(pw), b.backups, "the backup is encrypted with the account password, made before anything is replaced")
        assertEquals(a.picture(), b.picture())
        assertFalse(b.picture().contains("Zzlocal"))
        assertEquals(0, queued(b), "the old budget's changes are never sent")
    }

    @Test fun aWrongSetupCodeIsRefusedAndFiveLockItOut() = runBlocking<Unit> {
        val a = Phone("A")
        repeat(5) {
            val e = assertFailsWith<ServerException> { a.sync.createAccount("server.example", "WRNG-CODE", "tester", pw, "Test phone") }
            assertEquals(ServerException.Kind.REFUSED, e.kind)
        }
        assertFailsWith<ServerException> { a.sync.createAccount("server.example", "TEST-CODE", "tester", pw, "Test phone") }
        assertFalse(a.sync.connected)
        assertNull(a.vault.key, "no key is kept for a refused setup")
    }

    @Test fun aWrongPasswordDoesNotLogIn() = runBlocking<Unit> {
        Phone("A").also { it.fill("Sample"); it.create() }
        val b = Phone("B")
        val e = assertFailsWith<ServerException> { b.sync.login("server.example", "tester", "wrong-password", "Test phone 2") { "" } }
        assertEquals(ServerException.Kind.REFUSED, e.kind)
        assertFalse(b.sync.connected)
    }

    @Test fun aDifferentServerKeyLaterIsRefused() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        // the same address now answers with a different key
        a.fake = FakeServer(); a.sync = a.newSync()
        a.actions.saveAccount(null, "Late Savings", AccountType.BANK, 1_00, day)
        a.sync.syncNow()
        assertEquals(SyncStatus.State.ERROR, a.sync.status.value.state)
        assertEquals(ServerClient.KEY_CHANGED, a.sync.status.value.message)
        assertTrue(a.fake.changes.isEmpty(), "nothing was sent to it")
        // and a fresh connect to that address is refused too
        a.prefs.deviceId = null
        assertEquals(ServerException.Kind.KEY_CHANGED, assertFailsWith<ServerException> { a.sync.contact("server.example") }.kind)
    }

    @Test fun thePasswordIsNeverKeptOnTheDevice() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val kept = listOf(a.prefs.serverUrl, a.prefs.serverKey, a.prefs.deviceId, a.prefs.deviceName, a.prefs.username, a.prefs.phase).joinToString()
        assertFalse(kept.contains(pw))
        assertFalse(String(a.vault.key!!, Charsets.ISO_8859_1).contains(pw))
        assertFalse(a.store.dao.changes().any { it.changedFields.contains(pw) })
    }

    // ---- step 3: two-way sync, the offline queue, the lock, notices, devices

    @Test fun changesGoBothWays() = runBlocking<Unit> {
        val a = Phone("A"); val food = a.fill("Sample"); a.create()
        val b = Phone("B"); b.login()
        // the lock follows the app in front: A edits, B reads it
        a.sync.foreground()
        a.actions.move(null, food, 25_00, date = day)
        a.sync.syncNow()
        b.sync.syncNow()
        assertEquals(a.picture(), b.picture())
        a.sync.background()
        b.sync.foreground()
        b.actions.saveAccount(null, "Shared Savings", AccountType.BANK, 10_00, day)
        b.sync.syncNow()
        a.sync.syncNow()
        assertEquals(b.picture(), a.picture())
        assertTrue(a.picture().contains("Shared Savings"))
    }

    @Test fun anOfflineEditIsQueuedThenSent() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        server.online = false
        a.actions.saveAccount(null, "Offline Savings", AccountType.BANK, 2_00, day)
        a.sync.syncNow()
        assertEquals(SyncStatus.State.OFFLINE, a.sync.status.value.state)
        assertTrue(queued(a) > 0)
        server.online = true
        a.sync.syncNow()
        assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state)
        assertEquals(0, queued(a))
        assertTrue(server.records.values.any { it.data.optString("name") == "Offline Savings" })
    }

    @Test fun nothingIsLostWhenTheAppClosesWhileOffline() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        server.online = false
        a.actions.saveAccount(null, "Closed Savings", AccountType.BANK, 3_00, day)
        a.sync.syncNow()
        val waiting = queued(a)
        assertTrue(waiting > 0)
        // the app closes: a new start has only what was saved (the database, the prefs, the key)
        a.sync = a.newSync()
        server.online = true
        a.sync.syncNow()
        assertEquals(0, queued(a))
        assertTrue(server.records.values.any { it.data.optString("name") == "Closed Savings" })
    }

    @Test fun theLockIsTakenInFrontAndLetGoBehind() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        a.sync.foreground()
        assertEquals(a.prefs.deviceId, server.lockHolder)
        assertTrue(server.lockExpires > System.currentTimeMillis() + 100_000, "heartbeat keeps it for 120 s")
        a.sync.background()
        assertNull(server.lockHolder)
    }

    @Test fun readOnlyWhileAnotherDeviceHoldsTheLock() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val desk = server.otherDevice("Test desktop")
        server.lockAs(desk)
        a.sync.foreground()
        assertEquals("Test desktop", a.sync.status.value.readOnlyBy)
        val e = assertFailsWith<ReadOnlyException> { a.actions.saveAccount(null, "Blocked", AccountType.BANK, 1_00, day) }
        assertTrue(e.message!!.contains("Test desktop"))
        assertFalse(a.picture().contains("Blocked"))
        // what the app does on its own still goes in, and reaches the server as an offline change
        withContext(BackgroundWrite) { a.actions.saveAccount(null, "Background Read", AccountType.BANK, 1_00, day) }
        a.sync.syncNow()
        assertTrue(server.changes.any { it.offline && it.change.getJSONObject("changedFields").optString("name") == "Background Read" })
        // once it lets go, this one can edit
        server.lockHolder = null
        a.sync.tick()
        assertNull(a.sync.status.value.readOnlyBy)
        a.actions.saveAccount(null, "Now Allowed", AccountType.BANK, 1_00, day)
    }

    @Test fun aLostChangeShowsALastWriteWinsNote() = runBlocking<Unit> {
        val a = Phone("A"); val food = a.fill("Sample"); a.create()
        val desk = server.otherDevice("Test desktop")
        // A renames Food while offline; the desktop renames it later, and gets to the server first
        server.online = false
        a.actions.saveEnvelope(food, EnvelopeDraft("Phone Name", EnvelopeMode.FILL_TO_TARGET, target = 200_00), day)
        val later = System.currentTimeMillis() + 60_000
        server.online = true
        server.pushAs(desk, listOf(JSONObject().put("changeId", java.util.UUID.randomUUID().toString()).put("entityType", "envelope").put("entityId", food)
            .put("operation", "UPDATE").put("changedFields", JSONObject().put("name", "Desktop Name")).put("timestamp", later)))
        a.sync.syncNow()
        assertTrue(a.picture().contains("Desktop Name"), "the newer change wins here too")
        assertFalse(a.picture().contains("Phone Name"))
        assertEquals(1, a.sync.notices.value.size, "${a.sync.notices.value}")
        assertTrue(a.sync.notices.value.single().contains("Test desktop"))
        // shown once
        a.sync.syncNow()
        assertEquals(1, a.sync.notices.value.size)
    }

    @Test fun theSameChangeIsTakenInOnce() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val row = net.darkrelay.deaddropfinance.data.AccountEntity(name = "Dup Account", type = AccountType.BANK, startDate = day, createdAt = 1_000, updatedAt = 1_000)
        val fields = JSONObject().also { f -> row.fields().forEach { (k, v) -> f.put(k, v ?: JSONObject.NULL) } }
        val ch = ServerChange(99, "dup-change-1", "account", "dup-account-1", "INSERT", fields, 1_000, "other")
        a.sync.applyRemote(listOf(ch))
        a.sync.applyRemote(listOf(ch))
        assertEquals(1, a.store.dao.changes().count { it.changeId == "dup-change-1" })
        assertEquals(1, a.store.dao.accountsNow().count { it.name == "Dup Account" })
    }

    @Test fun revokingADeviceCutsItOff() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        val b = Phone("B"); b.login()
        val bId = b.prefs.deviceId!!
        assertTrue(a.sync.devices().any { it.id == bId })
        a.sync.revoke(bId)
        b.sync.syncNow()
        assertEquals(SyncStatus.State.ERROR, b.sync.status.value.state)
        assertEquals(ServerClient.REVOKED, b.sync.status.value.message)
        val before = server.changes.size
        b.actions.saveAccount(null, "After Revoke", AccountType.BANK, 1_00, day)
        b.sync.syncNow()
        assertEquals(before, server.changes.size, "nothing from it is taken")
        assertTrue(b.picture().contains("After Revoke"), "its own budget stays")
    }

    @Test fun loggingOutLetsGoOfTheLockAndDeletesTheKey() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample"); a.create()
        a.sync.foreground()
        val id = a.prefs.deviceId!!
        val pic = a.picture()
        a.sync.logout()
        assertNull(server.lockHolder)
        assertNull(a.vault.key)
        assertFalse(a.sync.connected)
        assertNotNull(server.devices[id]!!.revokedAt, "removed from the server too")
        assertEquals(SyncStatus.State.NOT_CONNECTED, a.sync.status.value.state)
        assertEquals(pic, a.picture(), "the budget stays here")
        assertEquals(server.keys.publicKey.hex(), a.prefs.pins["http://server.example:5090"], "the pin stays")
        // edits are allowed again (no lock to respect)
        a.actions.saveAccount(null, "Solo", AccountType.BANK, 1_00, day)
    }

    @Test fun everySyncedColumnIsInTheChangeLog() = runBlocking<Unit> {
        val a = Phone("A"); a.fill("Sample")
        val d = a.store.dao
        val rows: Map<String, net.darkrelay.deaddropfinance.data.Synced> = mapOf(
            "account" to d.accountsNow().first(), "envelope" to d.envelopesNow().first(), "transaction" to d.transactionsNow().first(),
            "transaction_line" to d.linesNow().first(), "envelope_move" to d.movesNow().first(), "category" to d.categoriesNow().first(),
        )
        a.store.db.rawTransaction { raw ->
            for ((type, e) in rows) {
                val cols = ServerSync.columns(raw, ServerSync.TABLES.getValue(type)) - setOf("id", "created_at", "updated_at")
                assertEquals(cols, e.fields().keys, "$type: every column is in its fields(), so a row from the server is whole")
            }
        }
    }
}
