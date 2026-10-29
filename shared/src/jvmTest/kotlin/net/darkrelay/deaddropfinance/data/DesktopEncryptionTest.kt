package net.darkrelay.deaddropfinance.data

import androidx.room.Room
import androidx.room.useReaderConnection
import net.darkrelay.deaddropfinance.desktopdb.CipherDriver
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The desktop budget's encryption (SQLCipher 4 format, key under Windows DPAPI) and the move of a plain 0.6.0 budget
 * into it. Temp folders, invented data and test keys only; the real DPAPI is used, for this Windows account.
 */
class DesktopEncryptionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 4, 1)
    private val plainHeader = "SQLite format 3\u0000".toByteArray()

    private fun ok(o: DesktopDatabase.Opened) = assertIs<DesktopDatabase.Opened.Ok>(o, (o as? DesktopDatabase.Opened.Unavailable)?.reason)

    private fun header(f: File) = f.inputStream().use { it.readNBytes(16) }

    private fun bytesOf(dir: File) = dir.listFiles()!!.filter { it.isFile }.associate { it.name to it.readBytes().toList() }

    /** Invented rows: an account, two envelopes, a split purchase, a move. */
    private suspend fun fill(store: BudgetStore): String {
        store.ensureSystemEnvelopes()
        store.ensureCategories()
        val a = BudgetActions(store)
        val acct = a.saveAccount(null, "Sample Checking", AccountType.BANK, 900_00, day)
        val food = a.saveEnvelope(null, EnvelopeDraft("Food", EnvelopeMode.FILL_TO_TARGET, target = 300_00, everyday = true), day)
        val gas = a.saveEnvelope(null, EnvelopeDraft("Gas", EnvelopeMode.FILL_TO_TARGET, target = 80_00), day)
        a.move(null, food, 300_00, date = day)
        a.move(null, gas, 80_00, date = day)
        a.saveTransaction(null, TransactionDraft(acct, day, "Sample Market", "", -45_50, listOf(LineDraft(food, -40_00), LineDraft(gas, -5_50))))
        return acct
    }

    private suspend fun summary(store: BudgetStore): String {
        val b = BudgetActions(store).snapshot()
        return "accounts=${b.accounts.map { it.name to b.accountBalance(it.id) }} envelopes=${b.fillOrder.map { it.name to b.envelopeBalance(it.id) }} " +
            "rta=${b.readyToAssign} txs=${b.transactions.size} lines=${b.lines.size} moves=${b.moves.size} changes=${store.dao.changes().size}"
    }

    /** A plain budget exactly as 0.6.0 made it: Room on unencrypted SQLite, in budget.db. */
    private fun plainBudget(dir: File): String = runBlocking {
        val db = Room.databaseBuilder<AppDatabase>(name = File(dir, "budget.db").absolutePath).setDriver(CipherDriver(null)).build()
        val store = BudgetStore(db) { 1_000 }
        fill(store)
        summary(store).also { db.close() }
    }

    @Test fun aNewBudgetIsEncryptedAndReopensAfterARestart() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val first = ok(DesktopDatabase.open(dir, clock = { 1_000L }))
        fill(first.store)
        val before = summary(first.store)
        first.store.db.close()
        val db = File(dir, "budget.db")
        assertFalse(header(db).contentEquals(plainHeader), "no plain SQLite header: the file is encrypted")
        val key = File(dir, "budget.key").readBytes()
        assertTrue(String(key, 0, 8, Charsets.US_ASCII) == "DDFKEY1\n" && key.size > 100, "the key file holds a DPAPI blob")
        // the file doesn't open as plain SQLite
        val plain = runCatching { java.sql.DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next() } } } }
        assertTrue(plain.isFailure, "without the key it isn't readable")
        // a restart: same data, same schema as the phone
        val second = ok(DesktopDatabase.open(dir, clock = { 2_000L }))
        assertFalse(second.migrated)
        assertEquals(before, summary(second.store))
        val (v, hash) = second.store.db.useReaderConnection { c ->
            c.usePrepared("PRAGMA user_version") { it.step(); it.getLong(0) } to c.usePrepared("SELECT identity_hash FROM room_master_table") { it.step(); it.getText(0) }
        }
        assertEquals(12L, v)
        assertEquals("80de05f7f93425e5cb834536302f3ade", hash)
        second.store.db.close()
    }

    @Test fun aMissingKeyIsReportedAndNothingIsReplaced() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        ok(DesktopDatabase.open(dir)).also { fill(it.store); it.store.db.close() }
        File(dir, "budget.key").delete()
        listOf("-wal", "-shm").forEach { File(dir, "budget.db$it").delete() }
        val files = bytesOf(dir)
        val o = assertIs<DesktopDatabase.Opened.Unavailable>(DesktopDatabase.open(dir))
        assertTrue(o.reason.contains("key"), o.reason)
        assertEquals(files.keys, bytesOf(dir).keys, "no new key file, nothing new at all")
        assertEquals(files, bytesOf(dir), "the budget file is untouched")
    }

    @Test fun aWrongKeyIsReportedAndNothingIsReplaced() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        ok(DesktopDatabase.open(dir)).also { fill(it.store); it.store.db.close() }
        listOf("-wal", "-shm").forEach { File(dir, "budget.db$it").delete() }
        // another budget's key, valid for this Windows account but not this file
        val other = tmp.newFolder("Other")
        ok(DesktopDatabase.open(other)).store.db.close()
        File(other, "budget.key").copyTo(File(dir, "budget.key"), overwrite = true)
        val files = bytesOf(dir)
        val o = assertIs<DesktopDatabase.Opened.Unavailable>(DesktopDatabase.open(dir))
        assertTrue(o.reason.contains("key"), o.reason)
        assertEquals(files, bytesOf(dir).filterKeys { it in files }, "the budget and the key file are untouched")
        // a key file DPAPI can't unlock (damaged, or another Windows account's)
        val broken = File(dir, "budget.key").readBytes().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        File(dir, "budget.key").writeBytes(broken)
        val o2 = assertIs<DesktopDatabase.Opened.Unavailable>(DesktopDatabase.open(dir))
        assertTrue(o2.reason.contains("key"), o2.reason)
        assertTrue(File(dir, "budget.key").readBytes().contentEquals(broken))
    }

    @Test fun aPlainBudgetFrom060MovesIntoAnEncryptedOne() = runBlocking<Unit> {
        val dir = tmp.newFolder("DeadDropFinance")
        val before = plainBudget(dir)
        assertTrue(header(File(dir, "budget.db")).contentEquals(plainHeader))
        val o = ok(DesktopDatabase.open(dir, clock = { 3_000L }))
        assertTrue(o.migrated)
        assertEquals(before, summary(o.store), "every row came across")
        o.store.db.close()
        assertFalse(header(File(dir, "budget.db")).contentEquals(plainHeader), "budget.db is now encrypted")
        assertEquals(setOf("budget.db", "budget.key"), dir.list()!!.filter { !it.endsWith("-wal") && !it.endsWith("-shm") && !it.endsWith(".lck") }.toSet(),
            "the plain file and the working copy are gone")
        // and it opens again as an ordinary encrypted budget
        val again = ok(DesktopDatabase.open(dir, clock = { 4_000L }))
        assertFalse(again.migrated)
        assertEquals(before, summary(again.store))
        again.store.db.close()
    }

    @Test fun aFailedMoveKeepsThePlainFileAndOpensNothing() = runBlocking<Unit> {
        // the copy is made but doesn't check out: the plain file stays, the copy goes
        val dir = tmp.newFolder("DeadDropFinance")
        plainBudget(dir)
        val db = File(dir, "budget.db")
        val plainBytes = db.readBytes()
        val key = assertIs<DesktopKey.Result.Ok>(DesktopKey(File(dir, "budget.key")).create()).key
        val reason = assertNotNull(DesktopDatabase.migratePlain(db, key, failAfterCopy = true))
        assertTrue(reason.contains("kept"), reason)
        assertTrue(db.readBytes().contentEquals(plainBytes), "the plain budget is exactly as it was")
        assertEquals(setOf("budget.db", "budget.key"), dir.list()!!.filter { !it.endsWith(".lck") }.toSet(), "no half-made copy is left (Room's lock file aside)")

        // a damaged plain file: opening reports it, nothing is opened, and the file is never removed
        val dir2 = tmp.newFolder("Damaged")
        val damaged = plainHeader + ByteArray(8192) { (it * 31).toByte() }
        File(dir2, "budget.db").writeBytes(damaged)
        val o = assertIs<DesktopDatabase.Opened.Unavailable>(DesktopDatabase.open(dir2))
        assertTrue(o.reason.contains("kept"), o.reason)
        assertTrue(File(dir2, "budget.db").readBytes().contentEquals(damaged))
        assertFalse(File(dir2, "budget.db.encrypting").exists())
        assertFalse(File(dir2, "budget.db.plain").exists())
    }

    @Test fun theRawKeyTextIsSqlCiphersForm() {
        val k = ByteArray(32) { it.toByte() }
        // x' then two lowercase hex digits per byte, then ': the phone's DbKeys.rawKeyPassword form
        assertEquals("x'" + (0 until 32).joinToString("") { "%02x".format(it) } + "'", DesktopKey.rawKeyText(k))
        assertEquals(67, DesktopKey.rawKeyText(k).length)
    }
}
