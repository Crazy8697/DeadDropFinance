package net.darkrelay.deaddropfinance.ui

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetRuleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Stage 11's Home: Safe to Spend counts only the everyday envelopes (never Rollover) and follows the flag at once,
 * Home's parts come in the prototype's order, and the old "It's payday" card is gone. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HomeTest {

    private val day = LocalDate.of(2030, 6, 3)

    private fun world(block: suspend (BudgetStore, BudgetActions) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val store = BudgetStore(db)
            runBlocking { store.ensureSystemEnvelopes(); block(store, BudgetActions(store)) }
        } finally {
            db.close()
        }
    }

    @Test fun safeToSpendCountsOnlyEverydayEnvelopesAndNeverRollover() = world { store, a ->
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 1000_00, day.minusDays(5))
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 400_00, everyday = true))
        val gas = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Gas", EnvelopeMode.FILL_TO_TARGET, target = 100_00, everyday = true))
        val home = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Household", EnvelopeMode.FILL_TO_TARGET, target = 150_00))
        val roll = a.snapshot().rollover!!.id
        a.move(null, food, 312_00); a.move(null, gas, 83_00); a.move(null, home, 150_00); a.move(null, roll, 140_00)
        assertEquals(395_00L, a.snapshot().safeToSpend)
        assertEquals(listOf("Groceries", "Gas"), a.snapshot().everydayEnvelopes.map { it.name })
        // Rollover never counts, even if its row says everyday, and it can't be switched on
        store.write { val r = store.dao.envelope(roll)!!; put(r, r.copy(everyday = true, updatedAt = now)) }
        assertEquals(395_00L, a.snapshot().safeToSpend)
        try { a.setEveryday(roll, true); fail() } catch (e: BudgetRuleException) { /* expected */ }
        // changing which envelopes count changes it right away
        a.setEveryday(home, true)
        assertEquals(545_00L, a.snapshot().safeToSpend)
        a.setEveryday(gas, false)
        assertEquals(462_00L, a.snapshot().safeToSpend)
        a.setEveryday(food, false); a.setEveryday(home, false)
        assertNull("nothing counted: the card asks the user to pick", a.snapshot().safeToSpend)
        assertEquals("Groceries, Gas and Household", namesText(listOf("Groceries", "Gas", "Household")))
        assertEquals("Groceries and Gas", namesText(listOf("Groceries", "Gas")))
    }

    @Test fun homeIsInThePrototypesOrder() = world { store, a ->
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day.minusDays(5))
        store.write {
            val t = put(null, TransactionEntity(accountId = acct, date = day, payee = "SAMPLE SHOP", amount = -12_00, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -12_00, createdAt = now, updatedAt = now))
        }
        val all = homeSections(a.snapshot(), hasForecast = true, hasChecklist = true)
        assertEquals(
            listOf(
                HomeSection.SAFE_TO_SPEND, HomeSection.FORECAST, HomeSection.READY_TO_ASSIGN, HomeSection.BONUS, HomeSection.PAY_THESE_NOW,
                HomeSection.TO_SORT, HomeSection.PARKED_BUYS, HomeSection.ENVELOPES,
            ),
            all,
        )
        // Ready to Assign shows only while money is waiting; the rest keep their order
        val env = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, everyday = true))
        a.move(null, env, a.snapshot().readyToAssign)
        assertEquals(0L, a.snapshot().readyToAssign)
        assertEquals(
            listOf(HomeSection.SAFE_TO_SPEND, HomeSection.BONUS, HomeSection.PAY_THESE_NOW, HomeSection.TO_SORT, HomeSection.PARKED_BUYS, HomeSection.ENVELOPES),
            homeSections(a.snapshot(), hasForecast = false, hasChecklist = true),
        )
        assertEquals(HomeSection.SAFE_TO_SPEND, homeSections(a.snapshot(), false, false).first())
        // Rollover isn't among Home's envelopes, even with money in it
        val b = a.snapshot()
        assertFalse(b.everydayEnvelopes.any { it.mode == EnvelopeMode.ROLLOVER })
    }

    @Test fun homeListsADebtEnvelopeDueBeforePaydayAndHidesOneDueAfter() = world { _, a ->
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 900_00, day.minusDays(5))
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, everyday = true))
        val soon = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Loan", EnvelopeMode.DEBT, requiredPayment = 75_00, dueDate = day.plusDays(4)))
        val later = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Card", EnvelopeMode.DEBT, requiredPayment = 40_00, dueDate = day.plusDays(20)))
        a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Fund", EnvelopeMode.FILL_TO_TARGET))
        val payday = day.plusDays(12)
        val b = a.snapshot()
        assertEquals(listOf(food, soon), homeEnvelopes(b, day, payday).map { it.id })
        assertFalse(homeEnvelopes(b, day, payday).any { it.id == later })
        // on the payday itself isn't before it; with no next payday known, only the everyday ones
        assertEquals(listOf(food), homeEnvelopes(b, day, day.plusDays(4)).map { it.id })
        assertEquals(listOf(food), homeEnvelopes(b, day, null).map { it.id })
        assertFalse(homeEnvelopes(b, day, payday).any { it.mode == EnvelopeMode.ROLLOVER })
    }

    @Test fun aNegativeReadyToAssignShowsTheOverAssignedRow() = world { _, a ->
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 100_00, day.minusDays(5))
        val food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, everyday = true))
        a.move(null, food, 100_00)
        assertFalse(HomeSection.OVER_ASSIGNED in homeSections(a.snapshot(), false, false))
        // the account is changed to hold less than the envelopes do
        a.saveAccount(a.snapshot().accounts.single().id, "Sample Checking", AccountType.BANK, 60_00, day.minusDays(5))
        val b = a.snapshot()
        assertEquals(-40_00L, b.readyToAssign)
        val s = homeSections(b, hasForecast = true, hasChecklist = false)
        assertEquals(listOf(HomeSection.SAFE_TO_SPEND, HomeSection.FORECAST, HomeSection.OVER_ASSIGNED, HomeSection.BONUS, HomeSection.PARKED_BUYS, HomeSection.ENVELOPES), s)
        assertFalse(HomeSection.READY_TO_ASSIGN in s)
    }

    @Test fun thereIsNoItsPaydayCardAnywhere() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val shared = listOf(File("../shared/src"), File("shared/src")).first { it.isDirectory }
        val hits = (main.walk() + shared.walk()).filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.filter { f ->
            val s = f.readText()
            "It's payday" in s || "PaydayLandedCard" in s || "notPayday" in s || "Looks like payday landed" in s
        }.map { it.name }.toList()
        assertEquals(emptyList<String>(), hits)
        assertTrue(HomeSection.entries.none { it.name.contains("LANDED") })
    }
}
