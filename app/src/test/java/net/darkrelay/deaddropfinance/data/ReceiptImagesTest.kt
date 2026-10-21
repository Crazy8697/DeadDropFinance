package net.darkrelay.deaddropfinance.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Receipt photos are stored encrypted, bound to their name, and gone when deleted. Invented bytes only. */
class ReceiptImagesTest {

    private val key = ByteArray(32) { (it * 3 + 1).toByte() }
    private fun store(dir: File = Files.createTempDirectory("ddf-img").toFile(), k: ByteArray = key) = ReceiptImages(dir, { k })

    /** A fake JPEG: its header and some text that would show up if it were stored as is. */
    private val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "INVENTED RECEIPT SAMPLE STORE 12.34".toByteArray()

    @Test fun storedEncryptedAndReadBack() {
        val dir = Files.createTempDirectory("ddf-img").toFile()
        val s = store(dir)
        s.write("r1", 0, photo)
        val raw = dir.listFiles()!!.single().readBytes()
        assertFalse("no JPEG header in the file", raw.toList().windowed(2).any { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() })
        assertFalse("no readable text", String(raw, Charsets.ISO_8859_1).contains("SAMPLE STORE"))
        assertArrayEquals(photo, s.read("r1", 0))
        // a new nonce every time: the same photo twice is two different files
        s.write("r1", 1, photo)
        val files = dir.listFiles()!!.sortedBy { it.name }.map { it.readBytes() }
        assertFalse(files[0].contentEquals(files[1]))
    }

    @Test fun theWrongKeyOrAMovedOrChangedFileReadsNothing() {
        val dir = Files.createTempDirectory("ddf-img").toFile()
        store(dir).write("r1", 0, photo)
        assertNull(store(dir, ByteArray(32)).read("r1", 0))
        File(dir, "r1-0.enc").copyTo(File(dir, "r2-0.enc"))
        assertNull("a file renamed to another receipt doesn't decrypt", store(dir).read("r2", 0))
        val f = File(dir, "r1-0.enc")
        val b = f.readBytes(); b[b.size - 3] = (b[b.size - 3].toInt() xor 1).toByte(); f.writeBytes(b)
        assertNull("a changed file doesn't decrypt", store(dir).read("r1", 0))
    }

    @Test fun deleteAndSweep() {
        val dir = Files.createTempDirectory("ddf-img").toFile()
        val s = store(dir)
        s.write("keep", 0, photo); s.write("gone", 0, photo); s.write("gone", 1, photo); s.write("orphan", 0, photo)
        assertEquals(2, s.pages("gone"))
        s.delete("gone")
        assertEquals(0, s.pages("gone"))
        s.sweep(setOf("keep"))
        assertEquals(listOf("keep-0.enc"), dir.listFiles()!!.map { it.name })
        assertTrue(s.read("keep", 0)!!.contentEquals(photo))
    }
}
