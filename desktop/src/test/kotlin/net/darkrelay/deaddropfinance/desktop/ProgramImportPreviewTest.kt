package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.TransactionSource
import net.darkrelay.deaddropfinance.ui.DdfColors
import net.darkrelay.deaddropfinance.ui.PhonePalette
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The import preview and Undo last import in the program (0.15.10), on the program's own Activity account tab, with an
 * invented bank file in a USAA-like layout against the sample budget's hand-entered transactions: three new rows, three
 * already in (the payees named differently), one a day off (unsure, the hand entry beside it) and one Pending. Nothing
 * is written until Import; Undo removes exactly what that import added. At 1400 x 900 and 1100 x 700.
 */
@OptIn(ExperimentalTestApi::class)
class ProgramImportPreviewTest {

    @get:Rule val tmp = TemporaryFolder()
    private val models = mutableListOf<AppModel>()

    @After fun close() { models.forEach { it.engine?.close() }; DdfColors.palette = PhonePalette }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.10" }?.let { File(it).also { d -> d.mkdirs() } }
    /** 0.15.11's shots: the balance check and a Venmo statement. */
    private val shots11: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.11" }?.let { File(it).also { d -> d.mkdirs() } }

    private val file = """
        Date,Description,Original Description,Category,Amount,Status
        2030-10-26,Sample Payroll,SAMPLE PAYROLL CO DIR DEP,Income,1850.00,Posted
        2030-10-23,Power Co,POWER COMPANY AUTOPAY 0099,Utilities,-58.00,Posted
        2030-10-29,Fuel Stop,FUEL STOP 12 SAMPLE CITY,Gas,-40.00,Posted
        2030-10-29,Corner Market,CORNER MARKET 4 SAMPLE CITY,Groceries,-43.12,Posted
        2030-10-27,Sample Bookshop,SAMPLE BOOKSHOP 9,Shopping,-12.50,Posted
        2030-10-28,Sample Pharmacy,SAMPLE PHARMACY 22,Health,-8.20,Posted
        2030-10-28,Interest Paid,INTEREST PAID,Income,0.42,Posted
        2030-10-29,Sample Diner,SAMPLE DINER,Restaurants,-18.40,Pending
    """.trimIndent() + "\n"

    private fun ComposeUiTest.image(): BufferedImage {
        val roots = onAllNodes(isRoot())
        val img = roots[0].captureToImage().toAwtImage()
        for (k in 1 until roots.fetchSemanticsNodes().size) img.createGraphics().apply { drawImage(roots[k].captureToImage().toAwtImage(), 0, 0, null); dispose() }
        return img
    }

