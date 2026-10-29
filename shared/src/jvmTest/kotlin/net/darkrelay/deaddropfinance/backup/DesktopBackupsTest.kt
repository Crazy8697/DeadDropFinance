package net.darkrelay.deaddropfinance.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import net.darkrelay.deaddropfinance.data.EnvelopeMode
import net.darkrelay.deaddropfinance.domain.BudgetActions
import net.darkrelay.deaddropfinance.domain.BudgetActions.EnvelopeDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.LineDraft
import net.darkrelay.deaddropfinance.domain.BudgetActions.TransactionDraft
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The desktop's encrypted backup and restore, in the phone's format. Temp folders, invented data, test passwords. */
class DesktopBackupsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val day = LocalDate.of(2030, 5, 1)
    private val rounds = 1000      // fewer PBKDF2 rounds than real backups, so the tests are quick

    private class Desk(val store: BudgetStore, val file: File) {
        val actions = BudgetActions(store)
        val backups = DesktopBackups(store.db, file, "desktop-test", { 1_000 })
    }

    private fun open(name: String): Desk = runBlocking {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder(name), clock = { 1_000L }))
        o.store.ensureSystemEnvelopes()
        o.store.ensureCategories()
        Desk(o.store, o.file)
    }

    private suspend fun Desk.fill(label: String) {
        val acct = actions.saveAccount(null, "$label Checking", AccountType.BANK, 500_00, day)
        val food = actions.saveEnvelope(null, EnvelopeDraft("$label Food", EnvelopeMode.FILL_TO_TARGET, target = 200_00), day)
        actions.move(null, food, 150_00, date = day)
        val cat = actions.snapshot().categories.first { it.name == "Groceries" }.id
        actions.saveTransaction(null, TransactionDraft(acct, day, "Sample Market", "", -40_00, listOf(LineDraft(food, -40_00, categoryId = cat))))
    }

    private suspend fun Desk.picture(): String {
        val b = actions.snapshot()
        return "accounts=${b.accounts.map { it.name to b.accountBalance(it.id) }} envelopes=${b.fillOrder.map { it.name to b.envelopeBalance(it.id) }} " +
            "txs=${b.transactions.map { it.payee to it.amount }} lines=${b.lines.size} moves=${b.moves.size} categories=${b.categories.size} rta=${b.readyToAssign}"
    }

    private suspend fun Desk.backup(pass: String): ByteArray = ByteArrayOutputStream().also { backups.write(it, pass.toCharArray(), rounds) }.toByteArray()

    @Test fun backupThenRestoreGivesBackTheSameBudget() = runBlocking<Unit> {
        val a = open("A")
        a.fill("Sample")
        val want = a.picture()
        val bytes = a.backup("correct horse battery")
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Sample Market"), "the file is encrypted")
        val b = open("B")
        b.fill("Other")
        assertNotEquals(want, b.picture())
        val s = b.backups.restore(ByteArrayInputStream(bytes), "correct horse battery".toCharArray())
        assertEquals(DesktopBackups.Summary(1, b.actions.snapshot().envelopes.size, 1), s)
        assertEquals(want, b.picture())
        assertFalse(File(b.file.path + DesktopBackups.BEFORE_RESTORE).exists(), "the copy is gone once the restore checked out")
        a.store.db.close(); b.store.db.close()
    }

    @Test fun aWrongPasswordChangesNothing() = runBlocking<Unit> {
        val a = open("A")
        a.fill("Sample")
        val bytes = a.backup("right password")
        val b = open("B")
        b.fill("Other")
        val before = b.picture()
        val e = assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(bytes), "wrong password".toCharArray()) }
        assertTrue(e.message!!.contains("Wrong password"), e.message)
        assertEquals(before, b.picture())
        a.store.db.close(); b.store.db.close()
    }

    @Test fun aDamagedOrCutShortFileChangesNothing() = runBlocking<Unit> {
        val a = open("A")
        a.fill("Sample")
        val bytes = a.backup("pw for tests")
        val b = open("B")
        b.fill("Other")
        val before = b.picture()
        val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(flipped), "pw for tests".toCharArray()) }
        assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(bytes.copyOf(bytes.size - 40)), "pw for tests".toCharArray()) }
        val notOne = "just some text, not a backup".toByteArray()
        val e = assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(notOne), "pw for tests".toCharArray()) }
        assertTrue(e.message!!.contains("isn't a Dead Drop Finance backup"), e.message)
        assertEquals(before, b.picture())
        a.store.db.close(); b.store.db.close()
    }

    @Test fun aRestoreThatFailsHalfwayKeepsTheCurrentBudget() = runBlocking<Unit> {
        val a = open("A")
        a.fill("Sample")
        val bytes = a.backup("pw for tests")
        val b = open("B")
        b.fill("Other")
        val before = b.picture()
        assertFailsWith<BackupException> { b.backups.restore(ByteArrayInputStream(bytes), "pw for tests".toCharArray(), failForTest = true) }
        assertEquals(before, b.picture(), "rolled back: the current budget is exactly as it was")
        assertFalse(File(b.file.path + DesktopBackups.BEFORE_RESTORE).exists())
        // and it still works afterwards
        b.backups.restore(ByteArrayInputStream(bytes), "pw for tests".toCharArray())
        assertEquals(a.picture(), b.picture())
        a.store.db.close(); b.store.db.close()
    }

    @Test fun aBackupKeepsThisComputersDeviceId() = runBlocking<Unit> {
        val a = open("A")
        a.fill("Sample")
        val bytes = a.backup("pw for tests")
        val b = open("B")
        val mine = b.store.deviceId()
        assertNotEquals(a.store.deviceId(), mine)
        b.backups.restore(ByteArrayInputStream(bytes), "pw for tests".toCharArray())
        assertEquals(mine, b.store.deviceId())
        a.store.db.close(); b.store.db.close()
    }
}
