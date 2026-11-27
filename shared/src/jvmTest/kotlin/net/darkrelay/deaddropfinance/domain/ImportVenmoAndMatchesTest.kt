package net.darkrelay.deaddropfinance.domain

import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSV import, 1.9.8 and program 0.15.11, on invented files and a budget of its own: a Venmo statement in Venmo's own
 * layout (title lines, a beginning balance row, payments in, card charges out, an Instant Transfer with its fee, a
 * Venmo account repayment and a closing row with the disclaimer); bank rows matched by a transfer leg, a split, and a
 * purchase entered as one transaction per envelope; and the preview's balance check.
 */
class ImportVenmoAndMatchesTest {

    @get:Rule val tmp = TemporaryFolder()

    private val blank = List(21) { "" }
    private fun line(vararg cells: Pair<Int, String>): String = blank.toMutableList().also { l -> cells.forEach { (i, v) -> l[i] = v } }
        .joinToString(",") { if (it.contains(',') || it.contains('"')) "\"" + it.replace("\"", "\"\"") + "\"" else it }

    private val header = "ID,Datetime,Type,Status,Note,From,To,Amount (total),Amount (tip),Amount (tax),Amount (fee),Tax Rate,Tax Exempt,Funding Source,Destination,Beginning Balance,Ending Balance,Statement Period Venmo Fees,Terminal Location,Year to Date Venmo Fees,Disclaimer"

    /** An invented Venmo statement: begins at $120.00 and ends at $1,056.44 without the repayment. */
    private fun statement(ending: String = "$1,056.44") = listOf(
        line(0 to "Account Statement - (@Sample-User)"),
        line(0 to "Account Activity"),
        header,
        line(15 to "$120.00"),
        line(0 to "4100000001", 1 to "2030-11-02T10:15:00", 2 to "Payment", 3 to "Complete", 4 to "Lunch", 5 to "Sample Friend", 6 to "Sample User", 7 to "+ $25.00", 14 to "Venmo balance"),
        line(0 to "4100000002", 1 to "2030-11-05T18:30:00", 2 to "Venmo Card Transaction", 3 to "Complete", 5 to "Sample User", 6 to "Sample Cafe", 7 to "- $8.16", 13 to "Venmo balance"),
        line(0 to "4100000003", 1 to "2030-11-09T09:00:00", 2 to "Payment", 3 to "Complete", 4 to "Rent share", 5 to "Sample User", 6 to "Sample Roommate", 7 to "- $50.00", 13 to "Venmo balance"),
        line(0 to "4100000004", 1 to "2030-11-12T12:00:00", 2 to "Instant Transfer", 3 to "Issued", 7 to "- $40.40", 10 to "- $0.40", 14 to "Sample Bank"),
        line(0 to "4100000005", 1 to "2030-11-20T08:00:00", 2 to "Venmo account repayment", 3 to "Complete", 5 to "Sample User", 7 to "+ $30.00"),
        line(0 to "4100000006", 1 to "2030-11-25T16:45:00", 2 to "Payment", 3 to "Complete", 4 to "Gift", 5 to "Sample Aunt", 6 to "Sample User", 7 to "+ $1,000.00", 14 to "Venmo balance"),
        line(0 to "4100000007", 1 to "2030-11-26T11:00:00", 2 to "Payment", 3 to "Complete", 4 to "Movie", 5 to "Sample Friend", 6 to "Sample User", 7 to "+ $10.00", 14 to "Venmo balance"),
        line(16 to ending, 17 to "$0.40", 19 to "$0.40", 20 to "In case of errors or questions about your electronic transfers, contact us. This is an invented disclaimer, with commas, for a test."),
    ).joinToString("\n") + "\n"

