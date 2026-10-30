package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * 0.13.0: what the tests of the phone's screens in the program share. The phone's screens date things by the real
 * clock (as on the phone), so this invented budget is made around today, not SampleBudget's 2030.
 */
abstract class ProgramScreensBase {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    lateinit var model: AppModel
    lateinit var shell: Shell
    lateinit var settings: Settings
    val ids = mutableMapOf<String, String>()
    val today: LocalDate = LocalDate.now()

    fun store(): BudgetStore = (model.engine!!.opened as DesktopDatabase.Opened.Ok).store
    fun a(): BudgetActions = model.actions!!
    fun b() = model.budget!!
    fun id(name: String) = ids.getValue(name)
    fun category(name: String) = runBlocking { store().dao.categoriesNow() }.first { it.name == name }.id

    /** What the screens' Open and Save As dialogs return in a test. */
    var picked: File? = null
    var saveTo: File? = null
    val posted = mutableListOf<Pair<String, String>>()

    /** The program on an invented budget: Checking and Cash, three envelopes, a payday three days ago. */
    fun show(extra: suspend BudgetActions.() -> Unit = {}) {
        val dir = tmp.newFolder("DeadDropFinance")
        val engine = runBlocking { Engine.start(dir) }
        model = AppModel(engine)
        runBlocking {
            val a = model.actions!!
            ids["Checking"] = a.saveAccount(null, "Checking", AccountType.BANK, 1500_00, today.minusDays(30))
            ids["Cash"] = a.saveAccount(null, "Cash", AccountType.CASH, 50_00, today.minusDays(30))
            // the budget started a month ago, so last week's transactions count (not history before the start)
            store().setBudgetStart(today.minusDays(30))
            ids["Groceries"] = a.saveEnvelope(null, EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 400_00, everyday = true), today.minusDays(30))
            ids["Rent"] = a.saveEnvelope(null, EnvelopeDraft("Rent", EnvelopeMode.FILL_TO_TARGET, target = 900_00), today.minusDays(30))
            ids["Gifts"] = a.saveEnvelope(null, EnvelopeDraft("Gifts", EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 40_00), today.minusDays(30))
            val pay = a.saveTransaction(null, TransactionDraft(id("Checking"), today.minusDays(3), "Sample Payroll Co", "", 1600_00, listOf(LineDraft(null, 1600_00, toReadyToAssign = true))))
            a.markPayday(pay, today.minusDays(3), fill = true)
            a.saveTransaction(null, TransactionDraft(id("Checking"), today.minusDays(1), "Corner Market", "", -42_00,
                listOf(LineDraft(id("Groceries"), -42_00, categoryId = category("Groceries")))))
            a.extra()
            model.refresh()
        }
        settings = Settings(tmp.newFolder("settings"))
        val notices = object : Notices {
            override fun allowed() = true
            override fun post(title: String, text: String) { posted += title to text }
        }
        shell = Shell(
            run = { block -> runBlocking { block() } }, settings = settings, notices = notices,
            pickCsv = { picked }, pickJson = { picked }, pickReceipt = { picked }, pickSave = { saveTo },
            keyProtector = TestProtector, onOpenPage = { opened += it },
            onErase = { erases++ }, onPurge = { runBlocking { ProgramReset.purgeNow(model, settings) } },
        )
        compose.setContent { DesktopApp(model, shell) }
        compose.waitForIdle()
    }

    /** The program on [m] (a budget made elsewhere, as the end to end test's logged-in one). */
    fun showOn(m: AppModel) {
        model = m
        settings = Settings(tmp.newFolder("settings"))
        shell = Shell(run = { block -> runBlocking { block() } }, settings = settings, keyProtector = TestProtector, pickSave = { saveTo })
        compose.setContent { DesktopApp(model, shell) }
        compose.waitForIdle()
    }

    val opened = mutableListOf<String>()
    var erases = 0
    fun dataDir(): File = (model.engine!!.opened as DesktopDatabase.Opened.Ok).file.parentFile

    @After fun closeBudget() { if (::model.isInitialized) model.engine?.close() }

    fun refresh() = runBlocking { model.refresh() }
    fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    fun click(t: String) { tag(t).performClick(); compose.waitForIdle() }
    fun shows(t: String) = compose.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    /** Waits for [t] to show, scrolling the screen's list to it (a phone list composes only what's on screen). */
    fun until(t: String, ms: Long = 8_000) {
        val end = System.currentTimeMillis() + ms
        while (!shows(t)) {
            if (System.currentTimeMillis() > end) throw AssertionError("never showed: $t. Showing: " + dump())
            scrollTo(t)
            if (!shows(t)) Thread.sleep(100)
            compose.waitForIdle()
        }
    }
    fun gone(t: String, ms: Long = 8_000) { compose.waitUntil(ms) { !shows(t) } }

    /** The first node showing [t] (scrolled into view first when it's in a scrolling list). */
    fun node(t: String, exact: Boolean = false): SemanticsNodeInteraction {
        // the phone's screens draw once the budget has been read: wait for the text first
        val end = System.currentTimeMillis() + 8_000
        while (compose.onAllNodesWithText(t, substring = !exact, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            if (System.currentTimeMillis() > end) throw AssertionError("never showed: $t. Showing: " + dump())
            scrollTo(t, exact)
            Thread.sleep(50)
            compose.waitForIdle()
        }
        return compose.onAllNodesWithText(t, substring = !exact, useUnmergedTree = true)[0]
    }

    fun clickText(t: String, exact: Boolean = false) {
        val n = node(t, exact)
        // into view first: a click lands where the node is drawn
        runCatching { n.performScrollTo() }
        compose.waitForIdle()
        n.performClick()
        compose.waitForIdle()
    }

    fun scrollTo(t: String, exact: Boolean = false) {
        if (compose.onAllNodesWithText(t, substring = !exact, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return
        val lists = compose.onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true).fetchSemanticsNodes()
        for (i in lists.indices) {
            runCatching { compose.onAllNodes(hasScrollToNodeAction(), useUnmergedTree = true)[i].performScrollToNode(hasText(t, substring = !exact)) }
            compose.waitForIdle()
            if (shows(t)) return
        }
    }

    /** The switch or checkbox on the row showing [t]: the toggle nearest that text's line. */
    fun toggleFor(t: String) {
        runCatching { node(t, exact = true).performScrollTo() }
        compose.waitForIdle()
        val label = node(t, exact = true).fetchSemanticsNode()
        val y = label.boundsInRoot.center.y
        val toggles = compose.onAllNodes(isToggleable(), useUnmergedTree = true).fetchSemanticsNodes()
        val i = toggles.indices.minBy { kotlin.math.abs(toggles[it].boundsInRoot.center.y - y) }
        compose.onAllNodes(isToggleable(), useUnmergedTree = true)[i].performClick()
        compose.waitForIdle()
    }

    /** Types into the text field labeled [label] (the phone's OutlinedTextField labels). */
    fun typeInto(label: String, text: String) {
        val f = fieldFor(label)
        f.performTextClearance()
        f.performTextInput(text)
        compose.waitForIdle()
    }

    fun fieldFor(label: String): SemanticsNodeInteraction {
        scrollTo(label, exact = true)
        val editable = SemanticsMatcher("editable labeled $label") { n: SemanticsNode ->
            n.config.contains(SemanticsProperties.EditableText) &&
                (n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true ||
                    n.children.any { c -> c.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true })
        }
        return compose.onAllNodes(editable, useUnmergedTree = false)[0]
    }

    /** Clicks the lowest node showing exactly [t]: a phone screen's button, below the program's menu strip of the same name. */
    fun clickLowest(t: String) {
        node(t, exact = true)
        // the clickable that holds the text (a button merges its label), by its own click action
        val all = compose.onAllNodes(androidx.compose.ui.test.hasClickAction() and hasText(t, substring = false), useUnmergedTree = false)
        val ns = all.fetchSemanticsNodes()
        all[ns.indices.maxBy { ns[it].boundsInRoot.center.y }].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    /** Every text on screen with its height, and the toggles: for a failing test's message. */
    fun dump(): String {
        fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap { all(it) }
        return compose.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { all(it) }.mapNotNull { n ->
            val text = n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }
            val y = n.boundsInRoot.center.y.toInt()
            when {
                text != null -> "$text@$y"
                n.config.contains(SemanticsProperties.ToggleableState) -> "TOGGLE(${n.config[SemanticsProperties.ToggleableState]})@$y"
                else -> null
            }
        }.joinToString(" | ")
    }

    object TestProtector : net.darkrelay.deaddropfinance.data.KeyProtector {
        override fun protect(plain: ByteArray) = "TEST".toByteArray() + plain.map { (it.toInt() xor 0x5a).toByte() }
        override fun unprotect(blob: ByteArray) = blob.copyOfRange(4, blob.size).map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
