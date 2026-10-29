package net.darkrelay.deaddropfinance

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules that hold for the whole codebase, checked by reading the sources. */
class RulesTest {

    // Gradle runs unit tests from the app module folder
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    // the app's own code plus the logic it shares with the desktop program (shared/, from 1.3.2)
    private val shared = listOf(File("../shared/src"), File("shared/src")).first { it.isDirectory }
    private val kotlinSources = (main.resolve("java").walkTopDown() + shared.walkTopDown()).filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noDestructiveMigrationAnywhere() {
        val offenders = kotlinSources.filter { it.readText().contains("fallbackToDestructiveMigration") }
        assertEquals("destructive migration is forbidden (docs/DATABASE.md)", emptyList<File>(), offenders)
        assertTrue(File(main.parentFile.parentFile, "schemas").walkTopDown().any { it.name == "1.json" })
    }

    @Test fun moneyIsNeverFloatOrDouble() {
        // the data model, DAOs and domain layer: not a single Float or Double (money is Long cents)
        val pkgs = listOf("data", "domain")
        val offenders = kotlinSources.filter { f -> pkgs.any { f.path.replace('\\', '/').contains("/deaddropfinance/$it/") } }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, l ->
                    val code = l.substringBefore("//").trim()
                    val comment = code.startsWith("*") || code.startsWith("/*")
                    if (!comment && Regex("""\b(Float|Double)\b|toFloat\(|toDouble\(""").containsMatchIn(code)) "${f.name}:${i + 1}" else null
                }
            }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun noLoggingOfFinancialData() {
        // no logging calls at all in the app's sources, in any build type: nothing about money can leak to logcat
        val offenders = kotlinSources.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if (Regex("""\bLog\.[vdiwe]\(|\bprintln\(|\bprint\(|printStackTrace\(""").containsMatchIn(l)) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun nothingLeavesThroughAndroidBackup() {
        val manifest = main.resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(main.resolve("res/xml/data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = doc.getElementsByTagName(section).item(0)
            val excluded = (0 until node.childNodes.length).map { node.childNodes.item(it) }
                .filter { it.nodeName == "exclude" }.map { it.attributes.getNamedItem("domain").nodeValue }.toSet()
            assertEquals(section, setOf("root", "file", "database", "sharedpref", "external"), excluded)
            val included = (0 until node.childNodes.length).count { node.childNodes.item(it).nodeName == "include" }
            assertEquals(0, included)
        }
    }
}
