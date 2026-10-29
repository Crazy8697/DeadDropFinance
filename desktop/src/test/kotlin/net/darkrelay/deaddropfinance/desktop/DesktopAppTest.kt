package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The screens' main flows, clicked through on an invented budget in a temp folder (encrypted, as the program keeps it). */
class DesktopAppTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var made: SampleBudget.Made
    private val m get() = made.model
    private var csvFile: File? = null

    private fun show(update: UpdateState = UpdateState.Hidden, canInstall: Boolean = true, onInstall: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        val shell = Shell(
            update = update, canInstall = canInstall, installedVersion = "0.6.0", onInstall = onInstall, onDismissUpdate = onDismiss,
            run = { block -> runBlocking { block() } }, pickCsv = { csvFile },
        )
        compose.setContent { DesktopApp(m, shell) }
        compose.waitForIdle()
    }

    @After fun close() { if (::made.isInitialized) m.engine?.close() }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun b() = m.budget!!
    private fun id(name: String) = made.ids.getValue(name)
    private fun pick(select: String, option: String) {
        tag(select).performClick()
        compose.waitForIdle()
        tag("$select.option.$option").performClick()
        compose.waitForIdle()
    }

    @Test fun assignFromTheEnvelopeTable() {
        show()
        val rta = b().readyToAssign
        val before = b().envelopeBalance(id("Gas"))
        tag("assign.Gas").performClick()
        tag("dialog.move").assertExists()
        tag("f.amount").performTextInput("25.00")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertNull(m.dialog, m.message?.first)
        assertEquals(before + 25_00, b().envelopeBalance(id("Gas")))
        assertEquals(rta - 25_00, b().readyToAssign)
        tag("card.rta.value").assertTextEquals(money(rta - 25_00))
        // more than Ready to Assign holds: refused, said plainly, nothing moved
        tag("assign.Gas").performClick()
        tag("f.amount").performTextInput("99999.00")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertTrue(m.message!!.second && m.message!!.first.contains("ready to assign"), m.message?.first)
        assertEquals(before + 25_00, b().envelopeBalance(id("Gas")))
    }

    @Test fun moveMoneyBetweenEnvelopes() {
        show()
        val g = b().envelopeBalance(id("Groceries"))
        val d = b().envelopeBalance(id("Dining out"))
        tag("top.move").performClick()
        pick("f.from", "Groceries")
        pick("f.to", "Dining out")
        tag("f.amount").performTextInput("10.50")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertNull(m.dialog)
        assertEquals(g - 10_50, b().envelopeBalance(id("Groceries")))
        assertEquals(d + 10_50, b().envelopeBalance(id("Dining out")))
    }

    @Test fun addATransaction() {
        show()
        val acct = b().accountBalance(id("Checking"))
        val gas = b().envelopeBalance(id("Gas"))
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Car Wash")
        tag("f.amount").performTextInput("12.34")
        pick("f.account", "Checking")
        // a purchase needs a category (0.8.0, the phone's rule); no envelope claims Car gas, so it would go to Unplanned
        pick("f.category", "Car gas")
        pick("f.envelope", "Gas")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertNull(m.dialog, m.message?.first)
        assertEquals(acct - 12_34, b().accountBalance(id("Checking")))
        assertEquals(gas - 12_34, b().envelopeBalance(id("Gas")))
        tag("nav.ACTIVITY").performClick()
        compose.waitForIdle()
        tag("act.Sample Car Wash").assertExists()
        tag("actAmount.Sample Car Wash").assertTextEquals("-$12.34")
    }

    @Test fun csvImportOnAnAccountTab() {
        show()
        csvFile = tmp.newFile("sample-bank.csv").also { it.writeText(SampleBudget.csv) }
        val toSort = b().toSort.size
        tag("nav.ACTIVITY").performClick()
        tag("tab.Savings").performClick()
        compose.waitForIdle()
        tag("importCsv").performClick()
        compose.waitForIdle()
        tag("dialog.import").assertExists()
        tag("import.new").assertTextEquals("3 new transactions go to To sort.")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertNull(m.dialog)
        assertEquals(toSort + 3, b().toSort.size)
        assertEquals(1042_10L - 12_50 - 8_20 + 42, b().accountBalance(id("Savings")))
        tag("act.Sample Bookshop").assertExists()
        // the same file again: nothing new
        tag("importCsv").performClick()
        compose.waitForIdle()
        tag("import.new").assertTextEquals("0 new transactions go to To sort.")
    }

    @Test fun theAllTabIsViewOnly() {
        show()
        tag("nav.ACTIVITY").performClick()
        compose.waitForIdle()
        tag("tab.All").performClick()
        compose.waitForIdle()
        tag("add").assertHasNoClickAction()
        tag("importCsv").assertHasNoClickAction()
        tag("viewOnly").assertExists()
        // moves are labeled rows
        tag("act.Payday auto-fill").assertExists()
        tag("tab.Checking").performClick()
        compose.waitForIdle()
        tag("add").assertHasClickAction()
        tag("importCsv").assertHasClickAction()
    }

    @Test fun aNewAccount() {
        show()
        val inBudget = b().accounts.filter { it.onBudget }.sumOf { b().accountBalance(it.id) }
        tag("nav.ACCOUNTS").performClick()
        tag("newAccount").performClick()
        tag("f.name").performTextInput("Sample Credit Union")
        tag("f.start").performTextInput("500.00")
        tag("dialog.save").performClick()
        compose.waitForIdle()
        assertNull(m.dialog, m.message?.first)
        tag("acct.Sample Credit Union").assertExists()
        tag("bal.Sample Credit Union").assertTextEquals("$500.00")
        tag("card.inBudget.value").assertTextEquals(money(inBudget + 500_00))
        assertTrue(b().accounts.any { it.name == "Sample Credit Union" && it.onBudget })
    }

    @Test fun theDrawerShowsTheEnvelope() {
        show()
        tag("env.Groceries").performClick()
        compose.waitForIdle()
        tag("drawer.name").assertTextEquals("Groceries")
        tag("drawer.available.value").assertTextEquals(money(b().envelopeBalance(id("Groceries"))))
        tag("drawer.target.value").assertTextEquals("$600.00")
        tag("act.Corner Market").assertExists()
        tag("drawer.move").performClick()
        compose.waitForIdle()
        tag("dialog.move").assertExists()
    }

    @Test fun syncSaysNotConnectedAndPointsToSettings() {
        show()
        tag("sideSync").assertTextEquals("Not connected")
        tag("nav.SYNC").performClick()
        compose.waitForIdle()
        tag("syncSummary").assertExists()
        compose.onAllNodesWithText("Not connected", substring = false).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
        tag("sync.now").assertDoesNotExist()
        tag("sync.settings").performClick()
        compose.waitForIdle()
        assertEquals(Screen.SETTINGS, m.screen)
        tag("serverPanel").assertExists()
        tag("nav.SYNC").performClick()
        compose.waitForIdle()
        for (word in listOf("Synced", "Add a phone", "Log out")) {
            assertEquals(0, compose.onAllNodesWithText(word, substring = false).fetchSemanticsNodes().size, word)
        }
        tag("top.add").assertDoesNotExist()
        tag("top.move").assertDoesNotExist()
    }

    @Test fun theUpdateBarOffersTheUpdate() {
        var installs = 0
        var dismissed = 0
        show(UpdateState.Available(Updater.Release("0.7.0", "desktop-v0.7.0", "", "")), onInstall = { installs++ }, onDismiss = { dismissed++ })
        tag("updateText").assertTextEquals("Dead Drop Finance 0.7.0 is available. You are on 0.6.0.")
        tag("install").performClick()
        tag("dismiss").performClick()
        assertEquals(1, installs)
        assertEquals(1, dismissed)
    }
}
