package net.darkrelay.deaddropfinance.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.AppDatabase
import net.darkrelay.deaddropfinance.data.BillRecurrence
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.data.LineBucket
import net.darkrelay.deaddropfinance.data.RuleAction
import net.darkrelay.deaddropfinance.data.SubscriptionStatus
import net.darkrelay.deaddropfinance.data.TransactionEntity
import net.darkrelay.deaddropfinance.data.TransactionLineEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The JSON setup importer: new names added, known names updated, nothing deleted, never transactions; a bad file or
 * field applies nothing and names the problem; the preview counts are right; docs/IMPORT.md's example imports.
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SetupImportTest {

    private val day = LocalDate.of(2030, 5, 2)

    private class W(val store: BudgetStore, val a: BudgetActions, val importer: SetupImporter) {
        lateinit var acct: String
        lateinit var food: String
        lateinit var keep: String
        suspend fun b() = a.snapshot()
        suspend fun changes() = store.dao.changes().size
    }

    private fun world(block: suspend W.() -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val store = BudgetStore(db)
            val a = BudgetActions(store)
            val w = W(store, a, SetupImporter(store, a) { day })
            runBlocking {
                store.ensureSystemEnvelopes()
                w.acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 500_00, day.minusDays(10))
                w.food = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Groceries", EnvelopeMode.FILL_TO_TARGET, target = 300_00))
                w.keep = a.saveEnvelope(null, BudgetActions.EnvelopeDraft("Sample Old Fund", EnvelopeMode.ADD_EVERY_PAYDAY, perPayday = 10_00))
                BillActions(store, a) { day }.saveBill(null, BillActions.BillDraft("Sample Power Co", 80_00, day.plusDays(7), envelopeId = w.food, autopay = true))
                Sorting(store, a).saveRule(null, Sorting.RuleDraft("sample market", splits = listOf(Rules.Split(w.keep, 1))))
                store.write {
                    val t = put(null, TransactionEntity(accountId = w.acct, date = day, payee = "SAMPLE MARKET", amount = -12_00, createdAt = now, updatedAt = now))
                    put(null, TransactionLineEntity(transactionId = t.id, bucket = LineBucket.UNSORTED, amount = -12_00, createdAt = now, updatedAt = now))
                }
                w.block()
            }
        } finally {
            db.close()
        }
    }

    private fun ok(text: String) = (SetupImport.parse(text) as? SetupImport.Parsed.Ok)?.plan ?: fail("not parsed: ${SetupImport.parse(text)}") as Nothing

    private val file = """
        {
          "format": "deaddropfinance-setup", "version": 1,
          "envelopes": [
            { "name": "groceries", "target": "420.00", "everyday": true },
            { "name": "Fuel", "mode": "fill_to_target", "target": 130, "everyday": true, "order": 1 },
            { "name": "Sample Loan", "mode": "debt", "perPayday": "50.00", "owed": "900.00", "requiredPayment": "35.00", "dueDate": "2030-05-17", "remind": true, "remindDays": 3 }
          ],
          "bills": [
            { "name": "Sample Power Co", "amount": "92.40", "amountChanges": true, "reminder": true },
            { "name": "Sample Car Cover", "amount": "118.00", "due": "2030-05-25", "repeats": "every_6_months", "envelope": "Fuel", "autopay": true, "payeeWords": "sample insurer" }
          ],
          "paydays": { "next": "2030-05-14" },
          "rules": [
            { "match": "Sample Market", "envelope": "Groceries" },
            { "match": "sample payroll", "direction": "in", "envelope": "Ready to assign" },
            { "match": "sample corner shop", "splits": [ { "envelope": "Groceries", "share": 3 }, { "envelope": "Fuel", "share": 1 } ], "alwaysAsk": true }
          ],
          "subscriptions": [ { "name": "Sample Stream", "payees": [ "sample stream" ], "bill": "Sample Car Cover" } ],
          "settings": { "autoApprove": true, "alerts": { "spending": false }, "parkingLotWait": "1 week" }
        }
    """.trimIndent()

    @Test fun addsNewUpdatesKnownNamesDeletesNothingAndNeverTouchesTransactions() = world {
        val txBefore = b().transactions.map { it.id to it.amount }
        val plan = ok(file)
        val p = importer.apply(plan)
        val b = b()
        // updated in place, fields the file left out kept
        val g = b.envelopes.single { it.name.equals("groceries", true) }
        assertEquals(food, g.id)
        assertEquals(420_00L, g.target)
        assertEquals(true, g.everyday)
        assertEquals(EnvelopeMode.FILL_TO_TARGET, g.mode)
        // added
        val fuel = b.envelopes.single { it.name == "Fuel" }
        assertEquals(130_00L, fuel.target)
        val loan = b.envelopes.single { it.name == "Sample Loan" }
        assertEquals(EnvelopeMode.DEBT, loan.mode)
        assertEquals(900_00L, loan.debtOwed)
        assertEquals(35_00L, loan.requiredPayment)
        assertEquals(LocalDate.of(2030, 5, 17), loan.dueDate)
        assertEquals(true, loan.reminder)
        assertEquals(3, loan.reminderDays)
        // order: Rollover first, then Fuel at 1
        assertEquals(listOf("Rollover", "Fuel"), b.fillOrder.take(2).map { it.name })
        // nothing deleted
        assertTrue(b.envelopes.any { it.id == keep && !it.deleted })
        // the bill updated: new amount and options, its due date, envelope and autopay kept
        val power = store.dao.billsNow().single { it.name == "Sample Power Co" }
        assertEquals(92_40L, power.amountDue)
        assertEquals(true, power.varies)
        assertEquals(true, power.reminder)
        assertEquals(day.plusDays(7), power.dueDate)
        assertEquals(food, power.envelopeId)
        assertEquals(true, power.autopay)
        val car = store.dao.billsNow().single { it.name == "Sample Car Cover" }
        assertEquals(BillRecurrence.SEMIANNUAL, car.recurrence)
        assertEquals(fuel.id, car.envelopeId)
        assertEquals("sample insurer", car.matchText)
        assertEquals(LocalDate.of(2030, 5, 14), BillActions(store, a) { day }.paydayCorrection())
        // rules: the known one now goes to Groceries; the new ones added
        val rules = store.dao.rulesNow()
        assertEquals(3, rules.size)
        assertEquals(listOf(Rules.Split(food, 1)), Rules.splits(rules.single { it.matchText == Payees.clean("sample market") }))
        assertEquals(listOf(Rules.Split(Rules.RTA, 1)), Rules.splits(rules.single { it.matchText == Payees.clean("sample payroll") }))
        val corner = rules.single { it.matchText == Payees.clean("sample corner shop") }
        assertEquals(RuleAction.ENVELOPE, corner.action)
        assertEquals(true, corner.alwaysAsk)
        assertEquals(listOf(Rules.Split(food, 3), Rules.Split(fuel.id, 1)), Rules.splits(corner))
        val sub = store.dao.subscriptionsNow().single()
        assertEquals("Sample Stream", sub.name)
        assertEquals(SubscriptionStatus.CONFIRMED, sub.status)
        assertEquals(car.id, sub.billId)
        assertEquals(7, ParkedBuys(store, a, { day }).defaultWait())
        // "alerts": { "spending" } was a spend alert of the notification reader (removed in 1.5.0): read, then ignored
        assertEquals(listOf("autoApprove", "parkingLotWait"), plan.settings!!.named())
        // transactions untouched
        assertEquals(txBefore, b().transactions.map { it.id to it.amount })
        assertEquals(6, p.addCount)
        assertEquals(3, p.updateCount)
        // a second import of the same file only updates
        val again = (importer.check(ok(file)) as SetupImporter.Checked.Ok).preview
        assertEquals(0, again.addCount)
        assertEquals(9, again.updateCount)
    }

    @Test fun thePreviewCountsWhatWillBeAddedAndUpdated() = world {
        val before = changes()
        val p = (importer.check(ok(file)) as SetupImporter.Checked.Ok).preview
        assertEquals(mapOf("Envelopes" to listOf("Fuel", "Sample Loan"), "Bills" to listOf("Sample Car Cover"), "Rules" to listOf("sample payroll", "sample corner shop"), "Subscriptions" to listOf("Sample Stream")), p.add)
        assertEquals(mapOf("Envelopes" to listOf("groceries"), "Bills" to listOf("Sample Power Co"), "Rules" to listOf("Sample Market")), p.update)
        assertEquals(LocalDate.of(2030, 5, 14), p.nextPayday)
        assertEquals("checking changes nothing", before, changes())
    }

    @Test fun transactionsInTheFileAreRefused() {
        val bad = SetupImport.parse("""{ "format": "deaddropfinance-setup", "version": 1, "transactions": [ { "payee": "SAMPLE", "amount": "1.00" } ] }""")
        bad as SetupImport.Parsed.Bad
        assertTrue(bad.errors.single().contains("never imports transactions"))
    }

    @Test fun aBadFileOrFieldAppliesNothingAndNamesTheProblem() = world {
        val before = changes()
        fun errors(text: String) = (SetupImport.parse(text) as SetupImport.Parsed.Bad).errors
        assertTrue(errors("{ not json").single().startsWith("This isn't a JSON file"))
        assertTrue(errors("""{ "version": 1 }""").contains("\"format\" must be \"deaddropfinance-setup\"."))
        val e = errors(
            """{ "format": "deaddropfinance-setup", "version": 1,
                 "envelopes": [ { "name": "Groceries", "target": "lots" }, { "name": "Rollover", "mode": "fill_to_target" }, { "name": "X", "mode": "sideways", "colour": "red" } ],
                 "bills": [ { "name": "Sample Phone", "amount": "40.00", "due": "next tuesday" } ],
                 "rules": [ { "match": "sample shop" } ],
                 "settings": { "syncEveryHours": 7 } }""",
        )
        assertTrue(e.toString(), "envelopes[1] (\"Groceries\"): \"target\" must be an amount like \"12.34\"." in e)
        assertTrue(e.toString(), "envelopes[2] (\"Rollover\"): Rollover is made by the app and can't be imported." in e)
        assertTrue(e.toString(), e.any { it.startsWith("envelopes[3] (\"X\"): \"mode\" must be one of") })
        assertTrue(e.toString(), "envelopes[3] (\"X\"): \"colour\" isn't a field here." in e)
        assertTrue(e.toString(), "bills[1] (\"Sample Phone\"): \"due\" must be a date like \"2030-05-17\"." in e)
        assertTrue(e.toString(), "rules[1] (\"sample shop\"): give an \"envelope\", \"splits\", a \"category\", or \"transfer\": true." in e)
        // bank sync's settings went with it in 1.3.0
        assertTrue(e.toString(), "settings: \"syncEveryHours\" isn't a field here." in e)
        // a name that's in neither the file nor the app: checked against the app, refused, nothing applied
        val plan = ok("""{ "format": "deaddropfinance-setup", "version": 1, "envelopes": [ { "name": "Fuel", "mode": "fill_to_target" } ], "bills": [ { "name": "Sample Phone", "amount": "40.00", "due": "2030-05-20", "envelope": "Phone" } ] }""")
        val c = importer.check(plan) as SetupImporter.Checked.Bad
        assertEquals(listOf("bills (\"Sample Phone\"): there's no envelope called \"Phone\" in the file or the app."), c.errors)
        try { importer.apply(plan); fail() } catch (x: BudgetRuleException) { /* expected */ }
        assertEquals("nothing applied", before, changes())
        assertNull(b().envelopes.firstOrNull { it.name == "Fuel" })
    }

    @Test fun theExampleInImportMdImports() = world {
        val doc = listOf(File("../docs/IMPORT.md"), File("docs/IMPORT.md")).first { it.isFile }.readText()
        val json = SetupExample.extract(doc)!!
        val plan = ok(json)
        val c = importer.check(plan)
        assertTrue(c.toString(), c is SetupImporter.Checked.Ok)
        importer.apply(plan)
        assertEquals(6 + 1, b().envelopes.count { it.name in setOf("Groceries", "Fuel", "Power", "Rainy Day", "Weekend Trip", "Sample Card Catch-up", "Sample Old Fund") })
        // the example's categories and claims came in with it
        val b = b()
        val groceries = b.envelopes.first { it.name == "Groceries" }.id
        assertEquals(setOf("Groceries", "Snacks"), b.claimsOf(groceries).map { it.name }.toSet())
        assertEquals(setOf("Car gas"), b.claimsOf(b.envelopes.first { it.name == "Fuel" }.id).map { it.name }.toSet())
        assertEquals(b.categories.first { it.name == "Eating out" }.id, store.dao.rulesNow().first { it.matchText == "sample pizza" }.categoryId)
    }

    @Test fun categoriesAndClaimsImportAndNewNamesAreCreated() = world {
        store.ensureCategories()
        val plan = ok("""{ "format": "deaddropfinance-setup", "version": 1,
            "envelopes": [ { "name": "Food", "mode": "fill_to_target", "categories": [ "Groceries", "Brand New Category" ] } ],
            "rules": [ { "match": "sample cafe", "category": "Eating out" }, { "match": "sample gift shop", "envelope": "Food", "category": "Gifts" } ] }""")
        val c = importer.check(plan) as SetupImporter.Checked.Ok
        assertEquals(listOf("Brand New Category"), c.preview.add["Categories"])
        importer.apply(plan)
        val b = b()
        val food = b.envelopes.first { it.name == "Food" }.id
        assertEquals(setOf("Groceries", "Brand New Category"), b.claimsOf(food).map { it.name }.toSet())
        val cafe = store.dao.rulesNow().first { it.matchText == "sample cafe" }
        assertEquals(b.categories.first { it.name == "Eating out" }.id, cafe.categoryId)
        assertEquals("", cafe.splits)
        val gift = store.dao.rulesNow().first { it.matchText == "sample gift shop" }
        assertEquals(b.categories.first { it.name == "Gifts" }.id, gift.categoryId)
        assertTrue(Rules.splits(gift).single().target == food)
        // a category two envelopes both claim, or one for money in, is refused whole
        val twice = ok("""{ "format": "deaddropfinance-setup", "version": 1, "envelopes": [ { "name": "A", "mode": "fill_to_target", "categories": [ "Rent" ] }, { "name": "B", "mode": "fill_to_target", "categories": [ "Rent" ] } ] }""")
        assertTrue(importer.check(twice) is SetupImporter.Checked.Bad)
        val taken = ok("""{ "format": "deaddropfinance-setup", "version": 1, "envelopes": [ { "name": "Other", "mode": "fill_to_target", "categories": [ "Groceries" ] } ] }""")
        val bad = importer.check(taken) as SetupImporter.Checked.Bad
        assertTrue(bad.errors.toString(), bad.errors.any { it.contains("already claimed by \"Food\"") })
        val rin = SetupImport.parse("""{ "format": "deaddropfinance-setup", "version": 1, "rules": [ { "match": "sample", "category": "Gifts", "direction": "in" } ] }""")
        assertTrue(rin is SetupImport.Parsed.Bad)
    }

    @Test fun accountsSetShowInActivityOnlyAndAreNeverMade() = world {
        val plan = ok("""{ "format": "deaddropfinance-setup", "version": 1, "accounts": [ { "name": "sample checking", "showInActivity": false } ] }""")
        val c = importer.check(plan) as SetupImporter.Checked.Ok
        assertEquals(listOf("sample checking"), c.preview.update["Accounts"])
        val before = b().accounts.single()
        importer.apply(plan)
        val after = b().accounts.single()
        assertEquals(false, after.showInActivity)
        assertEquals(before.copy(showInActivity = false, updatedAt = after.updatedAt), after)
        val unknown = ok("""{ "format": "deaddropfinance-setup", "version": 1, "accounts": [ { "name": "Sample Nowhere", "showInActivity": true } ] }""")
        assertTrue(importer.check(unknown) is SetupImporter.Checked.Bad)
        assertTrue(SetupImport.parse("""{ "format": "deaddropfinance-setup", "version": 1, "accounts": [ { "name": "X", "colour": "red" } ] }""") is SetupImport.Parsed.Bad)
    }
}
