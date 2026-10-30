package net.darkrelay.deaddropfinance.desktop

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.ServerSync
import net.darkrelay.deaddropfinance.serversync.SyncStatus
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0 end to end against a real, throwaway Dead Drop server (never the live one): edits made on the phone's
 * screens in the program (Bills, Manage categories, Parked buys, a rule from More) reach a phone through sync.
 * Skipped unless DDF_E2E_URL and DDF_E2E_CODE (that test server's setup code) are set; the server must be new.
 */
class ProgramScreensE2ETest : ProgramScreensBase() {

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)
    // once the program shows its budget, ProgramScreensBase closes it after the test
    private var shown = false

    private fun until(what: String, ms: Long = 20_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    @Test fun editsOnThePhonesScreensInTheProgramReachThePhone() {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engines = mutableListOf<Engine>()
        try {
            // a phone creates the account
            val pEngine = runBlocking { Engine.start(tmp.newFolder("P")) }.also { engines += it }
            val pStore = (pEngine.opened as DesktopDatabase.Opened.Ok).store
            runBlocking { pEngine.actions!!.saveAccount(null, "E2E Phone Checking", AccountType.BANK, 300_00, today.minusDays(5)) }
            val phone = ServerSync(pStore, DesktopE2ETest.MemoryPrefs(), DesktopE2ETest.MemoryVault(), scope, transportFor = { HttpTransport(url) }, kind = "phone", sendDelayMs = 60_000)
            runBlocking { phone.createAccount(url, code, "e2e", pw, "E2E phone") }
            assertEquals(SyncStatus.State.SYNCED, phone.status.value.state, phone.status.value.message)
            runBlocking { phone.background() }

            // the program logs in and holds the edit lock (its window is open)
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

            // Bills: a new bill
            click("nav.BILLS")
            clickText("Add", exact = true)
            typeInto("Name", "E2E Program Bill")
            typeInto("Amount", "25.00")
            typeInto("Next due (yyyy-mm-dd)", today.plusDays(6).toString())
            clickText("Save", exact = true)
            compose.waitUntil(8_000) { runBlocking { store().dao.billsNow() }.any { it.name == "E2E Program Bill" } }
            // Manage categories: a new category
            click("nav.CATEGORIES")
            clickText("Manage", exact = true)
            typeInto("New category", "E2E Program Category")
            clickText("Add", exact = true)
            compose.waitUntil(8_000) { runBlocking { store().dao.categoriesNow() }.any { it.name == "E2E Program Category" } }
            // Parked buys: something parked
            click("nav.MORE")
            clickText("Parked buys", exact = true)
            clickText("Park something", exact = true)
            typeInto("What is it?", "E2E Program Lamp")
            typeInto("Price", "30.00")
            clickText("Park it", exact = true)
            compose.waitUntil(8_000) { runBlocking { store().dao.parkedBuysNow() }.any { it.name == "E2E Program Lamp" } }

            // the program sends, the phone takes
            desk.hidden()
            runBlocking { dSync.syncNow() }
            until("the program let go") { runBlocking { phone.foreground(); phone.status.value.readOnlyBy == null } }
            runBlocking { phone.syncNow() }
            assertTrue(runBlocking { pStore.dao.billsNow() }.any { it.name == "E2E Program Bill" && it.amountDue == 25_00L }, "the program's bill reached the phone")
            assertTrue(runBlocking { pStore.dao.categoriesNow() }.any { it.name == "E2E Program Category" }, "the program's category reached the phone")
            assertTrue(runBlocking { pStore.dao.parkedBuysNow() }.any { it.name == "E2E Program Lamp" && it.amount == 30_00L }, "the program's parked buy reached the phone")
            runBlocking { phone.logout() }
        } finally {
            scope.cancel()
            // the program's budget is closed after the test (ProgramScreensBase); the phone's here
            engines.drop(1).firstOrNull()?.let { if (!shown) runCatching { it.close() } }
            engines.firstOrNull()?.let { runCatching { it.close() } }
        }
    }
}
