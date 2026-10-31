package net.darkrelay.deaddropfinance.serversync

import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.Reports
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 1.9.0 / 0.15.0, end to end against a real, throwaway Dead Drop server (never the live one): a Debt envelope's
 * interest rate goes from one device to the other and back, and is cleared on both, and both work out the same payoff.
 * (A change from a 1.8.x device, which doesn't carry it, is DebtRateSyncTest's.) Skipped unless DDF_E2E_URL and DDF_E2E_CODE are set; the server must be new.
 */
class DebtRateE2ETest {

    @get:Rule val tmp = TemporaryFolder()

    private val url = System.getenv("DDF_E2E_URL")
    private val code = System.getenv("DDF_E2E_CODE")
    private val day = LocalDate.of(2030, 5, 1)
    private val pw = "e2e-test-pass-" + java.util.UUID.randomUUID().toString().take(8)

    @Test fun aDebtEnvelopesRateSyncsBothWays() = runBlocking<Unit> {
        assumeTrue("set DDF_E2E_URL and DDF_E2E_CODE to run against a test server", !url.isNullOrBlank() && !code.isNullOrBlank())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = ServerE2ETest().also { it.tmp.create() }
        val opened = mutableListOf<ServerE2ETest.Device>()
        try {
            val a = world.Device("A", scope).also { opened += it }
            a.actions.saveAccount(null, "E2E Checking", AccountType.BANK, 900_00, day)
            val loan = a.actions.saveEnvelope(null, EnvelopeDraft("E2E Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00, interestRate = 2499), day)
            a.sync.contact(url)
            a.sync.createAccount(url, code, "e2e", pw, "E2E phone A")
            assertEquals(SyncStatus.State.SYNCED, a.sync.status.value.state, a.sync.status.value.message)

            // B takes the server's budget: the rate came with it
            val b = world.Device("B", scope).also { opened += it }
            b.sync.login(url, "e2e", pw, "E2E desktop B") { "" }
            assertEquals(2499L, b.store.dao.envelope(loan)!!.interestRate)
            assertEquals(Reports.debtPlan(a.actions.snapshot(), a.store.dao.envelope(loan)!!, day).payoff, Reports.debtPlan(b.actions.snapshot(), b.store.dao.envelope(loan)!!, day).payoff)

            // B changes it; A gets the change
            a.sync.background()
            b.sync.foreground()
            b.actions.saveEnvelope(loan, EnvelopeDraft("E2E Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00, interestRate = 1999), day)
            b.sync.syncNow()
            a.sync.syncNow()
            assertEquals(1999L, a.store.dao.envelope(loan)!!.interestRate)

            // clearing it clears it everywhere
            b.sync.background()
            a.sync.foreground()
            a.actions.saveEnvelope(loan, EnvelopeDraft("E2E Loan", EnvelopeMode.DEBT, perPayday = 100_00, debtOwed = 1200_00), day)
            a.sync.syncNow()
            b.sync.syncNow()
            assertNull(b.store.dao.envelope(loan)!!.interestRate)
        } finally {
            scope.cancel()
            opened.forEach { runCatching { it.store.db.close() } }
            world.tmp.delete()
        }
    }
}
