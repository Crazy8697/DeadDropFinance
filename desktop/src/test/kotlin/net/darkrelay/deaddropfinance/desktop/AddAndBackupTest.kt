package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.domain.Budget
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop 0.8.0: the Payday and Fill envelopes boxes, the category picker, split lines, To sort as money in, and
 * backup and restore, clicked through on an invented budget in a temp folder.
 */
class AddAndBackupTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var made: SampleBudget.Made
    private val m get() = made.model
    private var saveTo: File? = null
    private var openFrom: File? = null

    private fun show() {
        made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        val shell = Shell(run = { block -> runBlocking { block() } }, pickBackupSave = { saveTo }, pickBackupOpen = { openFrom })
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
    private fun save() {
        tag("dialog.save").performClick()
        compose.waitForIdle()
    }

    // ---- Payday and Fill envelopes ----

    @Test fun fillIsGreyedUntilPaydayIsTickedAndPaydayIsGreyedForSpending() {
        show()
        tag("top.add").performClick()
        compose.waitForIdle()
        // the dialog starts on an envelope: spending, so both boxes are greyed
        tag("f.payday").assertIsNotEnabled().assertIsOff()
        tag("f.fill").assertIsNotEnabled().assertIsOff()
        pick("f.envelope", "Ready to assign")
        tag("f.payday").assertIsEnabled().assertIsOff()
        tag("f.fill").assertIsNotEnabled()
        tag("f.payday").performClick()
        tag("f.fill").assertIsEnabled().assertIsOff()
        tag("f.fill").performClick()
        tag("f.fill").assertIsOn()
        // unticking Payday unticks and greys Fill
        tag("f.payday").performClick()
        tag("f.fill").assertIsNotEnabled().assertIsOff()
        tag("f.payday").performClick()
        tag("f.fill").assertIsOff()
        // To sort isn't Ready to assign: greyed again
        pick("f.envelope", "Not sorted yet (To sort)")
        tag("f.payday").assertIsNotEnabled().assertIsOff()
        tag("f.fill").assertIsNotEnabled().assertIsOff()
    }

    @Test fun aPaydayWithFillStartsTheMonthAndFills() {
        show()
        val payday = SampleBudget.today
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Payroll Co")
        tag("f.amount").performTextInput("2000.00")
        pick("f.envelope", "Ready to assign")
        tag("f.payday").performClick()
        tag("f.fill").performClick()
        save()
        assertNull(m.dialog, m.message?.first)
        assertTrue(m.message!!.first.startsWith("Payday:"), m.message?.first)
        assertEquals(Budget.Month(payday, null), b().months(payday).first())
        assertTrue(b().moves.any { it.kind == MoveKind.AUTOFILL && it.date == payday })
        assertEquals(600_00L, b().envelopeBalance(id("Groceries")), "filled to its target")
    }

    @Test fun aPaydayWithoutFillSweepsLeftoversButFillsNothing() {
        show()
        val before = b()
        val rollover = before.rollover!!.id
        tag("top.add").performClick()
        tag("f.amount").performTextInput("300.00")
        pick("f.envelope", "Ready to assign")
        tag("f.payday").performClick()
        save()
        assertNull(m.dialog, m.message?.first)
        assertEquals(Budget.Month(SampleBudget.today, null), b().months(SampleBudget.today).first())
        // 0.9.0: the month closes, so Fill to target leftovers go to Rollover; nothing is filled
        val added = b().moves.filter { mv -> before.moves.none { it.id == mv.id } }
        assertTrue(added.isNotEmpty() && added.all { it.kind == MoveKind.SWEEP && it.toEnvelopeId == rollover }, "$added")
        assertTrue(b().moves.none { it.kind == MoveKind.AUTOFILL && it.date == SampleBudget.today })
        assertEquals(before.readyToAssign + 300_00, b().readyToAssign)
        assertTrue(m.message!!.first.contains("swept into Rollover"), m.message?.first)
    }

    @Test fun moneyInThatIsntAPaydayIsPlainMoneyIn() {
        show()
        val months = b().months(SampleBudget.today)
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Store Refund")
        tag("f.amount").performTextInput("15.00")
        pick("f.envelope", "Ready to assign")
        save()
        assertEquals(months, b().months(SampleBudget.today), "no new month")
        assertFalse(b().transactions.single { it.payee == "Sample Store Refund" }.isPayday)
    }

    @Test fun toSortIsMoneyInLeftToSort() {
        show()
        val toSort = b().toSort.size
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Unknown Deposit")
        tag("f.amount").performTextInput("9.99")
        pick("f.envelope", "Not sorted yet (To sort)")
        save()
        assertNull(m.dialog, m.message?.first)
        val t = b().transactions.single { it.payee == "Sample Unknown Deposit" }
        assertEquals(9_99L, t.amount)
        assertEquals(toSort + 1, b().toSort.size)
        assertEquals(listOf(LineBucket.UNSORTED), b().lines.filter { it.transactionId == t.id }.map { it.bucket })
    }

    // ---- categories and splits ----

    @Test fun aPurchaseNeedsACategoryAndTheCategoryPicksItsEnvelope() {
        show()
        val count = b().transactions.size
        tag("top.add").performClick()
        tag("f.amount").performTextInput("5.00")
        pick("f.envelope", "Gas")
        tag("f.category.value").assertTextEquals("Choose a category")
        save()
        assertEquals("Choose a category.", m.message?.first)
        assertEquals(count, b().transactions.size, "nothing saved")
        // a category no envelope claims goes to Unplanned, as on the phone
        pick("f.category", "Kids")
        tag("f.envelope.value").assertTextEquals("Unplanned")
        save()
        assertNull(m.dialog, m.message?.first)
        val line = b().lines.single { l -> b().transactions.single { it.amount == -5_00L }.id == l.transactionId }
        assertEquals(b().unplannedEnvelope!!.id, line.envelopeId)
        assertEquals(b().categories.single { it.name == "Kids" }.id, line.categoryId)
    }

    @Test fun oneTripSplitsIntoLinesEachWithItsCategory() {
        show()
        val acct = b().accountBalance(id("Checking"))
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Superstore")
        tag("f.amount").performTextInput("90.00")
        pick("f.envelope", "Groceries")
        tag("split.start").performClick()
        compose.waitForIdle()
        tag("split.add").performClick()
        compose.waitForIdle()
        pick("split.0.category", "Groceries")
        pick("split.0.envelope", "Groceries")
        tag("split.0.amount").performTextInput("50.00")
        pick("split.1.category", "Household")
        tag("split.1.amount").performTextInput("25.00")
        pick("split.2.category", "Snacks")
        tag("split.left").assertTextEquals("Left to split: $15.00")
        tag("split.2.amount").performTextInput("15.00")
        tag("split.left").assertTextEquals("Left to split: $0.00")
        tag("f.payday").assertIsNotEnabled()
        save()
        assertNull(m.dialog, m.message?.first)
        val t = b().transactions.single { it.payee == "Sample Superstore" }
        assertEquals(-90_00L, t.amount)
        val lines = b().lines.filter { it.transactionId == t.id }.sortedBy { it.amount }
        assertEquals(listOf(-50_00L, -25_00L, -15_00L), lines.map { it.amount })
        assertEquals(listOf("Groceries", "Household", "Snacks"), lines.map { l -> b().categories.single { it.id == l.categoryId }.name })
        assertEquals(acct - 90_00, b().accountBalance(id("Checking")))
    }

    @Test fun moneyInSplitsAcrossEnvelopes() {
        show()
        val rta = b().readyToAssign
        val gifts = b().envelopeBalance(id("Gifts"))
        tag("top.add").performClick()
        tag("f.payee").performTextInput("Sample Side Job")
        tag("f.amount").performTextInput("100.00")
        pick("f.envelope", "Ready to assign")
        tag("split.startIncome").performClick()
        compose.waitForIdle()
        pick("split.0.envelope", "Ready to assign")
        tag("split.0.amount").performTextInput("60.00")
        pick("split.1.envelope", "Gifts")
        tag("split.1.amount").performTextInput("40.00")
        tag("split.left").assertTextEquals("Left to split: $0.00")
        tag("f.payday").assertIsNotEnabled()
        save()
        assertNull(m.dialog, m.message?.first)
        val t = b().transactions.single { it.payee == "Sample Side Job" }
        assertEquals(100_00L, t.amount)
        val lines = b().lines.filter { it.transactionId == t.id }.sortedBy { it.amount }
        assertEquals(listOf(40_00L, 60_00L), lines.map { it.amount })
        assertEquals(listOf(LineBucket.ENVELOPE, LineBucket.READY_TO_ASSIGN), lines.map { it.bucket })
        assertTrue(lines.all { it.categoryId == null }, "money in has no category")
        assertEquals(rta + 60_00, b().readyToAssign)
        assertEquals(gifts + 40_00, b().envelopeBalance(id("Gifts")))
    }

    @Test fun aSplitThatDoesntAddUpOrLacksACategoryIsRefused() {
        show()
        val count = b().transactions.size
        tag("top.add").performClick()
        tag("f.amount").performTextInput("30.00")
        pick("f.envelope", "Groceries")
        tag("split.start").performClick()
        compose.waitForIdle()
        pick("split.0.category", "Groceries")
        tag("split.0.amount").performTextInput("20.00")
        pick("split.1.category", "Snacks")
        tag("split.1.amount").performTextInput("5.00")
        save()
        assertTrue(m.message!!.first.contains("adds up to"), m.message?.first)
        assertEquals(count, b().transactions.size)
    }

    // ---- backup and restore ----

    @Test fun backupAndRestoreThroughTheDialogs() {
        show()
        val before = b().accounts.map { it.name to b().accountBalance(it.id) }
        saveTo = tmp.root.resolve("sample-backup.ddfbak")
        tag("nav.SETTINGS").performClick()
        tag("settings.backup").performClick()
        compose.waitForIdle()
        tag("lostPassword").assertExists()
        tag("f.password").performTextInput("sample pass 1")
        tag("f.again").performTextInput("sample pass 2")
        save()
        tag("f.problem").assertTextEquals("The two passwords don't match.")
        assertFalse(saveTo!!.exists())
        tag("f.again").performTextClearance()
        tag("f.again").performTextInput("sample pass 1")
        save()
        assertNull(m.dialog, m.message?.first)
        assertTrue(saveTo!!.isFile && saveTo!!.length() > 100)
        assertFalse(File(saveTo!!.path + ".part").exists())

        // change the budget, then restore: wrong password first
        tag("nav.ACCOUNTS").performClick()
        tag("newAccount").performClick()
        tag("f.name").performTextInput("Sample Later Account")
        save()
        assertNotEquals(before, b().accounts.map { it.name to b().accountBalance(it.id) })
        openFrom = saveTo
        tag("nav.SETTINGS").performClick()
        tag("settings.restore").performClick()
        compose.waitForIdle()
        tag("restore.file").assertTextEquals("sample-backup.ddfbak")
        tag("f.password").performTextInput("not the password")
        save()
        tag("dialog.confirm").performClick()
        compose.waitForIdle()
        assertTrue(m.message!!.second && m.message!!.first.contains("Wrong password"), m.message?.first)
        assertTrue(b().accounts.any { it.name == "Sample Later Account" }, "nothing replaced")
        // the right password, confirmed
        tag("settings.restore").performClick()
        compose.waitForIdle()
        tag("f.password").performTextInput("sample pass 1")
        save()
        tag("dialog.confirmRestore").assertExists()
        tag("dialog.confirm").performClick()
        compose.waitForIdle()
        assertFalse(m.message!!.second, m.message?.first)
        assertEquals(before, b().accounts.map { it.name to b().accountBalance(it.id) })
    }

    @Test fun theFileMenuHasBackupAndRestore() {
        show()
        tag("menu.File").performClick()
        compose.waitForIdle()
        tag("menuItem.Back up...").assertExists()
        tag("menuItem.Restore...").assertExists()
        tag("menuItem.Back up...").performClick()
        compose.waitForIdle()
        tag("dialog.backup").assertExists()
    }
}