    @Test fun aVenmoStatementIsFoundByItsHeaderAndReadByItsOwnColumns() {
        val text = statement()
        val layout = CsvImport.detect(text)
        assertTrue(layout.venmo)
        val r = CsvImport.read(text, layout)
        assertEquals(listOf("Sample Friend", "Sample Cafe", "Sample Roommate", "Instant Transfer", "Sample Aunt", "Sample Friend"), r.rows.map { it.description })
        assertEquals(listOf(25_00L, -8_16, -50_00, -40_40, 1000_00, 10_00), r.rows.map { it.amount })
        assertEquals(listOf("Lunch", "", "Rent share", "", "Gift", "Movie"), r.rows.map { it.memo })
        assertEquals("4100000001", r.rows.first().original)
        assertEquals(LocalDate.of(2030, 11, 2), r.rows.first().date)
        assertEquals(120_00L, r.beginningBalance)
        assertEquals(1056_44L, r.endingBalance)
        // the Instant Transfer is one row for its total, the fee in it; no fee row of its own
        assertEquals(1, r.rows.count { it.amount == -40_40L })
        assertTrue(r.rows.none { it.amount == -40L })
        // the repayment doesn't move the balance here: left out, with why; the statement adds up
        val left = r.notAdded.single()
        assertEquals(30_00L, left.amount)
        assertTrue(left.reason.contains("repayment"))
        assertEquals(r.endingBalance, r.beginningBalance!! + r.rows.sumOf { it.amount })
        assertTrue(r.bad.isEmpty(), "${r.bad}")
    }

    @Test fun aRepaymentThatMovesTheBalanceIsAdded() {
        val r = CsvImport.read(statement(ending = "$1,086.44"), CsvImport.detect(statement(ending = "$1,086.44")))
        assertEquals(7, r.rows.size)
        assertTrue(r.notAdded.isEmpty())
        assertEquals(r.endingBalance, r.beginningBalance!! + r.rows.sumOf { it.amount })
    }

