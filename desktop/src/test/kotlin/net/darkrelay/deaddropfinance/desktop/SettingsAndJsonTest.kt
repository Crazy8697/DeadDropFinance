package net.darkrelay.deaddropfinance.desktop

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsAndJsonTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun checkForUpdatesAtStartupIsOnByDefaultAndRemembered() {
        val dir = File(tmp.root, "DeadDropFinance")
        assertTrue(Settings(dir).checkUpdatesAtStartup)
        assertFalse(dir.exists(), "reading writes nothing")
        Settings(dir).checkUpdatesAtStartup = false
        assertFalse(Settings(dir).checkUpdatesAtStartup)
        assertEquals(listOf("settings.properties"), dir.list()!!.toList())
        Settings(dir).checkUpdatesAtStartup = true
        assertTrue(Settings(dir).checkUpdatesAtStartup)
    }

    @Test fun anUnreadableSettingsFileMeansTheDefaults() {
        val dir = tmp.newFolder("DeadDropFinance")
        File(dir, "settings.properties").writeBytes(byteArrayOf(0x5c, 0x75, 0x5a, 0x5a))   // a broken \u escape
        assertTrue(Settings(dir).checkUpdatesAtStartup)
    }

    @Test fun aSettingsFolderThatCantBeWrittenIsNotACrash() {
        val blocker = tmp.newFile("not-a-folder")
        val s = Settings(File(blocker, "DeadDropFinance"))
        s.checkUpdatesAtStartup = false
        assertFalse(s.checkUpdatesAtStartup, "kept in memory")
    }

    @Test fun theSettingsLiveInAppData() {
        val appData = System.getenv("APPDATA")
        if (appData != null) assertEquals(File(appData, "DeadDropFinance"), Settings.defaultDir())
    }

    @Test fun json() {
        val v = Json.parse("""[{"a":"x\"yé\n","b":true,"c":null,"d":-1.5e2,"e":[],"f":{}}, 42]""") as List<*>
        val o = v[0] as Map<*, *>
        assertEquals("x\"yé\n", o["a"])
        assertEquals(true, o["b"])
        assertTrue(o.containsKey("c") && o["c"] == null)
        assertEquals(-150.0, o["d"])
        assertEquals(emptyList<Any>(), o["e"])
        assertEquals(42L, v[1])
        for (bad in listOf("", "[", "{\"a\"}", "[1,]", "tru", "\"\u0001\"", "[1] x", "{\"a\":1,}", "[" .repeat(100) + "]".repeat(100))) {
            assertFailsWith<Json.ParseException>(bad.take(20)) { Json.parse(bad) }
        }
    }
}
