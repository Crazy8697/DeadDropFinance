package net.darkrelay.deaddropfinance.desktop

import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One program per Windows user (0.12.3). The other instance is a real second process (this JVM's own java, the test
 * classpath, [InstanceHolder]), so the lock is the operating system's, as between two launches of the program. Temp
 * settings folders only; nothing of the real program or its settings is touched.
 */
class SingleInstanceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val kids = mutableListOf<Process>()

    @After fun done() { kids.forEach { it.destroyForcibly() } }

    /** Another instance holding [dir]'s lock for [holdMs], printing LOCKED, then FOCUSED each time it's asked to show. */
    private fun holder(dir: File, holdMs: Long): Pair<Process, BufferedReader> {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), InstanceHolder::class.java.name, dir.path, holdMs.toString())
            .redirectErrorStream(true).start()
        kids += p
        val out = p.inputStream.bufferedReader()
        val line = readLine(out, 20_000)
        assertTrue(line == "LOCKED", "the other instance took the lock: $line")
        return p to out
    }

    private fun readLine(r: BufferedReader, ms: Long): String? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (r.ready()) return r.readLine(); Thread.sleep(50) }
        return null
    }

    @Test fun aSecondLaunchAsksTheFirstToShowAndOpensNothing() {
        val dir = tmp.newFolder()
        val (_, out) = holder(dir, 30_000)
        val second = SingleInstance(dir)
        assertEquals(SingleInstance.Claim.SECOND, second.claim(), "the first instance holds it and took the request")
        assertTrue(readLine(out, 10_000) == "FOCUSED", "the first instance was asked to come to the front")
        // asked again, it comes again
        second.askFirstToShow()
        assertTrue(readLine(out, 10_000) == "FOCUSED")
    }

    @Test fun aKilledInstanceLeavesNoLockBehind() {
        val dir = tmp.newFolder()
        val (p, _) = holder(dir, 60_000)
        assertFalse(SingleInstance(dir).tryAcquire())
        p.destroyForcibly(); p.waitFor(10, TimeUnit.SECONDS)
        assertTrue(File(dir, SingleInstance.LOCK).exists(), "the lock file is still there")
        val next = SingleInstance(dir)
        assertEquals(SingleInstance.Claim.FIRST, next.claim(), "nobody takes the request and its lock went with the process, so the program opens")
        next.release()
    }

    @Test fun theRelaunchAfterAnUpdateWaitsForTheOldInstanceToExit() {
        val dir = tmp.newFolder()
        holder(dir, 1_500)                      // the old copy, exiting a moment after Setup starts the new one
        val relaunch = SingleInstance(dir)
        assertFalse(relaunch.tryAcquire(0), "without waiting it would hand over to the old copy")
        val start = System.currentTimeMillis()
        assertEquals(SingleInstance.Claim.FIRST, relaunch.claim(afterUpdate = true), "with --after-update it waits and opens")
        assertTrue(System.currentTimeMillis() - start < 15_000)
        relaunch.release()
        // and Setup's relaunch passes the flag
        val iss = File("installer/deaddropfinance.iss").readText()
        assertTrue(Regex("""Parameters: "--after-update"; Check: ShouldRelaunch""").containsMatchIn(iss))
    }

    @Test fun otherSettingsHaveTheirOwnLock() {
        val real = tmp.newFolder("roaming")
        val isolated = tmp.newFolder("isolated")
        holder(real, 30_000)
        val test = SingleInstance(isolated)
        assertTrue(test.tryAcquire(), "an isolated copy (other settings) never collides with the one running")
        test.release()
    }

    @Test fun aRequestLeftFromBeforeIsNotForTheNewInstance() {
        val dir = tmp.newFolder()
        File(dir, SingleInstance.FOCUS).writeText("old")
        val first = SingleInstance(dir)
        assertTrue(first.tryAcquire())
        assertFalse(File(dir, SingleInstance.FOCUS).exists())
        first.release()
    }
}

/** The other instance for [SingleInstanceTest]: takes the lock of args[0], holds it for args[1] ms, reports on stdout. */
object InstanceHolder {
    @JvmStatic fun main(args: Array<String>) {
        val i = SingleInstance(File(args[0]))
        if (!i.tryAcquire()) { println("BUSY"); System.out.flush(); return }
        i.watch { println("FOCUSED"); System.out.flush() }
        println("LOCKED"); System.out.flush()
        Thread.sleep(args[1].toLong())
        i.release()
    }
}
