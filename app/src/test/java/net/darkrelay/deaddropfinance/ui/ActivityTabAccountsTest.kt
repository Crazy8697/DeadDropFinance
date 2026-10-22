package net.darkrelay.deaddropfinance.ui

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Which accounts get an Activity tab (1.2.0), and that unticking one changes nothing else. Invented data. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActivityTabAccountsTest {

    private val day = LocalDate.of(2030, 8, 20)

    private fun acct(id: String, name: String, type: AccountType, start: Long, order: Int, show: Boolean = true) =
        AccountEntity(id = id, name = name, type = type, startingBalance = start, startDate = day.minusDays(30), sortOrder = order, showInActivity = show, createdAt = 1, updatedAt = 1)

    private val budget = Budget(
        listOf(acct("chk", "Sample Checking", AccountType.BANK, 500_00, 0), acct("wal", "Sample Wallet", AccountType.PAYMENT_APP, 25_00, 1), acct("hid", "Sample Hidden", AccountType.BANK, 10_00, 2, show = false)),
        emptyList(), emptyList(), emptyList(), emptyList(),
    )

    @Test fun tabsFollowTheTickedAccountsInAccountOrder() {
        assertEquals(listOf("chk", "wal"), activityTabAccounts(budget).map { it.id })
    }

    @Test fun untickingRemovesOnlyTheTab() = runBlocking<Unit> {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val store = BudgetStore(db)
            val a = BudgetActions(store)
            store.ensureSystemEnvelopes()
            store.ensureCategories()
            val id = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day.minusDays(30))
            store.write {
                val t = put(null, TransactionEntity(accountId = id, date = day, payee = "SAMPLE SHOP", amount = -20_00, createdAt = now, updatedAt = now))
                put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -20_00, createdAt = now, updatedAt = now))
            }
            val before = a.snapshot()
            assertEquals(listOf(id), activityTabAccounts(before).map { it.id })
            a.saveAccount(id, "Sample Checking", AccountType.BANK, 500_00, day.minusDays(30), showInActivity = false)
            val after = a.snapshot()
            assertEquals("no tab", emptyList<String>(), activityTabAccounts(after).map { it.id })
            assertEquals(before.accountBalance(id), after.accountBalance(id))
            assertEquals(before.readyToAssign, after.readyToAssign)
            assertEquals(before.toSort.map { it.id }, after.toSort.map { it.id })
            assertEquals(before.transactions, after.transactions)
            assertEquals(before.lines, after.lines)
            val m = before.months(day).first()
            assertEquals(before.categoryTotals(m), after.categoryTotals(m))
        } finally {
            db.close()
        }
    }

}