    @Test fun aVenmoStatementImportedTwiceAddsNothing() = runBlocking<Unit> {
        val (store, ids) = budget()
        val a = BudgetActions(store)
        val venmo = ids.getValue("Venmo")
        val text = statement()
        val first = CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), venmo, store.dao.transactionsNow())
        assertEquals(6, first.new.size)
        assertEquals(1, first.notAdded.size)
        a.importCsv(venmo, first)
        // the balance after it: the beginning balance (the account's start) plus the rows, as the statement ends
        assertEquals(1056_44L, a.snapshot().accountBalance(venmo))
        val again = CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), venmo, store.dao.transactionsNow())
        assertEquals(0, again.new.size)
        assertEquals(0, again.unsure.size)
        assertEquals(6, again.alreadyHere.size)
        assertEquals(0, again.toImport.size)
    }

    private fun budget(): Pair<BudgetStore, Map<String, String>> = runBlocking {
        val store = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(File(tmp.root, "budget"), clock = { System.nanoTime() / 1000 })).store
        store.ensureSystemEnvelopes(); store.ensureCategories()
        val a = BudgetActions(store)
        a.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2030, 9, 1))
        a.saveAccount(null, "Venmo", AccountType.PAYMENT_APP, 120_00, LocalDate.of(2030, 11, 1))
        store to store.dao.accountsNow().associate { it.name.removePrefix("Sample ") to it.id }
    }

    private fun usaa(vararg rows: String) = "Date,Description,Original Description,Category,Amount,Status\n" + rows.joinToString("\n") + "\n"
    private fun plan(store: BudgetStore, acct: String, text: String) = runBlocking { CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), acct, store.dao.transactionsNow()) }

    private fun byHand(store: BudgetStore, acct: String, date: String, payee: String, vararg lines: Pair<Long, String?>) = runBlocking {
        store.write {
            val total = lines.sumOf { it.first }
            val t = put(null, TransactionEntity(accountId = acct, date = LocalDate.parse(date), payee = payee, amount = total, createdAt = now, updatedAt = now))
            lines.forEachIndexed { i, (amt, env) -> put(null, TransactionLineEntity(transactionId = t.id, bucket = if (env == null) LineBucket.UNSORTED else LineBucket.ENVELOPE, envelopeId = env, amount = amt, sortOrder = i, createdAt = now, updatedAt = now)) }
            t
        }
    }

    @Test fun aTransferLegIsAMatchOnBothAccounts() = runBlocking<Unit> {
        val (store, ids) = budget()
        val a = BudgetActions(store)
        val checking = ids.getValue("Checking"); val venmo = ids.getValue("Venmo")
        val leg = a.saveTransfer(null, checking, venmo, 400_00, LocalDate.of(2030, 10, 28), "")
        val out = plan(store, checking, usaa("2030-10-28,Transfer to Venmo,VENMO TRANSFER OUT,Transfer,-400.00,Posted"))
        assertEquals(1, out.alreadyHere.size); assertEquals(leg, out.matches.single().id)
        // the other side: money into Venmo that day
        val inn = plan(store, venmo, usaa("2030-10-28,From bank,BANK TRANSFER IN,Transfer,400.00,Posted"))
        assertEquals(1, inn.alreadyHere.size)
        // a day off: unsure, with the transfer beside it, never new
        val off = plan(store, checking, usaa("2030-10-29,Transfer to Venmo,VENMO TRANSFER OUT,Transfer,-400.00,Posted"))
        assertEquals(leg, off.unsure.single().maybe.id)
        assertEquals(-400_00L, off.unsure.single().maybe.amount, "shown signed as the bank sees it")
    }

    @Test fun aSplitMatchesOnItsTotal() = runBlocking<Unit> {
        val (store, ids) = budget()
        val checking = ids.getValue("Checking")
        val env = store.dao.envelopesNow().first().id
        val t = byHand(store, checking, "2030-10-28", "Sample Superstore", -200_00L to env, -102_61L to null)
        val p = plan(store, checking, usaa("2030-10-28,Sample Superstore,SAMPLE SUPERSTORE 123,Shopping,-302.61,Posted"))
        assertEquals(t.id, p.matches.single().id)
        assertEquals(0, p.new.size)
    }

    @Test fun onePurchaseEnteredAsOnePerEnvelopeIsAlreadyIn() = runBlocking<Unit> {
        val (store, ids) = budget()
        val checking = ids.getValue("Checking")
        // the cause found in this round's look: a store run entered as two or three transactions, one per envelope, the same day and payee
        val parts = listOf(byHand(store, checking, "2030-10-28", "Sample Superstore", -150_00L to null), byHand(store, checking, "2030-10-28", "Sample Superstore", -100_00L to null),
            byHand(store, checking, "2030-10-28", "Sample Superstore", -52_61L to null))
        byHand(store, checking, "2030-10-28", "Sample Diner", -9_99L to null)
        byHand(store, checking, "2030-10-29", "Sample Grocer", -20_00L to null); byHand(store, checking, "2030-10-29", "Sample Grocer", -19_31L to null)
        val p = plan(store, checking, usaa(
            "2030-10-28,Sample Superstore,SAMPLE SUPERSTORE 123,Shopping,-302.61,Posted",
            "2030-10-29,Sample Grocer,SAMPLE GROCER 7,Groceries,-39.31,Posted",
        ))
        assertEquals(2, p.alreadyHere.size)
        assertEquals(parts.map { it.id }.toSet(), p.groups.values.first { it.size == 3 }.map { it.id }.toSet())
        assertEquals(0, p.new.size); assertEquals(0, p.unsure.size)
        // the same sum from different payees: unsure, with all of them beside it
        val (store2, ids2) = Pair(store, ids)
        byHand(store2, checking, "2030-11-03", "Sample Shop A", -10_00L to null); byHand(store2, checking, "2030-11-03", "Sample Shop B", -6_56L to null)
        val q = plan(store2, checking, usaa("2030-11-03,Sample Shop,SAMPLE SHOP,Shopping,-16.56,Posted"))
        assertEquals(0, q.new.size)
        assertEquals(1, q.unsure.single().others.size)
        assertEquals(setOf(-10_00L, -6_56L), (listOf(q.unsure.single().maybe) + q.unsure.single().others).map { it.amount }.toSet())
    }

    @Test fun theImportBalance() = runBlocking<Unit> {
        val (store, ids) = budget()
        val a = BudgetActions(store)
        val checking = ids.getValue("Checking")
        // a hand entry still pending isn't in the bank's posted balance
        store.write { put(null, TransactionEntity(accountId = checking, date = LocalDate.of(2030, 11, 3), payee = "Sample Pending", amount = -5_00, pending = true, createdAt = now, updatedAt = now)) }
        val p = plan(store, checking, usaa("2030-11-04,Sample Shop,SAMPLE SHOP,Shopping,-16.56,Posted", "2030-11-05,Sample Pay,SAMPLE PAY,Income,100.00,Posted"))
        val after = CsvImport.balanceAfter(a.snapshot(), checking, p)
        assertEquals(1_000_00L - 16_56 + 100_00, after)
        // unticked: it follows
        val text = usaa("2030-11-04,Sample Shop,SAMPLE SHOP,Shopping,-16.56,Posted", "2030-11-05,Sample Pay,SAMPLE PAY,Income,100.00,Posted")
        val less = CsvImport.plan(CsvImport.read(text, CsvImport.detect(text)), checking, store.dao.transactionsNow(), mapOf(3 to false))
        assertEquals(1_000_00L - 16_56, CsvImport.balanceAfter(a.snapshot(), checking, less))
    }
}
