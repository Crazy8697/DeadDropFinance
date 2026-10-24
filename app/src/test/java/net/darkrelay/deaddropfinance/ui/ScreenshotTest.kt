package net.darkrelay.deaddropfinance.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asImageBitmap
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
import net.darkrelay.deaddropfinance.domain.BalanceCheck
import net.darkrelay.deaddropfinance.domain.Sorting
import net.darkrelay.deaddropfinance.domain.Suggest
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.RuleEntity
import net.darkrelay.deaddropfinance.data.RuleOrigin
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.ReceiptEntity
import net.darkrelay.deaddropfinance.data.ReceiptItemEntity
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.data.ItemReturnEntity
import net.darkrelay.deaddropfinance.domain.ReceiptMatch
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.data.BillEntity
import net.darkrelay.deaddropfinance.data.BillPaymentEntity
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.PaymentMethod
import net.darkrelay.deaddropfinance.data.NotifAppEntity
import net.darkrelay.deaddropfinance.data.ParkedBuyEntity
import net.darkrelay.deaddropfinance.data.ParkedStatus
import net.darkrelay.deaddropfinance.domain.ParkedBuys
import net.darkrelay.deaddropfinance.data.SubscriptionEntity
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import net.darkrelay.deaddropfinance.domain.Bills
import net.darkrelay.deaddropfinance.domain.Forecast
import net.darkrelay.deaddropfinance.domain.NotifParse
import net.darkrelay.deaddropfinance.domain.Paydays
import net.darkrelay.deaddropfinance.domain.Recurring
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runners.model.Statement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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

    @Test fun home() = shot("01-home") { HomeScreen(budget, null, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test fun homeWithUpdate() = shot("02-home-update-bar") { HomeScreen(budget, { UpdateBar(release, UpdateInstallState(), {}, {}, {}, {}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test fun homeUpdateDownloading() = shot("02b-home-update-downloading") { HomeScreen(budget, { UpdateBar(release, UpdateInstallState(working = true, percent = 42), {}, {}, {}, {}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test fun homeUpdateNeedsPermission() = shot("02c-home-update-needs-permission") {
        HomeScreen(budget, { UpdateBar(release, UpdateInstallState(needsPermission = true, message = "Allow installs, then come back here to continue."), {}, {}, {}, {}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {})
    }
    @Test fun homeEmpty() = shot("03-home-empty") { HomeScreen(empty, null, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test fun envelopes() = shot("04-envelopes") { EnvelopesScreen(budget, {}, {}, {}, {}) }
    @Test fun envelopesEmpty() = shot("05-envelopes-empty") { EnvelopesScreen(empty, {}, {}, {}, {}) }
    @Test fun envelopeDetailFill() = shot("06-envelope-detail") { EnvelopeDetailScreen(budget.envelopes[2], budget, today, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun envelopeDetailDebt() = shot("07-envelope-detail-debt") { EnvelopeDetailScreen(budget.envelopes[6], budget, today, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun envelopeEdit() = shot("08-envelope-edit") { EnvelopeEditScreen(budget.envelopes[4], null, {}, {}) }
    @Test fun moveMoney() = shot("09-move-money") { MoveMoneyScreen(budget, "roll", null, {}) { _, _, _ -> } }
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
            LinkPanel(links.filter { it.connectionId == "c1" }.map { it.copy(accountId = null) }, budget.accounts, mapOf("l1" to LinkPick("chk")), { _, _ -> }, false, null, today, null)
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
        Page { ScreenTitle("Link accounts", {}); LinkPanel(links.filter { it.connectionId == "c1" }, budget.accounts, emptyMap(), { _, _ -> }, false, null, today, onApply = {}) }
    }
    @Test @Config(qualifiers = TALL) fun accountSyncMismatch() = shot("44-account-balance-check") {
        AccountEditScreen(budget.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(budget, "chk", links, conns, today) { _, _, _ -> } }, budget = budget)
    }
    @Test @Config(qualifiers = TALL) fun accountNotLinked() = shot("45-account-not-linked") {
        AccountEditScreen(budget.accounts[2], null, {}, {}, {}, syncSection = { AccountSyncSection(budget, "cash", links, conns, today) { _, _, _ -> } }, budget = budget)
    }
    @Test fun homeFinishSetup() = shot("46-home-finish-setup") { HomeScreen(budget, { FinishSetupCard({}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test fun fixDialog() = shot("47-fix-dialog") {
        HomeScreen(budget, null, {}, {}, {}, {}, {}, {}, 0, 0, {})
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
            LinkPanel(od, budget.accounts, mapOf("o1" to LinkPick("new"), "o2" to LinkPick("track")), { _, _ -> }, false, null, today, null)
        }
    }
    @Test @Config(qualifiers = TALL) fun linkActuallyNegativeOn() = shot("53b-link-actually-negative-on") {
        val od = listOf(LinkedAccountEntity("o1", "c1", "ACT-O", "Sample Overdrawn Checking", "", null, 42_17, at))
        WizardFrame(WizardPage.LINK, "Link accounts", {}, "Link and sync" to {}) {
            LinkPanel(od, budget.accounts, mapOf("o1" to LinkPick("new", actuallyNegative = true)), { _, _ -> }, false, null, today, null)
        }
    }
    @Test fun homeMobileDataQuestion() = shot("02d-home-mobile-data-question") {
        HomeScreen(budget, { UpdateBar(release, UpdateInstallState(working = true), {}, {}, {}, {}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {})
        MobileDataDialog(41_165_299L, {}, {})
    }
    @Test fun unlinkDialog() = shot("54-unlink-choice") { HomeScreen(budget, null, {}, {}, {}, {}, {}, {}, 0, 0, {}); UnlinkDialog("Everyday Checking", {}, {}) }
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
    @Test fun homeNoCard() = shot("59-home-after-setup") { HomeScreen(budget2, null, {}, {}, {}, {}, {}, {}, 0, 0, {}) }

    // ---- 0.7.3 ---------------------------------------------------------------------------------------------------
    private fun epochOf(d: java.time.LocalDate) = d.atTime(9, 0).toEpochSecond(java.time.ZoneOffset.UTC)
    @Test @Config(qualifiers = TALL) fun linkStaleBankData() = shot("60-link-bank-data-as-of") {
        val ls = listOf(
            LinkedAccountEntity("s1", "c1", "ACT-1", "Sample Checking", "", "chk", -41_07, at, epochOf(today.minusDays(12))),
            LinkedAccountEntity("s2", "c1", "ACT-2", "Sample Savings", "", null, 5_00, at, epochOf(today)),
        )
        WizardFrame(WizardPage.LINK, "Link accounts", {}, "Link and sync" to {}) { LinkPanel(ls, budget.accounts, emptyMap(), { _, _ -> }, false, null, today, null) }
    }
    @Test @Config(qualifiers = TALL) fun accountBalanceCheckStale() = shot("61-account-balance-check-stale") {
        val ls = listOf(LinkedAccountEntity("s1", "c1", "ACT-1", "Sample Checking", "", "chk", -41_07, at, epochOf(today.minusDays(12))))
        AccountEditScreen(budget.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(budget, "chk", ls, conns, today) { _, _, _ -> } }, budget = budget, onDelete = {}, deleteCount = 7)
    }
    @Test fun deleteAccountDialog() = shot("62-delete-account") {
        Box {
            AccountEditScreen(budget.accounts[1], null, {}, {}, {}, budget = budget, onDelete = {}, deleteCount = 1)
            AlertDialogPreview("Delete Pay App?", "This deletes the account and its 1 transaction for good. Envelope balances and Ready to Assign change to match. A transfer with another account stays there as an entry to sort. Any bank link for this account is removed. This can't be undone.", "Delete for good")
        }
    }
    @Test @Config(qualifiers = TALL) fun eraseScreen() = shot("63-erase-everything") { EraseScreen({}, false) {} }
    @Test fun erasedScreen() = shot("64-erased") { ErasedScreen(emptyList(), {}, {}) }
    @Test fun erasedScreenLeftSomething() = shot("64b-erased-left-something") { ErasedScreen(listOf("keys folder"), {}, {}) }
    @Test @Config(qualifiers = TALL) fun moreWithErase() = shot("65-more-erase-row") { MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, {}, {}, null, {}) }
    @Test fun transactionBeforeStart() = shot("66-transaction-before-start") {
        val old = tx("old", "chk", "Sample Old Charge", -9_99, 60)
        val b = Budget(budget.accounts, budget.envelopes, budget.transactions + old, budget.lines + line("old", -9_99), budget.moves)
        TransactionDetailScreen(old, b, emptyList(), null, {}, {}, {}, {}, {}, {})
    }

    // ---- 0.8.0: auto-categorize, approve flow, alerts, returns, balance check -------------------------------------
    private fun rule(id: String, text: String, splits: String, priority: Int, ask: Boolean = false, origin: RuleOrigin = RuleOrigin.USER,
                     action: RuleAction = RuleAction.ENVELOPE, direction: Int = -1, min: Long? = null, max: Long? = null) =
        RuleEntity(id = id, matchText = text, splits = splits, priority = priority, alwaysAsk = ask, origin = origin, acknowledged = origin == RuleOrigin.USER,
            action = action, direction = direction, minAmount = min, maxAmount = max, createdAt = priority.toLong(), updatedAt = 0)
    private val rules8 = listOf(
        rule("r1", "sample market", "food:1", 0),
        rule("r2", "sample supercenter", "food:1", 1, ask = true),
        rule("r3", "sample car wash", "fuel:1", 2, min = 5_00, max = 20_00),
        rule("r4", "sample phone co", "save:6000;debt:4000", 3),
        rule("r5", "sample savings", "", 4, action = RuleAction.TRANSFER, direction = 0),
        rule("r6", "sample bakery", "food:1", 5, origin = RuleOrigin.LEARNED),
    )
    private fun bankTx(id: String, desc: String, amount: Long, days: Long, sortedBy: SortedBy? = null, pending: Boolean = false) =
        tx(id, "chk", desc, amount, days, pending = pending).copy(importDescription = desc, sortedBy = sortedBy, sortedAt = if (sortedBy != null) 1 else null)
    private val budget8 = Budget(
        budget.accounts, budget.envelopes,
        budget.transactions + listOf(
            bankTx("s1", "POS SAMPLE MARKET #0412", -23_40, 0, pending = true),
            bankTx("s2", "QUICK FUEL STOP 0091", -31_75, 1),
            bankTx("s3", "CORNER GROCER REFUND", 12_00, 1),
            bankTx("s4", "SAMPLE SUPERCENTER 2231", -64_10, 1),
            bankTx("w1", "SAMPLE CAR WASH 17", -9_00, 1, sortedBy = SortedBy.AUTO),
        ),
        budget.lines + listOf(line("s1", -23_40), line("s2", -31_75), line("s3", 12_00), line("s4", -64_10), line("w1", -9_00, "fuel")),
        budget.moves,
    )
    private val sug8 = Suggest.all(budget8, rules8)
    private fun sortingUi() = SortingUi(
        suggestions = sug8, offer = Sorting.Learned.Offer("sample deli", "food", -1),
        learned = rules8.filter { it.origin == RuleOrigin.LEARNED }, recentlySorted = budget8.transactions.filter { it.id == "w1" }, rules = rules8,
    )

    @Test @Config(qualifiers = TALL) fun activitySuggestions() = shot("67-activity-to-sort-suggestions") {
        ActivityScreen(budget8, {}, {}, { _, _, _ -> }, {}, sorting = sortingUi())
    }
    @Test fun approveAllDialog() = shot("68-approve-all") {
        Box {
            ActivityScreen(budget8, {}, {}, { _, _, _ -> }, {}, sorting = sortingUi().copy(offer = null, learned = emptyList()))
            AlertDialogPreview(
                "Approve 4 suggested?",
                "4 of the 5 in To sort have a suggestion. Each goes where its suggestion says, splits and returns included. You can change any of them afterwards.",
                "Approve 4",
            )
        }
    }
    @Test @Config(qualifiers = TALL) fun rulesList() = shot("69-rules") { RulesScreen(rules8, budget8, {}, {}, {}, { _, _ -> }) }
    @Test @Config(qualifiers = TALL) fun ruleEditPreview() = shot("70-rule-edit-preview") { RuleEditScreen(rules8[0], null, budget8, rules8, null, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun ruleEditSplit() = shot("71-rule-edit-split") { RuleEditScreen(rules8[3], null, budget8, rules8, null, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun ruleFromTransaction() = shot("72-rule-new-from-transaction") {
        RuleEditScreen(null, RulePrefill("quick fuel stop", -1, null, "fuel"), budget8, rules8, null, {}, {}, null)
    }
    @Test @Config(qualifiers = TALL) fun moreSortingAlerts() = shot("73-more-sorting-alerts") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, {}, {}, "1 connection, last sync Mar 15, 9:30 AM", {}, sortAlerts = {
            SortingAndAlertsSection(SortAlertState(6, false, true, true, true, false, false, true, 6), {}, {}, { _, _ -> }, {})
        })
    }
    @Test @Config(qualifiers = TALL) fun returnPicker() = shot("74-this-is-a-return") {
        val credit = budget8.transactions.first { it.id == "s3" }
        TransactionDetailScreen(credit, budget8, emptyList(), null, {}, {}, {}, {}, {}, {}, returnCandidates = Suggest.returnCandidates(budget8, credit), onMakeRule = {}, pickingReturn = true)
    }
    @Test fun returnConfirmed() = shot("75-return-confirmed") {
        val credit = budget8.transactions.first { it.id == "s3" }.copy(returnOf = "shop", sortedBy = SortedBy.RETURN)
        val lines = budget8.lines.filter { it.transactionId != "s3" } + listOf(line("s3", 8_77, "food"), line("s3", 3_23, "save"))
        val b = Budget(budget8.accounts, budget8.envelopes, budget8.transactions.map { if (it.id == "s3") credit else it }, lines, budget8.moves)
        TransactionDetailScreen(credit, b, emptyList(), null, {}, {}, {}, {}, {}, {}, onMakeRule = {})
    }
    private fun checkLink(bank: Long) = listOf(LinkedAccountEntity(id = "s1", connectionId = "c1", providerAccountId = "ACT-1", providerName = "Sample Checking", accountId = "chk",
        providerBalance = bank, providerBalanceAt = at, providerBalanceDate = epochOf(today)))
    private val appBalance get() = budget8.postedBalanceOn("chk", today)
    @Test @Config(qualifiers = TALL) fun balanceMismatch() = shot("76-balance-check-mismatch") {
        AccountEditScreen(budget8.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(budget8, "chk", checkLink(appBalance + 25_00), conns, today, onAdjust = { _, _ -> }) { _, _, _ -> } }, budget = budget8)
    }
    @Test fun balanceAdjustDialog() = shot("77-balance-check-adjustment") {
        Page { BalanceCheckView(BalanceCheck.check(budget8, "chk", appBalance + 25_00, today), "Everyday Checking", { _, _ -> }, confirming = true) }
    }
    @Test @Config(qualifiers = TALL) fun balanceFlipped() = shot("78-balance-check-flipped-sign") {
        val over = Budget(budget8.accounts, budget8.envelopes, budget8.transactions + tx("big", "chk", "Sample Rent Co", -2500_00, 3), budget8.lines + line("big", -2500_00), budget8.moves)
        val app = over.postedBalanceOn("chk", today)
        AccountEditScreen(over.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(over, "chk", checkLink(-app), conns, today, onAdjust = { _, _ -> }) { _, _, _ -> } }, budget = over)
    }
    @Test @Config(qualifiers = TALL) fun balanceMatch() = shot("79-balance-check-match") {
        AccountEditScreen(budget8.accounts[0], null, {}, {}, {}, syncSection = { AccountSyncSection(budget8, "chk", checkLink(appBalance), conns, today, onAdjust = { _, _ -> }) { _, _, _ -> } }, budget = budget8)
    }
    @Test @Config(qualifiers = TALL) fun linkBalanceCheck() = shot("80-link-balance-check") {
        WizardFrame(WizardPage.LINK, "Link accounts", {}, "Link and sync" to {}) {
            LinkPanel(checkLink(appBalance + 25_00).map { it.copy(accountId = null) }, budget8.accounts, mapOf("s1" to LinkPick("chk")), { _, _ -> }, false, null, today, null, budget = budget8)
        }
    }
    @Test fun homeAlertsCard() = shot("81-home-alerts-card") { HomeScreen(budget8, { AlertsCard({}, {}) }, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test @Config(qualifiers = TALL) fun envelopeEditEveryday() = shot("82-envelope-edit-everyday") { EnvelopeEditScreen(budget.envelopes[1].copy(everyday = true), null, {}, {}) }

    // ---- 0.9.0: receipts ----------------------------------------------------------------------------------------
    /** An invented receipt drawn on the canvas, as a scan would show it. */
    private fun receiptImage(lines: List<String>): androidx.compose.ui.graphics.ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(420, 40 + lines.size * 30, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(android.graphics.Color.rgb(236, 236, 236))
        val p = android.graphics.Paint().apply { color = android.graphics.Color.rgb(30, 30, 30); textSize = 22f; typeface = android.graphics.Typeface.MONOSPACE; isAntiAlias = true }
        lines.forEachIndexed { i, l -> c.drawText(l, 16f, 40f + i * 30, p) }
        return b.asImageBitmap()
    }
    private val shopLines = listOf("SAMPLE SUPERSTORE", "03/14/2030", "SAMPLE TOWELS 2 @ 10.00  20.00", "SAMPLE SNACKS          12.50", "SAMPLE SOAP             6.00",
        "COUPON                 1.00-", "SUBTOTAL               37.50", "TAX                     3.00", "TOTAL                  40.50")
    private val rcpt = ReceiptEntity(id = "rc1", transactionId = "shop9", store = "SAMPLE SUPERSTORE", date = today.minusDays(3), total = 40_50, tax = 3_00, pages = 1, createdAt = 0, updatedAt = 0)
    private fun item(id: String, text: String, amount: Long, line: Int, qty: Long = 1000, env: String? = null) =
        ReceiptItemEntity(id = id, receiptId = "rc1", line = line, text = text, name = ReceiptParser.cleanName(text), quantityMilli = qty, unitPrice = amount * 1000 / qty, amount = amount, envelopeId = env, createdAt = 0, updatedAt = 0)
    private val rItems = listOf(item("i1", "SAMPLE TOWELS", 20_00, 0, 2000, "save"), item("i2", "SAMPLE SNACKS", 12_50, 1, env = "food"), item("i3", "SAMPLE SOAP", 6_00, 2, env = "save"), item("i4", "COUPON", -1_00, 3, env = "save"))
    private val budget9 = Budget(
        budget8.accounts, budget8.envelopes,
        budget8.transactions + listOf(bankTx("shop9", "SAMPLE SUPERSTORE #88", -40_50, 3), bankTx("back9", "SAMPLE SUPERSTORE REFUND", 10_60, 0).copy(returnOf = "shop9")),
        budget8.lines + listOf(line("shop9", -13_63, "food"), line("shop9", -26_87, "save"), line("back9", 10_60)),
        budget8.moves,
    )
    private val waiting = ReceiptEntity(id = "rc2", store = "SAMPLE HARDWARE", date = today.minusDays(1), total = 18_99, tax = 1_40, pages = 1, createdAt = 0, updatedAt = 0)

    @Test @Config(qualifiers = TALL) fun activityScanAndWaiting() = shot("83-activity-scan-and-waiting") {
        ActivityScreen(budget8, {}, {}, { _, _, _ -> }, {}, sorting = sortingUi().copy(offer = null, learned = emptyList(), onScan = {}, waitingReceipts = 2))
    }
    @Test fun scanScreen() = shot("84-scan-receipt") { ScanScreen("SAMPLE SUPERSTORE #88, -\$40.50", false, null, {}, {}, {}) }
    @Test fun scanReading() = shot("85-scan-reading") { ScanScreen(null, true, null, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun receiptCheck() = shot("86-receipt-check") {
        val parsed = ReceiptParser.parse(listOf(shopLines.map { ReceiptParser.Line(it, if (it.startsWith("SAMPLE SOAP")) 40 else 95) }))
        ReceiptEditScreen(ReceiptForm.from(parsed), listOf(receiptImage(shopLines)), true, false, null, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun receiptDetail() = shot("87-receipt-detail") {
        ReceiptDetailScreen(rcpt, rItems, listOf(receiptImage(shopLines)), budget9.transactions.first { it.id == "shop9" }, budget9,
            listOf(ItemReturnEntity(itemId = "i1", transactionId = "back9", quantityMilli = 1000, amount = 10_60, createdAt = 0, updatedAt = 0)), null,
            {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun receiptWaiting() = shot("88-receipt-waiting") {
        ReceiptDetailScreen(waiting, emptyList(), listOf(receiptImage(listOf("SAMPLE HARDWARE", "TOTAL 18.99"))), null, budget9, emptyList(),
            "Saved. It waits in Receipts and is matched when its charge comes in.", {}, {}, {}, {}, {}, {}, {})
    }
    @Test fun receiptMatch() = shot("89-receipt-match") {
        val t1 = bankTx("m1", "SAMPLE HARDWARE 0042", -18_99, 0)
        val t2 = bankTx("m2", "OTHER SAMPLE SHOP", -18_99, 1).copy(pending = true)
        ReceiptMatchScreen(waiting, listOf(ReceiptMatch.Candidate(t1, ReceiptMatch.Fit.EXACT, false, 1), ReceiptMatch.Candidate(t2, ReceiptMatch.Fit.EXACT, false, 0)), {}, {})
    }
    @Test @Config(qualifiers = TALL) fun itemSplit() = shot("90-split-by-items") {
        ItemSplitScreen(rcpt, rItems, budget9.transactions.first { it.id == "shop9" }, budget9, rItems.associate { it.id to it.envelopeId }, null, {}, {})
    }
    @Test fun receiptsList() = shot("91-receipts") { ReceiptsScreen(listOf(waiting, rcpt), budget9, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun returnItems() = shot("92-return-items") {
        ReturnItemsScreen(budget9.transactions.first { it.id == "back9" }, budget9.transactions.first { it.id == "shop9" }, rcpt, rItems, emptyList(),
            mapOf("i1" to 1000L), budget9, null, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun transactionWithReceipt() = shot("93-transaction-receipt") {
        val t = budget9.transactions.first { it.id == "shop9" }
        TransactionDetailScreen(t, budget9, emptyList(), null, {}, {}, {}, {}, {}, {}, onMakeRule = {}, receipt = rcpt, onAddReceipt = {})
    }
    @Test fun transactionAddReceipt() = shot("94-transaction-add-receipt") {
        TransactionDetailScreen(budget8.transactions.first { it.id == "gas" }, budget8, emptyList(), null, {}, {}, {}, {}, {}, {}, onMakeRule = {}, onAddReceipt = {})
    }
    @Test @Config(qualifiers = TALL) fun moreReceiptsRow() = shot("95-more-receipts-row") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, {}, {}, "1 connection, last sync Mar 15, 9:30 AM", {}, onReceipts = {})
    }

    // ---- 0.10.0: bills, paydays, subscriptions, forecast, reading bank notifications -------------------------------
    private fun bill(id: String, name: String, amount: Long, due: java.time.LocalDate, env: String?, rec: BillRecurrence = BillRecurrence.MONTHLY, autopay: Boolean = false, reminder: Boolean = false, varies: Boolean = false) =
        BillEntity(id = id, name = name, envelopeId = env, amountDue = amount, dueDate = due, recurrence = rec, autopay = autopay, reminder = reminder, varies = varies, matchText = name.lowercase(), createdAt = 0, updatedAt = 0)
    private fun pay(bill: String, amount: Long, due: java.time.LocalDate, method: PaymentMethod = PaymentMethod.BANK, tx: String? = null) =
        BillPaymentEntity(billId = bill, transactionId = tx, amount = amount, date = due.minusDays(1), periodDue = due, method = method, createdAt = 0, updatedAt = 0)
    /** Paydays on the 14th of each month; today (Mar 15) is the day after one. Spending history in each budget month. */
    private val budget10: Budget = run {
        val txs = mutableListOf<TransactionEntity>()
        val lines = mutableListOf<TransactionLineEntity>()
        var n = 0
        val paydays = listOf(java.time.LocalDate.of(2029, 12, 14), java.time.LocalDate.of(2030, 1, 14), java.time.LocalDate.of(2030, 2, 14), java.time.LocalDate.of(2030, 3, 14))
        for (pd in paydays) {
            val t = TransactionEntity(id = "pay${n++}", accountId = "chk", date = pd, payee = "Sample Employer Payroll", amount = 1900_00, isPayday = true, createdAt = 0, updatedAt = 0)
            txs += t; lines += line(t.id, t.amount, rta = true)
            if (pd == paydays.last()) continue
            for ((off, amt) in listOf(0L to 95_00L, 1L to 120_00L, 3L to 60_00L, 9L to 40_00L, 16L to 35_00L, 23L to 30_00L)) {
                val s = TransactionEntity(id = "h${n++}", accountId = "chk", date = pd.plusDays(off), payee = "Sample Shop", amount = -amt, createdAt = 0, updatedAt = 0)
                txs += s; lines += line(s.id, s.amount, "food")
            }
        }
        for ((i, m) in listOf(12, 1, 2).withIndex()) {
            val y = if (m == 12) 2029 else 2030
            val s = TransactionEntity(id = "st$i", accountId = "chk", date = java.time.LocalDate.of(y, m, 3), payee = "SAMPLE STREAMING", importDescription = "SAMPLE STREAMING", amount = if (i == 2) -12_49 else -10_99, createdAt = 0, updatedAt = 0)
            txs += s; lines += line(s.id, s.amount, "save")
        }
        val cloud = TransactionEntity(id = "cl", accountId = "chk", date = java.time.LocalDate.of(2029, 4, 2), payee = "SAMPLE CLOUD BACKUP", importDescription = "SAMPLE CLOUD BACKUP", amount = -59_88, createdAt = 0, updatedAt = 0)
        txs += cloud; lines += line(cloud.id, cloud.amount, "save")
        val acct = AccountEntity(id = "chk", name = "Everyday Checking", type = AccountType.BANK, startingBalance = 0, startDate = java.time.LocalDate.of(2029, 12, 1), createdAt = 0, updatedAt = 0)
        val moved = txs.sumOf { it.amount }
        val envs = listOf(
            env("roll", "Rollover", EnvelopeMode.ROLLOVER, 0),
            env("food", "Groceries", EnvelopeMode.FILL_TO_TARGET, 1, target = 400_00).copy(everyday = true),
            env("fuel", "Fuel", EnvelopeMode.FILL_TO_TARGET, 2, target = 150_00).copy(everyday = true),
            env("rent", "Rent", EnvelopeMode.FILL_TO_TARGET, 3, target = 950_00),
            env("phone", "Phone", EnvelopeMode.FILL_TO_TARGET, 4, target = 70_00),
            env("ins", "Car Insurance", EnvelopeMode.FILL_TO_TARGET, 5, target = 120_00),
            env("save", "Rainy Day", EnvelopeMode.ADD_EVERY_PAYDAY, 6, per = 50_00),
            env("debt", "Sample Loan", EnvelopeMode.DEBT, 7, per = 60_00).copy(requiredPayment = 75_00, dueDate = java.time.LocalDate.of(2030, 1, 22), reminder = true),
        )
        val bills = listOf(
            bill("b1", "Sample Rent", 950_00, java.time.LocalDate.of(2030, 1, 16), "rent", reminder = true),
            bill("b2", "Sample Phone Line A", 35_00, java.time.LocalDate.of(2030, 1, 20), "phone"),
            bill("b3", "Sample Phone Line B", 35_00, java.time.LocalDate.of(2030, 1, 27), "phone"),
            bill("b4", "Sample Car Insurance", 120_00, java.time.LocalDate.of(2030, 1, 25), "ins", autopay = true),
            bill("b5", "Sample Power", 88_40, java.time.LocalDate.of(2030, 1, 10), "rent", varies = true),
            bill("b6", "Sample Registration", 145_00, java.time.LocalDate.of(2030, 9, 30), null, rec = BillRecurrence.YEARLY),
        )
        val pays = listOf(
            pay("b1", 950_00, java.time.LocalDate.of(2030, 1, 16)), pay("b1", 950_00, java.time.LocalDate.of(2030, 2, 16)), pay("b1", 600_00, java.time.LocalDate.of(2030, 3, 16)),
            pay("b2", 35_00, java.time.LocalDate.of(2030, 1, 20)), pay("b2", 35_00, java.time.LocalDate.of(2030, 2, 20)), pay("b2", 35_00, java.time.LocalDate.of(2030, 3, 20), PaymentMethod.HAND),
            pay("b3", 35_00, java.time.LocalDate.of(2030, 1, 27)), pay("b3", 35_00, java.time.LocalDate.of(2030, 2, 27)),
            pay("b4", 120_00, java.time.LocalDate.of(2030, 1, 25)), pay("b4", 120_00, java.time.LocalDate.of(2030, 2, 25)),
            pay("b5", 88_40, java.time.LocalDate.of(2030, 1, 10)), pay("b5", 96_10, java.time.LocalDate.of(2030, 2, 10)),
        )
        val moves = listOf(move(null, "food", 1050_00 + 260_00), move(null, "fuel", 90_00), move(null, "rent", 950_00), move(null, "phone", 70_00), move(null, "ins", 75_00), move(null, "save", 150_00))
        Budget(listOf(acct.copy(startingBalance = 2100_00 - moved)), envs, txs, lines, moves, bills, pays)
    }
    private val subs10 = listOf(SubscriptionEntity(matchKey = "sample cloud backup", status = SubscriptionStatus.CONFIRMED, name = "Cloud Backup", createdAt = 0, updatedAt = 0))
    private val series10 get() = Recurring.detect(budget10.transactions, subs10) + Recurring.Series(
        "sample cloud backup", listOf("sample cloud backup"), "Cloud Backup", Recurring.Interval.YEARLY,
        listOf(budget10.transactions.first { it.id == "cl" }, budget10.transactions.first { it.id == "cl" }.copy(id = "cl0", date = java.time.LocalDate.of(2028, 4, 2), amount = -49_88)),
        subs10[0],
    ).let { s -> s.copy(charges = s.charges.sortedBy { it.date }) }
    private val pred10 get() = Paydays.predict(budget10.paydayDates, today)
    private val forecast10 get() = Forecast.build(budget10, series10, today, pred10.next!!)

    @Test fun bottomBarWithBills() = shot("96-bottom-bar-bills") { Box(Modifier.fillMaxSize()) { Column(Modifier.align(androidx.compose.ui.Alignment.BottomCenter)) { BottomTabs(Nav.Bills) {} } } }
    @Test @Config(qualifiers = TALL) fun homeWithForecastAndChecklist() = shot("97-home-forecast-checklist") {
        val list = Bills.checklist(budget10.bills, budget10.billPayments, budget10.paydayDates.last(), pred10.next!!)
        HomeScreen(budget10, null, {}, {}, {}, {}, {}, {}, 0, 0, {}, forecast = { ForecastCard(forecast10) {} }, checklist = { PaydayChecklistCard(list, { _, _ -> }, {}) })
    }
    @Test fun homeChecklistCollapsed() = shot("98-checklist-all-paid") {
        val list = Bills.checklist(budget10.bills, budget10.billPayments, budget10.paydayDates.last(), pred10.next!!).map { c -> c.copy(payments = c.payments + pay(c.bill.id, c.remaining, c.due, PaymentMethod.HAND)) }
        Page { PaydayChecklistCard(list, { _, _ -> }, {}) }
    }
    @Test fun homePaydayLate() = shot("99-payday-late") { Page { LatePaydayNote(today.minusDays(2), today) } }
    @Test @Config(qualifiers = TALL) fun billsTab() = shot("100-bills-tab") { BillsScreen(budget10, today, pred10, series10, {}, {}, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun billDetailPartial() = shot("101-bill-detail-partial") {
        BillDetailScreen(budget10.bills.first { it.id == "b1" }, budget10.let { b -> Budget(b.accounts + acct("cash", "Wallet", AccountType.CASH, 40_00), b.envelopes, b.transactions, b.lines, b.moves, b.bills, b.billPayments) }, today, null, {}, {}, {}, { _, _ -> }, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun billEdit() = shot("102-bill-edit") { BillEditScreen(budget10.bills.first { it.id == "b4" }.copy(reminder = true, reminderDays = 3), null, budget10, null, {}, {}, {}) }
    @Test fun paydayChange() = shot("103-payday-change") { BillsScreen(budget10, today, pred10, series10, {}, {}, {}, {}, {}, editingPayday = true) }
    @Test @Config(qualifiers = TALL) fun subscriptions() = shot("104-subscriptions") { SubscriptionsScreen(series10, {}, {}) }
    @Test @Config(qualifiers = TALL) fun subscriptionDetail() = shot("105-subscription-detail") {
        val s = series10.first { it.key == "sample streaming" }
        SubscriptionDetailScreen(s, series10.filter { it.key != s.key }, budget10, null, {}, {}, {}, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun forecastUsual() = shot("106-forecast-usual-pace") { ForecastScreen(forecast10, {}) }
    @Test @Config(qualifiers = TALL) fun forecastPlanOnly() = shot("107-forecast-plan-only") {
        val b = budget10
        val thin = Budget(b.accounts, b.envelopes, b.transactions.filter { !it.isPayday || it.date == b.paydayDates.last() }, b.lines, b.moves, b.bills, b.billPayments)
        ForecastScreen(Forecast.build(thin, series10, today, pred10.next!!), {})
    }
    @Test @Config(qualifiers = TALL) fun moreAlertsStage10() = shot("108-more-alerts-bills-reading") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, {}, {}, null, {}, sortAlerts = {
            SortingAndAlertsSection(SortAlertState(4, false, true, true, true, false, true, true, 6, bills = true, autopay = true, reading = true), {}, {}, { _, _ -> }, {})
        })
    }
    private val bankApp = NotifAppEntity("com.example.samplebank", "Sample Bank", "chk", templates = NotifParse.encode(listOf(NotifParse.Template(false, "of", "at", "with card"))), lastAmount = 42_18, lastPayee = "SAMPLE MARKET 0042", lastAt = at)
    @Test fun readingOff() = shot("109-reading-off") { NotifReadingScreen(ReadingState(false, false, emptyList(), emptyList(), emptyMap()), budget10, {}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {}) }
    @Test @Config(qualifiers = TALL) fun readingRestricted() = shot("110-reading-restricted-steps") {
        NotifReadingScreen(ReadingState(true, false, emptyList(), listOf("com.example.samplebank" to "Sample Bank", "com.example.samplewallet" to "Sample Wallet"), emptyMap()), budget10, {}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun readingTeach() = shot("111-reading-teach-and-last-read") {
        val wallet = NotifAppEntity("com.example.samplewallet", "Sample Wallet", "chk")
        NotifReadingScreen(
            ReadingState(true, true, listOf(bankApp, wallet), emptyList(), mapOf("com.example.samplewallet" to "Sample Wallet: You paid \$6.25 to SAMPLE COFFEE CART. Balance \$40.10.")),
            budget10, {}, {}, {}, {}, { _, _, _ -> }, {}, { _, _ -> }, {}, {},
        )
    }
    @Test fun activityStale() = shot("112-activity-not-in-sync") {
        val t = TransactionEntity(id = "n1", accountId = "chk", date = today.minusDays(9), payee = "SAMPLE KIOSK", amount = -9_99, pending = true, source = net.darkrelay.deaddropfinance.data.TransactionSource.NOTIFICATION, createdAt = 0, updatedAt = 0)
        ActivityScreen(budget8, {}, {}, { _, _, _ -> }, {}, sorting = SortingUi(stale = listOf(t)))
    }
    @Test @Config(qualifiers = TALL) fun envelopeDebtReminder() = shot("113-envelope-debt-reminder") { EnvelopeEditScreen(budget10.envelopes.first { it.id == "debt" }, null, {}, {}) }
    @Test @Config(qualifiers = TALL) fun ruleCountsTowardBill() = shot("114-rule-counts-toward-bill") {
        RuleEditScreen(RuleEntity(id = "rb", matchText = "sample landlord", splits = "rent:1", billId = "b1", createdAt = 0, updatedAt = 0), null, budget10, emptyList(), null, {}, {}, {})
    }

    // ---- Stage 11: Safe to Spend Home, parked buys -----------------------------------------------------------------
    private val budget11: Budget get() = budget10.let { b -> Budget(b.accounts, b.envelopes, b.transactions, b.lines, b.moves + move(null, "roll", 140_00), b.bills, b.billPayments) }
    private val nowMs = today.atTime(18, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    private fun parkedAt(id: String, name: String, amount: Long, daysAgo: Long, wait: Int, status: ParkedStatus = ParkedStatus.WAITING, env: String? = null): ParkedBuyEntity {
        val at = nowMs - daysAgo * ParkedBuys.DAY_MS - 3 * 60 * 60 * 1000 + 40 * 60 * 1000
        return ParkedBuyEntity(id = id, name = name, amount = amount, waitDays = wait, parkedAt = at, readyAt = at + wait * ParkedBuys.DAY_MS, status = status, envelopeId = env, boughtOn = if (env != null) today else null, createdAt = 0, updatedAt = 0)
    }
    private val parked11 = listOf(
        parkedAt("pk1", "Sample Soldering Station", 189_99, 4, 2),
        parkedAt("pk2", "Sample Drill Bits", 39_99, 3, 1),
        parkedAt("pk3", "Sample Game", 29_99, 0, 1),
        parkedAt("pk4", "Sample Desk Lamp", 24_00, 5, 1, ParkedStatus.BOUGHT, "food"),
        parkedAt("pk5", "Sample Headphones", 59_00, 6, 2, ParkedStatus.DROPPED),
    )

    @Test @Config(qualifiers = TALL) fun homeSafeToSpend() = shot("115-home-safe-to-spend") {
        val list = Bills.checklist(budget11.bills, budget11.billPayments, budget11.paydayDates.last(), pred10.next!!)
        HomeScreen(
            budget11, null, {}, {}, {}, {}, {}, {}, ParkedBuys.waiting(parked11).size, ParkedBuys.ready(parked11, nowMs).size, {},
            forecast = { ForecastCard(forecast10) {} }, checklist = { PaydayChecklistCard(list, { _, _ -> }, {}) },
        )
    }
    @Test fun homeNoEverydayYet() = shot("116-home-pick-everyday") { HomeScreen(budget, null, {}, {}, {}, {}, {}, {}, 0, 0, {}) }
    @Test @Config(qualifiers = TALL) fun safeToSpendPick() = shot("117-safe-to-spend-envelopes") { SafeToSpendScreen(budget11, null, {}) { _, _ -> } }
    @Test @Config(qualifiers = TALL) fun parkedBuys() = shot("118-parked-buys") {
        ParkedBuysScreen(parked11, budget11, nowMs, 1, null, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, zone = java.time.ZoneOffset.UTC)
    }
    @Test @Config(qualifiers = TALL) fun parkSomething() = shot("119-park-something") {
        ParkedBuysScreen(parked11.filter { it.id == "pk3" }, budget11, nowMs, 2, null, {}, { _, _, _ -> }, { _, _ -> }, {}, {}, startAdding = true, zone = java.time.ZoneOffset.UTC)
    }
    @Test @Config(qualifiers = TALL) fun moreParked() = shot("120-more-parked-buys") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, onReceipts = {}, onParked = {}, parkWait = 2)
    }

    // ---- 0.15.0: the launcher icon from the master (as a round and a squircle launcher mask it, themed in one colour) --------------
    @Test fun launcherIcon() = shot("121-launcher-icon") {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp)) {
            for (shape in listOf(androidx.compose.foundation.shape.CircleShape, androidx.compose.foundation.shape.RoundedCornerShape(30))) {
                Box(Modifier.size(108.dp).clip(shape).background(androidx.compose.ui.graphics.Color(0xFF0B131B))) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(net.darkrelay.deaddropfinance.R.drawable.ic_launcher_foreground), null, Modifier.size(108.dp))
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            }
            Box(Modifier.size(108.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFF2E4A3E))) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(net.darkrelay.deaddropfinance.R.drawable.ic_launcher_monochrome), null, Modifier.size(108.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFCDE8D6)),
                )
            }
        }
    }

    // ---- 0.15.0: Home answers, backup, setup import, purge, About, Help, Licenses -----------------------------------------
    /** budget11 over-assigned (the account holds less than the envelopes); its Sample Loan payment is due before payday, a card's after. */
    private val budget15: Budget get() = budget11.let { b ->
        val card = env("card", "Sample Card", EnvelopeMode.DEBT, 9, per = 40_00).copy(requiredPayment = 35_00, dueDate = today.plusDays(40), debtOwed = 600_00)
        Budget(b.accounts, b.envelopes + card, b.transactions, b.lines, b.moves + move(null, "save", b.readyToAssign + 75_00), b.bills, b.billPayments)
    }
    @Test @Config(qualifiers = TALL) fun homeOverAssignedAndDebtDue() = shot("122-home-over-assigned-debt-due") {
        val b = budget15
        HomeScreen(
            b, null, {}, {}, {}, {}, {}, {}, 1, 0, {},
            forecast = { ForecastCard(forecast10) {} }, envelopes = homeEnvelopes(b, today, pred10.next),
        )
    }
    @Test fun moveMoneyBack() = shot("123-move-money-back-to-ready") { MoveMoneyScreen(budget15, null, null, {}, backToReady = true) { _, _, _ -> } }
    @Test @Config(qualifiers = TALL) fun backup() = shot("124-backup-and-restore") {
        BackupScreen(BackupState(lastBackup = "Mar 14, 9:30 PM, deaddrop-2030-03-14.ddfbak", message = "Backed up: 2 accounts, 41 transactions, 3 receipts, 5 photos."), {}, {}, {}, {}, {})
    }
    @Test @Config(qualifiers = TALL) fun backupRestorePicked() = shot("125-backup-restore-picked") {
        BackupScreen(BackupState(restoreName = "deaddrop-2030-03-01.ddfbak", error = "Wrong password, or the backup file is damaged."), {}, {}, {}, {}, {})
    }
    @Test fun setupImportInitial() = shot("125b-import-setup-initial") { SetupImportScreen(SetupImportState(), {}, {}, {}, {}, {}) }
    @Test @Config(qualifiers = TALL) fun setupImportPreview() = shot("126-import-setup-preview") {
        SetupImportScreen(
            SetupImportState(
                fileName = "sample-setup.json", plan = net.darkrelay.deaddropfinance.domain.SetupImport.Plan(),
                preview = net.darkrelay.deaddropfinance.domain.SetupImporter.Preview(
                    add = linkedMapOf("Envelopes" to listOf("Fuel", "Sample Loan"), "Bills" to listOf("Sample Car Cover"), "Rules" to listOf("sample payroll", "sample corner shop")),
                    update = linkedMapOf("Envelopes" to listOf("Groceries"), "Bills" to listOf("Sample Power Co")),
                    settings = listOf("autoApprove", "syncEveryHours", "parkingLotWait"), nextPayday = today.plusDays(14),
                ),
            ),
            {}, {}, {}, {}, {},
        )
    }
    @Test @Config(qualifiers = TALL) fun setupImportErrors() = shot("127-import-setup-errors") {
        SetupImportScreen(
            SetupImportState(fileName = "sample-setup.json", errors = listOf(
                "envelopes[1] (\"Groceries\"): \"target\" must be an amount like \"12.34\".",
                "bills[2] (\"Sample Phone\"): \"due\" must be a date like \"2030-05-17\".",
                "\"transactions\": the importer never imports transactions. Take them out of the file (CSV import or bank sync brings transactions in).",
            )),
            {}, {}, {}, {}, {},
        )
    }
    @Test @Config(qualifiers = TALL) fun purge() = shot("128-purge-data") { PurgeScreen({}, false, listOf("Sample Bridge")) {} }
    @Test fun wizardAfterPurge() = shot("129-wizard-after-purge") {
        val c = net.darkrelay.deaddropfinance.data.ConnectionEntity(id = "c1", provider = net.darkrelay.deaddropfinance.data.Provider.SIMPLEFIN, name = "Sample Bridge", createdAt = 0)
        SetupWizardHost(
            page = WizardPage.CONNECT, forced = true, budget = empty, connections = listOf(c), links = emptyList(), ui = ConnectUi(), picks = emptyMap(), linkBusy = false,
            syncMessage = null, starters = emptySet(), more = more, hasAccounts = false, onPage = {}, onDoor = {}, door = Door.EASY, onConnected = {}, wizardConn = "c1",
            onSimpleFin = {}, onApplyLinks = {}, onPick = { _, _ -> }, onLock = {}, onAddAccount = {}, onImport = {}, onOpen = {}, onToggleStarter = {}, onAddStarters = {}, onFinish = {},
        )
    }
    @Test @Config(qualifiers = TALL) fun moreFull() = shot("130-more-backup-import-purge-about") {
        MoreScreen(
            more.copy(version = "0.15.0"), {}, {}, {}, {}, {}, {}, {}, {}, {}, "1 connection, last sync Mar 15, 9:30 AM", {}, onReceipts = {}, onParked = {}, onBackup = {},
            backupLine = "Last backup Mar 14, 9:30 PM, deaddrop-2030-03-14.ddfbak", onSetupImport = {}, onPurge = {}, onAbout = {},
        )
    }

    // ---- 1.1.0: categories -------------------------------------------------------------------------------------
    private fun cat(id: String, name: String, order: Int, envelope: String? = null) =
        net.darkrelay.deaddropfinance.data.CategoryEntity(id = id, name = name, envelopeId = envelope, sortOrder = order, createdAt = 0, updatedAt = 0)

    /** A budget with two paydays, categorized and old uncategorized spending, claims, Unplanned, and this month's assigning. */
    private val catBudget: Budget by lazy {
        val a = acct("chk", "Sample Checking", AccountType.BANK, 800_00)
        val groceries = env("e-groc", "Groceries", EnvelopeMode.FILL_TO_TARGET, 1, target = 300_00).copy(everyday = true)
        val housing = env("e-house", "Housing", EnvelopeMode.FILL_TO_TARGET, 2, target = 700_00)
        val unplanned = env("e-unp", "Unplanned", EnvelopeMode.FILL_TO_TARGET, 3).copy(everyday = true, unplanned = true)
        val cats = listOf(
            cat("c-rent", "Rent", 0, "e-house"), cat("c-util", "Utilities", 1, "e-house"), cat("c-groc", "Groceries", 2, "e-groc"), cat("c-snack", "Snacks", 3, "e-groc"),
            cat("c-out", "Eating out", 4), cat("c-gas", "Car gas", 5), cat("c-gift", "Gifts", 6), cat("c-other", "Other", 7),
        )
        val p1 = today.minusDays(14)
        val p2 = today.minusDays(1)
        fun t(id: String, d: LocalDate, amount: Long, payee: String, payday: Boolean = false) =
            TransactionEntity(id = id, accountId = "chk", date = d, payee = payee, amount = amount, isPayday = payday, createdAt = 0, updatedAt = 0)
        fun l(tx: String, amount: Long, bucket: LineBucket, env: String? = null, c: String? = null) =
            TransactionLineEntity(transactionId = tx, bucket = bucket, envelopeId = env, amount = amount, categoryId = c, createdAt = 0, updatedAt = 0)
        val txs = listOf(
            t("p1", p1, 1500_00, "SAMPLE PAYROLL", payday = true), t("p2", p2, 1500_00, "SAMPLE PAYROLL", payday = true),
            t("r1", p1.plusDays(2), -700_00, "SAMPLE LANDLORD"), t("g1", p1.plusDays(4), -86_40, "SAMPLE MARKET"), t("o1", p1.plusDays(6), -18_25, "SAMPLE TACO TRUCK"),
            t("old", p1.plusDays(5), -22_10, "SAMPLE HARDWARE"),
            t("g2", today, -54_12, "SAMPLE MARKET"), t("s1", today, -3_75, "SAMPLE VENDING"), t("f1", today, -41_00, "SAMPLE FUEL STOP"),
        )
        val lines = listOf(
            l("p1", 1500_00, LineBucket.READY_TO_ASSIGN), l("p2", 1500_00, LineBucket.READY_TO_ASSIGN),
            l("r1", -700_00, LineBucket.ENVELOPE, "e-house", "c-rent"), l("g1", -86_40, LineBucket.ENVELOPE, "e-groc", "c-groc"),
            l("o1", -18_25, LineBucket.ENVELOPE, "e-unp", "c-out"), l("old", -22_10, LineBucket.ENVELOPE, "e-groc", null),
            l("g2", -54_12, LineBucket.ENVELOPE, "e-groc", "c-groc"), l("s1", -3_75, LineBucket.ENVELOPE, "e-groc", "c-snack"), l("f1", -41_00, LineBucket.ENVELOPE, "e-unp", "c-gas"),
        )
        val moves = listOf(
            MoveEntity(fromEnvelopeId = null, toEnvelopeId = "e-groc", amount = 300_00, date = today, kind = MoveKind.ASSIGN, createdAt = 0, updatedAt = 0),
            MoveEntity(fromEnvelopeId = null, toEnvelopeId = "e-house", amount = 700_00, date = today, kind = MoveKind.ASSIGN, createdAt = 0, updatedAt = 0),
            MoveEntity(fromEnvelopeId = "e-groc", toEnvelopeId = null, amount = 50_00, date = today, kind = MoveKind.MOVE, createdAt = 0, updatedAt = 0),
        )
        Budget(listOf(a), listOf(groceries, housing, unplanned), txs, lines, moves, categories = cats)
    }

    @Test @Config(qualifiers = TALL) fun categoriesTab() = shot("140-categories-tab") {
        Column { Box(Modifier.weight(1f)) { CategoriesScreen(catBudget, today, 0, {}, {}, {}) }; BottomTabs(Nav.Categories) {} }
    }
    @Test @Config(qualifiers = TALL) fun categoriesOlderMonth() = shot("140b-categories-older-month") {
        Column { Box(Modifier.weight(1f)) { CategoriesScreen(catBudget, today, 1, {}, {}, {}) }; BottomTabs(Nav.Categories) {} }
    }
    @Test fun categoryPurchases() = shot("141-category-purchases") { CategoryPurchasesScreen(catBudget, today, 0, "c-groc", {}, {}) }
    @Test @Config(qualifiers = TALL) fun envelopeEditorClaims() = shot("142-envelope-editor-claims") {
        val groceries = catBudget.envelopes.first { it.id == "e-groc" }
        EnvelopeEditScreen(
            groceries, null, {}, {},
            ClaimsUi(catBudget.categories, mapOf("c-rent" to "Housing", "c-util" to "Housing"), setOf("c-groc", "c-snack"), {}),
        )
    }
    @Test @Config(qualifiers = TALL) fun purchaseWithCategory() = shot("143-purchase-with-category") {
        TransactionDetailScreen(catBudget.transactions.first { it.id == "g2" }, catBudget, emptyList(), null, {}, {}, {}, {}, {}, {}, onSetCategory = { _, _ -> })
    }
    @Test @Config(qualifiers = TALL) fun activityWithCategories() = shot("143b-activity-with-categories") {
        val unsorted = TransactionEntity(id = "u1", accountId = "chk", date = today, payee = "SAMPLE BAKERY", amount = -12_40, createdAt = 1, updatedAt = 1)
        val b = Budget(
            catBudget.accounts, catBudget.envelopes, catBudget.transactions + unsorted,
            catBudget.lines + TransactionLineEntity(transactionId = "u1", bucket = LineBucket.UNSORTED, amount = -12_40, createdAt = 1, updatedAt = 1), catBudget.moves, categories = catBudget.categories,
        )
        ActivityScreen(b, {}, {}, { _, _, _ -> }, {})
    }
    @Test @Config(qualifiers = TALL) fun envelopesWithAssignedLine() = shot("144-envelopes-assigned-line") {
        EnvelopesScreen(catBudget, {}, {}, {}, {}, today)
    }
    @Test @Config(qualifiers = TALL) fun manageCategories() = shot("145-manage-categories") {
        ManageCategoriesScreen(catBudget.categories, { 0 }, null, {}, {}, { _, _ -> }, { _, _ -> })
    }
    // ---- 1.1.1: budget start date ------------------------------------------------------------------------------
    @Test @Config(qualifiers = TALL) fun moreBudgetStart() = shot("150-more-budget-start") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, budgetStart = today.minusDays(3), onBudgetStart = {})
    }
    @Test fun budgetStartConfirm() = shot("151-budget-start-confirm") {
        Page { BudgetStartSetting(today.minusDays(3), {}, editing = true, confirming = today.minusDays(10)) }
    }
    @Test @Config(qualifiers = TALL) fun activityBeforeBudgetStart() = shot("152-activity-before-budget-start") {
        ActivityScreen(Budget(catBudget.accounts, catBudget.envelopes, catBudget.transactions, catBudget.lines, catBudget.moves, categories = catBudget.categories, budgetStart = today.minusDays(1)), {}, {}, { _, _, _ -> }, {})
    }
    // ---- 1.2.0: Activity tabs -------------------------------------------------------------------------------------
    private val tabsBudget: Budget by lazy {
        val wallet = acct("wal", "Sample Wallet", AccountType.PAYMENT_APP, 40_00).copy(sortOrder = 1)
        val cash = acct("cash", "Cash", AccountType.CASH, 60_00).copy(sortOrder = 2)
        val w1 = TransactionEntity(id = "w1", accountId = "wal", date = today.minusDays(2), payee = "SAMPLE CAFE", amount = -6_50, createdAt = 2, updatedAt = 2)
        val w2 = TransactionEntity(id = "w2", accountId = "chk", kind = net.darkrelay.deaddropfinance.data.TransactionKind.TRANSFER, transferAccountId = "wal", date = today.minusDays(5), memo = "", amount = 25_00, createdAt = 1, updatedAt = 1)
        Budget(
            catBudget.accounts + wallet + cash, catBudget.envelopes, catBudget.transactions + w1 + w2,
            catBudget.lines + TransactionLineEntity(transactionId = "w1", bucket = LineBucket.ENVELOPE, envelopeId = "e-unp", amount = -6_50, categoryId = "c-out", createdAt = 0, updatedAt = 0),
            catBudget.moves, categories = catBudget.categories,
        )
    }
    private fun tabs(selected: String?) = ActivityTabs(
        accounts = activityTabAccounts(tabsBudget), selected = selected, lastSync = { if (it == "chk") "Last sync Mar 15, 9:30 AM" else null },
    )
    @Test @Config(qualifiers = TALL) fun activityTabsAll() = shot("160-activity-tabs-all") {
        val unsorted = TransactionEntity(id = "u1", accountId = "chk", date = today, payee = "SAMPLE BAKERY", amount = -12_40, createdAt = 1, updatedAt = 1)
        val b = Budget(
            tabsBudget.accounts, tabsBudget.envelopes, tabsBudget.transactions + unsorted,
            tabsBudget.lines + TransactionLineEntity(transactionId = "u1", bucket = LineBucket.UNSORTED, amount = -12_40, createdAt = 1, updatedAt = 1), tabsBudget.moves, categories = tabsBudget.categories,
        )
        Column { Box(Modifier.weight(1f)) { ActivityScreen(b, {}, {}, { _, _, _ -> }, {}, tabs = tabs(null)) }; BottomTabs(Nav.Activity) {} }
    }
    @Test @Config(qualifiers = TALL) fun activityTabsSyncedAccount() = shot("161-activity-tab-synced-account") {
        Column { Box(Modifier.weight(1f)) { ActivityScreen(tabsBudget, {}, {}, { _, _, _ -> }, {}, tabs = tabs("chk")) }; BottomTabs(Nav.Activity) {} }
    }
    @Test @Config(qualifiers = TALL) fun activityTabsManualAccount() = shot("162-activity-tab-manual-account") {
        Column { Box(Modifier.weight(1f)) { ActivityScreen(tabsBudget, {}, {}, { _, _, _ -> }, {}, tabs = tabs("wal")) }; BottomTabs(Nav.Activity) {} }
    }
    @Test @Config(qualifiers = TALL) fun accountEditShowInActivity() = shot("163-account-edit-show-in-activity") {
        AccountEditScreen(tabsBudget.accounts.first { it.id == "wal" }, null, {}, {}, {}, budget = tabsBudget, onDelete = {}, deleteCount = 2)
    }
    // ---- 1.2.1: three-line Activity rows -------------------------------------------------------------------------
    private val longRows: Budget by lazy {
        val long = TransactionEntity(id = "L1", accountId = "chk", date = today, payee = "POS DEBIT SAMPLE MERCHANT #0042 1234 SAMPLE AVENUE SAMPLETOWN XX 00000 CARD 9999", amount = -12_34, createdAt = 3, updatedAt = 3)
        val mixed = TransactionEntity(id = "L2", accountId = "chk", date = today, payee = "Sample Coffee Roasters And Bakery Market", amount = -45_67, pending = true, createdAt = 4, updatedAt = 4)
        val big = TransactionEntity(id = "L3", accountId = "chk", date = today, payee = "SAMPLE FURNITURE OUTLET", amount = -98_765_43, createdAt = 5, updatedAt = 5)
        val bigIn = TransactionEntity(id = "L4", accountId = "chk", date = today, payee = "SAMPLE BONUS PAYOUT", amount = 12_345_67, isPayday = true, createdAt = 6, updatedAt = 6)
        fun l(tx: String, amount: Long, bucket: LineBucket, env: String? = null, c: String? = null) =
            TransactionLineEntity(transactionId = tx, bucket = bucket, envelopeId = env, amount = amount, categoryId = c, createdAt = 0, updatedAt = 0)
        Budget(
            tabsBudget.accounts, tabsBudget.envelopes, tabsBudget.transactions + long + mixed + big + bigIn,
            tabsBudget.lines + l("L1", -12_34, LineBucket.ENVELOPE, "e-groc", "c-groc") + l("L2", -45_67, LineBucket.ENVELOPE, "e-unp", "c-out") +
                l("L3", -98_765_43, LineBucket.ENVELOPE, "e-unp", "c-other") + l("L4", 12_345_67, LineBucket.READY_TO_ASSIGN),
            tabsBudget.moves, categories = tabsBudget.categories,
        )
    }
    @Test @Config(qualifiers = TALL) fun activityRowsAll() = shot("170-activity-rows-all") {
        Column { Box(Modifier.weight(1f)) { ActivityScreen(longRows, {}, {}, { _, _, _ -> }, {}, tabs = tabs(null)) }; BottomTabs(Nav.Activity) {} }
    }
    @Test @Config(qualifiers = TALL) fun activityRowsAccountTab() = shot("171-activity-rows-account-tab") {
        Column { Box(Modifier.weight(1f)) { ActivityScreen(longRows, {}, {}, { _, _, _ -> }, {}, tabs = tabs("chk")) }; BottomTabs(Nav.Activity) {} }
    }
    @Test fun activityRowsLongestName() = shot("172-activity-row-longest-name") {
        Page { longRows.transactions.filter { it.id == "L1" || it.id == "L2" }.forEach { TransactionRow(it, longRows) {} } }
    }
    @Test fun activityRowsLargestAmounts() = shot("173-activity-row-largest-amounts") {
        Page { longRows.transactions.filter { it.id == "L3" || it.id == "L4" || it.id == "L1" }.forEach { TransactionRow(it, longRows) {} } }
    }
    @Test @Config(qualifiers = "w400dp-h860dp-xhdpi", fontScale = 1.5f) fun activityRowsLargerFont() = shot("174-activity-rows-larger-font") {
        Page { longRows.transactions.filter { it.id in setOf("L1", "L2", "L3", "L4") }.forEach { TransactionRow(it, longRows) {} } }
    }
    @Test @Config(qualifiers = TALL) fun about() = shot("131-about") { AboutScreen("1.1.0", {}, {}, {}, {}) }
    @Test fun bonusIncomeForm() = shot("134-bonus-income-form") {
        BonusIncomeScreen(null, null, {}, {}, today = today)
    }
    @Test fun activityWithBonusIncome() = shot("135-activity-bonus-income") {
        val bonus = net.darkrelay.deaddropfinance.data.TransactionEntity(
            id = "bonus1", accountId = net.darkrelay.deaddropfinance.data.BONUS_ACCOUNT_ID, kind = net.darkrelay.deaddropfinance.data.TransactionKind.BONUS,
            date = today, payee = "Cash", memo = "Sample side work", amount = 40_00, createdAt = 1, updatedAt = 1,
        )
        val line = net.darkrelay.deaddropfinance.data.TransactionLineEntity(
            transactionId = "bonus1", bucket = net.darkrelay.deaddropfinance.data.LineBucket.READY_TO_ASSIGN, amount = 40_00, createdAt = 1, updatedAt = 1,
        )
        val withBonus = Budget(budget.accounts, budget.envelopes, budget.transactions + bonus, budget.lines + line, budget.moves)
        Page { withBonus.transactions.filter { it.kind == net.darkrelay.deaddropfinance.data.TransactionKind.BONUS }.forEach { TransactionRow(it, withBonus) {} } }
    }
    @Test @Config(qualifiers = TALL) fun moreWithUpdateBar() = shot("136-more-update-bar") {
        MoreScreen(more, {}, {}, {}, {}, {}, {}, {}, updateBar = { UpdateBar(release, UpdateInstallState(), {}, {}, {}, {}, {}) })
    }
    @Test fun licenses() = shot("133-licenses") {
        LicensesScreen(listOf("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.md", "PRIVACY.md", "TERMS.md", "Apache-2.0.txt", "BSD-3-Clause-SQLCipher.txt", "libpng-2.0.txt"), {}, {})
    }
    @Test fun document() = shot("134-document") {
        DocumentScreen("Privacy policy", "# Dead Drop Finance privacy policy\n\nInvented sample text for the screenshot.\n\n## What DarkRelay gets\n\nNothing.", {})
    }
}

/** A plain dialog look-alike for screenshots of a confirmation (the real ones are AlertDialogs). */
@Composable
private fun AlertDialogPreview(title: String, text: String, confirm: String) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {}, title = { androidx.compose.material3.Text(title) }, text = { androidx.compose.material3.Text(text) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text(confirm, color = DdfColors.Error) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = {}) { androidx.compose.material3.Text("Cancel") } },
    )
}
