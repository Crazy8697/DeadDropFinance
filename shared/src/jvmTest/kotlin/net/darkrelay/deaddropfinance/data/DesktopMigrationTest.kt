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
            c.execSQL("ALTER TABLE `receipt_item` DROP COLUMN `tax_letter`")
            c.execSQL("ALTER TABLE `receipt_item` DROP COLUMN `price_with_tax`")
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `original_description`")
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `import_id`")
            c.execSQL("DROP TABLE `bill_month`")
            c.execSQL("ALTER TABLE `envelope_move` DROP COLUMN `for_month`")
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
        assertEquals(16L, version)
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

    /** A schema 13 budget as 0.15.1 left it: a receipt item in it, the two tax columns gone, user_version 13. */
    @Test fun aSchema13BudgetGetsTheTaxLetterAndPriceWithTaxAndKeepsItsReceipts() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val o = ok(DesktopDatabase.open(dir, clock = { 1_000L }))
        o.store.write {
            val r = put(null, net.darkrelay.deaddropfinance.data.ReceiptEntity(store = "Sample Mart", total = 7_96, tax = 47, createdAt = now, updatedAt = now))
            put(null, net.darkrelay.deaddropfinance.data.ReceiptItemEntity(receiptId = r.id, text = "MILK", name = "milk", amount = 3_00, createdAt = now, updatedAt = now))
        }
        o.store.db.close()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).load()).key
        CipherDriver { key.copyOf() }.open(File(dir, "budget.db").absolutePath).use { c ->
            c.execSQL("ALTER TABLE `receipt_item` DROP COLUMN `tax_letter`")
            c.execSQL("ALTER TABLE `receipt_item` DROP COLUMN `price_with_tax`")
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `original_description`")
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `import_id`")
            c.execSQL("DROP TABLE `bill_month`")
            c.execSQL("ALTER TABLE `envelope_move` DROP COLUMN `for_month`")
            c.execSQL("PRAGMA user_version = 13")
        }
        val n = ok(DesktopDatabase.open(dir, clock = { 2_000L }))
        assertEquals(16L, n.store.raw { it.query("PRAGMA user_version").single()[0] as Long })
        val item = n.store.dao.itemsOf(n.store.dao.receiptsNow().single().id).single()
        assertEquals("MILK", item.text); assertEquals(3_00L, item.amount)
        assertEquals("", item.taxLetter, "no letter until one is read")
        assertNull(item.priceWithTax, "no price with tax: the shelf price is what it paid")
        assertEquals(3_00L, item.paid)
        // and it takes a letter and a price with tax
        n.store.write { put(item, item.copy(taxLetter = "A", priceWithTax = 3_18, updatedAt = now)) }
        assertEquals(3_18L, n.store.dao.itemsOf(item.receiptId).single().paid)
        n.store.db.close()
    }

    /** A schema 14 budget as 0.15.9 left it (an import's transactions in it, no new columns): every row kept, the columns empty. */
    @Test fun aSchema14BudgetGetsTheImportColumnsAndKeepsEveryTransaction() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val o = ok(DesktopDatabase.open(dir, clock = { 1_000L }))
        val acct = o.store.write { put(null, net.darkrelay.deaddropfinance.data.AccountEntity(name = "Sample Checking", type = net.darkrelay.deaddropfinance.data.AccountType.BANK, startDate = java.time.LocalDate.of(2030, 1, 1), createdAt = now, updatedAt = now)) }
        o.store.write {
            for (a in listOf(-40_00L, -3_00L)) put(null, net.darkrelay.deaddropfinance.data.TransactionEntity(accountId = acct.id, date = java.time.LocalDate.of(2030, 11, 3), payee = "Sample", amount = a,
                source = net.darkrelay.deaddropfinance.data.TransactionSource.CSV, importDescription = "SAMPLE", createdAt = now, updatedAt = now))
        }
        o.store.db.close()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).load()).key
        CipherDriver { key.copyOf() }.open(File(dir, "budget.db").absolutePath).use { c ->
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `original_description`")
            c.execSQL("ALTER TABLE `txn` DROP COLUMN `import_id`")
            c.execSQL("DROP TABLE `bill_month`")
            c.execSQL("ALTER TABLE `envelope_move` DROP COLUMN `for_month`")
            c.execSQL("PRAGMA user_version = 14")
        }
        val n = ok(DesktopDatabase.open(dir, clock = { 2_000L }))
        assertEquals(16L, n.store.raw { it.query("PRAGMA user_version").single()[0] as Long })
        val txs = n.store.dao.transactionsNow()
        assertEquals(2, txs.size)
        assertTrue(txs.all { it.originalDescription == null && it.importId == null && it.importDescription == "SAMPLE" })
        // the import they came from is still found, by its one write
        assertEquals(2, net.darkrelay.deaddropfinance.domain.CsvImport.lastImport(txs, acct.id)!!.size)
        n.store.db.close()
    }

    /** A schema 15 budget as 0.15.11 left it (bills in it, no bill_month table): every bill kept, the new table empty and usable. */
    @Test fun aSchema15BudgetGetsTheBillMonthTableAndKeepsEveryBill() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val o = ok(DesktopDatabase.open(dir, clock = { 1_000L }))
        o.store.write { repeat(3) { n -> put(null, net.darkrelay.deaddropfinance.data.BillEntity(name = "Sample $n", envelopeId = null, amountDue = 100_00, dueDate = java.time.LocalDate.of(2030, 12, 1), createdAt = now, updatedAt = now)) } }
        o.store.db.close()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).load()).key
        CipherDriver { key.copyOf() }.open(File(dir, "budget.db").absolutePath).use { c ->
            c.execSQL("DROP TABLE `bill_month`")
            c.execSQL("ALTER TABLE `envelope_move` DROP COLUMN `for_month`")
            c.execSQL("PRAGMA user_version = 15")
        }
        val n = ok(DesktopDatabase.open(dir, clock = { 2_000L }))
        assertEquals(16L, n.store.raw { it.query("PRAGMA user_version").single()[0] as Long })
        assertEquals(3, n.store.dao.billsNow().size)
        assertTrue(n.store.dao.billMonthsNow().isEmpty())
        val bill = n.store.dao.billsNow().first()
        net.darkrelay.deaddropfinance.domain.BillActions(n.store).setMonthAmount(bill.id, java.time.LocalDate.of(2030, 11, 28), 123_45)
        assertEquals(123_45L, n.store.dao.billMonthsNow().single().amount)
        n.store.db.close()
    }
}
