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
import net.darkrelay.deaddropfinance.data.TransactionSource
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CSV import duplicates (1.9.7, program 0.15.10), on invented bank files in a USAA-like layout and a budget of its own in
 * a temporary folder: a row is already here when the bank's original description, amount and date match one imported
 * before, whatever the payee was renamed to; a transaction entered by hand (or imported before 1.9.7) is matched by
 * amount on the same day, count by count, and one a few days off is shown as unsure; Pending rows are never added; an
 * existing transaction is matched once at most; and Undo last import removes exactly what one import added.
 */
class CsvImportDedupeTest {

    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L
    private lateinit var store: BudgetStore
    private lateinit var actions: BudgetActions
    private lateinit var acct: String
    private lateinit var other: String

    private fun open() = runBlocking {
        store = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(File(tmp.root, "budget"), clock = { clock += 1_000; clock })).store
        store.ensureSystemEnvelopes(); store.ensureCategories()
        actions = BudgetActions(store)
        actions.saveAccount(null, "Sample Checking", AccountType.BANK, 1_000_00, LocalDate.of(2030, 9, 1))
        actions.saveAccount(null, "Sample Card", AccountType.BANK, 0, LocalDate.of(2030, 9, 1))
        val all = store.dao.accountsNow()
        acct = all.first { it.name == "Sample Checking" }.id
        other = all.first { it.name == "Sample Card" }.id
    }

    private val head = "Date,Description,Original Description,Category,Amount,Status\n"
    private fun csv(vararg rows: String) = head + rows.joinToString("\n") + "\n"
    private fun read(text: String) = CsvImport.read(text, CsvImport.detect(text))
    private fun now() = runBlocking { store.dao.transactionsNow() }
    private fun plan(text: String, account: String = acct, choices: Map<Int, Boolean> = emptyMap()) = CsvImport.plan(read(text), account, now(), choices)
    private fun import(text: String, account: String = acct, choices: Map<Int, Boolean> = emptyMap()) = runBlocking { actions.importCsv(account, plan(text, account, choices)) }

    /** A transaction typed in by hand (no original description), as the app saves one. */
    private fun byHand(date: String, payee: String, amount: Long, account: String = acct) = runBlocking {
        store.write {
            val t = put(null, TransactionEntity(accountId = account, date = LocalDate.parse(date), payee = payee, amount = amount, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
            t
        }
    }

    private val october = csv(
        "2030-10-02,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted",
        "2030-10-05,Sample Mart,SAMPLE MART 77 SAMPLE CITY,Groceries,-62.18,Posted",
        "2030-10-09,Sample Payroll,SAMPLE PAYROLL CO DIR DEP,Income,1500.00,Posted",
        "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
        "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
        "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
    )

    @Test fun theUsaaColumnsAreFound() {
        val l = CsvImport.detect(october)
        assertEquals(
            listOf(CsvImport.Role.DATE, CsvImport.Role.DESCRIPTION, CsvImport.Role.ORIGINAL, CsvImport.Role.MEMO, CsvImport.Role.AMOUNT, CsvImport.Role.STATUS),
            l.roles,
        )
        val r = CsvImport.read(october, l).rows.first()
        assertEquals("QuikTrip", r.description)
        assertEquals("QUIKTRIP 0123 SAMPLE CITY", r.original)
        // a bank without an original description column: its description is what's kept
        val plain = "Date,Description,Amount\n2030-10-02,Sample Shop,-1.00\n"
        assertEquals("Sample Shop", read(plain).rows.single().original)
    }

    @Test fun renamedPayeesStillMatchOnTheBanksOriginalDescription() {
        open()
        val first = import(october)
        assertEquals(6, first.imported)
        // the user renames every payee in the app
        runBlocking { val all = store.dao.transactionsNow(); store.write { for (t in all) put(t, t.copy(payee = "Renamed ${t.amount}", updatedAt = now)) } }
        assertTrue(now().all { it.originalDescription != null && it.payee.startsWith("Renamed") })
        val again = plan(october)
        assertEquals(6, again.alreadyHere.size)
        assertEquals(0, again.new.size)
        assertEquals(0, again.unsure.size)
        assertEquals(0, again.toImport.size)
    }

    @Test fun sameDayRepeatsCountAsSeparateRows() {
        open()
        val three = csv(
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
        )
        // three typed in by hand: all three already in
        repeat(3) { byHand("2030-10-30", "Coffee", -3_00) }
        plan(three).let { assertEquals(3, it.alreadyHere.size); assertEquals(0, it.new.size); assertEquals(0, it.unsure.size) }
        // three in the file and two here: one is new
        runBlocking { actions.deleteTransaction(now().first().id) }
        plan(three).let { assertEquals(2, it.alreadyHere.size); assertEquals(1, it.new.size); assertEquals(0, it.unsure.size) }
        // and against an earlier import of the same three: count by count too
        runBlocking { now().forEach { actions.deleteTransaction(it.id) } }
        import(three)
        plan(three).let { assertEquals(3, it.alreadyHere.size); assertEquals(0, it.new.size) }
        val two = csv(
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
        )
        plan(two).let { assertEquals(2, it.alreadyHere.size); assertEquals(0, it.new.size) }
    }

    @Test fun aHandEntryADayOffIsUnsureWithItBesideTheRow() {
        open()
        val hand = byHand("2030-11-02", "Quick Trip", -40_00)
        val p = plan(csv("2030-11-03,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted"))
        val u = p.unsure.single()
        assertEquals(hand.id, u.maybe.id)
        assertEquals(1L, u.daysApart)
        assertEquals(0, p.new.size); assertEquals(0, p.alreadyHere.size)
        // unticked by default: nothing goes in; ticked: it does
        assertEquals(0, p.toImport.size)
        val ticked = plan(csv("2030-11-03,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted"), choices = mapOf(u.row.rowNumber to true))
        assertEquals(1, ticked.toImport.size)
        // more than one candidate on the day: unsure as well
        byHand("2030-11-05", "Corner Shop", -7_00); byHand("2030-11-05", "Corner Shop", -7_00)
        val two = plan(csv("2030-11-05,Corner Shop,CORNER SHOP 9,Shopping,-7.00,Posted"))
        assertEquals(1, two.unsure.size); assertEquals(0, two.alreadyHere.size)
        // the same amount five days off is new
        assertEquals(1, plan(csv("2030-11-07,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted")).new.size)
    }

    @Test fun aRenamedHandEntryOnTheSameDayIsAlreadyIn() {
        open()
        byHand("2030-10-02", "Quick Trip", -40_00)
        byHand("2030-10-05", "Groceries run", -62_18)
        val p = plan(october)
        assertEquals(2, p.alreadyHere.size)
        assertEquals(4, p.new.size)
        assertEquals(0, p.unsure.size)
    }

    @Test fun aStatementImportOverlappingTheNextOneAddsOnlyTheNewRows() {
        open()
        import(october)
        val later = csv(
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-10-30,Sample Coffee,SAMPLE COFFEE 12,Restaurants,-3.00,Posted",
            "2030-11-03,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted",
            "2030-11-04,Sample Mart,SAMPLE MART 77 SAMPLE CITY,Groceries,-62.18,Posted",
        )
        val p = plan(later)
        assertEquals(3, p.alreadyHere.size)
        assertEquals(listOf("2030-11-03", "2030-11-04"), p.new.map { it.date.toString() })
        // an imported row a day off is never "unsure": it has its original description, and that date differs
        assertEquals(0, p.unsure.size)
        import(later)
        assertEquals(8, now().count { it.accountId == acct })
        // another account's transactions never count
        assertEquals(5, plan(later, other).new.size)
    }

    @Test fun pendingRowsAreSetAsideAndNeverAdded() {
        open()
        val p = plan(csv(
            "2030-11-25,Sample Mart,SAMPLE MART 77 SAMPLE CITY,Groceries,-12.00,Posted",
            "2030-11-27,Sample Diner,SAMPLE DINER,Restaurants,-18.40,Pending",
        ))
        assertEquals(1, p.pending.size)
        assertEquals("Sample Diner", p.pending.single().description)
        assertEquals(1, p.toImport.size)
        val r = runBlocking { actions.importCsv(acct, p) }
        assertEquals(1, r.imported); assertEquals(1, r.pending)
        assertTrue(now().none { it.payee == "Sample Diner" })
    }

    @Test fun anExistingTransactionIsMatchedOnceAtMost() {
        open()
        val hand = byHand("2030-11-01", "Corner Shop", -5_00)
        val p = plan(csv(
            "2030-11-01,Corner Shop,CORNER SHOP 9,Shopping,-5.00,Posted",
            "2030-11-02,Corner Shop,CORNER SHOP 9,Shopping,-5.00,Posted",
        ))
        assertEquals(1, p.alreadyHere.size)
        assertEquals(hand.id, p.matches.single().id)
        // the second row can't have the same transaction beside it: it's new
        assertEquals(0, p.unsure.size); assertEquals(1, p.new.size)
        // one imported with its original description, two identical rows on its day: one already in, one new
        runBlocking { actions.deleteTransaction(hand.id) }
        import(csv("2030-11-01,Corner Shop,CORNER SHOP 9,Shopping,-5.00,Posted"))
        val q = plan(csv(
            "2030-11-01,Corner Shop,CORNER SHOP 9,Shopping,-5.00,Posted",
            "2030-11-01,Corner Shop,CORNER SHOP 9,Shopping,-5.00,Posted",
        ))
        assertEquals(1, q.alreadyHere.size); assertEquals(1, q.new.size)
    }

    @Test fun anUntickedNewRowStaysOut() {
        open()
        val p = plan(october, choices = mapOf(read(october).rows.first().rowNumber to false))
        assertEquals(5, p.toImport.size)
        assertEquals(1, runBlocking { actions.importCsv(acct, p) }.skippedByUser)
    }

    @Test fun undoLastImportRemovesExactlyWhatThatImportAdded() {
        open()
        val kept = byHand("2030-10-15", "Rent", -900_00)
        import(october)
        import(csv("2030-10-01,Sample Card Payment,CARD PMT,Payment,-25.00,Posted"), other)
        val later = csv(
            "2030-11-03,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted",
            "2030-11-04,Sample Mart,SAMPLE MART 77 SAMPLE CITY,Groceries,-62.18,Posted",
        )
        val r = import(later)
        val before = now()
        val batch = assertNotNull(CsvImport.lastImport(before, acct))
        assertEquals(r.importId, batch.importId)
        assertEquals(r.newIds.toSet(), batch.transactionIds.toSet())
        assertEquals(2, batch.size)
        assertEquals(2, runBlocking { actions.undoImport(batch) })
        val after = now()
        assertEquals(before.size - 2, after.size)
        assertTrue(after.none { it.id in r.newIds })
        assertTrue(after.any { it.id == kept.id })
        assertEquals(6, after.count { it.accountId == acct && it.importId != null })
        assertEquals(1, after.count { it.accountId == other })
        // soft deletes, with their lines, logged so they sync
        runBlocking {
            for (id in r.newIds) {
                assertTrue(store.dao.transaction(id)!!.deleted)
                assertTrue(store.dao.linesOf(id).isEmpty())
            }
            val deletes = store.dao.changes().filter { it.operation.name == "DELETE" && it.entityType == "transaction" }.map { it.entityId }.toSet()
            assertEquals(r.newIds.toSet(), deletes)
        }
        // the import before it is the last one now
        assertEquals(6, CsvImport.lastImport(after, acct)!!.size)
        // and the file can be imported again, adding the same two
        assertEquals(2, plan(later).new.size)
    }

    @Test fun anImportMadeBeforeImportIdsIsOneWriteAndUndoesExactly() {
        open()
        val kept = byHand("2030-11-02", "Quick Trip", -40_00)
        // as 0.15.9 saved an import: CSV rows with no import id and no original description, all in one write
        fun legacy(vararg rows: Pair<String, Long>) = runBlocking {
            store.write {
                rows.map { (d, a) ->
                    val t = put(null, TransactionEntity(accountId = acct, date = LocalDate.parse(d), payee = "Sample $a", amount = a,
                        source = TransactionSource.CSV, importDescription = "Sample $a", createdAt = now, updatedAt = now))
                    put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = a, createdAt = now, updatedAt = now))
                    t.id
                }
            }
        }
        val earlier = legacy("2030-10-02" to -10_00L, "2030-10-03" to -11_00L)
        val morning = legacy("2030-10-02" to -10_00L, "2030-11-03" to -40_00L, "2030-11-20" to -9_99L)
        val batch = assertNotNull(CsvImport.lastImport(now(), acct))
        assertNull(batch.importId)
        assertEquals(morning.toSet(), batch.transactionIds.toSet())
        assertEquals(3, runBlocking { actions.undoImport(batch) })
        val after = now()
        assertTrue(after.none { it.id in morning })
        assertTrue(after.all { it.id in earlier || it.id == kept.id })
        assertEquals(earlier.toSet(), CsvImport.lastImport(after, acct)!!.transactionIds.toSet())
        // the same file in again: what the earlier import and the hand entry hold is found, the hand entry a day off is unsure
        val again = plan(csv(
            "2030-10-02,Sample,SAMPLE A,Shopping,-10.00,Posted",
            "2030-11-03,QuikTrip,QUIKTRIP 0123 SAMPLE CITY,Fuel,-40.00,Posted",
            "2030-11-20,Sample,SAMPLE B,Shopping,-9.99,Posted",
        ))
        assertEquals(1, again.alreadyHere.size)
        assertEquals(kept.id, again.unsure.single().maybe.id)
        assertEquals(1, again.new.size)
    }

    @Test fun noImportNoUndo() {
        open()
        byHand("2030-11-02", "Quick Trip", -40_00)
        assertNull(CsvImport.lastImport(now(), acct))
    }
}
