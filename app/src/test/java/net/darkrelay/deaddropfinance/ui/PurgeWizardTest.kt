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
 * After Purge data the app opens at the setup wizard, whose way-in step is Old School (CSV import or entering things
 * by hand), with no bank sync step (removed in 1.3.0). The screen needs the debug build's test activity, so release
 * unit tests skip it; the purge itself is tested in data/PurgeTest. Synthetic data only.
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

    @Test fun afterThePurgeTheAppOpensAtTheWizardWithOldSchoolAndNoBankSync() = runBlocking<Unit> {
        val store = BudgetStore(db)
        val a = BudgetActions(store)
        store.ensureSystemEnvelopes()
        a.saveAccount(null, "Sample Everyday", AccountType.BANK, 100_00, LocalDate.of(2030, 1, 1))
        val prefs = ctx.getSharedPreferences("purge-wizard-sync", Context.MODE_PRIVATE).also { it.edit(commit = true) { clear(); putBoolean("setup_done", true) } }
        val settings = SyncSettings(prefs)
        Purger(db, null, listOf(prefs), emptyList()).purge()
        settings.setupAfterPurge = true
        val budget = a.snapshot()
        assertEquals(SetupMode.WIZARD, setupMode(settings.setupDone, settings.setupCardDismissed, budget.accounts.isNotEmpty(), settings.setupAfterPurge))
        compose.setContent {
            DdfTheme {
                SetupWizardHost(
                    page = WizardPage.CONNECT, forced = true, budget = budget, starters = emptySet(),
                    more = MoreState(true, true, 5, false, true, false, null, "0.0.0"), hasAccounts = false,
                    onPage = {}, onLock = {}, onAddAccount = {}, onImport = {}, onToggleStarter = {}, onAddStarters = {}, onFinish = {},
                )
            }
        }
        compose.onNodeWithText("Old School").assertExists()
        compose.onNodeWithText("Import a CSV file").assertExists()
        compose.onNodeWithText("Setup, step 3 of 5").assertExists()
        compose.onNodeWithText("SimpleFIN", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Bank sync", substring = true).assertDoesNotExist()
    }
}
