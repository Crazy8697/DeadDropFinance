package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.MoveKind
import net.darkrelay.deaddropfinance.data.TransactionKind
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.12.3: the program does what the phone app does. Each feature the program lacked, clicked through on an invented
 * budget in a temp folder: editing and deleting a transaction from its row (Activity, an account tab, the envelope
 * drawer), transfers and ATM withdrawals, bonus income, money straight into an envelope, Pending, archiving and
 * deleting accounts, archiving envelopes, Cover from Rollover, the budget start date, a Debt envelope keeping its
 * payment details, and the after-import step. The sync of an edit and a delete is in DesktopE2ETest.
 */
class DesktopParityTest {

    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var made: SampleBudget.Made
    private val m get() = made.model
    private fun b() = m.budget!!
    private fun id(name: String) = made.ids.getValue(name)
    private fun store() = (m.engine!!.opened as DesktopDatabase.Opened.Ok).store

    private fun show() {
        made = SampleBudget.filled(tmp.newFolder("DeadDropFinance"))
        compose.setContent { DesktopApp(m, Shell(run = { block -> runBlocking { block() } })) }
        compose.waitForIdle()
    }

    @After fun close() { runCatching { compose.setContent {}; compose.waitForIdle() }; if (::made.isInitialized) m.engine?.close() }

    private fun tag(t: String): SemanticsNodeInteraction = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun click(t: String) { tag(t).performClick(); compose.waitForIdle() }
    private fun type(t: String, text: String) { tag(t).performTextClearance(); tag(t).performTextInput(text); compose.waitForIdle() }
    private fun pick(select: String, option: String) { click(select); click("$select.option.$option") }
    private fun textOf(t: String): String {
        val c = tag(t).fetchSemanticsNode().config
        return c.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.EditableText) { null }?.text
            ?: c[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
    }
    private fun tx(payee: String) = b().transactions.first { it.payee == payee }
    private fun changesFor(entityId: String) = runBlocking { store().dao.changes() }.filter { it.entityId == entityId }

    // ---- editing and deleting a transaction ----

    @Test fun aRowInActivityOpensItsEditFormAndSavingEditsIt() {
        show()
        val before = tx("Corner Market")
        click("nav.ACTIVITY")
        click("act.Corner Market")
        tag("dialog.add").assertExists()
        tag("dialog.delete").assertExists()
        assertEquals("Corner Market", textOf("f.payee"))
        type("f.payee", "Corner Market Edited")
        type("f.amount", "50.00")
        click("dialog.save")
        assertNull(m.dialog, m.message?.first)
        val after = tx("Corner Market Edited")
        assertEquals(before.id, after.id, "the same transaction, edited")
        assertEquals(-50_00, after.amount)
        assertEquals(1, b().transactions.count { it.id == before.id })
        // it syncs as an edit: an UPDATE of that transaction in the change log, as the phone's edits are
        assertTrue(changesFor(before.id).any { it.operation.name == "UPDATE" && it.changedFields.contains("Corner Market Edited") })
    }

    @Test fun anAccountTabRowAndTheEnvelopeDrawerRowOpenItToo() {
        show()
        click("nav.ACTIVITY")
        click("tab.Checking")
        click("act.Fuel Stop 12")
        assertEquals("Fuel Stop 12", textOf("f.payee"))
        click("dialog.cancel")
        click("nav.BUDGET")
        m.drawerEnvelope = id("Groceries")
        compose.waitForIdle()
        click("act.Corner Market")
        assertEquals("Corner Market", textOf("f.payee"))
        assertEquals("43.12", textOf("f.amount"))
    }

    @Test fun deleteAsksFirstThenSoftDeletesAsThePhoneDoes() {
        show()
        val t = tx("Corner Market")
        val groceries = b().envelopeBalance(id("Groceries"))
        click("nav.ACTIVITY")
        click("act.Corner Market")
        click("dialog.delete")
        assertEquals("Delete this transaction?", textOf("delete.ask"))
        click("delete.keep")
        assertTrue(b().transactions.any { it.id == t.id }, "Keep changes nothing")
        click("dialog.delete")
        click("delete.confirm")
        assertNull(m.dialog)
        assertFalse(b().transactions.any { it.id == t.id }, "gone from the budget")
        assertEquals(groceries + 43_12, b().envelopeBalance(id("Groceries")), "its money is back in the envelope")
        // a soft delete: the row stays as a tombstone, and the change log carries it for sync
        val row = runBlocking { store().dao.transaction(t.id) }
        assertTrue(row == null || row.deleted, "tombstone")
        assertTrue(changesFor(t.id).any { it.operation.name == "DELETE" || it.changedFields.contains("\"deleted\":true") })
    }

