package net.darkrelay.deaddropfinance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against the real published release on GitHub. Skipped unless asked for with -Pddf.liveCheck=true, so the
 * normal test run never touches the network. [EXPECTED] is the version that is live when this is run.
 */
class LiveUpdateCheckTest {

    private val live = System.getProperty("ddf.liveCheck") == "true"
    private val expected = System.getProperty("ddf.liveExpected") ?: EXPECTED

    @Test fun olderInstallIsOfferedTheLiveRelease() {
        assumeTrue(live)
        val r = UpdateClient.check(UpdateCheck.REAL_UPDATE_URL, PREVIOUS)
        assertTrue("got $r", r is UpdateCheck.Result.Available)
        assertEquals(expected, (r as UpdateCheck.Result.Available).release.version)
    }

    @Test fun sameVersionIsNotOffered() {
        assumeTrue(live)
        assertEquals(UpdateCheck.Result.UpToDate, UpdateClient.check(UpdateCheck.REAL_UPDATE_URL, expected))
    }

    companion object {
        const val EXPECTED = "1.0.1"
        /** The release before it, which an installed copy updates from. */
        const val PREVIOUS = "1.0.0"
    }
}
