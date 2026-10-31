package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.MoveKind
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edit a move on the program (0.14.0): a move row in Activity and in the envelope's drawer opens Edit move (date, from,
 * to, amount); saving moves the money and both balances follow; Delete asks first, as a transaction's does; the
 * payday's auto-fill row doesn't open. The phone's envelope screen in the program opens the phone's Edit move.
 */
class ProgramEditMoveTest : ProgramScreensBase() {

    private fun showWithMove() = show { move(ids.getValue("Groceries"), ids.getValue("Gifts"), 25_00, MoveKind.MOVE, today) }
    private fun theMove() = b().moves.single { it.kind == MoveKind.MOVE }

    private fun typeTag(t: String, text: String) { tag(t).performTextClearance(); tag(t).performTextInput(text); compose.waitForIdle() }

    @Test fun aMoveRowInActivityOpensEditMoveAndSavingMovesTheMoney() {
        showWithMove()
        val groceries = b().envelopeBalance(id("Groceries")); val gifts = b().envelopeBalance(id("Gifts")); val rent = b().envelopeBalance(id("Rent"))
        click("nav.ACTIVITY")
        click("tab.All")
        click("act.Moved from Groceries")
        until("Edit move")
        typeTag("f.amount", "40.00")
        typeTag("f.date", today.minusDays(1).toString())
        click("dialog.save")
        compose.waitUntil(5_000) { theMove().amount == 40_00L }
        assertEquals(today.minusDays(1), theMove().date)
        assertEquals(groceries - 15_00, b().envelopeBalance(id("Groceries")))
        assertEquals(gifts + 15_00, b().envelopeBalance(id("Gifts")))
        assertEquals(rent, b().envelopeBalance(id("Rent")))
        // the payday's auto-fill row doesn't open
        click("act.Payday auto-fill")
        assertFalse(shows("Edit move"))
    }

    @Test fun anEditBeyondWhatTheSourceHoldsShowsItBelowZeroAsMoveMoneyDoes() {
        showWithMove()
        click("nav.ACTIVITY")
        click("act.Moved from Groceries")
        typeTag("f.amount", "9000.00")
        click("dialog.save")
        compose.waitUntil(5_000) { theMove().amount == 9000_00L }
        assertTrue(b().envelopeBalance(id("Groceries")) < 0)
    }

    @Test fun theEnvelopeDrawersMoveRowOpensItAndDeleteAsksFirst() {
        showWithMove()
        val groceries = b().envelopeBalance(id("Groceries"))
        val moveId = theMove().id
        click("env.Groceries")
        click("act.Moved out to Gifts")
        until("Edit move")
        click("dialog.delete")
        until("Delete this move?")
        click("delete.keep")
        assertTrue(b().moves.any { it.kind == MoveKind.MOVE })
        click("dialog.delete")
        click("delete.confirm")
        compose.waitUntil(5_000) { b().moves.none { it.kind == MoveKind.MOVE } }
        assertEquals(groceries + 25_00, b().envelopeBalance(id("Groceries")))
        assertTrue(runBlocking { store().dao.move(moveId) }!!.deleted, "a tombstone, synced like any delete")
    }

    @Test fun thePhonesEnvelopeScreenInTheProgramOpensThePhonesEditMove() {
        showWithMove()
        click("env.Groceries")
        click("drawer.details")
        until("Moved out to Gifts")
        clickText("Moved out to Gifts", exact = true)
        until("Edit move")
        typeInto("Amount", "30.00")
        clickLowest("Save")
        compose.waitUntil(5_000) { theMove().amount == 30_00L }
    }
}
