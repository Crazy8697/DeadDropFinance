package net.darkrelay.deaddropfinance.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.Purger
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.sync.SyncSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * After Purge data the app opens at the setup wizard, and its SimpleFIN step shows bank sync as connected (the kept
 * connection), with no setup token to paste. The screen needs the debug build's test activity, so release unit tests
 * skip it; the purge itself is tested in sync/PurgeTest. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PurgeWizardTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()

    @After fun tearDown() = db.close()

    @Test fun afterThePurgeTheAppOpensAtTheWizardWithBankSyncConnected() = runBlocking<Unit> {
        val store = BudgetStore(db)
        val a = BudgetActions(store)
        store.ensureSystemEnvelopes()
        val acct = a.saveAccount(null, "Sample Everyday", AccountType.BANK, 100_00, LocalDate.of(2030, 1, 1))
        db.syncDao().upsertConnection(ConnectionEntity(id = "c1", provider = Provider.SIMPLEFIN, name = "Sample Bridge", createdAt = 1))
        db.syncDao().upsertLinked(LinkedAccountEntity(id = "l1", connectionId = "c1", providerAccountId = "ACT-1", providerName = "Sample Checking", accountId = acct))
        val prefs = ctx.getSharedPreferences("purge-wizard-sync", Context.MODE_PRIVATE).also { it.edit(commit = true) { clear(); putBoolean("setup_done", true) } }
        val settings = SyncSettings(prefs)
        Purger(db, null, listOf(prefs), emptyList()).purge()
        settings.setupAfterPurge = true
        val connections = db.syncDao().connectionsNow()
        val budget = a.snapshot()
        // a fresh install's wizard, even though a connection exists
        assertEquals(SetupMode.WIZARD, setupMode(settings.setupDone, settings.setupCardDismissed, budget.accounts.isNotEmpty(), connections.isNotEmpty(), settings.setupAfterPurge))
        assertEquals(SetupMode.NONE, setupMode(false, false, hasAccounts = false, hasConnections = true))
        val links = db.syncDao().linkedOf("c1")
        compose.setContent {
            DdfTheme {
                SetupWizardHost(
                    page = WizardPage.CONNECT, forced = true, budget = budget, connections = connections, links = links,
                    ui = ConnectUi(), picks = emptyMap(), linkBusy = false, syncMessage = null, starters = emptySet(),
                    more = MoreState(true, true, 5, false, true, false, null, "0.0.0"), hasAccounts = false,
                    onPage = {}, onDoor = {}, door = Door.EASY, onConnected = {}, wizardConn = "c1", onSimpleFin = {}, onApplyLinks = {},
                    onPick = { _, _ -> }, onLock = {}, onAddAccount = {}, onImport = {}, onOpen = {}, onToggleStarter = {}, onAddStarters = {}, onFinish = {},
                )
            }
        }
        compose.onNodeWithText("Bank sync is connected: Sample Bridge").assertExists()
        compose.onNodeWithText("Next").assertExists()
        // and the Link step lists the kept bank account, linked to nothing
        assertEquals(listOf<String?>(null), links.map { it.accountId })
    }
}
