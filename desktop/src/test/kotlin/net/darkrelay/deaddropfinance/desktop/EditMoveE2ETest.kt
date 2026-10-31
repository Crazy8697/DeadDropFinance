package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Edit a move end to end (0.14.0) against a real, throwaway Dead Drop server (never the live one): a move edited in
 * the program's Activity reaches the phone with both envelopes' balances; then the phone edits it (the shared editMove
 * its Edit move screen calls) and the program takes it. Skipped unless DDF_E2E_URL and DDF_E2E_CODE (that test
 * server's setup code) are set; the server must be new.
 */
class EditMoveE2ETest : ProgramScreensBase() {

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)
    private var shown = false

    private fun until(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test fun aMoveEditedOnEitherSideReachesTheOther() {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engines = mutableListOf<Engine>()
        try {
            // a phone with two envelopes and a move between them creates the account
            val pEngine = runBlocking { Engine.start(tmp.newFolder("P")) }.also { engines += it }
            val pStore = (pEngine.opened as DesktopDatabase.Opened.Ok).store
            val pa = BudgetActions(pStore)
            val (food, gas) = runBlocking {
                val acct = pa.saveAccount(null, "E2E Phone Checking", AccountType.BANK, 0, today.minusDays(5))
                pStore.setBudgetStart(today.minusDays(5))
                pa.saveTransaction(null, BudgetActions.TransactionDraft(acct, today.minusDays(2), "E2E Payroll", "", 400_00, listOf(BudgetActions.LineDraft(null, 400_00, toReadyToAssign = true))))
                val f = pa.saveEnvelope(null, BudgetActions.EnvelopeDraft("E2E Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00), today.minusDays(5))
                val g = pa.saveEnvelope(null, BudgetActions.EnvelopeDraft("E2E Gas", EnvelopeMode.FILL_TO_TARGET, target = 100_00), today.minusDays(5))
                pa.move(null, f, 200_00, MoveKind.ASSIGN, today.minusDays(1))
                pa.move(f, g, 50_00, MoveKind.MOVE, today)
                f to g
            }
            val moveId = runBlocking { pa.snapshot() }.moves.single { it.kind == MoveKind.MOVE }.id
            val phone = ServerSync(pStore, DesktopE2ETest.MemoryPrefs(), DesktopE2ETest.MemoryVault(), scope, transportFor = { HttpTransport(url) }, kind = "phone", sendDelayMs = 60_000)
            runBlocking { phone.createAccount(url, code, "e2e", pw, "E2E phone") }
            assertEquals(SyncStatus.State.SYNCED, phone.status.value.state, phone.status.value.message)
            runBlocking { phone.background() }

            // the program logs in and holds the edit lock
            val dDir = tmp.newFolder("D")
            val dEngine = runBlocking { Engine.start(dDir) }.also { engines += it }
            val d = AppModel(dEngine)
            val dSync = ServerSync((dEngine.opened as DesktopDatabase.Opened.Ok).store, DesktopSyncPrefs(dDir), DesktopKeyVault(dDir, TestProtector), scope,
                transportFor = { HttpTransport(url) }, kind = "desktop", sendDelayMs = 60_000,
                recovery = net.darkrelay.deaddropfinance.backup.RecoveryKeys(net.darkrelay.deaddropfinance.backup.MemoryRecoveryKeyStore()).also { d.recoveryKeys = it; it.ensure(); it.confirmSaved() })
            val desk = ServerModel(dSync, d, scope, File(dDir, "Downloads"), defaultDeviceName = "E2E desktop").also { d.server = it }
            runBlocking { assertTrue(desk.connect(url), desk.error); assertTrue(desk.login("e2e", pw, "E2E desktop"), desk.error); d.refresh() }
            desk.shown()
            until("the program holds the edit lock") { dSync.holdsEditLock }
            showOn(d)
            shown = true

            // the program edits the move in Activity: 50.00 becomes 80.00
            click("nav.ACTIVITY")
            click("tab.All")
            click("act.Moved from E2E Food")
            until("Edit move")
            tag("f.amount").performTextClearance(); tag("f.amount").performTextInput("80.00"); compose.waitForIdle()
            click("dialog.save")
            compose.waitUntil(8_000) { b().moves.single { it.id == moveId }.amount == 80_00L }

            desk.hidden()
            runBlocking { dSync.syncNow() }
            until("the program let go") { runBlocking { phone.foreground(); phone.status.value.readOnlyBy == null } }
            runBlocking { phone.syncNow() }
            val pb = runBlocking { pa.snapshot() }
            assertEquals(80_00L, pb.moves.single { it.id == moveId }.amount, "the program's edit reached the phone")
            assertEquals(120_00L, pb.envelopeBalance(food))
            assertEquals(80_00L, pb.envelopeBalance(gas))

            // the phone edits it back to 10.00, the program takes it
            runBlocking { pa.editMove(moveId, food, gas, 10_00, today) }
            runBlocking { phone.syncNow(); phone.background() }
            runBlocking { dSync.syncNow(); d.refresh() }
            until("the phone's edit reached the program") { runBlocking { dSync.syncNow(); d.refresh() }; b().moves.single { it.id == moveId }.amount == 10_00L }
            assertEquals(190_00L, b().envelopeBalance(food))
            assertEquals(10_00L, b().envelopeBalance(gas))
            runBlocking { phone.logout() }
        } finally {
            scope.cancel()
            engines.drop(1).firstOrNull()?.let { if (!shown) runCatching { it.close() } }
            engines.firstOrNull()?.let { runCatching { it.close() } }
        }
    }
}
