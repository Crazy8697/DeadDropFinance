package net.darkrelay.deaddropfinance.data

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 1.9.5: the receipt being checked is kept encrypted beside the receipt photos until it's saved or cleared: never taken
 * for a photo (so never in a backup's photos), kept by a sweep of photos whose receipt is gone, gone with the folder.
 */
class ReceiptDraftStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun theDraftIsKeptEncryptedApartFromThePhotosUntilCleared() {
        val dir = tmp.newFolder("receipts")
        val images = ReceiptImages(dir) { ByteArray(32) { it.toByte() } }
        images.write("r1", 0, "a photo".toByteArray())
        assertNull(images.readDraft())
        images.writeDraft("""{"store":"Sample Mart draft"}""")
        assertEquals("""{"store":"Sample Mart draft"}""", images.readDraft())
        // encrypted on disk
        val onDisk = File(dir, "draft").listFiles()!!.single()
        assertFalse(String(onDisk.readBytes(), Charsets.ISO_8859_1).contains("Sample Mart"))
        // not a photo, and a sweep of photos whose receipt is gone leaves it
        assertEquals(listOf("r1" to 0), images.stored())
        images.sweep(emptySet())
        assertTrue(images.stored().isEmpty())
        assertEquals("""{"store":"Sample Mart draft"}""", images.readDraft())
        // cleared: nothing left of it
        images.writeDraft(null)
        assertNull(images.readDraft())
        assertFalse(File(dir, "draft").exists())
    }
}
