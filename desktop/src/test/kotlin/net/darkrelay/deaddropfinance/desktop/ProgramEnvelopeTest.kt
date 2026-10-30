package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Test
import kotlin.test.assertEquals

/**
 * 0.13.0: an envelope's own screen in the program (the phone's, from its drawer's Details): the suggested target,
 * the editor's Goal and Debt fields and the categories it claims; and the phone's envelope list, where dragging the
 * handle with the mouse sets the fill order.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramEnvelopeTest : ProgramScreensBase() {

    private fun last(text: String) {
        val all = compose.onAllNodes(hasText(text), useUnmergedTree = true)
        all[all.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    private fun pick(label: String, current: String, option: String) {
        val top = node(label, exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val all = compose.onAllNodes(hasText(current), useUnmergedTree = true)
        val ns = all.fetchSemanticsNodes()
        all[ns.indices.filter { ns[it].boundsInRoot.center.y > top }.minBy { ns[it].boundsInRoot.center.y }].performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(option), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        last(option)
    }

    private fun env(name: String) = b().envelopes.first { it.name == name }

    private fun openEnvelope(name: String) {
        model.drawerEnvelope = id(name)
        compose.waitForIdle()
        click("drawer.details")
        // the phone's screen (the program's top bar has a Move money too)
        until("Back")
        until("Add activity")
    }

    @Test fun twoClosedMonthsOfSpendingSuggestATargetThatIsUsedWithOneClick() {
        show {
            // older history: an account from 100 days ago, paydays 70 and 40 days ago, spending in each month between
            store().setBudgetStart(today.minusDays(90))
            val old = saveAccount(null, "Sample Old Checking", AccountType.BANK, 900_00, today.minusDays(100))
            ids["Coffee"] = saveEnvelope(null, EnvelopeDraft("Coffee", EnvelopeMode.FILL_TO_TARGET), today.minusDays(100))
            for (d in listOf(70L, 40L)) {
                val p = saveTransaction(null, TransactionDraft(old, today.minusDays(d), "Sample Payroll Co", "", 500_00, listOf(LineDraft(null, 500_00, toReadyToAssign = true))))
                markPayday(p, today.minusDays(d), fill = false)
            }
            move(null, ids.getValue("Coffee"), 200_00, date = today.minusDays(69))
            for ((d, c) in listOf(60L to 30_00L, 30L to 50_00L)) saveTransaction(null, TransactionDraft(old, today.minusDays(d), "Sample Cafe", "", -c,
                listOf(LineDraft(ids.getValue("Coffee"), -c, categoryId = category("Eating out")))))
        }
        openEnvelope("Coffee")
        until("You spend about $40.00 here each budget month.")
        clickText("Use it as the target")
        compose.waitUntil(5_000) { refresh(); env("Coffee").target == 40_00L }
    }

    @Test fun theEditorSetsADebtsFieldsAGoalsAndTheCategoriesItClaims() {
        show()
        openEnvelope("Gifts")
        clickLowest("Edit")
        until("How it fills on payday")
        pick("How it fills on payday", "Add every payday", "Debt")
        until("Required payment (optional)")
        typeInto("Balance owed (optional until known)", "1200.00")
        typeInto("Required payment (optional)", "60.00")
        typeInto("Due date (optional) (yyyy-mm-dd)", today.plusDays(9).toString())
        // claims Snacks: a purchase in Snacks goes here
        until("Categories this envelope claims")
        toggleFor("Snacks")
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { refresh(); env("Gifts").mode == EnvelopeMode.DEBT }
        val e = env("Gifts")
        assertEquals(1200_00L, e.debtOwed)
        assertEquals(60_00L, e.requiredPayment)
        assertEquals(today.plusDays(9), e.dueDate)
        assertEquals(e.id, runBlocking { store().dao.categoriesNow() }.first { it.name == "Snacks" }.envelopeId)
        // and a goal: an amount and a date to reach it by
        until("Add activity")
        clickLowest("Edit")
        pick("How it fills on payday", "Debt", "Goal")
        typeInto("Goal amount", "500.00")
        typeInto("Reach it by (yyyy-mm-dd)", today.plusDays(120).toString())
        clickText("Save", exact = true)
        compose.waitUntil(5_000) { refresh(); env("Gifts").mode == EnvelopeMode.GOAL }
        assertEquals(500_00L, env("Gifts").goalAmount)
        assertEquals(today.plusDays(120), env("Gifts").goalDate)
    }

    @Test fun draggingAnEnvelopesHandleWithTheMouseSetsTheFillOrder() {
        show()
        click("nav.BUDGET")
        click("fillOrder")
        until("drag the handle")
        val before = b().fillOrder.map { it.name }
        assertEquals(listOf("Groceries", "Rent", "Gifts"), before.filter { it in setOf("Groceries", "Rent", "Gifts") })
        // Gifts' handle, dragged up past Rent and Groceries
        val row = node("Gifts", exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val handles = compose.onAllNodes(hasText("≡"), useUnmergedTree = true)
        val hs = handles.fetchSemanticsNodes()
        val i = hs.indices.minBy { kotlin.math.abs(hs[it].boundsInRoot.center.y - row) }
        val rowHeight = (hs[1].boundsInRoot.center.y - hs[0].boundsInRoot.center.y)
        handles[i].performMouseInput {
            moveTo(center)
            press()
            repeat(40) { moveBy(Offset(0f, -rowHeight * 3.5f / 40), 20) }
            release()
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            refresh()
            b().fillOrder.map { it.name }.filter { it in setOf("Groceries", "Rent", "Gifts") } == listOf("Gifts", "Groceries", "Rent")
        }
    }
}
