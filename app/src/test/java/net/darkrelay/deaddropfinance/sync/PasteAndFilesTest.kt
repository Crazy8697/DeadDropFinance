package net.darkrelay.deaddropfinance.sync

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paste cleanup, Teller file reading and amount parsing. All values are made up here. */
class PasteAndFilesTest {

    @Test fun applicationIdsAreFoundInsideLabelsAndSpaces() {
        assertEquals(Paste.Result.Ok("app_abc123"), Paste.appId("  Application ID:\n app_abc123 \n"))
        assertTrue(Paste.appId("abc123") is Paste.Result.Bad)
    }

    @Test fun setupTokensLoseLabelsQuotesAndLineBreaks() {
        val token = Base64.getEncoder().encodeToString("https://bridge.example.invalid/simplefin/claim/TEST".toByteArray())
        val messy = "Setup token: \"" + token.chunked(10).joinToString("\n ") + "\"\n"
        assertEquals(Paste.Result.Ok(token), Paste.setupToken(messy))
    }

    @Test fun badTokensGetAPlainMessageNotASilentRefusal() {
        val url = Paste.setupToken("https://bridge.example.invalid/simplefin")
        assertTrue(url is Paste.Result.Bad && url.message.contains("web address"))
        assertTrue(Paste.setupToken("") is Paste.Result.Bad)
        assertTrue(Paste.setupToken("not a token!!") is Paste.Result.Bad)
        val insecure = Base64.getEncoder().encodeToString("http://bridge.example.invalid/claim".toByteArray())
        assertTrue((Paste.setupToken(insecure) as Paste.Result.Bad).message.contains("secure"))
    }

    @Test fun providerAmountsBecomeCentsWithoutFloatingPoint() {
        assertEquals(-1234L, Cents.parse("-12.34"))
        assertEquals(1234L, Cents.parse("12.3400"))
        assertEquals(500L, Cents.parse("5"))
        assertEquals(0L, Cents.parse("0.005"))           // a half cent rounds to even
        assertEquals(2L, Cents.parse("0.015"))
        assertNull(Cents.parse("1e5"))
        assertNull(Cents.parse(""))
        assertNull(Cents.parse("abc"))
    }

    @Test fun tellerZipGivesBothFilesAndSinglePemsAreRecognized() {
        val cert = TestCerts.get().clientCertPem
        val key = TestCerts.get().clientKeyPem
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("certificate.pem")); z.write(cert.toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("private_key.pem")); z.write(key.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val f = TellerFiles.read(zip)
        assertNotNull(f.certificate)
        assertNotNull(f.privateKey)
        assertEquals(TellerFiles.Found(cert, null), TellerFiles.read(cert.toByteArray()))
        assertEquals(TellerFiles.Found(null, key), TellerFiles.read(key.toByteArray()))
    }

    @Test fun credentialsAreCheckedBeforeTheyreSaved() {
        val c = TestCerts.get()
        TellerCredentials.parse(c.clientCertPem, c.clientKeyPem)
        val mismatch = runCatching { TellerCredentials.parse(c.clientCertPem, c.otherKeyPem) }.exceptionOrNull()
        assertTrue(mismatch is TellerCredentials.Invalid && mismatch.message!!.contains("don't belong together"))
        assertTrue(runCatching { TellerCredentials.parse("nothing", c.clientKeyPem) }.exceptionOrNull() is TellerCredentials.Invalid)
        assertTrue(runCatching { TellerCredentials.parse(c.clientCertPem, "nothing") }.exceptionOrNull() is TellerCredentials.Invalid)
    }
}
