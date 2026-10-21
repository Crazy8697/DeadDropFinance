package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeEntity
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveEntity
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.CsvImport
import net.darkrelay.deaddropfinance.domain.Payday
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runners.model.Statement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.ConnectionStatus
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.sync.FixCode
import net.darkrelay.deaddropfinance.sync.SyncService
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshots of every screen on the JVM (Roborazzi), with invented data and with the empty states a new install
 * shows. Runs only when asked for (-Pddf.screens=true); the PNGs go to app/build/outputs/roborazzi.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {

    private val compose = createComposeRule()

    /** Skips before the compose rule starts an activity, so release unit tests (no test activity) skip cleanly. */
    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() {
            override fun evaluate() { assumeTrue(System.getProperty("ddf.screens") == "true"); base.evaluate() }
        } }
        .around(compose)

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            DdfTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { content() } } }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    // ---- invented data -----------------------------------------------------------------------------------------
    private val today = LocalDate.of(2030, 3, 15)
    private fun acct(id: String, name: String, type: AccountType, start: Long) =
        AccountEntity(id = id, name = name, type = type, startingBalance = start, startDate = today.minusDays(40), createdAt = 0, updatedAt = 0)
    private fun env(id: String, name: String, mode: EnvelopeMode, order: Int, target: Long? = null, per: Long? = null) =
        EnvelopeEntity(id = id, name = name, mode = mode, target = target, perPayday = per, sortOrder = order, createdAt = 0, updatedAt = 0)
    private fun tx(id: String, acct: String, payee: String, amount: Long, days: Long, payday: Boolean = false, pending: Boolean = false, memo: String = "") =
        TransactionEntity(id = id, accountId = acct, date = today.minusDays(days), payee = payee, memo = memo, amount = amount, isPayday = payday, pending = pending, createdAt = days, updatedAt = 0)
    private fun line(tx: String, amount: Long, env: String? = null, rta: Boolean = false) =
        TransactionLineEntity(transactionId = tx, bucket = if (env != null) LineBucket.ENVELOPE else if (rta) LineBucket.READY_TO_ASSIGN else LineBucket.UNSORTED, envelopeId = env, amount = amount, createdAt = 0, updatedAt = 0)
    private fun move(from: String?, to: String?, amount: Long) = MoveEntity(fromEnvelopeId = from, toEnvelopeId = to, amount = amount, date = today, kind = MoveKind.ASSIGN, createdAt = 0, updatedAt = 0)

    private val budget = Budget(
        accounts = listOf(acct("chk", "Everyday Checking", AccountType.BANK, 250_00), acct("app", "Pay App", AccountType.PAYMENT_APP, 0), acct("cash", "Wallet", AccountType.CASH, 0)),
        envelopes = listOf(
            env("roll", "Rollover", EnvelopeMode.ROLLOVER, 0),
            env("food", "Groceries", EnvelopeMode.FILL_TO_TARGET, 1, target = 400_00),
            env("fuel", "Fuel", EnvelopeMode.FILL_TO_TARGET, 2, target = 150_00),
            env("save", "Rainy Day", EnvelopeMode.ADD_EVERY_PAYDAY, 3, per = 50_00),
            env("trip", "Weekend Trip", EnvelopeMode.GOAL, 4).copy(goalAmount = 600_00, goalDate = today.plusDays(70)),
            env("heat", "Heating", EnvelopeMode.SEASONAL, 5, per = 40_00),
            env("debt", "Card Catch-up", EnvelopeMode.DEBT, 6, per = 60_00).copy(debtOwed = 900_00, debtOwedSince = today.minusDays(30)),
        ),
        transactions = listOf(
            tx("pay", "chk", "Sample Payroll Co", 1800_00, 10, payday = true),
            tx("shop", "chk", "Corner Grocer", -82_15, 6),
            tx("gas", "chk", "Quick Fuel Stop", -35_20, 4),
            tx("unk", "chk", "Unknown Store", -12_50, 2),
            tx("gift", "app", "Transfer from family", 60_00, 1, memo = "Water bill"),
            tx("pend", "chk", "Book Shop", -20_00, 0, pending = true),
            TransactionEntity(id = "atm", accountId = "chk", kind = TransactionKind.TRANSFER, transferAccountId = "cash", date = today.minusDays(3), amount = 40_00, createdAt = 3, updatedAt = 0),
        ),
        lines = listOf(
            line("pay", 1800_00, rta = true), line("shop", -60_00, "food"), line("shop", -22_15, "save"), line("gas", -35_20, "fuel"),
            line("unk", -12_50), line("gift", 60_00, rta = true), line("pend", -20_00, "food"),
        ),
        moves = listOf(move(null, "food", 400_00), move(null, "fuel", 20_00), move(null, "save", 50_00), move(null, "trip", 100_00), move(null, "debt", 60_00), move(null, "roll", 30_00)),
    )
    private val empty = Budget(emptyList(), listOf(env("roll", "Rollover", EnvelopeMode.ROLLOVER, 0)), emptyList(), emptyList(), emptyList())
    private val more = MoreState(true, true, 5, false, true, false, null, "0.7.0")
    private val release = UpdateCheck.Release("99.1.0", "https://example.com/notes")

    private companion object { const val TALL = "w400dp-h1500dp-xhdpi" }

    @Test fun home() = shot("01-home") { HomeScreen(budget, null, {}, {}, {}, {}) }
    @Test fun homeWithUpdate() = shot("02-home-update-bar") { HomeScreen(budget, { UpdateBar(release, {}, {}, {}, {}) }, {}, {}, {}, {}) }
    @Test fun homeEmpty() = shot("03-home-empty") { HomeScreen(empty, null, {}, {}, {}, {}) }
    @Test fun envelopes() = shot("04-envelopes") { EnvelopesScreen(budget, {}, {}, {}, {}) }
    @Test fun envelopesEmpty() = shot("05-envelopes-empty") { EnvelopesScreen(empty, {}, {}, {}, {}) }
    @Test fun envelopeDetailFill() = shot("06-envelope-detail") { EnvelopeDetailScreen(budget.envelopes[2], budget, today, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun envelopeDetailDebt() = shot("07-envelope-detail-debt") { EnvelopeDetailScreen(budget.envelopes[6], budget, today, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun envelopeEdit() = shot("08-envelope-edit") { EnvelopeEditScreen(budget.envelopes[4], null, {}, {}) }
    @Test fun moveMoney() = shot("09-move-money") { MoveMoneyScreen(budget, "roll", null, {}, { _, _, _ -> }) }
    @Test @Config(qualifiers = TALL) fun assign() = shot("10-assign") {
        AssignScreen(budget, today, null, {}, {}, { _, _ -> }, listOf(Payday.Shortfall("heat", 40_00, 15_00)))
    }
    @Test fun activity() = shot("11-activity") { ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}) }
    @Test fun activityEmpty() = shot("12-activity-empty") { ActivityScreen(empty, {}, {}, { _, _, _ -> }, {}) }
    @Test @Config(qualifiers = TALL) fun transactionSplit() = shot("13-transaction-split") { TransactionEditScreen(budget, budget.transactions[1], true, null, {}, {}, { _, _, _, _, _, _ -> }) }
    @Test fun transactionNew() = shot("14-transaction-new") { TransactionEditScreen(budget, null, false, null, {}, {}, { _, _, _, _, _, _ -> }) }
    @Test fun transactionDetail() = shot("15-transaction-detail") {
        TransactionDetailScreen(budget.transactions[4], budget, emptyList(), null, {}, {}, {}, {}, {}, {})
    }
    @Test fun more() = shot("16-more") { MoreScreen(more, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun moreNoScreenLock() = shot("17-more-no-screen-lock") { MoreScreen(more.copy(lockAvailable = false), {}, {}, {}, {}, {}, {}, {}) }
    @Test fun accounts() = shot("18-accounts") { AccountsScreen(budget, {}, {}, {}) }
    @Test fun accountsEmpty() = shot("19-accounts-empty") { AccountsScreen(empty, {}, {}, {}) }
    @Test fun accountEdit() = shot("20-account-edit") { AccountEditScreen(null, null, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun import() = shot("21-import-preview") {
        val text = "Account: Sample\n\nDate,Description,Debit,Credit,Balance\n03/01/2030,Corner Grocer,40.00,,960.00\n03/02/2030,Sample Payroll Co,,900.00,1860.00\n03/02/2030,Bad Row,,,1860.00\n"
        ImportScreen(budget, ImportState("sample.csv", text, CsvImport.detect(text), "chk"), {}, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun importDuplicates() = shot("25-import-possible-duplicates") {
        val text = "Date,Description,Amount\n2030-03-01,Sample Transfer Out,-25.00\n2030-03-01,Sample Transfer Out,-25.00\n2030-03-01,Sample Transfer Out,-25.00\n2030-03-02,Corner Grocer,-18.40\n2030-03-02,Corner Grocer,-18.40\n2030-03-04,Sample Payroll Co,900.00\n"
        val layout = CsvImport.detect(text)
        ImportScreen(budget, ImportState("sample.csv", text, layout, "chk", skippedRows = setOf(4)), {}, {}, {}, {}, {}, {})
    }
    @Test fun importEmpty() = shot("22-import-no-accounts") { ImportScreen(empty, ImportState(), {}, {}, {}, {}, {}) }
    @Test fun lock() = shot("23-lock") { LockScreen({}, null) }
    @Test fun unavailable() = shot("24-data-unavailable") { DataUnavailableScreen("the stored key can't be unlocked on this phone") }

    // ---- 0.7.0: setup wizard and bank sync (invented connections; no secret, masked or not, looks real) ------------
    private val at = today.atTime(9, 30).toEpochSecond(java.time.ZoneOffset.UTC) * 1000
    private val conns = listOf(
        ConnectionEntity("c1", Provider.SIMPLEFIN, "SimpleFIN", null, ConnectionStatus.OK, null, at, "3 new transactions, 1 updated.", null, 0),
        ConnectionEntity("c2", Provider.SIMPLEFIN, "SimpleFIN", null, ConnectionStatus.NEEDS_FIX, FixCode.SIMPLEFIN_TOKEN, at - 86_400_000,
            "SimpleFIN no longer accepts this connection. Get a new setup token from the Bridge and connect again.", "Example Credit Union needs you to log in again.", 0),
    )
    private val links = listOf(
        LinkedAccountEntity("l1", "c1", "ACT-A", "Sample Bank Checking", "", "chk", 240_00, at),
        LinkedAccountEntity("l2", "c1", "ACT-B", "Sample Bank Savings", "", null, 1_000_00, at),
        LinkedAccountEntity("l3", "c2", "ACT-1", "Example Credit Union Card", "", "app", -55_00, at),
    )
    @Composable private fun Page(content: @Composable () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) { content() }

    @Test fun wizardWelcome() = shot("30-wizard-welcome") { WizardFrame(WizardPage.WELCOME, "Welcome", null, "Start" to {}) { WelcomePage() } }
    @Test fun wizardLock() = shot("31-wizard-lock") { WizardFrame(WizardPage.LOCK, "App lock", {}, "Next" to {}) { LockPage(true, true) {} } }
    @Test fun wizardLockNoScreenLock() = shot("32-wizard-lock-no-screen-lock") { WizardFrame(WizardPage.LOCK, "App lock", {}, "Next" to {}) { LockPage(false, false) {} } }
    @Test fun wizardDoor() = shot("33-wizard-door") { WizardFrame(WizardPage.DOOR, "Pick your door", {}, null) { DoorList({}) } }
    @Test @Config(qualifiers = TALL) fun wizardSimpleFin() = shot("36-wizard-simplefin") {
        val ui = ConnectUi().apply { error = "SimpleFIN says that token is already used or doesn't exist. Setup tokens work once. If you didn't use it, someone else may have: disable it in your SimpleFIN account, then get a new setup token." }
        WizardFrame(WizardPage.CONNECT, "Connect with SimpleFIN", {}, "Skip for now" to {}) { SimpleFinDoor(ui, {}, {}) }
    }
    @Test fun wizardOldSchool() = shot("37-wizard-old-school") { WizardFrame(WizardPage.CONNECT, "Old School", {}, null) { OldSchoolDoor(false, {}, {}, {}) } }
    @Test @Config(qualifiers = TALL) fun wizardLink() = shot("38-wizard-link") {
        WizardFrame(WizardPage.LINK, "Link accounts", {}, "Link and sync" to {}) {
            LinkPanel(links.filter { it.connectionId == "c1" }.map { it.copy(accountId = null) }, budget.accounts, mapOf("l1" to LinkPick("chk")), { _, _ -> }, false, null, null)
        }
    }
    @Test @Config(qualifiers = TALL) fun wizardStarter() = shot("39-wizard-starter") {
        WizardFrame(WizardPage.STARTER, "Starter envelopes", {}, "Add 2" to {}) { StarterPage(setOf("Groceries", "Phone"), setOf("Rollover", "Gas"), {}, {}) }
    }
    @Test fun wizardFinish() = shot("40-wizard-finish") {
        WizardFrame(WizardPage.FINISH, "Finish", {}, "Finish" to {}) {
            FinishPage(listOf("App lock is on.", "Connected: SimpleFIN", "3 account(s), 7 envelope(s) including Rollover.", "Rerun setup from More, Setup. Add or change bank connections from More, Bank sync."))
        }
    }
    @Test fun bankSyncEmpty() = shot("41-bank-sync-empty") { BankSyncScreen(emptyList(), emptyList(), budget.accounts, emptyMap(), true, 6, false, null, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) }
    @Test @Config(qualifiers = TALL) fun bankSync() = shot("42-bank-sync") {
        BankSyncScreen(conns, links, budget.accounts, mapOf("c1" to "...TEST", "c2" to "...TEST"), true, 6, false, null, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {})
    }
    @Test fun syncLinksFromBankSync() = shot("43-bank-sync-accounts") {
        Page { ScreenTitle("Link accounts", {}); LinkPanel(links.filter { it.connectionId == "c1" }, budget.accounts, emptyMap(), { _, _ -> }, false, null) {} }
    }
    @Test @Config(qualifiers = TALL) fun accountSyncMismatch() = shot("44-account-balance-check") {
        AccountEditScreen(budget.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(budget, "chk", links, conns) { _, _, _ -> } }, budget = budget)
    }
    @Test @Config(qualifiers = TALL) fun accountNotLinked() = shot("45-account-not-linked") {
        AccountEditScreen(budget.accounts[2], null, {}, {}, {}, syncSection = { AccountSyncSection(budget, "cash", links, conns) { _, _, _ -> } }, budget = budget)
    }
    @Test fun homeFinishSetup() = shot("46-home-finish-setup") { HomeScreen(budget, { FinishSetupCard({}, {}) }, {}, {}, {}, {}) }
    @Test fun fixDialog() = shot("47-fix-dialog") {
        HomeScreen(budget, null, {}, {}, {}, {})
        FixDialog(SyncService.FixPrompt("c2", FixCode.SIMPLEFIN_TOKEN, "SimpleFIN no longer accepts this connection. Get a new setup token from the Bridge and connect again."), {}, {})
    }
    @Test fun activitySyncNote() = shot("48-activity-sync-note") {
        ActivityScreen(budget, {}, {}, { _, _, _ -> }, {}, listOf("0 new transactions, 1 pending removed. A pending SAMPLE HOLD for -\$9.99 on 2030-03-14 disappeared without posting and was removed."))
    }
    @Test fun moreWithSync() = shot("49-more-bank-sync") { MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, {}, {}, "2 connections, last sync Mar 15, 9:30 AM. One needs attention.") }

    // ---- 0.7.2 ---------------------------------------------------------------------------------------------------
    @Test fun bottomTabsNarrow() = shot("50-bottom-tabs-narrow") { Column { Box(Modifier.weight(1f)) {}; BottomTabs(Nav.Home) {} } }
    @Test @Config(qualifiers = "w600dp-h400dp-xhdpi") fun bottomTabsWide() = shot("51-bottom-tabs-wide") { Column { Box(Modifier.weight(1f)) {}; BottomTabs(Nav.Activity) {} } }
    @Test @Config(qualifiers = "w320dp-h500dp-xhdpi") fun bottomTabsSmallest() = shot("52-bottom-tabs-320dp") { Column { Box(Modifier.weight(1f)) {}; BottomTabs(Nav.Envelopes) {} } }
    @Test @Config(qualifiers = TALL) fun linkOverdrawn() = shot("53-link-overdrawn-and-track-only") {
        val od = listOf(
            LinkedAccountEntity("o1", "c1", "ACT-O", "Sample Overdrawn Checking", "", null, -42_17, at),
            LinkedAccountEntity("o2", "c1", "ACT-S", "Sample Membership Share", "", null, 5_00, at),
        )
        WizardFrame(WizardPage.LINK, "Link accounts", {}, "Link and sync" to {}) {
            LinkPanel(od, budget.accounts, mapOf("o1" to LinkPick("new"), "o2" to LinkPick("track")), { _, _ -> }, false, null, null)
        }
    }
    @Test fun unlinkDialog() = shot("54-unlink-choice") { HomeScreen(budget, null, {}, {}, {}, {}); UnlinkDialog("Everyday Checking", {}, {}) }
    private val savingsHeld = acct("sav", "Rainy Savings", AccountType.BANK, 300_00).copy(heldAmount = 25_00)
    private val shareTrack = acct("share", "Membership Share", AccountType.BANK, 5_00).copy(trackOnly = true)
    private val budget2 = Budget(budget.accounts + listOf(savingsHeld, shareTrack), budget.envelopes, budget.transactions, budget.lines, budget.moves)
    @Test @Config(qualifiers = TALL) fun accountHeld() = shot("55-account-held-amount") { AccountEditScreen(savingsHeld, null, {}, {}, {}, budget = budget2) }
    @Test @Config(qualifiers = TALL) fun accountHeldShort() = shot("56-account-below-held") {
        val low = savingsHeld.copy(startingBalance = 10_00)
        AccountEditScreen(low, null, {}, {}, {}, budget = Budget(budget.accounts + low, budget.envelopes, budget.transactions, budget.lines, budget.moves))
    }
    @Test @Config(qualifiers = TALL) fun accountTrackOnly() = shot("57-account-track-only") { AccountEditScreen(shareTrack, null, {}, {}, {}, budget = budget2) }
    @Test fun accountsList() = shot("58-accounts-track-only-and-archived") {
        AccountsScreen(Budget(budget2.accounts + acct("old", "Old Savings", AccountType.BANK, 80_00).copy(archived = true), budget.envelopes, budget.transactions, budget.lines, budget.moves), {}, {}, {})
    }
    @Test fun homeNoCard() = shot("59-home-after-setup") { HomeScreen(budget2, null, {}, {}, {}, {}) }
}