    /** 0.14.0: a move made by hand opens Edit move (ProgramEditMoveTest); before, move rows opened nothing. */
    @Test fun aMoveRowOpensEditMove() {
        show()
        click("nav.ACTIVITY")
        click("act.Moved from Dining out")
        assertTrue((m.dialog as? Dialog.MoveMoney)?.editId != null, m.dialog.toString())
    }

    // ---- transfers, ATM withdrawals, bonus income, money in, Pending ----

    @Test fun aTransferAndAnAtmWithdrawalAreAddedEditedAndKeepTheirEnvelope() {
        show()
        click("nav.ACTIVITY")
        click("tab.Checking")
        click("transfer")
        tag("dialog.transfer").assertExists()
        pick("f.to", "Cash")
        type("f.amount", "20.00")
        pick("f.atmEnvelope", "Groceries")
        click("dialog.save")
        assertNull(m.dialog, m.message?.first)
        val t = b().transactions.first { it.kind == TransactionKind.TRANSFER }
        assertEquals(id("Cash"), t.transferAccountId)
        val atm = b().moves.single { it.linkedTransactionId == t.id }
        assertEquals(MoveKind.ATM, atm.kind)
        assertEquals(id("Groceries"), atm.fromEnvelopeId)
        // edited: the amount changes, and the ATM move keeps its envelope (the phone's edit used to drop it)
        m.openTransaction(t.id)
        compose.waitForIdle()
        tag("atm.note").assertExists()
        type("f.amount", "25.00")
        click("dialog.save")
        val again = b().moves.single { it.linkedTransactionId == t.id }
        assertEquals(25_00, again.amount)
        assertEquals(id("Groceries"), again.fromEnvelopeId)
    }

    @Test fun bonusIncomeIsAddedEditedAndDeletedWithThePhonesConfirmation() {
        show()
        val rta = b().readyToAssign
        click("bonus")
        type("f.amount", "30.00")
        pick("f.receivedAs", "Gift card")
        click("dialog.save")
        assertEquals(rta + 30_00, b().readyToAssign)
        val t = b().transactions.single { it.kind == TransactionKind.BONUS }
        assertEquals("Gift card", t.payee)
        m.openTransaction(t.id)
        compose.waitForIdle()
        tag("dialog.bonus").assertExists()
        click("dialog.delete")
        tag("dialog.deleteBonus").assertExists()
        click("dialog.confirm")
        assertEquals(rta, b().readyToAssign)
        assertTrue(b().transactions.none { it.kind == TransactionKind.BONUS })
    }

    @Test fun moneyInGoesStraightIntoAnEnvelopeAndPendingIsKept() {
        show()
        val gifts = b().envelopeBalance(id("Gifts"))
        click("top.add")
        pick("f.envelope", "Gifts")
        click("f.moneyIn")
        click("f.pending")
        type("f.payee", "Sample Refund")
        type("f.amount", "15.00")
        click("dialog.save")
        assertNull(m.dialog, m.message?.first)
        val t = tx("Sample Refund")
        assertEquals(15_00, t.amount)
        assertTrue(t.pending)
        val line = b().lines.single { it.transactionId == t.id }
        assertEquals(LineBucket.ENVELOPE, line.bucket)
        assertEquals(15_00, line.amount)
        assertEquals(gifts + 15_00, b().envelopeBalance(id("Gifts")))
    }

    // ---- accounts and envelopes ----

    @Test fun anAccountIsArchivedAndDeletedWithThePhonesConfirmations() {
        show()
        click("nav.ACCOUNTS")
        click("editAccount.Savings")
        click("account.archive")
        tag("dialog.archiveAccount").assertExists()
        click("dialog.confirm")
        assertTrue(b().accounts.first { it.id == id("Savings") }.archived)
        click("editAccount.Cash")
        click("account.delete")
        tag("dialog.deleteAccount").assertExists()
        click("dialog.confirm")
        assertTrue(b().accounts.none { it.id == id("Cash") }, "deleted for good")
        assertTrue(b().transactions.none { it.accountId == id("Cash") }, "with its transactions")
    }

