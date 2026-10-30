package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
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
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Restore while logged in (1.7.2), end to end against a real, throwaway Dead Drop server (never the live one): a program
 * makes the account, a phone pairs by its QR code and takes the server's copy, the phone restores an older (synthetic)
 * backup file while logged in, and the program takes the restored budget at its next sync, saving its own first. Real HTTP,
 * real envelopes, real backup files, invented budgets in temp folders. Skipped unless DDF_E2E_URL and DDF_E2E_CODE (that
 * test server's setup code) are set; the server must be new. The server's own backup is checked from outside afterwards.
 */
class RestoreE2ETest {

    @get:Rule val tmp = TemporaryFolder()

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)

    inner class Device(name: String, val kind: String, val scope: CoroutineScope) {
        val file: java.io.File
        val store: BudgetStore
        init {
            val o = runBlocking { assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(name))) }
            runBlocking { o.store.ensureSystemEnvelopes(); o.store.ensureCategories() }
            store = o.store; file = o.file
        }
        val actions = BudgetActions(store)
        val prefs = ServerSyncTest.MemoryPrefs()
        val vault = ServerSyncTest.MemoryVault()
        val keys = RecoveryKeys(MemoryRecoveryKeyStore())
        val saved = mutableListOf<String>()
        val sync = ServerSync(store, prefs, vault, scope, transportFor = { HttpTransport(it) }, kind = kind, sendDelayMs = 60_000, recovery = keys)
            .also { s -> s.backupBeforeReplaced = { k -> saved += RecoveryKey.id(k); "Downloads/DeadDropFinance/replaced.ddfbak" } }
        val backups get() = DesktopBackups(store.db, file, "e2e-test", { 1_000 })
        suspend fun fill(label: String) {
            val acct = actions.saveAccount(null, "$label Checking", AccountType.BANK, 900_00, day)
            val food = actions.saveEnvelope(null, EnvelopeDraft("$label Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), day)
            actions.move(null, food, 120_00, date = day)
            val cat = actions.snapshot().categories.first { it.name == "Groceries" }.id
            actions.saveTransaction(null, TransactionDraft(acct, day, "$label Market", "", -35_00, listOf(LineDraft(food, -35_00, categoryId = cat))))
            store.setBudgetStart(day)
        }
        suspend fun picture(): String {
            val b = actions.snapshot()
            return "accounts=${b.accounts.map { it.name to b.accountBalance(it.id) }.sortedBy { it.first }} " +
                "envelopes=${b.fillOrder.map { it.name to b.envelopeBalance(it.id) }} txs=${b.transactions.map { it.payee to it.amount }.sortedBy { it.first }} " +
                "lines=${b.lines.size} moves=${b.moves.size} rta=${b.readyToAssign} start=${store.budgetStart()}"
        }
    }

    @Test fun aPhoneRestoresWhileLoggedInAndTheProgramTakesIt() = runBlocking<Unit> {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val opened = mutableListOf<Device>()
        try {
            // the older backup: an invented budget, written to a real file under its own recovery key
            val older = Device("Older", "phone", scope).also { opened += it }
            older.fill("Older")
            val olderKey = older.keys.ensure().also { older.keys.confirmSaved() }
            val file = tmp.newFile("older.ddfbak")
            file.outputStream().use { older.backups.write(it, olderKey) }
            val olderPicture = older.picture()

            // 1. the program makes the account with its own budget
            val program = Device("Program", "desktop", scope).also { opened += it }
            program.fill("Program")
            program.sync.createAccount(url, code, "e2e", pw, "E2E program")
            assertEquals(SyncStatus.State.SYNCED, program.sync.status.value.state, program.sync.status.value.message)

            // 2. the phone pairs by the program's QR code and takes the server's copy (it has nothing of its own)
            program.sync.background()
            val (_, qrText) = program.sync.newPairingCode("E2E phone")
            val info = assertNotNull(PairingQr.parse(qrText))
            val phone = Device("Phone", "phone", scope).also { opened += it }
            assertEquals(null, phone.sync.pair(info, "E2E phone") { error("nothing to back up") })
            assertEquals(program.picture(), phone.picture())
            program.sync.syncNow(); phone.sync.syncNow()     // the account's recovery key reaches the phone
            assertTrue(program.picture().contains("Program Checking"))

            // 3. the phone restores the older backup while logged in: it holds the lock, the server backs up, the budget goes up
            phone.sync.foreground()
            assertTrue(phone.sync.holdsEditLock, phone.sync.status.value.message)
            phone.sync.restoreAsMaster { file.inputStream().use { phone.backups.restore(it, BackupSecret.Key(olderKey)) } }
            assertEquals(SyncStatus.State.SYNCED, phone.sync.status.value.state, phone.sync.status.value.message)
            assertEquals(olderPicture, phone.picture(), "the phone has the older budget")
            assertEquals(0, phone.sync.status.value.queued)
            phone.sync.background()

            // 4. the program takes it at its next sync, with its own backup made first
            program.sync.foreground()
            assertEquals(SyncStatus.State.SYNCED, program.sync.status.value.state, program.sync.status.value.message)
            assertEquals(olderPicture, program.picture(), "the program has the restored budget")
            assertFalse(program.picture().contains("Program Checking"))
            assertEquals(1, program.saved.size, "its own budget was saved first")
            assertTrue(program.sync.notices.value.single().contains("replaced by a backup restored on another device"))
            assertEquals(0, program.sync.status.value.queued)

            // 5. it keeps working both ways afterwards
            val food = phone.actions.snapshot().fillOrder.first { it.name == "Older Food" }.id
            program.sync.foreground()
            program.actions.move(null, food, 5_00, date = day)
            program.sync.syncNow()
            phone.sync.syncNow()
            assertEquals(program.picture(), phone.picture())
        } finally {
            scope.cancel()
            opened.forEach { it.store.db.close() }
        }
    }
}
