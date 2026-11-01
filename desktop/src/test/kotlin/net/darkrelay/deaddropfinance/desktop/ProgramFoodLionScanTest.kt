package net.darkrelay.deaddropfinance.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.ReceiptImages
import net.darkrelay.deaddropfinance.domain.ReceiptParser
import net.darkrelay.deaddropfinance.ui.ReceiptForm
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.15.4 (phone 1.9.5): the real Food Lion receipt photo through exactly what the Scan button does: Receipts, Scan,
 * Choose an image or PDF, the file read by the program's own scanner, the check screen. And the same photo at the sizes
 * and encodings a photo of it comes in (a camera's full size, saved again as a JPEG), through that same scanner: the text
 * recognition reads each one a little differently, and every one gives the same receipt. Then the check screen kept as
 * left through another screen and a restart, and Clear.
 */
class ProgramFoodLionScanTest : ProgramScreensBase() {

    private val fixture = listOf(File("../shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp"), File("shared/src/jvmTest/resources/receipts/food-lion/food-lion-receipt.webp")).first { it.isFile }

    private val names = listOf(
        "FL 2 MILK GALLON", "FL SALTED BUTTER QTR", "FL HEAVY WHIP CREAM", "MODELA CHLD LM NA 6C", "FL LEMON JUICE", "FL RANCH DP DRESS PK",
        "FL GRN TEA BAGS 40CT", "FL CINNI MINI CRUNCH", "FOOD LION LIME JUICE", "KELLOGG FUN PAK", "TWIN EARL GREY TEA", "SIPPIE BOWLS",
        "ATHLC LITE NA 12CN", "CELS SPRK WLD BRRY", "CELS SPRK PCH VIBES", "SLVRFLOSS KRAUT 32Z", "LOF ITALIAN SUB KIT", "CARPRD CELLO RED LNK",
        "CELERY", "BUNCH RADISHES", "CHINESE CABBAGE",
    )
    private val prices = listOf("3.19", "2.99", "5.49", "22.98", "1.99", "0.59", "5.58", "2.49", "1.99", "5.49", "4.49", "4.49", "17.99", "1.49", "1.49", "3.99", "7.99", "6.49", "2.19", "1.99", "3.64")
    private val qtys = List(21) { "1" }.toMutableList().also { it[3] = "2"; it[6] = "2"; it[20] = "1.26" }

    /**
     * A name as text recognition reads it: on some sizes of this photo one letter of two names comes out wrong ("32Z" as
     * "322", "ITALIAN" as "ITALTAN"). The reader can't know a name; it keeps what it read. Every other name is exact.
     */
    private fun sameName(read: String, want: String) = read == want ||
        want in setOf("SLVRFLOSS KRAUT 32Z", "LOF ITALIAN SUB KIT") && read.length == want.length && read.zip(want).count { (a, b) -> a != b } == 1

    private fun cents(s: String) = s.replace(".", "").toLong()

    private fun text(t: String) = tag(t).fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""

    private fun scanFromReceipts(file: File) {
        picked = file
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        clickText("Choose an image or PDF", exact = true)
        until("Save receipt", ms = 90_000)
        compose.waitForIdle()
    }

    /**
     * 0.15.5: the very file picked in the program's Scan button on 0.15.3 (a small screenshot of the photo, 467 x 815,
     * text a dozen pixels high): enlarged before it's read, and the balance and total tax its one-block read drops taken
     * from a second, scattered read. Every line, the breakdown, and the total read from the receipt.
     */
    @Test fun theFilePickedOn0153ThroughTheScanButton() {
        show()
        val original = listOf(File("../shared/src/jvmTest/resources/receipts/food-lion/food-lion-scanned-in-0.15.3.png"), File("shared/src/jvmTest/resources/receipts/food-lion/food-lion-scanned-in-0.15.3.png")).first { it.isFile }
        scanFromReceipts(original.copyTo(File(tmp.root, "food-lion-scanned-in-0.15.3.png")))
        assertEquals(21, (0 until 40).count { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("item.card.$it"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        names.forEachIndexed { i, n -> assertTrue(sameName(text("item.name.$i"), n), "item $i: '${text("item.name.$i")}'") }
        assertEquals(prices, (0 until 21).map { text("item.price.$it") })
        assertEquals(qtys, (0 until 21).map { text("item.qty.$it") })
        assertEquals("", text("receipt.store"))
        assertEquals("", text("receipt.date"))
        assertTrue(shows("Check the store, it wasn't on the photo") && shows("Check the date, it wasn't on the photo"))
        assertEquals("63.56", text("breakdown.untaxed"))
        assertEquals("45.46", text("breakdown.taxed"))
        assertEquals("3.18", text("breakdown.tax"))
        assertEquals("112.20", text("breakdown.total"))
        assertTrue(!shows("total wasn't read"), "read from the receipt")
        assertTrue(shows("Untaxed items, taxed items and tax come to the total paid."), dump())
    }

    /** 0.15.5: a saved receipt's edits are kept like a new scan's, through another screen and a restart; Clear throws out only them. */
    @Test fun aSavedReceiptsEditsAreKeptUntilSavedOrClearedAndClearKeepsTheSavedReceipt() {
        show()
        val id = runBlocking {
            net.darkrelay.deaddropfinance.domain.Receipts(store(), a()).save(
                "saved-1",
                net.darkrelay.deaddropfinance.domain.Receipts.Draft(
                    "Corner Market", today, 12_00, 0,
                    listOf(net.darkrelay.deaddropfinance.domain.Receipts.DraftItem("SAMPLE APPLES", amount = 5_00), net.darkrelay.deaddropfinance.domain.Receipts.DraftItem("SAMPLE SOAP", amount = 7_00)),
                ),
            )
        }
        refresh()
        fun openEdit() { model.go(Screen.RECEIPTS); model.openPhone(net.darkrelay.deaddropfinance.ui.Nav.ReceiptEdit(id, isNew = false)); until("Edit receipt") }
        openEdit()
        tag("item.name.0").performTextReplacement("SAMPLE GREEN APPLES"); compose.waitForIdle()
        click("item.checked.1")
        Thread.sleep(300); compose.waitForIdle()
        // another screen, and back
        click("nav.BUDGET")
        openEdit()
        assertEquals("SAMPLE GREEN APPLES", text("item.name.0"))
        assertTrue(shows("✓ Checked"))
        shots?.let { compose.onRoot().captureToImage().toAwtImage().let { img -> ImageIO.write(img, "png", File(it, "program-edit-draft-after-screen-switch.png")) } }
        // a restart
        val dir = dataDir()
        compose.setContent {}
        compose.waitForIdle()
        model.engine?.close()
        model = AppModel(runBlocking { Engine.start(dir) }).also { runBlocking { it.refresh() } }
        compose.setContent { DesktopApp(model, shell) }
        compose.waitForIdle()
        openEdit()
        assertEquals("SAMPLE GREEN APPLES", text("item.name.0"))
        // nothing saved yet
        assertEquals(listOf("SAMPLE APPLES", "SAMPLE SOAP"), runBlocking { store().dao.itemsOf(id) }.map { it.text })
        // Clear on a saved receipt: says so, keeps the saved receipt, throws out the edits
        click("receipt.clear")
        until("Clear your changes?")
        assertTrue(shows("Only the changes not saved yet are thrown out. The saved receipt stays as it is."))
        click("clear.confirm")
        compose.waitForIdle()
        assertEquals(listOf("SAMPLE APPLES", "SAMPLE SOAP"), runBlocking { store().dao.itemsOf(id) }.map { it.text })
        assertTrue(runBlocking { store().dao.receipt(id) } != null)
        openEdit()
        assertEquals("SAMPLE APPLES", text("item.name.0"))
        assertTrue(!shows("✓ Checked"))
        // and Save keeps the edit and clears the draft
        tag("item.name.1").performTextReplacement("SAMPLE DISH SOAP"); compose.waitForIdle()
        Thread.sleep(300)
        click("receipt.save")
        compose.waitUntil(5_000) { runBlocking { store().dao.itemsOf(id) }.any { it.text == "SAMPLE DISH SOAP" } }
        // the draft goes as part of the save, just after its rows are written: wait for that, not for the rows
        compose.waitUntil(5_000) { File(dir, "receipts").listFiles().orEmpty().none { it.name == "draft" } }
        assertTrue(File(dir, "receipts").listFiles().orEmpty().none { it.name == "draft" }, File(dir, "receipts").list().orEmpty().joinToString())
    }

    private val shots: File? = System.getProperty("ddf.screens").orEmpty().takeIf { it.isNotBlank() && System.getProperty("ddf.screensRound") == "0.15.5" }?.let { File(it).also { d -> d.mkdirs() } }

    @Test fun theRealPhotoThroughTheScanButton() {
        show()
        scanFromReceipts(fixture.copyTo(File(tmp.root, "food-lion-receipt.webp")))
        assertEquals(21, (0 until 40).count { compose.onAllNodes(androidx.compose.ui.test.hasTestTag("item.card.$it"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() })
        names.forEachIndexed { i, n -> assertTrue(sameName(text("item.name.$i"), n), "item $i: '${text("item.name.$i")}'") }
        assertEquals(prices, (0 until 21).map { text("item.price.$it") })
        assertEquals(qtys, (0 until 21).map { text("item.qty.$it") })
        // store and date left for the user (never a section header), and the totals as the receipt shows them
        assertEquals("", text("receipt.store"))
        assertEquals("", text("receipt.date"))
        assertTrue(shows("Check the store, it wasn't on the photo"))
        assertEquals("63.56", text("breakdown.untaxed"))
        assertEquals("45.46", text("breakdown.taxed"))
        assertEquals("3.18", text("breakdown.tax"))
        assertEquals("112.20", text("breakdown.total"))
        // it adds up because it does: the total was read, and the items with tax come to it
        assertTrue(shows("Untaxed items, taxed items and tax come to the total paid."), dump())
        assertEquals(listOf("24.59", "4.80", "19.25"), listOf(3, 11, 12).map { text("item.withTax.$it") })
    }

    /** The photo as a camera or a re-save gives it: smaller, larger, re-encoded. Each through the Scan button's scanner. */
    @Test fun everySizeOfThePhotoGivesTheSameReceipt() {
        val img = org.jetbrains.skia.Image.makeFromEncoded(fixture.readBytes()).toComposeImageBitmap().toAwtImage()
        fun scaled(f: Float): File {
            val w = (img.width * f).toInt(); val h = (img.height * f).toInt()
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics(); g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.drawImage(img, 0, 0, w, h, null); g.dispose()
            return tmp.newFile("food-lion-x$f.jpg").also { ImageIO.write(out, "jpg", it) }
        }
        for (f in listOf(fixture) + listOf(1f, 1.6f, 2f, 2.5f, 3.2f).map(::scaled)) {
            // what platform.scanReceipt runs for the Scan button
            val pages = ProgramReceiptScanner(ReceiptImages(tmp.newFolder(), { ByteArray(32) })) { TessReader(TessReader.tessdata()!!) }.scan(f, "fl", 0)
            val p = ReceiptParser.parse(pages)
            val form = ReceiptForm.from(p)
            assertEquals(21, p.items.size, "${f.name}: ${p.items.map { it.text }}")
            // at other sizes the recognition misreads a letter here and there ("QTR" as "OTR"): every line is still the item
            p.items.forEachIndexed { i, it -> assertTrue(it.text.length == names[i].length && it.text.zip(names[i]).count { (a, b) -> a != b } <= 1, "${f.name} item $i: ${it.text}") }
            assertEquals(qtys.map { ReceiptForm.qtyMilli(it) }, p.items.map { it.quantityMilli }, f.name)
            assertEquals("", p.store, f.name)
            assertEquals(3_18L, p.tax, f.name)
            assertEquals(112_20L, p.total, f.name)
            // when the tax rows can't be read at all (one size here), nothing is guessed: the form asks, and the answer
            // (the B items taxed) is what the taxable amounts settle on every other size
            if (!form.taxWork().resolved) form.items.indices.forEach { i -> form.editItem(i, "taxed") { it.copy(taxed = it.letter == "B") } }
            val work = form.taxWork()
            assertTrue(work.resolved, f.name)
            assertEquals(listOf(3, 11, 12), form.items.indices.filter { form.taxedOf(it, work) == true }, f.name)
            val totals = form.totals(work)
            // a price misread by a digit (here and there "3.64" as "9.64") is kept as read, never changed: the form points
            // at it with the price that adds up, and every other price is exact
            val read = p.items.map { "%d.%02d".format(it.amount / 100, it.amount % 100) }
            val wrong = read.indices.filter { read[it] != prices[it] }
            assertTrue(wrong.size <= 1, "${f.name}: $read")
            if (wrong.isEmpty()) assertEquals(0L, totals.gap, f.name)
            else {
                val i = wrong.single()
                // every price one digit from closing the gap is pointed at; the misread one among them, with its right price
                assertEquals(cents(prices[i]), form.misread(totals)[i], f.name)
                assertTrue("price" in p.items[i].unsure || "qty" in p.items[i].unsure, "${f.name}: ${p.items[i].unsure}")
                form.items[i] = form.items[i].copy(amount = prices[i])
            }
            val fixed = form.totals(form.taxWork())
            assertEquals(0L, fixed.gap, f.name)
            assertEquals(63_56L, fixed.untaxed, f.name)
            assertEquals(45_46L, fixed.taxed, f.name)
        }
    }

    @Test fun theReceiptBeingCheckedIsKeptThroughAnotherScreenAndARestartAndClearThrowsItOut() {
        show()
        scanFromReceipts(fixture.copyTo(File(tmp.root, "food-lion-receipt.webp")))
        // the user's changes: a name, a Checked mark, an envelope (Rent, so it's not the guess)
        tag("item.name.0").performTextReplacement("FL 2% MILK GALLON"); compose.waitForIdle()
        click("item.checked.1")
        click("item.envelope.2")
        compose.onAllNodes(hasText("Rent"), useUnmergedTree = true).onLast().performClick()
        compose.waitForIdle()
        // another screen, and back through Scan: as it was left
        click("nav.BUDGET")
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        until("Check the receipt")
        assertEquals("FL 2% MILK GALLON", text("item.name.0"))
        assertTrue(shows("✓ Checked"))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Rent"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        // a restart: the program closed mid-check and opened again
        val dir = dataDir()
        compose.setContent {}
        compose.waitForIdle()
        model.engine?.close()
        model = AppModel(runBlocking { Engine.start(dir) }).also { runBlocking { it.refresh() } }
        compose.setContent { DesktopApp(model, shell) }
        compose.waitForIdle()
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        until("Check the receipt")
        assertEquals("FL 2% MILK GALLON", text("item.name.0"))
        assertTrue(shows("✓ Checked"))
        assertEquals("112.20", text("breakdown.total"))
        // Clear: asks; Cancel keeps it
        click("receipt.clear")
        until("Clear this receipt?")
        click("clear.cancel")
        assertEquals("FL 2% MILK GALLON", text("item.name.0"))
        click("receipt.clear")
        click("clear.confirm")
        compose.waitForIdle()
        // gone: the photos, what was read, the changes; Scan starts over
        assertTrue(File(dir, "receipts").listFiles().orEmpty().none { it.name.endsWith(".enc") || it.name == "draft" }, File(dir, "receipts").list().orEmpty().joinToString())
        assertTrue(runBlocking { store().dao.receiptsNow() }.isEmpty())
        click("nav.RECEIPTS")
        clickText("Scan", exact = true)
        until("Choose an image or PDF")
    }

}
