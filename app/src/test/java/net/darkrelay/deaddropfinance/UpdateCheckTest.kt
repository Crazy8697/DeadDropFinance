package net.darkrelay.deaddropfinance

import net.darkrelay.deaddropfinance.UpdateCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The release rules. Synthetic releases only, versioned far ahead (99.x) so they never go stale. No network. */
class UpdateCheckTest {

    @Test fun versionsCompareNumerically() {
        assertTrue(UpdateCheck.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateCheck.isNewer("0.9.0", "0.10.0"))
        assertTrue(UpdateCheck.isNewer("v1.0", "0.99.99"))
        assertTrue(UpdateCheck.isNewer("99.0.1", "99.0.0"))
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2"))
        assertEquals(0, UpdateCheck.compareVersions("v1.2", "1.2.0.0"))
    }

    @Test fun oddVersionsAreNotNewer() {
        assertNull(UpdateCheck.parseVersion("1.2.0-beta"))
        assertNull(UpdateCheck.parseVersion("latest"))
        assertNull(UpdateCheck.parseVersion(""))
        assertNull(UpdateCheck.parseVersion(null))
        assertFalse(UpdateCheck.isNewer("99.0.0-rc1", "0.9.0"))
        assertFalse(UpdateCheck.isNewer("99.0.0", "not a version"))
    }

    @Test fun skipHidesOnlyThatVersionAtStartup() {
        val r = UpdateCheck.Release("99.1.0", "https://example.com/r")
        assertTrue(UpdateCheck.shouldOffer(r, null, manual = false))
        assertFalse(UpdateCheck.shouldOffer(r, "99.1.0", manual = false))
        assertTrue(UpdateCheck.shouldOffer(r, "99.0.0", manual = false))      // newer than the skipped one
        assertTrue(UpdateCheck.shouldOffer(r, "99.1.0", manual = true))       // a manual check ignores Skip
        assertTrue(UpdateCheck.shouldOffer(r, "not a version", manual = false))
    }

    @Test fun badBodiesFailWithoutThrowing() {
        assertEquals(Result.Failed, UpdateCheck.evaluate("not json", "0.5.0"))
        assertEquals(Result.Failed, UpdateCheck.evaluate("[1,2]", "0.5.0"))
        assertEquals(Result.UpToDate, UpdateCheck.evaluate("{}", "0.5.0"))
    }

    @Test fun downloadIsTheFixedLatestPath() {
        assertEquals("https://github.com/Crazy8697/DeadDropFinance/releases/latest/download/DeadDropFinance.apk",
            UpdateCheck.DOWNLOAD_URL)
        assertEquals("https://github.com/Crazy8697/DeadDropFinance/releases/latest/download/DeadDropFinance.apk.sha256",
            UpdateCheck.SHA256_DOWNLOAD_URL)
    }
}
