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
        // 0.3.0 (Stage 3, privacy gate) -> 0 * 10000 + 3 * 100 + 0
        if (BuildConfig.VERSION_NAME == "0.3.0") assertEquals(300, BuildConfig.VERSION_CODE)
        // every later version must stay above 0.2.0 (code 200), the first signed release, so it installs as an update
        assertTrue(BuildConfig.VERSION_CODE > 200)
    }
}
