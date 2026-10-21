package net.darkrelay.deaddropfinance

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The release build never carries a test hook: the update check always goes to the real GitHub URL and compares
 * against the real versionName. Runs in the release unit tests (testReleaseUnitTest); in a debug build the test
 * is skipped, since a debug build may be pointed at a test server on purpose.
 */
class BuildConfigUpdateTest {

    @Test fun releaseUsesTheRealUrlAndVersion() {
        assumeTrue(BuildConfig.BUILD_TYPE == "release")
        assertEquals(UpdateCheck.REAL_UPDATE_URL, BuildConfig.UPDATE_URL)
        assertEquals("", BuildConfig.VERSION_OVERRIDE)
    }

    @Test fun realUrlIsGithubForThisApp() {
        assertEquals("https://api.github.com/repos/Crazy8697/DeadDropFinance/releases/latest", UpdateCheck.REAL_UPDATE_URL)
    }
}
