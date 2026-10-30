package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.13.0, the program's Home (the phone's): the Safe to Spend chooser, the over-assigned fix and Pay these now, with
 * the phone's HomeTest cases where they apply.
 */
class ProgramHomeTest : ProgramScreensBase() {

    @Test fun safeToSpendCountsTheEverydayEnvelopesAndTheChooserChangesThem() {
        show()
        click("nav.HOME")
        until("Safe to spend")
        // Groceries is everyday spending: Safe to Spend counts it (and never Rollover)
        assertEquals(b().envelopeBalance(id("Groceries")), b().safeToSpend)
        clickText("Safe to spend")
        until("Counts what's left in the envelopes switched on here")
        assertFalse(shows("Rollover"), "Rollover never counts, so it isn't listed")
        toggleFor("Gifts")
        compose.waitUntil(5_000) { refresh(); b().envelopes.first { it.id == id("Gifts") }.everyday }
        assertEquals(b().envelopeBalance(id("Groceries")) + b().envelopeBalance(id("Gifts")), b().safeToSpend)
        toggleFor("Groceries")
        compose.waitUntil(5_000) { refresh(); !b().envelopes.first { it.id == id("Groceries") }.everyday }
    }

    @Test fun aNegativeReadyToAssignShowsTheOverAssignedRowWhichMovesItBack() {
        show { move(null, ids.getValue("Rent"), readyToAssignNow() + 50_00) }
        assertEquals(-50_00, b().readyToAssign)
        click("nav.HOME")
        until("Over-assigned by")
        assertTrue(shows("Click to move some back"), "the program says Click where the phone says Tap")
        clickText("Over-assigned by")
        until("more is in envelopes than you have")
        clickText("Move", exact = true)
        compose.waitUntil(5_000) { refresh(); b().readyToAssign == 0L }
    }

    @Test fun payTheseNowListsThePaydayBillsAndTickingOneRecordsItsPayment() {
        show {
            BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Power", 60_00, today.plusDays(2), envelopeId = ids.getValue("Rent")))
        }
        click("nav.HOME")
        until("Pay these now")
        assertTrue(shows("0 of 1 paid"))
        toggleFor("Sample Power")
        // all paid: the card folds to one line, as on the phone
        until("All 1 payday bills paid")
        assertEquals(1, runBlocking { store().dao.billPaymentsNow() }.count { !it.deleted })
    }

    private suspend fun net.darkrelay.deaddropfinance.domain.BudgetActions.readyToAssignNow(): Long = snapshot().readyToAssign
}
