package net.darkrelay.deaddropfinance.desktop

import java.io.File
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * 0.15.7: a test still running after [afterSeconds] has every thread's stack written to build/hang-dumps/<class>.<test>.txt
 * (and to the test's output), so a hang that happens once leaves what it was waiting on behind. Nothing happens
 * while the test is quicker than that.
 */
class HangDump(private val afterSeconds: Long = 50) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val name = "${description.testClass.simpleName}.${description.methodName}"
            val started = System.nanoTime()
            val watch = Thread({
                try {
                    Thread.sleep(afterSeconds * 1000)
                    val text = buildString {
                        appendLine("$name still running after ${(System.nanoTime() - started) / 1_000_000} ms")
                        for ((t, stack) in Thread.getAllStackTraces()) {
                            appendLine()
                            appendLine("\"${t.name}\" ${t.state}${if (t.isDaemon) " daemon" else ""}")
                            stack.forEach { appendLine("    at $it") }
                        }
                    }
                    val dir = listOf(File("build"), File("desktop/build")).first { it.isDirectory }.resolve("hang-dumps").also { it.mkdirs() }
                    File(dir, "$name.txt").writeText(text)
                    println("HANG-DUMP written: ${File(dir, "$name.txt").absolutePath}")
                    println(text)
                } catch (_: InterruptedException) {
                }
            }, "hang-dump $name").apply { isDaemon = true; start() }
            try { base.evaluate() } finally { watch.interrupt() }
        }
    }
}
