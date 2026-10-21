package net.darkrelay.deaddropfinance.lock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class LockPolicyTest {
    private val min = 60_000L

    @Test fun locksAfterTheTimeout() {
        assertFalse(LockPolicy.shouldLockOnReturn(true, true, backgroundedAt = 0, now = 4 * min, timeoutMinutes = 5))
        assertTrue(LockPolicy.shouldLockOnReturn(true, true, backgroundedAt = 0, now = 5 * min, timeoutMinutes = 5))
        assertTrue(LockPolicy.shouldLockOnReturn(true, true, backgroundedAt = 0, now = 31 * min, timeoutMinutes = 30))
        assertFalse(LockPolicy.shouldLockOnReturn(true, true, backgroundedAt = 0, now = 29 * min, timeoutMinutes = 30))
    }

    @Test fun immediatelyMeansAnyTimeAway() {
        assertTrue(LockPolicy.shouldLockOnReturn(true, true, backgroundedAt = 0, now = 1, timeoutMinutes = 0))
    }

    @Test fun neverLocksWhenOffOrImpossible() {
        assertFalse(LockPolicy.shouldLockOnReturn(false, true, 0, 60 * min, 5))
        assertFalse(LockPolicy.shouldLockOnReturn(true, false, 0, 60 * min, 5))      // no screen lock on the phone
        assertFalse(LockPolicy.shouldLockOnReturn(true, true, null, 60 * min, 5))
        assertTrue(LockPolicy.shouldLockOnColdStart(true, true))
        assertFalse(LockPolicy.shouldLockOnColdStart(true, false))
        assertFalse(LockPolicy.shouldLockOnColdStart(false, true))
    }

    @Test fun timeoutChoices() = assertEquals(listOf(0, 1, 5, 15, 30), LockPolicy.TIMEOUTS)
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LockSettingsTest {
    @Test fun defaults() {
        val s = LockSettings(ApplicationProvider.getApplicationContext())
        assertTrue(s.lockEnabled)                // on by default
        assertEquals(5, s.timeoutMinutes)        // 5 minutes by default
        assertFalse(s.hideScreen)                // off by default
        s.timeoutMinutes = 15
        s.hideScreen = true
        assertEquals(15, LockSettings(ApplicationProvider.getApplicationContext()).timeoutMinutes)
        assertTrue(LockSettings(ApplicationProvider.getApplicationContext()).hideScreen)
    }
}
