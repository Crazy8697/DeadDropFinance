package net.darkrelay.deaddropfinance.desktop

import java.io.File
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1.9.0 / 0.15.0: Dropbox is gone everywhere (the backup screen said "Dropbox later"). Every text file of the repository
 * (code, docs, mockups, build files) is read; build output, Gradle's and Git's folders aren't the project's own.
 */
class NoDropboxTest {

    private val root = listOf(File(".."), File(".")).first { File(it, "settings.gradle.kts").isFile }.canonicalFile
    private val skip = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "node_modules")
    private val text = setOf("kt", "kts", "md", "html", "xml", "json", "txt", "properties", "ps1", "py", "toml", "yml", "yaml", "iss", "csv")

    @Test fun noFileMentionsDropbox() {
        val files = root.walkTopDown().onEnter { it.name !in skip }.filter { it.isFile && it.extension.lowercase() in text }.toList()
        assertTrue(files.size > 100, "the repository was found (${files.size} files in $root)")
        // this test's own name for what it looks for is the one place it's allowed
        val word = "drop" + "box"
        val found = files.filter { it.name != "NoDropboxTest.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> l.contains(word, ignoreCase = true) }.map { (i, _) -> "${f.relativeTo(root)}:${i + 1}" }
        }
        assertEquals(emptyList(), found)
    }
}
