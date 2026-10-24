package net.darkrelay.deaddropfinance.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.Purger
import net.darkrelay.deaddropfinance.domain.BalanceCheck
import net.darkrelay.deaddropfinance.domain.Budget
import net.darkrelay.deaddropfinance.ui.defaultPick
import net.darkrelay.deaddropfinance.ui.toChoices
import net.darkrelay.deaddropfinance.domain.BudgetActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 0.7.2 balance bug, reproduced with synthetic numbers: an account made by hand with today's (overdrawn) balance,
 * then linked to bank sync with history from before that date. The history was counted on top of a starting balance
 * that already held it. Also every way a SimpleFIN balance can be negative, and the balance check after each case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BalanceBugTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x5C).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x5C).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var secrets: SecretStore
    private lateinit var settings: SyncSettings
    private val day = LocalDate.of(2030, 8, 20)
    private val now = day.atTime(15, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        secrets = SecretStore(File(Files.createTempDirectory("ddf-bal").toFile(), SecretStore.FILE_NAME), FakeWrapper())
        settings = SyncSettings(ctx.getSharedPreferences("bal-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { closers.forEach { it.close() }; db.close() }

    private fun service() = SyncService(
        store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
        httpFor = { Http(sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = 3000, baseBackoffMs = 1) }, allowLoopbackHttp = true,
    )

    private fun e(d: LocalDate) = d.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)

    /** Two weeks of invented history ending 12 days before [day], netting +42.50, and an overdrawn balance now. */
    private val history = listOf(
        SimpleFinMock.txn("H1", e(day.minusDays(22)), "3000.00", "SAMPLE BENEFIT"),
        SimpleFinMock.txn("H2", e(day.minusDays(22)), "-1200.00", "SAMPLE TRANSFER"),
        SimpleFinMock.txn("H3", e(day.minusDays(18)), "-95.20", "SAMPLE INSURANCE"),
        SimpleFinMock.txn("H4", e(day.minusDays(15)), "-1640.00", "SAMPLE TRANSFER"),
        SimpleFinMock.txn("H5", e(day.minusDays(12)), "-22.30", "SAMPLE STORE"),
    )

    private fun bridge(balance: String, txns: List<String> = history, balanceDate: Long = e(day)) = SimpleFinMock().also { m ->
        closers += m
        m.accountsAnswer = { q ->
            val body = SimpleFinMock.accountSet(balance, if (q.contains("start-date=")) txns else emptyList())
                .replace("\"balance-date\":0", "\"balance-date\":$balanceDate")
            Answer(200, body)
        }
    }

    @Test fun linkingIntoAnExistingAccountNoLongerCountsHistoryTwice() = runBlocking<Unit> {
        // made by hand today with the bank's real (overdrawn) balance
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, -41_07, day)
        val m = bridge("-41.07")
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        val l = store.sync.linkedOf(id).single()
        assertEquals("the link screen gets the minus sign", -41_07L, l.providerBalance)
        s.link(listOf(SyncService.LinkChoice(l.id, SyncService.Target.Existing(acct), SyncService.History.YEAR)))
        assertTrue(s.syncConnection(id).ok)
        val b = actions.snapshot()
        assertEquals("history before the start date is already in the starting balance", -41_07L, b.accountBalance(acct))
        assertEquals(-41_07L, b.postedBalance(acct))
        assertEquals("balance check: matches the bank", store.sync.linkedOf(id).single().providerBalance, s.postedBalance(acct))
        assertTrue("history isn't put in To sort", b.toSort.isEmpty())
        assertEquals(5, b.transactions.count { b.beforeStart(it) })
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun linkingIntoANewAccountMatchesTheBankWithALongHistory() = runBlocking<Unit> {
        val m = bridge("-41.07")
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        val l = store.sync.linkedOf(id).single()
        s.link(listOf(SyncService.LinkChoice(l.id, SyncService.Target.New("Everyday"), SyncService.History.YEAR)))
        assertTrue(s.syncConnection(id).ok)
        val acct = store.sync.linkedOf(id).single().accountId!!
        val b = actions.snapshot()
        assertEquals(-41_07L, b.accountBalance(acct))
        assertEquals(-41_07L, s.postedBalance(acct))
        assertEquals(5, b.toSort.size)
        assertEquals(day.minusYears(1), store.dao.account(acct)!!.startDate)
        assertEquals(0L, b.sortedCheck)
    }

    private suspend fun linkNew(balance: String, negative: Boolean): Triple<Budget, String, String> {
        val m = bridge(balance, listOf(SimpleFinMock.txn("S1", e(day.minusDays(10)), "-10.00", "SAMPLE STORE")))
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        val l = store.sync.linkedOf(id).single()
        s.link(listOf(SyncService.LinkChoice(l.id, SyncService.Target.New("Everyday", actuallyNegative = negative), SyncService.History.DAYS_90)))
        assertTrue(s.syncConnection(id).ok)
        val acct = store.sync.linkedOf(id).single().accountId!!
        return Triple(actions.snapshot(), acct, l.id)
    }

    @Test fun theActuallyNegativeSwitchOffGivesTodaysResult() = runBlocking<Unit> {
        val (b, acct, _) = linkNew("10.00", negative = false)
        assertEquals(10_00L, b.accountBalance(acct))
        assertEquals("the history in To sort is taken out of the bank's number", 20_00L, b.readyToAssign)
        assertEquals(0L, b.sortedCheck)
    }

    @Test fun theActuallyNegativeSwitchOnCountsABankPlusTenAsMinusTen() = runBlocking<Unit> {
        val (b, acct, linkedId) = linkNew("10.00", negative = true)
        assertEquals(-10_00L, b.accountBalance(acct))
        assertEquals(0L, b.readyToAssign)
        assertEquals(-10_00L, b.toSortTotal)
        assertEquals(0L, b.sortedCheck)
        assertTrue("the choice stays with the bank's account", store.sync.linkedNow().single { it.id == linkedId }.balanceSignFlipped)
    }

    @Test fun newerTransactionsAfterTheStartDateStillCount() = runBlocking<Unit> {
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, -41_07, day.minusDays(5))
        val later = SimpleFinMock.txn("N1", e(day.minusDays(1)), "-10.00", "SAMPLE CAFE")
        val m = bridge("-51.07", history + later)
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(id).single().id, SyncService.Target.Existing(acct), SyncService.History.YEAR)))
        s.syncConnection(id)
        val b = actions.snapshot()
        assertEquals(-51_07L, b.accountBalance(acct))
        assertEquals(1, b.toSort.size)
        assertEquals(s.postedBalance(acct), store.sync.linkedOf(id).single().providerBalance)
    }

    @Test fun everyNegativeBalanceFormatKeepsItsSign() {
        for (raw in listOf("\"-41.07\"", "\"-41.0700\"", "\"-41.1\"", "\"-41\"", "-41.07", "\"-0041.07\"")) {
            val body = """{"errlist":[],"connections":[],"accounts":[{"id":"A","name":"X","conn_id":"C","currency":"USD","balance":$raw,"balance-date":1,"transactions":[]}]}"""
            val (rows, _) = SimpleFinSource.parseAccountSet(body, ZoneOffset.UTC)
            val expected = if (raw.contains("41.1\"")) -41_10L else if (raw == "\"-41\"") -41_00L else -41_07L
            assertEquals(raw, expected, rows.single().account.balance)
        }
        assertEquals(-50L, Cents.parse("-.5"))
        assertEquals(-50L, Cents.parse("-0.50"))
        assertEquals(0L, Cents.parse("-0.00"))
    }

    @Test fun theBanksAsOfDateIsKept() = runBlocking<Unit> {
        val m = bridge("-41.07", balanceDate = e(day.minusDays(12)))
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        assertEquals(e(day.minusDays(12)), store.sync.linkedOf(id).single().providerBalanceDate)
    }

    @Test fun aMissingBalanceDateIsNull() {
        val body = """{"errlist":[],"connections":[],"accounts":[{"id":"A","name":"X","conn_id":"C","currency":"USD","balance":"1.00","transactions":[]}]}"""
        assertNull(SimpleFinSource.parseAccountSet(body, ZoneOffset.UTC).first.single().account.balanceDate)
    }

    // ---- 1.2.1: the bank balance sign choice sticks with the bank's account ------------------------------------------

    private val tenDown get() = listOf(SimpleFinMock.txn("S1", e(day.minusDays(10)), "-10.00", "SAMPLE STORE"))

    private suspend fun linkedAccount(id: String) = store.sync.linkedOf(id).single()

    private suspend fun assertFlippedRight(id: String) {
        val l = linkedAccount(id)
        val acct = l.accountId!!
        val b = actions.snapshot()
        assertEquals("the bank's +10.00, read as -10.00", -10_00L, l.bankBalance)
        assertEquals(-10_00L, b.accountBalance(acct))
        assertTrue("matches the bank", BalanceCheck.check(b, acct, l.bankBalance!!, null) is BalanceCheck.Result.Match)
    }

    @Test fun theSignChoiceSurvivesResyncPurgeAndARelink() = runBlocking<Unit> {
        val m = bridge("10.00", tenDown)
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(linkedAccount(id).id, SyncService.Target.New("Everyday"), SyncService.History.DAYS_90, signFlipped = true)))
        assertTrue(s.syncConnection(id).ok)
        assertFlippedRight(id)
        // a later sync reads the bank's figure again and keeps the choice
        assertTrue(s.syncConnection(id).ok)
        assertTrue(linkedAccount(id).balanceSignFlipped)
        assertFlippedRight(id)
        // Purge data keeps the connection and its bank accounts, and the choice with them
        Purger(db, secrets, listOf(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("bal-test", Context.MODE_PRIVATE)), emptyList()).purge()
        store.ensureSystemEnvelopes()
        val kept = linkedAccount(id)
        assertNull(kept.accountId)
        assertTrue("the choice survives Purge", kept.balanceSignFlipped)
        // the link screen starts with the switch on, and linking again as it shows uses it
        assertTrue(defaultPick(kept, emptyList()).actuallyNegative)
        s.link(toChoices(listOf(kept), emptyList(), emptyMap()))
        assertTrue(s.syncConnection(id).ok)
        assertFlippedRight(id)
    }

    @Test fun noChoiceBehavesAsBefore() = runBlocking<Unit> {
        val m = bridge("10.00", tenDown)
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(linkedAccount(id).id, SyncService.Target.New("Everyday"), SyncService.History.DAYS_90)))
        assertTrue(s.syncConnection(id).ok)
        val l = linkedAccount(id)
        assertEquals(false, l.balanceSignFlipped)
        assertEquals(10_00L, l.bankBalance)
        val b = actions.snapshot()
        assertEquals(10_00L, b.accountBalance(l.accountId!!))
        assertTrue(BalanceCheck.check(b, l.accountId!!, l.bankBalance!!, null) is BalanceCheck.Result.Match)
    }

    @Test fun linkingIntoAnExistingAccountAndTheAccountScreenSwitchSetTheChoice() = runBlocking<Unit> {
        // made by hand with the real (overdrawn) balance, then linked: the bank's +41.07 is read as -41.07
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, -41_07, day)
        val m = bridge("41.07")
        val s = service()
        val id = s.connectSimpleFin(m.setupToken())
        s.link(listOf(SyncService.LinkChoice(linkedAccount(id).id, SyncService.Target.Existing(acct), SyncService.History.YEAR, signFlipped = true)))
        assertTrue(s.syncConnection(id).ok)
        val l = linkedAccount(id)
        assertEquals(-41_07L, l.bankBalance)
        assertEquals("an existing account's starting balance is never touched", -41_07L, store.dao.account(acct)!!.startingBalance)
        assertTrue(BalanceCheck.check(actions.snapshot(), acct, l.bankBalance!!, null) is BalanceCheck.Result.Match)
        // the account's screen turns it off and on again; nothing else changes
        s.setSignFlipped(l.id, false)
        assertEquals(41_07L, linkedAccount(id).bankBalance)
        s.setSignFlipped(l.id, true)
        assertEquals(-41_07L, linkedAccount(id).bankBalance)
        assertEquals(-41_07L, actions.snapshot().accountBalance(acct))
    }
}
