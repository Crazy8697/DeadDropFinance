package net.darkrelay.deaddropfinance.sync

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paste cleanup and amount parsing. All values are made up here. */
class PasteAndFilesTest {

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
}