    private fun ComposeUiTest.has(t: String) = onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun ComposeUiTest.text(t: String) = onNodeWithTag(t, useUnmergedTree = true).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text }
    private fun ComposeUiTest.click(t: String) { runCatching { onNodeWithTag(t, useUnmergedTree = true).performScrollTo() }; onNodeWithTag(t, useUnmergedTree = true).performClick(); waitForIdle() }
    private fun ComposeUiTest.wait(t: String) = waitUntil(timeoutMillis = 10_000) { has(t) }

    @Test fun thePreviewThenImportThenUndo() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val made = SampleBudget.filled(tmp.newFolder())
            val m = made.model.also { models += it }
            val csv = File(tmp.newFolder(), "sample-usaa.csv").also { it.writeText(file) }
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()), pickCsv = { csv })) }
            waitForIdle()
            val checking = made.ids.getValue("Checking")
            val before = m.budget!!.transactions.size
            click("nav.ACTIVITY")
            click("tab.Checking")
            assertTrue(!has("undoImport"), "no import yet, so no Undo")
            click("importCsv")
            wait("dialog.import")
            assertEquals("3 new, 3 already in, 1 unsure, 1 pending", text("import.counts"))
            // the hand entry a day off is beside the unsure row, which isn't ticked
            assertTrue(text("import.maybe.4").startsWith("Maybe already here: 2030-10-28, Fuel Stop 12"), text("import.maybe.4"))
            assertEquals(before, m.budget!!.transactions.size, "nothing written yet")
            shots?.let { ImageIO.write(image(), "png", File(it, "program-import-preview-${w}x$h.png")) }
            click("import.already")
            shots?.let { ImageIO.write(image(), "png", File(it, "program-import-preview-already-open-${w}x$h.png")) }
            click("dialog.save")
            waitUntil(timeoutMillis = 10_000) { m.dialog == null }
            val imported = m.budget!!.transactions.filter { it.accountId == checking && it.source == TransactionSource.CSV }
            assertEquals(setOf("Sample Bookshop", "Sample Pharmacy", "Interest Paid"), imported.map { it.payee }.toSet())
            assertTrue(imported.all { it.importId != null && it.originalDescription != null })
            assertTrue(m.budget!!.transactions.none { it.payee == "Sample Diner" }, "the pending row isn't added")
            // Undo last import, beside Import CSV now
            wait("undoImport")
            click("undoImport")
            wait("undoImport.confirm")
            assertEquals("This removes the 3 transactions it added. Nothing else changes.", text("undoImport.text"))
            shots?.let { ImageIO.write(image(), "png", File(it, "program-undo-import-confirm-${w}x$h.png")) }
            // the action is filled red, Cancel is on its left
            val go = onNodeWithTag("undoImport.go", useUnmergedTree = true).getBoundsInRoot()
            val cancel = onNodeWithTag("undoImport.cancel", useUnmergedTree = true).getBoundsInRoot()
            assertTrue(cancel.right <= go.left)
            val img = image()
            assertEquals(0xE2574C, img.getRGB((go.left.value + 4).toInt(), ((go.top.value + go.bottom.value) / 2).toInt()) and 0xFFFFFF)
            click("undoImport.go")
            waitUntil(timeoutMillis = 10_000) { m.dialog == null }
            assertEquals(before, m.budget!!.transactions.size)
            assertTrue(m.budget!!.transactions.none { it.source == TransactionSource.CSV })
            assertTrue(!has("undoImport"), "nothing left to undo")
        }
    }

    private val blank = List(21) { "" }
    private fun vline(vararg cells: Pair<Int, String>): String = blank.toMutableList().also { l -> cells.forEach { (i, v) -> l[i] = v } }
        .joinToString(",") { if (it.contains(',')) "\"$it\"" else it }

    /** An invented Venmo statement in Venmo's own layout: $120.00 at the start, $96.84 at the end (the repayment doesn't move it). */
    private val venmo = listOf(
        vline(0 to "Account Statement - (@Sample-User)"), vline(0 to "Account Activity"),
        "ID,Datetime,Type,Status,Note,From,To,Amount (total),Amount (tip),Amount (tax),Amount (fee),Tax Rate,Tax Exempt,Funding Source,Destination,Beginning Balance,Ending Balance,Statement Period Venmo Fees,Terminal Location,Year to Date Venmo Fees,Disclaimer",
        vline(15 to "$120.00"),
        vline(0 to "4100000001", 1 to "2030-10-20T10:15:00", 2 to "Payment", 3 to "Complete", 4 to "Lunch", 5 to "Sample Friend", 6 to "Sample User", 7 to "+ $25.00"),
        vline(0 to "4100000002", 1 to "2030-10-22T18:30:00", 2 to "Venmo Card Transaction", 3 to "Complete", 5 to "Sample User", 6 to "Sample Cafe", 7 to "- $8.16"),
        vline(0 to "4100000003", 1 to "2030-10-24T12:00:00", 2 to "Instant Transfer", 3 to "Issued", 7 to "- $40.00", 10 to "- $0.40"),
        vline(0 to "4100000004", 1 to "2030-10-25T08:00:00", 2 to "Venmo account repayment", 3 to "Complete", 5 to "Sample User", 7 to "+ $30.00"),
        vline(16 to "$96.84", 17 to "$0.40", 19 to "$0.40", 20 to "An invented disclaimer, with commas, for a test."),
    ).joinToString("\n") + "\n"

    /** 0.15.11: the balance check (typed: match, mismatch and its confirm) and a Venmo statement (its own ending balance). */
    @Test fun theImportBalanceAndAVenmoStatement() {
        for ((w, h) in listOf(1400 to 900, 1100 to 700)) runDesktopComposeUiTest(w, h) {
            val made = SampleBudget.filled(tmp.newFolder())
            val m = made.model.also { models += it }
            var picked = File(tmp.newFolder(), "sample-usaa.csv").also { it.writeText(file) }
            val venmoAcct = runBlocking { m.actions!!.saveAccount(null, "Venmo", net.darkrelay.deaddropfinance.data.AccountType.PAYMENT_APP, 120_00, java.time.LocalDate.of(2030, 10, 1)) }
            m.refresh()
            setContent { DesktopApp(m, Shell(run = { b -> runBlocking { b() } }, settings = Settings(tmp.newFolder()), pickCsv = { picked })) }
            waitForIdle()
            val checking = made.ids.getValue("Checking")
            click("nav.ACTIVITY"); click("tab.Checking"); click("importCsv")
            wait("dialog.import")
            val d = m.dialog as Dialog.ImportCsv
            val after = net.darkrelay.deaddropfinance.domain.CsvImport.balanceAfter(m.budget!!, checking, d.plan)
            // nothing typed: only what it comes to
            assertEquals("After import: ${money(after)}.", text("import.after"))
            fun type(v: String) { onNodeWithTag("import.bank", useUnmergedTree = true).performTextClearance(); onNodeWithTag("import.bank", useUnmergedTree = true).performTextInput(v); waitForIdle() }
            type(net.darkrelay.deaddropfinance.domain.Money.plain(after))
            assertEquals("After import: ${money(after)}. Bank says: ${money(after)}.", text("import.after"))
            assertTrue(!has("import.off"))
            shots11?.let { ImageIO.write(image(), "png", File(it, "program-import-balance-match-${w}x$h.png")) }
            type(net.darkrelay.deaddropfinance.domain.Money.plain(after + 10_00))
            assertEquals("Off by ${money(10_00)}", text("import.off"))
            // unticking a row moves what it comes to, live
            click("import.new.7")
            val after2 = net.darkrelay.deaddropfinance.domain.CsvImport.balanceAfter(m.budget!!, checking, (m.dialog as Dialog.ImportCsv).plan)
            assertEquals(after + 8_20, after2)
            assertTrue(text("import.after").startsWith("After import: ${money(after2)}."))
            click("import.new.7")
            shots11?.let { ImageIO.write(image(), "png", File(it, "program-import-balance-mismatch-${w}x$h.png")) }
            // a mismatch asks first; Cancel leaves it as it was
            click("dialog.save")
            wait("import.offConfirm")
            assertTrue(text("import.offText").contains("Off by"))
            shots11?.let { ImageIO.write(image(), "png", File(it, "program-import-balance-confirm-${w}x$h.png")) }
            click("import.offCancel")
            assertTrue(m.dialog is Dialog.ImportCsv)
            // the confirm's Import anyway imports
            click("dialog.save")
            wait("import.offConfirm")
            click("import.offGo")
            waitUntil(timeoutMillis = 10_000) { m.dialog == null }
            assertEquals(after, m.budget!!.accountBalance(checking))

            // a Venmo statement on the Venmo tab: its own columns, the repayment not added, the bank balance from the file
            picked = File(tmp.newFolder(), "VenmoStatement_October_2030.csv").also { it.writeText(venmo) }
            click("tab.Venmo"); click("importCsv")
            wait("dialog.import")
            assertEquals("3 new, 0 already in, 0 unsure, 0 pending", text("import.counts"))
            assertTrue(text("import.left.8").contains("repayment"), text("import.left.8"))
            assertEquals("After import: ${money(96_84)}. Bank says: ${money(96_84)}.", text("import.after"))
            shots11?.let { ImageIO.write(image(), "png", File(it, "program-import-venmo-${w}x$h.png")) }
            click("dialog.save")
            waitUntil(timeoutMillis = 10_000) { m.dialog == null }
            assertEquals(96_84L, m.budget!!.accountBalance(venmoAcct))
            // the same statement again: nothing new
            click("importCsv")
            wait("dialog.import")
            assertEquals("0 new, 3 already in, 0 unsure, 0 pending", text("import.counts"))
            click("dialog.cancel")
        }
    }
}
