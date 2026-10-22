package net.darkrelay.deaddropfinance.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SetupExample.extract: the fenced ```json block Export example saves. Synthetic text only. */
class SetupExampleTest {

    @Test fun extractsTheFencedJsonTrimmed() {
        val doc = "Some text.\n\n```json\n  { \"a\": 1 }  \n```\n\nMore text."
        assertEquals("{ \"a\": 1 }", SetupExample.extract(doc))
    }

    @Test fun nullWithNoFence() {
        assertNull(SetupExample.extract("no json fence here"))
        assertNull(SetupExample.extract("```json\nunterminated"))
    }
}
