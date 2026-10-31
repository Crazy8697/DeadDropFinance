package net.darkrelay.deaddropfinance

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parked buys are gone (1.8.1, program 0.14.0): nothing of them is left in the app's code, the screens it shares with
 * the program, the program's code, or the docs that ship. Only the data layer keeps their table (the schema doesn't
 * change, so no migration): the entity, the one query sync's whole-budget copy uses, the store's write path, sync's
 * table name, and the old migration that made it.
 */
class NoParkedBuysTest {

    private fun dir(vararg names: String) = names.map { File(it) }.first { it.isDirectory }
    private val roots = listOf(
        dir("src/main", "app/src/main"), dir("../shared/src/jvmShared", "shared/src/jvmShared"), dir("../shared/src/jvmMain", "shared/src/jvmMain"),
        dir("../ui/src", "ui/src"), dir("../desktop/src/main", "desktop/src/main"),
    )
    private val dataLayer = setOf("Entities.kt", "BudgetDao.kt", "BudgetStore.kt", "AppDatabase.kt", "ServerSync.kt", "Migrations.kt")
    private val docs = listOf("FEATURES.md", "PRIVACY.md", "README.md", "docs/IMPORT.md", "docs/mockups/README.md", "docs/mockups/app-prototype.html", "desktop/docs/desktop-mockup.html")
        .map { listOf(File("../$it"), File(it)).first { f -> f.isFile } }

    @Test fun noParkedBuysCodeIsLeftInEitherBuild() {
        val hits = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml", "md", "json") && it.name !in dataLayer }.toList() }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> Regex("(?i)park").containsMatchIn(l) }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" } }
        assertEquals(emptyList<String>(), hits)
    }

    @Test fun noShippedDocOrMockupMentionsThem() {
        val hits = docs.flatMap { f -> f.readLines().withIndex().filter { (_, l) -> Regex("(?i)parked|park it|parking").containsMatchIn(l) }.map { (i, _) -> "${f.name}:${i + 1}" } }
        assertEquals(emptyList<String>(), hits)
    }
}
