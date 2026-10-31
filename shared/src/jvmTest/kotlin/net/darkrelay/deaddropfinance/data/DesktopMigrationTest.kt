package net.darkrelay.deaddropfinance.data

import androidx.sqlite.execSQL
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.desktopdb.CipherDriver
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.serversync.rawTransaction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The program's database migrations (0.15.0). A budget made by 0.14.x (schema 12, encrypted) opens in 0.15.0 with
 * every row kept and the Debt interest rate column added, empty. Temp folders, invented data and a test key only.
 */
class DesktopMigrationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ok(o: DesktopDatabase.Opened) = assertIs<DesktopDatabase.Opened.Ok>(o, (o as? DesktopDatabase.Opened.Unavailable)?.reason)

    /** A schema 12 budget as 0.14.x left it: today's tables less the new column, user_version 12. */
    private fun budget12(dir: File): Map<String, Long> = runBlocking {
        val o = ok(DesktopDatabase.open(dir, clock = { 1_000L }))
        o.store.ensureSystemEnvelopes(); o.store.ensureCategories()
        val a = BudgetActions(o.store)
        val d = LocalDate.of(2030, 3, 1)
        val chk = a.saveAccount(null, "Sample Checking", net.darkrelay.deaddropfinance.data.AccountType.BANK, 500_00, d)
        val loan = a.saveEnvelope(null, EnvelopeDraft("Sample Loan", net.darkrelay.deaddropfinance.data.EnvelopeMode.DEBT, perPayday = 50_00, debtOwed = 900_00), d)
        a.saveTransaction(null, TransactionDraft(chk, d, "Sample Lender", "", -50_00, listOf(LineDraft(loan, -50_00))))
        val counts = listOf("account", "envelope", "txn", "txn_line", "category", "change_log").associateWith { t -> o.store.raw { it.query("SELECT count(*) FROM `$t`").single()[0] as Long } }
        o.store.db.close()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).load()).key
        CipherDriver { key.copyOf() }.open(File(dir, "budget.db").absolutePath).use { c ->
            c.execSQL("ALTER TABLE `envelope` DROP COLUMN `interest_rate`")
            c.execSQL("PRAGMA user_version = 12")
        }
        counts
    }

    private suspend fun BudgetStore.raw(block: suspend (net.darkrelay.deaddropfinance.serversync.RawSql) -> Long): Long = db.rawTransaction(block)

    @Test fun aSchema12BudgetOpensWithEveryRowAndTheNewColumn() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val before = budget12(dir)
        val o = ok(DesktopDatabase.open(dir, clock = { 2_000L }))
        val counts = listOf("account", "envelope", "txn", "txn_line", "category", "change_log").associateWith { t -> o.store.raw { it.query("SELECT count(*) FROM `$t`").single()[0] as Long } }
        assertEquals(before, counts, "every row is kept")
        val version = o.store.raw { it.query("PRAGMA user_version").single()[0] as Long }
        assertEquals(13L, version)
        val loan = o.store.dao.envelopesNow().first { it.name == "Sample Loan" }
        assertNull(loan.interestRate, "no rate until one is given")
        assertEquals(900_00L, loan.debtOwed)
        assertEquals(50_00L, loan.perPayday)
        // the new column takes a rate
        BudgetActions(o.store).saveEnvelope(loan.id, EnvelopeDraft("Sample Loan", net.darkrelay.deaddropfinance.data.EnvelopeMode.DEBT, perPayday = 50_00, debtOwed = 900_00, interestRate = 1999))
        assertEquals(1999L, o.store.dao.envelope(loan.id)!!.interestRate)
        assertTrue(o.store.dao.changes().any { it.entityId == loan.id && it.changedFields.contains("\"interest_rate\":1999") }, "and it goes to sync like the rest")
        o.store.db.close()
    }
}
