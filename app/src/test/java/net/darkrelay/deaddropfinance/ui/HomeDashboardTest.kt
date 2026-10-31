package net.darkrelay.deaddropfinance.ui

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import net.darkrelay.deaddropfinance.BuildConfig
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.Reports.Kind
import net.darkrelay.deaddropfinance.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's Home dashboard (1.9.1): picked reports as cards on Home, added, removed and dragged into order on Customize
 * Home, saved on this phone (its own settings, not a backup's carried keys); a phone that never customized shows no
 * report cards. Invented data. The compose rule needs the debug build's test activity, so release unit tests skip these.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "w360dp-h2400dp-xhdpi")
class HomeDashboardTest {

    private val compose = createComposeRule()

    @get:Rule val rules: RuleChain = RuleChain
        .outerRule { base, _ -> object : Statement() { override fun evaluate() { assumeTrue(BuildConfig.DEBUG); base.evaluate() } } }
        .around(compose)

    private val today = LocalDate.of(2030, 8, 20)
    private val budget = Budget(
        listOf(AccountEntity(id = "a", name = "Sample", type = AccountType.BANK, startDate = today.minusDays(30), createdAt = 1, updatedAt = 1)),
        emptyList(), emptyList(), emptyList(), emptyList(),
    )
    private val data = ReportData(budget, today, emptyList(), emptyList(), null)

    private var screen by mutableStateOf<(@Composable () -> Unit)?>(null)
    private var shown = false
    private fun show(content: @Composable () -> Unit) {
        screen = content
        if (!shown) { compose.setContent { screen?.invoke() }; shown = true }
        compose.waitForIdle()
    }

    private fun home(cards: List<Kind>?) = show {
        HomeScreen(
            budget, top = null, onAssign = {}, onToSort = {}, onEnvelope = {}, onAllEnvelopes = {}, onSafeToSpend = {}, onFixOverAssigned = {},
            dashboard = cards?.let { c -> { HomeReportCards(c, data, 0, onOpen = {}, onCustomize = {}) } },
        )
    }

    @Test fun theHelpersAddRemoveAndMove() {
        val a = HomeDashboard.add(emptyList(), Kind.NET_WORTH)
        val b = HomeDashboard.add(a, Kind.TRENDS)
        assertEquals(listOf(Kind.NET_WORTH, Kind.TRENDS), b)
        assertEquals(b, HomeDashboard.add(b, Kind.TRENDS))
        assertEquals(listOf(Kind.TRENDS, Kind.NET_WORTH), HomeDashboard.move(b, Kind.TRENDS, 0))
        assertEquals(listOf(Kind.TRENDS), HomeDashboard.remove(b, Kind.NET_WORTH))
        // what's saved reads back; an unknown or repeated name is dropped
        assertEquals(listOf(Kind.TRENDS, Kind.MONEY_FLOW), HomeDashboard.read(listOf("TRENDS", "NOT_A_REPORT", "MONEY_FLOW", "TRENDS")))
        assertEquals(listOf("TRENDS", "MONEY_FLOW"), HomeDashboard.write(listOf(Kind.TRENDS, Kind.MONEY_FLOW)))
    }

    @Test fun theChoiceIsSavedOnThisPhoneOnly() {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sync", Context.MODE_PRIVATE)
        val s = SyncSettings(prefs)
        assertEquals(emptyList<String>(), s.homeReports)
        s.homeReports = listOf("SAVINGS_GOALS", "NET_WORTH")
        assertEquals(listOf("SAVINGS_GOALS", "NET_WORTH"), SyncSettings(prefs).homeReports)
        // not among the settings a backup carries
        val carried = net.darkrelay.deaddropfinance.backup.SettingsTransfer(mapOf("sync" to prefs)).export().toString()
        assertTrue(carried, !carried.contains("home_reports") && !carried.contains("SAVINGS_GOALS"))
    }

    @Test fun aPhoneThatNeverCustomizedShowsNoReportCards() {
        home(HomeDashboard.read(emptyList()))
        assertEquals(0, compose.onAllNodesWithTag("report.NET_WORTH").fetchSemanticsNodes().size)
        Kind.entries.forEach { k -> assertEquals(0, compose.onAllNodesWithTag("report.${k.name}").fetchSemanticsNodes().size) }
        compose.onNodeWithTag("home.customize").performScrollTo().assertExists()
    }

    @Test fun pickedReportsShowAsCardsInTheirOrder() {
        home(listOf(Kind.SAVINGS_GOALS, Kind.NET_WORTH))
        val goals = compose.onNodeWithTag("report.SAVINGS_GOALS").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        val worth = compose.onNodeWithTag("report.NET_WORTH").performScrollTo().fetchSemanticsNode().boundsInRoot.top
        assertTrue("Savings goals above Net worth", goals < worth)
    }

    @Test fun customizeAddsRemovesAndReordersByDragging() {
        var cards by mutableStateOf(emptyList<Kind>())
        show { HomeCustomizeScreen(cards, onChange = { cards = it }, onBack = {}) }
        compose.onNodeWithTag("home.add.NET_WORTH").performScrollTo().performClick()
        compose.onNodeWithTag("home.add.TRENDS").performScrollTo().performClick()
        compose.onNodeWithTag("home.add.SAVINGS_GOALS").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Kind.NET_WORTH, Kind.TRENDS, Kind.SAVINGS_GOALS), cards)
        // drag the first card's handle down past the second
        compose.onNodeWithTag("home.handle.NET_WORTH").performTouchInput { swipeDown(startY = centerY, endY = centerY + 140f, durationMillis = 600) }
        compose.waitForIdle()
        assertEquals(listOf(Kind.TRENDS, Kind.NET_WORTH, Kind.SAVINGS_GOALS), cards)
        compose.onNodeWithTag("home.remove.TRENDS").performClick()
        compose.waitForIdle()
        assertEquals(listOf(Kind.NET_WORTH, Kind.SAVINGS_GOALS), cards)
        assertEquals(0, compose.onAllNodesWithTag("home.add.NET_WORTH").fetchSemanticsNodes().size)
    }
}
