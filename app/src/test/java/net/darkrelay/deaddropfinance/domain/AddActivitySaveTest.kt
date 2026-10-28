package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Saving from an envelope's Add activity (1.3.0) is the same save as Add in Activity: one real transaction, in the
 * main Activity list and the envelope's own, with the envelope's and the account's balances moved by its amount.
 * Invented numbers, a real (in-memory) database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AddActivitySaveTest {

    @Test fun oneTransactionInBothListsAndBothBalancesMove() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            runBlocking {
                var tick = 1_000L
                val store = BudgetStore(db) { ++tick }
                val a = BudgetActions(store)
                store.ensureSystemEnvelopes()
                val today = LocalDate.now()
                val cash = a.saveAccount(null, "Sample Cash", AccountType.CASH, 100_00, today.minusDays(10))
                a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, today.minusDays(10))
                val gas = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Gas", EnvelopeMode.FILL_TO_TARGET, target = 60_00))
                a.assign(gas, 50_00)
                val before = a.snapshot()
                val txBefore = before.transactions.size

                // what the Add transaction screen sends when opened from Gas's Add activity, Sample Cash picked
                val id = a.saveTransaction(null, BudgetActions.TransactionDraft(cash, today, "SAMPLE GAS STATION", "", -20_00, listOf(BudgetActions.LineDraft(gas, -20_00)), false))

                val after = a.snapshot()
                assertEquals(txBefore + 1, after.transactions.size)
                assertEquals("in the main Activity list", id, after.transactions.single { it.payee == "SAMPLE GAS STATION" }.id)
                assertEquals("in the envelope's Activity list", listOf(id), after.envelopeActivity(gas).mapNotNull { it.transactionId })
                assertEquals(before.envelopeBalance(gas) - 20_00, after.envelopeBalance(gas))
                assertEquals(before.accountBalance(cash) - 20_00, after.accountBalance(cash))
            }
        } finally {
            db.close()
        }
    }
}
