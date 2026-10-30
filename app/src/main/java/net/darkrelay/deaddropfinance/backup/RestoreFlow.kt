package net.darkrelay.deaddropfinance.backup

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * Restore on the phone (1.7.3), kept apart from the screen so it can be tested without one.
 *
 * The picked file is read once, as soon as it's picked, into a copy in the app's own storage ([copyPicked]); every later
 * step (which key opens it, Take over editing, the warning, the restore, the upload) uses that copy, never the picker's
 * address again. The restore itself ([run]) does no screen work: logged in, ServerSync.restoreAsMaster runs it on a
 * background thread, and 1.7.2's restore touched the window from there (the settings it brought back), which Android
 * refuses; that was the "That file couldn't be read" on every logged-in restore. Errors say what actually failed and
 * never name the file, its path, a key or anything in it.
 */
class RestoreFlow(
    /** Replaces this phone's budget with the backup in the file (BackupException: nothing changed). */
    private val restoreLocal: suspend (File, BackupSecret) -> Backups.Summary,
    /** Logged in: ServerSync.restoreAsMaster, which runs the local restore inside and then makes it the server's budget. */
    private val asMaster: (suspend (suspend () -> Unit) -> Unit)? = null,
) {
    sealed interface Result {
        /** [toServer]: the server's budget is now this backup too. */
        data class Done(val summary: Backups.Summary, val toServer: Boolean) : Result
        /** [changedHere]: this phone's budget was replaced although the step after it failed (the screen reloads the settings then). */
        data class Failed(val message: String, val changedHere: Boolean) : Result
    }

    suspend fun run(file: File, opener: BackupSecret): Result {
        var summary: Backups.Summary? = null
        return try {
            val local: suspend () -> Unit = { summary = restoreLocal(file, opener) }
            if (asMaster != null) asMaster.invoke(local) else local()
            Result.Done(summary!!, toServer = asMaster != null)
        } catch (e: BackupException) {
            Result.Failed(e.message ?: DAMAGED, changedHere = false)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val here = summary != null
            when {
                // the copy is in the app's own storage, so a read error there means the file itself is cut short or damaged
                !here && e is IOException -> Result.Failed(DAMAGED, changedHere = false)
                // a refusal before the restore (another device editing, the server can't back up) or after it (the upload)
                e is net.darkrelay.deaddropfinance.serversync.ServerException || e is net.darkrelay.deaddropfinance.serversync.ServerClient.RpcError ->
                    Result.Failed(e.message ?: "The server refused.", here)
                else -> Result.Failed(
                    "The restore stopped on an unexpected problem (${e::class.simpleName}). " +
                        if (here) "This phone has the backup's budget now, but the server doesn't have it yet." else "Nothing was changed.",
                    here,
                )
            }
        }
    }

    companion object {
        const val NO_PERMISSION = "The app wasn't allowed to read that file. Pick it again in the file picker. Nothing was changed."
        const val UNREADABLE = "That file couldn't be read where it is: it may have been moved, or its storage isn't available. Pick it again. Nothing was changed."
        const val EMPTY = "That file is empty, so it isn't a Dead Drop Finance backup. Nothing was changed."
        const val NOT_A_BACKUP = "That isn't a Dead Drop Finance backup file, or it was made by a newer version. Nothing was changed."
        const val DAMAGED = "The backup file is damaged. Nothing was changed."

        /**
         * Reads the picked file once, whole, into [dest] (in the app's own storage, readable only by the app), right after
         * it's picked, while the picker's read permission is fresh. Throws BackupException with what failed.
         */
        fun copyPicked(open: () -> InputStream?, dest: File): File {
            dest.parentFile?.mkdirs()
            val part = File(dest.parentFile, dest.name + ".part")
            try {
                val input = open() ?: throw BackupException(UNREADABLE)
                input.use { i -> part.outputStream().use { o -> i.copyTo(o) } }
                if (part.length() == 0L) throw BackupException(EMPTY)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw BackupException(UNREADABLE)
                return dest
            } catch (e: BackupException) {
                part.delete(); throw e
            } catch (e: SecurityException) {
                part.delete(); throw BackupException(NO_PERMISSION)
            } catch (e: FileNotFoundException) {
                part.delete(); throw BackupException(UNREADABLE)
            } catch (e: IOException) {
                part.delete(); throw BackupException(UNREADABLE)
            }
        }

        /** The first bytes of the copy: what it opens with and which recovery key made it (BackupCrypto.kindOf, keyIdOf). */
        fun head(file: File): ByteArray = file.inputStream().use { it.readNBytes(BackupCrypto.HEAD_BYTES) }
    }
}
