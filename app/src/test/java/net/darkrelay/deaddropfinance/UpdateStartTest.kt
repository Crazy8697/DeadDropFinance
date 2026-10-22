package net.darkrelay.deaddropfinance

import org.junit.Assert.assertEquals
import org.junit.Test

/** Download and install on a metered network asks first; on an unmetered one it starts right away (0.15.2). */
class UpdateStartTest {

    @Test fun meteredAsksFirstAndDoesNotStart() {
        val calls = mutableListOf<String>()
        UpdateStart.begin(metered = true, ask = { calls += "ask" }, start = { calls += "start" })
        assertEquals(listOf("ask"), calls)
    }

    @Test fun unmeteredStartsRightAwayWithoutAsking() {
        val calls = mutableListOf<String>()
        UpdateStart.begin(metered = false, ask = { calls += "ask" }, start = { calls += "start" })
        assertEquals(listOf("start"), calls)
    }

    @Test fun theMessageNamesTheSizeInMegabytes() {
        assertEquals("You're on mobile data. This download is about 41 MB.", UpdateStart.message(41_165_299L))
        assertEquals("You're on mobile data. This download is about 1 MB.", UpdateStart.message(10L))
        assertEquals("You're on mobile data. This download may be large.", UpdateStart.message(null))
    }
}
