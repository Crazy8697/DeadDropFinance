package net.darkrelay.deaddropfinance

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The version is set once, in gradle.properties; the build must carry it through unchanged. */
class VersionTest {
    private fun versionFromGradleProperties(): String {
        // Gradle runs unit tests from the module folder (app/); gradle.properties is one level up.
        val file = listOf(File("../gradle.properties"), File("gradle.properties")).first { it.isFile }
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return requireNotNull(props.getProperty("ddf.versionName")) { "ddf.versionName missing" }
    }

    @Test
    fun versionNameMatchesGradleProperties() {
        assertEquals(versionFromGradleProperties(), BuildConfig.VERSION_NAME)
    }

    @Test
    fun versionCodeIsComputedFromVersionName() {
        val (major, minor, patch) = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        assertTrue(minor < 100 && patch < 100)
        assertEquals(major * 10000 + minor * 100 + patch, BuildConfig.VERSION_CODE)
    }

    @Test
    fun currentVersionCode() {
        // 0.6.0 (Stage 6, base: encrypted database and budgeting) -> 0 * 10000 + 6 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.6.0") assertEquals(600, BuildConfig.VERSION_CODE)
        // 0.6.1 (CSV duplicate handling) -> 0 * 10000 + 6 * 100 + 1
        if (BuildConfig.VERSION_NAME == "0.6.1") assertEquals(601, BuildConfig.VERSION_CODE)
        // 0.7.0 (Stage 7, setup wizard and bank sync) -> 0 * 10000 + 7 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.7.0") assertEquals(700, BuildConfig.VERSION_CODE)
        // 0.7.1 (Teller removed) -> 0 * 10000 + 7 * 100 + 1
        if (BuildConfig.VERSION_NAME == "0.7.1") assertEquals(701, BuildConfig.VERSION_CODE)
        // 0.7.2 (Stage 7 fixes) -> 0 * 10000 + 7 * 100 + 2
        if (BuildConfig.VERSION_NAME == "0.7.2") assertEquals(702, BuildConfig.VERSION_CODE)
        // 0.7.3 (balance fix, Delete account, Erase everything) -> 0 * 10000 + 7 * 100 + 3
        if (BuildConfig.VERSION_NAME == "0.7.3") assertEquals(703, BuildConfig.VERSION_CODE)
        // 0.8.0 (Stage 8: auto-categorize, approve flow, spend alerts, returns, balance check) -> 0 * 10000 + 8 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.8.0") assertEquals(800, BuildConfig.VERSION_CODE)
        // 0.9.0 (Stage 9: receipt scanning, matching, split by items, item returns) -> 0 * 10000 + 9 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.9.0") assertEquals(900, BuildConfig.VERSION_CODE)
        // 0.10.0 (Stage 10: bills, paydays, reminders, subscriptions, forecast, bank notifications) -> 0 * 10000 + 10 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.10.0") assertEquals(1000, BuildConfig.VERSION_CODE)
        // 0.11.0 (Stage 11: Safe to Spend home, parked buys, payday from sync) -> 0 * 10000 + 11 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.11.0") assertEquals(1100, BuildConfig.VERSION_CODE)
        // 0.15.0 (Stages 12 to 15: backup, setup import, Purge data, icon, review fixes, terms, About and Help) -> 0 * 10000 + 15 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.15.0") assertEquals(1500, BuildConfig.VERSION_CODE)
        // every later version must stay above 0.2.0 (code 200), the first signed release, so it installs as an update
        assertTrue(BuildConfig.VERSION_CODE > 200)
    }
}
