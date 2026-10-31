package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.14.0: the program has no Home; what the phone's Home showed that nothing else did is on Budget, in the program's
 * style: the Safe to Spend chooser, the over-assigned fix, Pay these now (with the phone's HomeTest cases) and the
 * balance forecast (ProgramBillsTest).
 */
class ProgramHomeTest : ProgramScreensBase() {

    @Test fun safeToSpendCountsTheEverydayEnvelopesAndTheChooserChangesThem() {
        show()
        click("nav.BUDGET")
        // Groceries is everyday spending: Safe to Spend counts it (and never Rollover)
        assertEquals(b().envelopeBalance(id("Groceries")), b().safeToSpend)
        click("safeToSpend")
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
        click("nav.BUDGET")
        until("Over-assigned by")
        click("overAssigned.fix")
        until("more is in envelopes than you have")
        clickText("Move", exact = true)
        compose.waitUntil(5_000) { refresh(); b().readyToAssign == 0L }
        gone("Over-assigned by")
    }

    @Test fun payTheseNowListsThePaydayBillsAndTickingOneRecordsItsPayment() {
        show {
            BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Power", 60_00, today.plusDays(2), envelopeId = ids.getValue("Rent")))
        }
        click("nav.BUDGET")
        until("Pay these now")
        assertTrue(shows("0 of 1 paid"))
        click("payNow.Sample Power")
        // all paid: the card folds to one line, as on the phone
        until("All 1 payday bills paid")
        assertEquals(1, runBlocking { store().dao.billPaymentsNow() }.count { !it.deleted })
    }

    private suspend fun net.darkrelay.deaddropfinance.domain.BudgetActions.readyToAssignNow(): Long = snapshot().readyToAssign
}
