package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import net.darkrelay.deaddropfinance.data.TransactionSource
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0: the phone's CSV import in the program (More, Import CSV): the account, the column mapping, the date
 * format, the preview with duplicates in the file (each can be skipped), and what's already here on a second import.
 */
class ProgramImportTest : ProgramScreensBase() {

    private fun pick(label: String, current: String, option: String) {
        val top = node(label, exact = true).fetchSemanticsNode().boundsInRoot.center.y
        val all = compose.onAllNodes(hasText(current), useUnmergedTree = true)
        val ns = all.fetchSemanticsNodes()
        val below = ns.indices.filter { ns[it].boundsInRoot.center.y > top }
        if (below.isEmpty()) throw AssertionError("no $current under $label: " + dump())
        all[below.minBy { ns[it].boundsInRoot.center.y }].performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(option), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val items = compose.onAllNodes(hasText(option), useUnmergedTree = true)
        items[items.fetchSemanticsNodes().size - 1].performClick()
        compose.waitForIdle()
    }

    @Test fun aFileIsMappedPreviewedWithItsDuplicatesAndImportedOnce() {
        show()
        val d1 = today.minusDays(2)
        val d2 = today.minusDays(1)
        picked = tmp.newFile("sample-bank.csv").also {
            it.writeText("Date,Description,Amount\n$d1,Sample Bakery,-6.50\n$d1,Sample Bakery,-6.50\n$d2,Sample Garage,-120.00\n")
        }
        click("nav.SETTINGS")
        click("settings.importCsv")
        until("It's read once and never stored.")
        clickText("Choose a file", exact = true)
        // the account is chosen already (the only bank account), as on the phone
        until("3 rows read.")
        // the mapping: Description read as the memo instead shows no description in the preview, then back
        pick("Description", "Description", "Memo")
        click("import.preview")
        until("No description")
        click("import.cancel")
        pick("Description", "Memo", "Description")
        // 1.9.7: the preview dialog; nothing is written until its Import. The two identical rows are two new rows; one is unticked
        click("import.preview")
        until("3 new, 0 already in, 0 unsure, 0 pending")
        assertTrue(shows("Sample Garage"))
        click("import.new.3")
        clickText("Import 2", exact = true)
        until("Imported 2 into To sort.")
        refresh()
        val imported = b().transactions.filter { it.source == TransactionSource.CSV }
        assertEquals(setOf("Sample Bakery", "Sample Garage"), imported.map { it.payee }.toSet())
        assertEquals(2, imported.size)
        assertTrue(imported.all { t -> b().toSort.any { it.id == t.id } })
        assertTrue(imported.all { it.importId != null && it.originalDescription != null })
        assertTrue(shows("1 not ticked"))
        // the same file again: the two in are already in (the unticked twin can still come in)
        clickText("Choose a file", exact = true)
        until("3 rows read.")
        click("import.preview")
        until("1 new, 2 already in, 0 unsure, 0 pending")
    }
}
