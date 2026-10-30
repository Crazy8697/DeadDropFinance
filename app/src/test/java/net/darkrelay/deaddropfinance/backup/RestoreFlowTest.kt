package net.darkrelay.deaddropfinance.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.darkrelay.deaddropfinance.serversync.ServerException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 1.7.3: the phone's restore. 1.7.2 put screen work inside the restore, which logged in runs on a background thread, and
 * every logged-in restore ended in "That file couldn't be read" after the budget here had already been replaced. Now the
 * restore does no screen work, the picked file is read once into a copy, and each failure says what it was. No device,
 * no network; invented bytes.
 */
class RestoreFlowTest {

    @get:Rule val tmp = TemporaryFolder()

    private val summary = Backups.Summary(2, 3, 4, 0, 0)
    private val secret = BackupSecret.Key(ByteArray(32))

    /** As ServerSync.restoreAsMaster runs it: the local restore on a background thread, then (optionally) a failure after it. */
    private fun asMaster(after: Exception? = null): suspend (suspend () -> Unit) -> Unit = { local ->
        withContext(Dispatchers.IO) { local() }
        if (after != null) throw after
    }

    @Test fun loggedInTheRestoreRunsOnTheBackgroundThreadAndNothingElseRunsThere() = runBlocking {
        val threads = mutableListOf<String>()
        val flow = RestoreFlow({ _, _ -> threads += Thread.currentThread().name; summary }, asMaster())
        val r = flow.run(tmp.newFile(), secret)
        assertEquals(RestoreFlow.Result.Done(summary, toServer = true), r)
        assertEquals("only the restore itself ran inside", 1, threads.size)
        assertFalse("it ran off the caller's thread, so screen work there would fail", threads.single() == Thread.currentThread().name)
    }

    @Test fun loggedOutItIsLocalOnly() = runBlocking {
        val r = RestoreFlow({ _, _ -> summary }).run(tmp.newFile(), secret)
        assertEquals(RestoreFlow.Result.Done(summary, toServer = false), r)
    }

    @Test fun aBackupProblemSaysWhatItIsAndNothingChanged() = runBlocking {
        for (m in listOf("That recovery key doesn't open this backup: it was made with a different key.", "The backup file is damaged.",
                         "That isn't a Dead Drop Finance backup file.")) {
            val r = RestoreFlow({ _, _ -> throw BackupException(m) }, asMaster()).run(tmp.newFile(), secret)
            assertEquals(RestoreFlow.Result.Failed(m, changedHere = false), r)
        }
    }

    @Test fun aRefusalBeforeTheRestoreChangesNothingAndOneAfterItSaysSo() = runBlocking {
        var ran = false
        val before: suspend (suspend () -> Unit) -> Unit = { _ -> throw ServerException(ServerException.Kind.ERROR, "Test desktop is editing. Take over editing first, then restore.") }
        val r1 = RestoreFlow({ _, _ -> ran = true; summary }, before).run(tmp.newFile(), secret)
        assertEquals(RestoreFlow.Result.Failed("Test desktop is editing. Take over editing first, then restore.", changedHere = false), r1)
        assertFalse(ran)
        val after = ServerException(ServerException.Kind.ERROR, "The backup was restored on this device, but the server hasn't taken it yet: offline.")
        val r2 = RestoreFlow({ _, _ -> summary }, asMaster(after)).run(tmp.newFile(), secret)
        assertEquals(RestoreFlow.Result.Failed(after.message!!, changedHere = true), r2)
    }

    @Test fun anUnexpectedProblemIsNamedAndNeverClaimsNothingChangedWhenSomethingDid() = runBlocking {
        val r1 = RestoreFlow({ _, _ -> throw IllegalStateException("x") }, asMaster()).run(tmp.newFile(), secret) as RestoreFlow.Result.Failed
        assertTrue(r1.message, r1.message.contains("IllegalStateException") && r1.message.endsWith("Nothing was changed."))
        // 1.7.2's case: something failing after the budget here was replaced must not say nothing changed
        val r2 = RestoreFlow({ _, _ -> summary }, asMaster(IllegalStateException("x"))).run(tmp.newFile(), secret) as RestoreFlow.Result.Failed
        assertTrue(r2.changedHere)
        assertFalse(r2.message, r2.message.contains("Nothing was changed"))
        assertTrue(r2.message.contains("This phone has the backup's budget now"))
        // a read error from the app's own copy: the file itself is cut short or damaged
        val r3 = RestoreFlow({ _, _ -> throw IOException("cut") }).run(tmp.newFile(), secret)
        assertEquals(RestoreFlow.Result.Failed(RestoreFlow.DAMAGED, changedHere = false), r3)
    }

    @Test fun thePickedFileIsReadOnceIntoACopy() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        var opened = 0
        val dest = File(tmp.root, "restore/picked.ddfbak")
        val copy = RestoreFlow.copyPicked({ opened++; ByteArrayInputStream(bytes) }, dest)
        assertEquals(1, opened)
        assertArrayEquals(bytes, copy.readBytes())
        assertFalse("no partial file left", File(dest.parentFile, "picked.ddfbak.part").exists())
        assertArrayEquals(bytes.copyOf(BackupCrypto.HEAD_BYTES), RestoreFlow.head(copy))
        // a second pick replaces it
        RestoreFlow.copyPicked({ ByteArrayInputStream(byteArrayOf(1, 2, 3)) }, dest)
        assertArrayEquals(byteArrayOf(1, 2, 3), dest.readBytes())
    }

    @Test fun eachReadFailureSaysWhatItWasWithNoFileNameOrPath() {
        val dest = File(tmp.root, "restore/picked.ddfbak")
        fun fails(open: () -> InputStream?) = runCatching { RestoreFlow.copyPicked(open, dest) }.exceptionOrNull() as BackupException
        assertEquals(RestoreFlow.NO_PERMISSION, fails { throw SecurityException("Permission Denial: reading content://com.android.providers/document/123") }.message)
        assertEquals(RestoreFlow.UNREADABLE, fails { throw FileNotFoundException("/storage/emulated/0/Download/secret-name.ddfbak") }.message)
        assertEquals(RestoreFlow.UNREADABLE, fails { null }.message)
        assertEquals(RestoreFlow.UNREADABLE, fails { object : InputStream() { override fun read(): Int = throw IOException("gone") } }.message)
        assertEquals(RestoreFlow.EMPTY, fails { ByteArrayInputStream(ByteArray(0)) }.message)
        for (m in listOf(RestoreFlow.NO_PERMISSION, RestoreFlow.UNREADABLE, RestoreFlow.EMPTY, RestoreFlow.NOT_A_BACKUP, RestoreFlow.DAMAGED)) {
            assertFalse(m, m.contains("/") || m.contains("content:") || m.contains(".ddfbak"))
        }
        assertFalse("nothing left behind", dest.exists() || File(dest.parentFile, "picked.ddfbak.part").exists())
    }
}
