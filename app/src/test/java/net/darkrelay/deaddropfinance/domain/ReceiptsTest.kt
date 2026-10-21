package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.data.ReceiptKind
import net.darkrelay.deaddropfinance.data.SortedBy
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import net.darkrelay.deaddropfinance.domain.Receipts.Draft
import net.darkrelay.deaddropfinance.domain.Receipts.DraftItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Receipts against a real (in-memory) database: matching, split by items, item returns, delete. Invented data only. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReceiptsTest {

    private val d = LocalDate.of(2030, 7, 10)
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private class W(val store: BudgetStore, val a: BudgetActions, val r: Receipts, val images: ReceiptImages, val s: Sorting) {
        lateinit var chk: String
        lateinit var food: String
        lateinit var home: String
        suspend fun bank(desc: String, amount: Long, date: LocalDate, pending: Boolean = false): String = store.write {
            val t = put(null, TransactionEntity(accountId = chk, date = date, payee = desc, amount = amount, importDescription = desc, source = TransactionSource.SYNC, pending = pending, createdAt = now, updatedAt = now))
            put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = amount, createdAt = now, updatedAt = now))
            t.id
        }
        suspend fun receipt(store: String, total: Long, date: LocalDate, tax: Long = 0, items: List<DraftItem> = emptyList(), kind: ReceiptKind = ReceiptKind.PURCHASE, pages: Int = 1): String {
            val id = net.darkrelay.deaddropfinance.data.newId()
            repeat(pages) { images.write(id, it, "invented image $it".toByteArray()) }
            return r.save(id, Draft(store, date, total, tax, items, kind, pages = pages))
        }
        suspend fun b() = a.snapshot()
        suspend fun rec(id: String) = store.dao.receipt(id)!!
        suspend fun lines(id: String) = store.dao.linesOf(id)
    }

    private fun world(block: suspend W.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        var tick = 1_000L
        try {
            val store = BudgetStore(db) { ++tick }
            val key = ByteArray(32) { (it * 7).toByte() }
            val images = ReceiptImages(Files.createTempDirectory("ddf-rcpt").toFile(), { key })
            val actions = BudgetActions(store, images)
            val w = W(store, actions, Receipts(store, actions, images) { d.plusDays(2) }, images, Sorting(store, actions))
            runBlocking {
                store.ensureSystemEnvelopes()
                w.chk = actions.saveAccount(null, "Checking", AccountType.BANK, 900_00, d.minusDays(30))
                w.food = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET))
                w.home = actions.saveEnvelope(null, BudgetActions.EnvelopeDraft("Home", EnvelopeMode.FILL_TO_TARGET))
                actions.assign(w.food, 300_00)
                actions.assign(w.home, 300_00)
                w.block()
            }
        } finally {
            db.close()
        }
    }

    // ---- matching ------------------------------------------------------------------------------------------------

    @Test fun anExactMatchIsAttachedByItself() = world {
        val t = bank("SAMPLE MARKET #12", -42_18, d.plusDays(2))
        bank("OTHER PLACE", -42_18, d.minusDays(20))                       // same amount, outside the dates
        val id = receipt("Sample Market", 42_18, d)
        val dec = r.match(id)
        assertTrue(dec is ReceiptMatch.Decision.One)
        assertEquals(t, rec(id).transactionId)
    }

    @Test fun aTipOrFuelHoldIsAllowedOnlyWhereItCanHappen() = world {
        val dinner = bank("SAMPLE GRILL", -25_00, d.plusDays(1))
        val id = receipt("Sample Grill", 21_00, d)
        val c = r.candidates(id).single()
        assertEquals(ReceiptMatch.Fit.TIP, c.fit)
        assertTrue(r.match(id) is ReceiptMatch.Decision.One)
        assertEquals(dinner, rec(id).transactionId)
        // a shop doesn't get a tip: no match, it waits
        bank("SAMPLE BOOKSHOP", -25_00, d.plusDays(1))
        val book = receipt("Sample Bookshop", 21_00, d)
        assertEquals(ReceiptMatch.Decision.None, r.match(book))
        assertNull(rec(book).transactionId)
        // too much more than a tip
        bank("SAMPLE DINER", -40_00, d.plusDays(1))
        assertTrue(r.candidates(receipt("Sample Diner", 21_00, d)).none { it.tx.payee == "SAMPLE DINER" })
    }

    @Test fun postingDelaysAndPendingCount() = world {
        val late = bank("SAMPLE HARDWARE", -15_99, d.plusDays(4), pending = true)
        val id = receipt("Sample Hardware", 15_99, d)
        assertEquals(late, (r.match(id) as ReceiptMatch.Decision.One).c.tx.id)
        bank("SAMPLE HARDWARE", -16_99, d.plusDays(ReceiptMatch.DAYS_AFTER + 1))
        assertTrue(r.candidates(receipt("Sample Hardware", 16_99, d)).isEmpty())
    }

    @Test fun severalPossibleOnesAskAndNoneThenLaterSyncAttaches() = world {
        bank("SAMPLE CAFE", -9_50, d)
        bank("ANOTHER CAFE", -9_50, d.plusDays(1))
        val id = receipt("Unreadable", 9_50, d)
        val dec = r.match(id)
        assertTrue(dec is ReceiptMatch.Decision.Several)
        assertEquals(2, (dec as ReceiptMatch.Decision.Several).list.size)
        // two exact ones, one at the right store: that one is clear
        val id2 = receipt("Sample Cafe", 9_50, d)
        assertEquals("SAMPLE CAFE", (r.match(id2) as ReceiptMatch.Decision.One).c.tx.payee)
        // none yet: waits; the charge syncs in later and it's attached then
        val waiting = receipt("Sample Pharmacy", 12_34, d)
        assertEquals(ReceiptMatch.Decision.None, r.match(waiting))
        val later = bank("SAMPLE PHARMACY 0042", -12_34, d.plusDays(2))
        // the pharmacy one, and the unreadable one: with the other cafe charge taken, one exact charge is left for it
        assertEquals(2, r.matchWaiting())
        assertEquals(later, rec(waiting).transactionId)
        // a transaction keeps one receipt
        try { r.attach(id, later); fail() } catch (e: BudgetRuleException) { /* already has one */ }
    }

    // ---- split by items ------------------------------------------------------------------------------------------

    @Test fun splitByItemsSpreadsTaxAndTipInProportion() = world {
        val t = bank("SAMPLE SUPERSTORE", -66_00, d)
        val id = receipt(
            "Sample Superstore", 66_00, d, tax = 6_00,
            items = listOf(DraftItem("SAMPLE APPLES", amount = 20_00), DraftItem("SAMPLE SOAP", amount = 30_00), DraftItem("COUPON", amount = -5_00), DraftItem("SAMPLE BREAD", amount = 15_00)),
        )
        r.attach(id, t)
        val items = store.dao.itemsOf(id)
        val pick = mapOf(items[0].id to food, items[1].id to home, items[2].id to home, items[3].id to food)
        val shares = ItemSplit.shares(-66_00, items, 6_00, pick)
        assertEquals(listOf(food to 35_00L, home to 25_00L), shares.map { it.envelopeId to it.items })
        assertEquals(listOf(3_50L, 2_50L), shares.map { it.tax })
        assertEquals(1, images.pages(id))
        r.splitByItems(id, pick)
        assertEquals(mapOf(food to -38_50L, home to -27_50L), lines(t).associate { it.envelopeId to it.amount })
        assertEquals("the photo is kept after the split", 1, images.pages(id))
        assertEquals(1, rec(id).pages)
        assertEquals("the items stay", 4, store.dao.itemsOf(id).size)
        assertEquals(SortedBy.HAND, store.dao.transaction(t)!!.sortedBy)
        assertEquals(home, store.dao.itemsOf(id)[2].envelopeId)
        assertEquals(0L, b().sortedCheck)
        // a discount alone on an envelope can't work
        try { r.splitByItems(id, mapOf(items[0].id to food, items[1].id to food, items[2].id to home, items[3].id to food)); fail() } catch (e: BudgetRuleException) { /* expected */ }
    }

    @Test fun rulesSuggestAnEnvelopePerItem() = world {
        s.saveRule(null, Sorting.RuleDraft("sample soap", splits = listOf(Rules.Split(home, 1))))
        val id = receipt("Sample Superstore", 10_00, d, items = listOf(DraftItem("SAMPLE SOAP 2PK", amount = 6_00), DraftItem("SAMPLE TEA", amount = 4_00), DraftItem("DISCOUNT", amount = -1_00)))
        val items = store.dao.itemsOf(id)
        val sug = ItemSplit.suggestions(items, store.dao.rulesNow(), setOf(food, home), food)
        assertEquals(listOf(home, food, food), items.map { sug[it.id] })
    }

    // ---- returning items -----------------------------------------------------------------------------------------

    @Test fun returningSomeItemsPutsTheMoneyBackWhereEachWent() = world {
        val buy = bank("SAMPLE SUPERSTORE", -53_00, d)
        val id = receipt(
            "Sample Superstore", 53_00, d, tax = 3_00,
            items = listOf(DraftItem("SAMPLE TOWELS", 2000, 10_00, 20_00), DraftItem("SAMPLE SNACKS", amount = 30_00)),
        )
        r.attach(id, buy)
        val items = store.dao.itemsOf(id)
        r.splitByItems(id, mapOf(items[0].id to home, items[1].id to food))
        // one towel of two comes back: 10.00 plus its 0.60 tax share
        val back = bank("SAMPLE SUPERSTORE REFUND", 10_60, d.plusDays(3))
        r.returnItems(back, buy, mapOf(items[0].id to 1000))
        assertEquals(mapOf(home to 10_60L), lines(back).associate { it.envelopeId to it.amount })
        assertEquals(buy, store.dao.transaction(back)!!.returnOf)
        assertEquals(SortedBy.RETURN, store.dao.transaction(back)!!.sortedBy)
        // the second towel, then nothing left of it
        val back2 = bank("SAMPLE SUPERSTORE REFUND", 10_60, d.plusDays(4))
        r.returnItems(back2, buy, mapOf(items[0].id to 1000))
        val back3 = bank("SAMPLE SUPERSTORE REFUND", 10_60, d.plusDays(5))
        try { r.returnItems(back3, buy, mapOf(items[0].id to 1000)); fail() } catch (e: BudgetRuleException) { assertTrue(e.message!!.contains("left to return")) }
        // a refund that doesn't fit the items is refused
        try { r.returnItems(back3, buy, mapOf(items[1].id to 1000)); fail() } catch (e: BudgetRuleException) { assertTrue(e.message!!.contains("refund is")) }
        // not a return after all: the items can be returned again
        s.notAReturn(back2)
        r.returnItems(back3, buy, mapOf(items[0].id to 1000))
        assertEquals(0L, b().sortedCheck)
    }

    @Test fun aScannedReturnReceiptPicksTheItems() = world {
        val buy = bank("SAMPLE OUTLET", -60_00, d)
        val id = receipt("Sample Outlet", 60_00, d, items = listOf(DraftItem("SAMPLE LAMP", amount = 25_00), DraftItem("SAMPLE RUG", amount = 35_00)))
        r.attach(id, buy)
        s.sortByHand(buy, home)
        val credit = bank("SAMPLE OUTLET", 25_00, d.plusDays(6))
        val ret = receipt("Sample Outlet", 25_00, d.plusDays(6), items = listOf(DraftItem("Sample Lamp", amount = 25_00)), kind = ReceiptKind.RETURN)
        r.attach(ret, credit)
        val lamp = store.dao.itemsOf(id).first { it.name == "sample lamp" }
        assertEquals(mapOf(lamp.id to 1000L), r.preselect(credit, buy))
        r.returnItems(credit, buy, r.preselect(credit, buy))
        assertEquals("an item with no envelope follows the purchase", mapOf(home to 25_00L), lines(credit).associate { it.envelopeId to it.amount })
    }

    @Test fun withoutAReceiptThePurchaseLevelReturnStillWorks() = world {
        val buy = bank("SAMPLE SHOP", -30_00, d)
        s.sortByHand(buy, food)
        val credit = bank("SAMPLE SHOP REFUND", 10_00, d.plusDays(2))
        s.confirmReturn(credit, buy)
        assertEquals(food, lines(credit).single().envelopeId)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    @Test fun deletingATransactionSendsItsReceiptBackOrDeletesIt() = world {
        val t1 = bank("SAMPLE MARKET", -5_00, d)
        val r1 = receipt("Sample Market", 5_00, d, pages = 2)
        r.attach(r1, t1)
        a.deleteTransaction(t1)
        assertNull("back to the unmatched list", rec(r1).transactionId)
        assertEquals(2, images.pages(r1))
        val t2 = bank("SAMPLE MARKET", -6_00, d)
        val r2 = receipt("Sample Market", 6_00, d, items = listOf(DraftItem("SAMPLE THING", amount = 6_00)))
        r.attach(r2, t2)
        a.deleteTransaction(t2, deleteReceipt = true)
        assertTrue(rec(r2).deleted)
        assertTrue(store.dao.itemsOf(r2).isEmpty())
        assertEquals("its photos are gone", 0, images.pages(r2))
    }

    @Test fun deletingAnAccountDeletesItsReceiptsAndPhotos() = world {
        val t = bank("SAMPLE MARKET", -7_00, d)
        val id = receipt("Sample Market", 7_00, d, pages = 3)
        r.attach(id, t)
        val waiting = receipt("Sample Elsewhere", 1_00, d)
        a.deleteAccount(chk)
        assertTrue(rec(id).deleted)
        assertEquals(0, images.pages(id))
        assertFalse("an unmatched receipt isn't the account's", rec(waiting).deleted)
        assertEquals(1, images.pages(waiting))
    }

    @Test fun savingKeepsItemIdsAndChecksTheForm() = world {
        val id = receipt("Sample Market", 9_00, d, items = listOf(DraftItem("SAMPLE A", amount = 4_00), DraftItem("SAMPLE B", amount = 5_00)))
        val before = store.dao.itemsOf(id)
        r.save(id, Draft("Sample Market", d, 9_00, 0, listOf(DraftItem("SAMPLE A FIXED", amount = 4_00, id = before[0].id))))
        val after = store.dao.itemsOf(id)
        assertEquals(listOf(before[0].id), after.map { it.id })
        assertEquals("sample fixed", after[0].name)
        try { r.save(id, Draft("x", d, 1_00, 0, listOf(DraftItem(" ", amount = 1_00)))); fail() } catch (e: BudgetRuleException) { /* expected */ }
        assertTrue(store.dao.changes().any { it.entityType == "receipt_item" })
        assertEquals(LineBucket.UNSORTED, LineBucket.valueOf("UNSORTED"))
        assertEquals(listOf<LineDraft>(), emptyList<LineDraft>())
        File(".").exists()
    }
}
