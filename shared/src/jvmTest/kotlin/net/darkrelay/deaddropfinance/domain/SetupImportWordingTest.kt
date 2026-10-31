package net.darkrelay.deaddropfinance.domain

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.backup.BackupCrypto
import net.darkrelay.deaddropfinance.backup.BackupException
import net.darkrelay.deaddropfinance.backup.BackupSecret
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The shared messages say "the app" on the phone and "the program" on the computer (0.14.0): the setup import's errors
 * (read and checked), and a backup from a newer version. The phone's own wording is the default, unchanged.
 */
class SetupImportWordingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val rollover = """{ "format": "deaddropfinance-setup", "version": 1, "envelopes": [ { "name": "Rollover", "mode": "fill" } ] }"""
    private val missing = """{ "format": "deaddropfinance-setup", "version": 1, "bills": [ { "name": "Sample Phone", "amount": "10.00", "due": "2030-05-02", "envelope": "Phone" } ], "accounts": [ { "name": "Sample Nowhere", "showInActivity": true } ] }"""

    @Test fun theFilesOwnErrorsNameTheSideTheyreOn() {
        val app = (SetupImport.parse(rollover) as SetupImport.Parsed.Bad).errors
        assertTrue(app.any { it.contains("Rollover is made by the app and can't be imported.") }, app.toString())
        val program = (SetupImport.parse(rollover, "program") as SetupImport.Parsed.Bad).errors
        assertTrue(program.any { it.contains("Rollover is made by the program and can't be imported.") }, program.toString())
        assertTrue(program.none { it.contains("the app") }, program.toString())
    }

    @Test fun theChecksAgainstTheBudgetNameTheSideTheyreOn() = runBlocking<Unit> {
        val o = assertIs<DesktopDatabase.Opened.Ok>(DesktopDatabase.open(tmp.newFolder("b"), clock = { 1_000L }))
        o.store.ensureSystemEnvelopes(); o.store.ensureCategories()
        val plan = (SetupImport.parse(missing) as SetupImport.Parsed.Ok).plan
        val app = (SetupImporter(o.store).check(plan) as SetupImporter.Checked.Bad).errors
        assertEquals(listOf(
            "bills (\"Sample Phone\"): there's no envelope called \"Phone\" in the file or the app.",
            "accounts (\"Sample Nowhere\"): there's no account called \"Sample Nowhere\" in the app. The importer doesn't make accounts.",
        ).sorted(), app.sorted())
        val program = (SetupImporter(o.store, product = "program").check(plan) as SetupImporter.Checked.Bad).errors
        assertEquals(app.map { it.replace("the app", "the program") }.sorted(), program.sorted())
        o.store.db.close()
    }

    @Test fun aBackupFromANewerVersionNamesTheSide() {
        val newer = "DDFBAK".toByteArray() + byteArrayOf(9) + ByteArray(40)
        val app = assertFailsWith<BackupException> { BackupCrypto.decrypting(newer.inputStream(), BackupSecret.Key(ByteArray(32))) }
        assertEquals("This backup was made by a newer version of the app. Update the app, then restore.", app.message)
        val program = assertFailsWith<BackupException> { BackupCrypto.decrypting(newer.inputStream(), BackupSecret.Key(ByteArray(32)), "program") }
        assertEquals("This backup was made by a newer version of the program. Update the program, then restore.", program.message)
    }
}