    @Test fun anEnvelopeIsArchivedFromTheDrawerAndBroughtBack() {
        show()
        m.drawerEnvelope = id("Gifts")
        compose.waitForIdle()
        click("drawer.archive")
        assertTrue(b().envelopes.first { it.id == id("Gifts") }.archived)
        tag("archived.Gifts").assertExists()
        m.drawerEnvelope = null
        compose.waitForIdle()
        tag("unarchive.Gifts").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(b().envelopes.first { it.id == id("Gifts") }.archived)
    }

    @Test fun anOverspentEnvelopeIsCoveredFromRollover() {
        show()
        runBlocking {
            val a = m.actions!!
            // Ready to assign empty (else it covers the overspending), and money in Rollover
            val rollover = b().rollover!!.id
            if (b().readyToAssign > 0) a.move(null, rollover, b().readyToAssign, date = SampleBudget.today)
            a.move(id("Emergency fund"), rollover, 100_00, date = SampleBudget.today)
            m.refresh()
            val dining = id("Dining out")
            val over = b().envelopeBalance(dining) + 12_00
            a.saveTransaction(null, BudgetActions.TransactionDraft(id("Checking"), SampleBudget.today, "Sample Diner", "", -over,
                listOf(BudgetActions.LineDraft(dining, -over, categoryId = b().categories.first { it.name == "Eating out" }.id))))
            m.refresh()
        }
        assertEquals(-12_00, b().envelopeBalance(id("Dining out")))
        m.drawerEnvelope = id("Dining out")
        compose.waitForIdle()
        click("drawer.cover")
        assertEquals(0, b().envelopeBalance(id("Dining out")))
    }

    @Test fun aDebtEnvelopeKeepsItsPaymentAndDueDateWhenEditedHere() {
        show()
        val due = SampleBudget.today.plusDays(10)
        val debt = runBlocking {
            m.actions!!.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Card", EnvelopeMode.DEBT, perPayday = 50_00, debtOwed = 900_00,
                requiredPayment = 35_00, dueDate = due), SampleBudget.today).also { m.refresh() }
        }
        m.dialog = Dialog.EnvelopeForm(debt)
        compose.waitForIdle()
        type("f.name", "Sample Card Renamed")
        click("dialog.save")
        val e = b().envelopes.first { it.id == debt }
        assertEquals("Sample Card Renamed", e.name)
        assertEquals(35_00, e.requiredPayment)
        assertEquals(due, e.dueDate)
    }

    // ---- settings and import ----

    @Test fun theBudgetStartDateIsChangedAfterThePhonesWarning() {
        show()
        click("nav.SETTINGS")
        click("budgetStart.change")
        type("f.date", "2030-09-01")
        click("dialog.save")
        tag("dialog.confirmStart").assertExists()
        click("dialog.confirm")
        assertEquals(LocalDate.of(2030, 9, 1), b().budgetStart)
    }

    @Test fun anImportRunsTheAfterImportStepLikeThePhone() {
        show()
        // a bill that the imported pharmacy charge pays: the phone's after-import step matches it
        runBlocking {
            BillActions(store(), m.actions!!, { SampleBudget.today }).saveBill(null, BillActions.BillDraft("Sample Pharmacy", 8_20, LocalDate.of(2030, 10, 28), matchText = "Sample Pharmacy"))
        }
        assertTrue(runBlocking { m.planImport(id("Checking"), "bank.csv", SampleBudget.csv) }, m.message?.first)
        val d = assertNotNull(m.dialog as? Dialog.ImportCsv)
        assertTrue(runBlocking { m.importCsv(d) }, m.message?.first)
        val paid = runBlocking { store().dao.billPaymentsNow() }
        val pharmacy = tx("Sample Pharmacy")
        assertTrue(paid.any { it.transactionId == pharmacy.id }, "the bill payment was matched after the import")
    }

    @Test fun aBudgetMadeHereGetsItsStartDateWithItsFirstAccount() {
        val model = SampleBudget.empty(tmp.newFolder("Empty"))
        try {
            assertNull(model.budget!!.budgetStart, "no start date before there's anything to budget")
            assertTrue(runBlocking { model.saveAccount(null, "Sample Checking", AccountType.BANK, 100_00, model.today(), false, 0, true) })
            assertNotNull(model.budget!!.budgetStart)
        } finally {
            model.engine?.close()
        }
    }
}
