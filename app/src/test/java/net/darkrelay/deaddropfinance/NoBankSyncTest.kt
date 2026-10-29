package net.darkrelay.deaddropfinance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bank sync is gone (1.3.0): no bank sync screen, client or job is left in the app's code, and the only code that
 * talks to the network is the in-app updater (UpdateClient and UpdateDownloader) and, from 1.4.0, server sync's
 * HttpTransport, which talks only to the user's own Dead Drop server.
 */
class NoBankSyncTest {

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    // the app's own code plus the logic it shares with the desktop program (shared/, from 1.3.2)
    private val shared = listOf(File("../shared/src"), File("shared/src")).first { it.isDirectory }
    private val code = (main.walkTopDown() + shared.walkTopDown()).filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noBankSyncCodeIsLeft() {
        val gone = listOf(
            "SimpleFinSource", "SyncService", "SyncEngine", "SyncWorker", "SyncScheduler", "RequestLimiter", "BankSyncScreen", "LinkPanel",
            "AccountSyncSection", "BalanceCheck", "SimpleFinDoor", "beta-bridge.simplefin.org", "Nav.BankSync", "LinkedAccountEntity", "ConnectionEntity",
        )
        val hits = code.flatMap { f -> gone.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), hits)
        assertTrue(code.none { it.parentFile.name == "sync" && it.name != "SyncSettings.kt" })
    }

    @Test fun onlyTheUpdaterAndServerSyncTalkToTheNetwork() {
        val net = listOf("HttpURLConnection", "HttpsURLConnection", "openConnection", "java.net.URL", "java.net.Socket", "okhttp")
        val users = code.filter { f -> net.any { f.readText().contains(it) } }.map { it.name }.toSet()
        assertEquals(setOf("UpdateClient.kt", "UpdateDownloader.kt", "HttpTransport.kt"), users)
    }
}
