package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.util.concurrent.TimeUnit

/**
 * One program per Windows user (0.12.3). The first instance holds an operating-system lock on instance.lock in the
 * program's settings folder; a second launch finds it held, leaves instance.focus there to ask the first one to come to
 * the front, and exits without opening anything. The lock belongs to the process: when it exits, or crashes, or is
 * killed, Windows lets it go, so a lock file left behind never stops the program from opening. The lock lives with the
 * settings, so a copy run with other settings (the isolated test installs, the tests' temp folders) has a lock of its
 * own. After an update (--after-update, from Setup's relaunch) the new instance waits for the old one's lock instead of
 * handing over to it.
 */
class SingleInstance(private val dir: File) {
    private val lockFile = File(dir, LOCK)
    private val focusFile = File(dir, FOCUS)
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    @Volatile private var watcher: Thread? = null

    /** Takes the lock, waiting up to [waitMs] for another instance to let it go. False: another instance holds it. */
    fun tryAcquire(waitMs: Long = 0): Boolean {
        dir.mkdirs()
        val end = System.currentTimeMillis() + waitMs
        while (true) {
            val ch = RandomAccessFile(lockFile, "rw").channel
            val got = try { ch.tryLock() } catch (e: OverlappingFileLockException) { null } catch (e: java.io.IOException) { null }
            if (got != null) {
                channel = ch; lock = got
                focusFile.delete()      // a request left from before this instance started isn't for it
                return true
            }
            ch.close()
            if (System.currentTimeMillis() >= end) return false
            Thread.sleep(200)
        }
    }

    enum class Claim { FIRST, SECOND }

    /**
     * A launch: FIRST (this instance holds the lock and opens) or SECOND (a live instance was asked to come to the front;
     * this one exits). When the lock is held but nobody takes the request (the holder crashed or is still exiting, and
     * Windows hasn't let its lock go yet), this one waits for the lock and opens. [afterUpdate]: Setup's relaunch, which
     * waits for the old copy's lock instead of handing over to it.
     */
    fun claim(afterUpdate: Boolean = false): Claim {
        if (tryAcquire(if (afterUpdate) AFTER_UPDATE_WAIT_MS else 0)) return Claim.FIRST
        askFirstToShow()
        val end = System.currentTimeMillis() + HANDOVER_MS
        while (System.currentTimeMillis() < end) {
            if (!focusFile.exists()) return Claim.SECOND      // the holder took the request
            Thread.sleep(100)
        }
        focusFile.delete()
        return if (tryAcquire(STALE_WAIT_MS)) Claim.FIRST else Claim.SECOND
    }

    /** The second launch: asks the instance holding the lock to come to the front. */
    fun askFirstToShow() {
        dir.mkdirs()
        focusFile.writeText(System.currentTimeMillis().toString())
    }

    /** The first instance: [onFocus] runs (on this watcher's thread) each time a second launch asks. Daemon thread. */
    fun watch(onFocus: () -> Unit) {
        if (watcher != null) return
        watcher = Thread({
            try {
                FileSystems.getDefault().newWatchService().use { ws ->
                    dir.toPath().register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                    while (!Thread.currentThread().isInterrupted) {
                        val key = ws.poll(500, TimeUnit.MILLISECONDS)
                        // the watch can miss a change: the file itself is the request
                        if (focusFile.exists()) { focusFile.delete(); runCatching(onFocus) }
                        if (key != null) { key.pollEvents(); key.reset() }
                    }
                }
            } catch (e: InterruptedException) {
                // closing
            } catch (e: Exception) {
                // no watching here; a second launch then just exits
            }
        }, "single-instance").apply { isDaemon = true; start() }
    }

    fun release() {
        watcher?.interrupt(); watcher = null
        runCatching { lock?.release() }; runCatching { channel?.close() }
        lock = null; channel = null
    }

    companion object {
        const val LOCK = "instance.lock"
        const val FOCUS = "instance.focus"
        /** How long a relaunch after an update waits for the old instance to finish exiting. */
        const val AFTER_UPDATE_WAIT_MS = 30_000L
        /** How long a second launch waits for the open instance to take its request. */
        const val HANDOVER_MS = 3_000L
        /** Nobody took it: how long to wait for a dead instance's lock to go. */
        const val STALE_WAIT_MS = 10_000L
    }
}
