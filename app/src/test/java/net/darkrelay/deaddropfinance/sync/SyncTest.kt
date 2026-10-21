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
import net.darkrelay.deaddropfinance.data.ConnectionStatus
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.KeyWrapper
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bank sync end to end: the real SyncService and engine on an in-memory Room database, against local mock servers
 * for SimpleFIN (HTTP on 127.0.0.1) and Teller (mutual TLS with certificates made at test time). Synthetic data only;
 * nothing reaches the internet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SyncTest {

    private class FakeWrapper : KeyWrapper {
        override fun wrap(plain: ByteArray) = byteArrayOf(1) + plain.map { (it.toInt() xor 0x33).toByte() }
        override fun unwrap(blob: ByteArray) = blob.drop(1).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
    }

    private lateinit var db: AppDatabase
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var secrets: SecretStore
    private lateinit var secretsFile: File
    private lateinit var settings: SyncSettings
    private val day = LocalDate.of(2030, 3, 15)
    private var now = day.atTime(12, 0).toEpochSecond(ZoneOffset.UTC) * 1000
    private fun epoch(d: LocalDate) = d.atTime(9, 0).toEpochSecond(ZoneOffset.UTC)
    private val closers = mutableListOf<AutoCloseable>()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        store = BudgetStore(db) { now }
        actions = BudgetActions(store)
        secretsFile = File(Files.createTempDirectory("ddf-secrets").toFile(), SecretStore.FILE_NAME)
        secrets = SecretStore(secretsFile, FakeWrapper())
        settings = SyncSettings(ctx.getSharedPreferences("sync-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        runBlocking { store.ensureSystemEnvelopes() }
    }

    @After fun tearDown() { closers.forEach { it.close() }; db.close() }

    private fun service(teller: TellerMock? = null, limiterMax: Int = RequestLimiter.DEFAULT_MAX, readTimeoutMs: Int = 1500) = SyncService(
        store, secrets, settings, clock = { now }, zone = ZoneOffset.UTC,
        httpFor = { Http(it, sleep = {}, connectTimeoutMs = 2000, readTimeoutMs = readTimeoutMs, baseBackoffMs = 1) },
        tellerBaseUrl = teller?.baseUrl ?: "https://127.0.0.1:1", tellerTrust = TestCerts.get().clientTrust(),
        allowLoopbackHttp = true, limiterMax = limiterMax,
    )

    private fun simplefin() = SimpleFinMock().also { closers += it }

    /** Connects SimpleFIN, links its one account into a new app account, and returns the connection id. */
    private suspend fun connectAndLink(s: SyncService, m: SimpleFinMock, target: SyncService.Target = SyncService.Target.New("Everyday")): String {
        val id = s.connectSimpleFin(m.setupToken())
        val l = store.sync.linkedOf(id).single()
        s.link(listOf(SyncService.LinkChoice(l.id, target, SyncService.History.DAYS_30)))
        return id
    }

    private suspend fun synced() = store.dao.transactionsNow().filter { it.source == TransactionSource.SYNC }

    // ---- SimpleFIN ----------------------------------------------------------------------------------------------

    @Test fun firstSyncLandsInToSortAndARepeatAddsNothing() = runBlocking<Unit> {
        val m = simplefin()
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet("487.66", listOf(
                SimpleFinMock.txn("T1", epoch(day.minusDays(2)), "-12.34", "CORNER GROCER 12"),
                SimpleFinMock.txn("T2", epoch(day.minusDays(1)), "500.00", "SAMPLE PAYROLL"),
            )))
        }
        val s = service()
        val id = connectAndLink(s, m)
        val r = s.syncConnection(id)
        assertTrue(r.message, r.ok)
        assertEquals(2, r.outcome.added)
        val b = actions.snapshot()
        assertEquals(2, b.toSort.size)
        assertTrue(store.dao.linesNow().all { it.bucket == LineBucket.UNSORTED })
        // a new account's starting balance makes its posted balance match the bank's
        val acct = store.sync.linkedOf(id).single().accountId!!
        assertEquals(487_66L, s.postedBalance(acct))
        // repeat: nothing new, nothing changed
        val again = s.syncConnection(id)
        assertEquals(0, again.outcome.added)
        assertEquals(0, again.outcome.updated)
        assertEquals(2, synced().size)
        // the request asked for pending rows and a start date, with version 2
        assertTrue(m.queries.last().contains("pending=1") && m.queries.last().contains("start-date=") && m.queries.last().contains("version=2"))
    }

    @Test fun aClaimedTokenSaysSoPlainly() = runBlocking<Unit> {
        val m = simplefin()
        val s = service()
        s.connectSimpleFin(m.setupToken())
        try {
            s.connectSimpleFin(m.setupToken())
            fail("a used token must not connect")
        } catch (e: SyncError.NeedsFix) {
            assertEquals(FixCode.SIMPLEFIN_TOKEN, e.code)
            assertTrue(e.message!!.contains("already used"))
        }
    }

    @Test fun pendingToPostedUpdatesInPlaceAndKeepsTheEnvelope() = runBlocking<Unit> {
        val m = simplefin()
        var posted = false
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet("80.00", listOf(
                if (!posted) SimpleFinMock.txn("P1", epoch(day.minusDays(1)), "-20.00", "FUEL STOP", pending = true)
                else SimpleFinMock.txn("P1", epoch(day), "-23.50", "FUEL STOP"),
            )))
        }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        val tx = synced().single()
        assertTrue(tx.pending)
        val env = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Fuel", EnvelopeMode.FILL_TO_TARGET))
        actions.sortInto(tx.id, env)
        posted = true
        now += 2 * 60 * 60 * 1000
        val r = s.syncConnection(id)
        assertEquals(0, r.outcome.added)
        assertEquals(1, r.outcome.updated)
        val after = store.dao.transaction(tx.id)!!
        assertFalse(after.pending)
        assertEquals(-23_50L, after.amount)
        assertEquals(day, after.date)
        val line = store.dao.linesOf(tx.id).single()
        assertEquals(env, line.envelopeId)
        assertEquals(-23_50L, line.amount)
    }

    @Test fun aPendingThatVanishesIsRemovedAndNoted() = runBlocking<Unit> {
        val m = simplefin()
        var gone = false
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet("50.00", if (gone) emptyList() else listOf(SimpleFinMock.txn("V1", epoch(day.minusDays(1)), "-9.99", "SAMPLE HOLD", pending = true))))
        }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        assertEquals(1, synced().size)
        gone = true
        now += 60 * 60 * 1000
        val r = s.syncConnection(id)
        assertEquals(1, r.outcome.removed)
        assertTrue(synced().isEmpty())
        assertTrue(store.sync.lastLog(id)!!.message.contains("disappeared without posting"))
    }

    @Test fun aReissuedIdWhenPostingKeepsTheSameRow() = runBlocking<Unit> {
        val m = simplefin()
        var posted = false
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet("50.00", listOf(
                if (!posted) SimpleFinMock.txn("PEND-A", epoch(day.minusDays(2)), "-15.00", "SAMPLE CAFE", pending = true)
                else SimpleFinMock.txn("POST-B", epoch(day), "-15.00", "SAMPLE CAFE"),
            )))
        }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        val tx = synced().single()
        posted = true
        val r = s.syncConnection(id)
        assertEquals(0, r.outcome.added)
        assertEquals(0, r.outcome.removed)
        val after = synced().single()
        assertEquals(tx.id, after.id)
        assertFalse(after.pending)
        assertTrue(after.externalId!!.endsWith(":POST-B"))
    }

    @Test fun overlapWithCsvAndHandEntryIsCountAware() = runBlocking<Unit> {
        val m = simplefin()
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 0, day.minusDays(60))
        // two identical rows already here from a CSV import, plus one pending entered by hand
        repeat(2) { actions.saveTransaction(null, TransactionDraft(acct, day.minusDays(3), "SAMPLE TRANSFER OUT", "", -25_00, listOf(LineDraft(null, -25_00)))) }
        val hand = actions.saveTransaction(null, TransactionDraft(acct, day.minusDays(4), "lunch", "", -11_00, listOf(LineDraft(null, -11_00)), pending = true))
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet("0.00", listOf(
                SimpleFinMock.txn("D1", epoch(day.minusDays(3)), "-25.00", "Sample Transfer Out"),
                SimpleFinMock.txn("D2", epoch(day.minusDays(3)), "-25.00", "Sample Transfer Out"),
                SimpleFinMock.txn("D3", epoch(day.minusDays(3)), "-25.00", "Sample Transfer Out"),
                SimpleFinMock.txn("L1", epoch(day.minusDays(2)), "-11.00", "SAMPLE DELI"),
            )))
        }
        val s = service()
        val id = connectAndLink(s, m, SyncService.Target.Existing(acct))
        val r = s.syncConnection(id)
        assertEquals(1, r.outcome.added)                 // 3 in the feed, 2 already here
        assertEquals(3, r.outcome.linked)                 // 2 by key, 1 hand-entered pending
        assertEquals(4, store.dao.transactionsNow().count { it.accountId == acct })
        val posted = store.dao.transaction(hand)!!
        assertFalse(posted.pending)
        assertEquals("lunch", posted.payee)               // the user's own payee stays
        assertEquals(0, s.syncConnection(id).outcome.added)
    }

    @Test fun aBalanceMismatchIsShownNeverAdjusted() = runBlocking<Unit> {
        val m = simplefin()
        val acct = actions.saveAccount(null, "Everyday", AccountType.BANK, 100_00, day.minusDays(60))
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet("90.00", listOf(SimpleFinMock.txn("B1", epoch(day.minusDays(1)), "-5.00", "SAMPLE SHOP")))) }
        val s = service()
        val id = connectAndLink(s, m, SyncService.Target.Existing(acct))
        s.syncConnection(id)
        val link = store.sync.linkedOf(id).single()
        assertEquals(90_00L, link.providerBalance)
        assertEquals(95_00L, s.postedBalance(acct))      // 100 start - 5: off by 5, and left alone
        assertEquals(100_00L, store.dao.account(acct)!!.startingBalance)
    }

    @Test fun revokedAccessNeedsAFixAndPromptsOncePerSession() = runBlocking<Unit> {
        val m = simplefin()
        val s = service()
        val id = connectAndLink(s, m)
        m.accountsAnswer = { Answer(403, "") }
        val r = s.syncConnection(id, SyncService.Trigger.OPEN)
        assertFalse(r.ok)
        val c = store.sync.connection(id)!!
        assertEquals(ConnectionStatus.NEEDS_FIX, c.status)
        assertEquals(FixCode.SIMPLEFIN_TOKEN, c.fixCode)
        assertNotNull(s.prompt.value)
        s.dismissPrompt()
        s.syncConnection(id, SyncService.Trigger.MANUAL)
        assertNull("one popup per session", s.prompt.value)
    }

    @Test fun rateLimitsAreRetriedSilentlyWithBackoff() = runBlocking<Unit> {
        val m = simplefin()
        val s = service()
        val id = connectAndLink(s, m)
        var calls = 0
        m.accountsAnswer = { calls++; if (calls <= 2) Answer(429, "") else Answer(200, SimpleFinMock.accountSet()) }
        val r = s.syncConnection(id)
        assertTrue(r.message, r.ok)
        assertEquals(3, calls)
        // still limited after every retry: a quiet "try later", no fix needed
        m.accountsAnswer = { Answer(429, "") }
        val r2 = s.syncConnection(id)
        assertFalse(r2.ok)
        assertTrue(r2.message.contains("try again later"))
        assertEquals(ConnectionStatus.OK, store.sync.connection(id)!!.status)
    }

    @Test fun theSimpleFinDailyLimitIsEnforcedInCode() = runBlocking<Unit> {
        val m = simplefin()
        val s = service(limiterMax = 3)
        val id = connectAndLink(s, m)                       // 1 request (the account list)
        assertTrue(s.syncConnection(id).ok)                 // 2
        assertTrue(s.syncConnection(id).ok)                 // 3
        val before = m.queries.size
        val r = s.syncConnection(id)                        // over the limit: nothing is sent
        assertFalse(r.ok)
        assertTrue(r.message.contains("used up"))
        assertEquals(before, m.queries.size)
        now += RequestLimiter.DAY_MS + 1000                 // a day later the budget frees up
        assertTrue(s.syncConnection(id).ok)
    }

    @Test fun automaticSyncsAreSpacedOutAndManualOnesAreNot() = runBlocking<Unit> {
        val m = simplefin()
        val s = service()
        val id = connectAndLink(s, m)
        assertEquals(1, s.syncAll(SyncService.Trigger.OPEN).size)
        now += 40 * 60 * 1000                               // 40 minutes: too soon for SimpleFIN's automatic gap
        assertTrue(s.syncAll(SyncService.Trigger.OPEN).isEmpty())
        assertTrue(s.syncAll(SyncService.Trigger.BACKGROUND).isEmpty())
        assertEquals(1, s.syncAll(SyncService.Trigger.MANUAL).size)
        now += 61 * 60 * 1000
        assertEquals(id, s.syncAll(SyncService.Trigger.BACKGROUND).single().connectionId)
    }

    @Test fun badJsonIsReportedNotTrusted() = runBlocking<Unit> {
        val m = simplefin()
        val s = service()
        val id = connectAndLink(s, m)
        m.accountsAnswer = { Answer(200, "this is not json") }
        val r = s.syncConnection(id)
        assertFalse(r.ok)
        assertTrue(r.message.contains("isn't readable"))
        assertTrue(synced().isEmpty())
    }

    @Test fun timeoutsAreANetworkProblem() = runBlocking<Unit> {
        val m = simplefin()
        val s = service(readTimeoutMs = 300)
        val id = connectAndLink(s, m)
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet(), delayMs = 1200) }
        val r = s.syncConnection(id)
        assertFalse(r.ok)
        assertTrue(r.message.contains("try again later"))
    }

    @Test fun bridgeMessagesAreShownSanitizedAndApiOnesHidden() = runBlocking<Unit> {
        val m = simplefin()
        m.accountsAnswer = {
            Answer(200, SimpleFinMock.accountSet(errlist = """[{"code":"con.auth","msg":"Sample Bank needs you to <b>log in</b> again.","conn_id":"CON-1"},{"code":"gen.api","msg":"developer detail"}]"""))
        }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        val msgs = store.sync.connection(id)!!.providerMessages!!
        assertEquals("Sample Bank needs you to log in again.", msgs)
    }

    @Test fun disconnectKeepsTransactionsByDefaultAndDeletesSecrets() = runBlocking<Unit> {
        val m = simplefin()
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet(txns = listOf(SimpleFinMock.txn("K1", epoch(day), "-1.00", "SAMPLE")))) }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        assertTrue(secrets.names().isNotEmpty())
        s.disconnect(id)
        assertTrue(secrets.names().isEmpty())
        assertNull(store.sync.connection(id))
        assertTrue(store.sync.linkedOf(id).isEmpty())
        val kept = store.dao.transactionsNow().single { it.payee == "SAMPLE" }
        assertNull("kept rows become ordinary ones", kept.externalId)
    }

    @Test fun disconnectCanRemoveTheSyncedTransactions() = runBlocking<Unit> {
        val m = simplefin()
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet(txns = listOf(SimpleFinMock.txn("R1", epoch(day), "-1.00", "SAMPLE")))) }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        s.disconnect(id, removeTransactions = true)
        assertTrue(store.dao.transactionsNow().none { it.payee == "SAMPLE" })
        assertTrue(store.dao.linesNow().isEmpty())
    }

    @Test fun secretsNeverReachTheChangeLogOrTheDatabase() = runBlocking<Unit> {
        val m = simplefin()
        m.accountsAnswer = { Answer(200, SimpleFinMock.accountSet(txns = listOf(SimpleFinMock.txn("S1", epoch(day), "-1.00", "SAMPLE")))) }
        val s = service()
        val id = connectAndLink(s, m)
        s.syncConnection(id)
        val everything = store.dao.changes().joinToString { it.changedFields } +
            store.sync.connectionsNow().joinToString() + store.sync.linkedNow().joinToString() + store.dao.meta("device_id").orEmpty()
        assertFalse(everything.contains(m.pass))
        assertFalse(everything.contains(m.user))
        assertFalse(String(secretsFile.readBytes(), Charsets.ISO_8859_1).contains(m.pass))
        assertEquals(m.accessUrl, secrets.get(SecretStore.simplefinUrl(id)))
    }

    // ---- Teller (mutual TLS) ------------------------------------------------------------------------------------

    private fun teller(accessToken: String = "test_token_" + System.nanoTime()) = TellerMock(TestCerts.get(), accessToken).also { closers += it }

    @Test fun tellerSyncsOverMutualTls() = runBlocking<Unit> {
        val t = teller()
        t.transactions = listOf(
            TellerMock.txn("txn_1", day.minusDays(1).toString(), "-42.10", "SAMPLE MARKET"),
            TellerMock.txn("txn_2", day.toString(), "-3.25", "SAMPLE COFFEE", status = "pending"),
        )
        val s = service(t)
        s.saveTellerCredentials(TestCerts.get().clientCertPem, TestCerts.get().clientKeyPem)
        val id = s.connectTeller(t.accessToken, "enr_test1", "Sample Bank")
        val l = store.sync.linkedOf(id).single()
        assertEquals("0001", l.mask)
        assertEquals(250_00L, l.providerBalance)
        s.link(listOf(SyncService.LinkChoice(l.id, SyncService.Target.New("Checking"), SyncService.History.DAYS_30)))
        val r = s.syncConnection(id)
        assertTrue(r.message, r.ok)
        assertEquals(2, r.outcome.added)
        assertEquals(1, synced().count { it.pending })
        assertEquals(250_00L, s.postedBalance(store.sync.linkedOf(id).single().accountId!!))
        assertEquals(0, s.syncConnection(id).outcome.added)
        val log = store.dao.changes().joinToString { it.changedFields }
        assertFalse(log.contains(t.accessToken))
        assertFalse(log.contains("PRIVATE KEY"))
        assertEquals("enr_test1", store.sync.connection(id)!!.enrollmentId)
    }

    @Test fun aCertificateTellerDoesntKnowNeedsAFix() = runBlocking<Unit> {
        val t = teller()
        val s = service(t)
        s.saveTellerCredentials(TestCerts.get().otherCertPem, TestCerts.get().otherKeyPem)
        val id = s.connectTeller(t.accessToken, "enr_test1", "Sample Bank")
        val r = s.syncConnection(id)
        assertFalse(r.ok)
        assertEquals(FixCode.TELLER_CERTIFICATE, store.sync.connection(id)!!.fixCode)
    }

    @Test fun aDisconnectedEnrollmentAsksForAReconnect() = runBlocking<Unit> {
        val t = teller()
        val s = service(t)
        s.saveTellerCredentials(TestCerts.get().clientCertPem, TestCerts.get().clientKeyPem)
        val id = s.connectTeller(t.accessToken, "enr_test1", "Sample Bank")
        s.link(listOf(SyncService.LinkChoice(store.sync.linkedOf(id).single().id, SyncService.Target.New("Checking"))))
        t.forced = Answer(404, """{"error":{"code":"enrollment.disconnected.credentials_invalid","message":"x"}}""")
        val r = s.syncConnection(id)
        assertFalse(r.ok)
        val c = store.sync.connection(id)!!
        assertEquals(FixCode.TELLER_RECONNECT, c.fixCode)
        assertTrue(c.lastResult!!.contains("login changed"))
    }

    @Test fun aRevokedTellerTokenAsksForAReconnect() = runBlocking<Unit> {
        val t = teller()
        val s = service(t)
        s.saveTellerCredentials(TestCerts.get().clientCertPem, TestCerts.get().clientKeyPem)
        val id = s.connectTeller("not_the_token", "enr_test1", "Sample Bank")
        assertEquals(FixCode.TELLER_RECONNECT, (s.syncConnection(id).let { store.sync.connection(id)!!.fixCode }))
    }

    @Test fun aCreditCardBalanceIsWhatYouOwe() = runBlocking<Unit> {
        val t = teller()
        t.accountType = "credit"
        t.balance = "120.00"
        val s = service(t)
        s.saveTellerCredentials(TestCerts.get().clientCertPem, TestCerts.get().clientKeyPem)
        val id = s.connectTeller(t.accessToken, "enr_test1", "Sample Bank")
        assertEquals(-120_00L, store.sync.linkedOf(id).single().providerBalance)
    }
}
