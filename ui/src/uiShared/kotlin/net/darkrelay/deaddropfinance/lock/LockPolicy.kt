package net.darkrelay.deaddropfinance.lock

/** When the app should lock: pure, so the timing is unit tested. */
object LockPolicy {
    /** Timeout choices in minutes; 0 means "immediately". */
    val TIMEOUTS = listOf(0, 1, 5, 15, 30)
    const val DEFAULT_TIMEOUT = 5

    /** True when coming back to the foreground after [backgroundedAt] should lock. */
    fun shouldLockOnReturn(enabled: Boolean, deviceSecure: Boolean, backgroundedAt: Long?, now: Long, timeoutMinutes: Int): Boolean {
        if (!enabled || !deviceSecure || backgroundedAt == null) return false
        if (timeoutMinutes <= 0) return true
        return now - backgroundedAt >= timeoutMinutes * 60_000L
    }

    /** A cold start always locks when app lock is on and can work. */
    fun shouldLockOnColdStart(enabled: Boolean, deviceSecure: Boolean) = enabled && deviceSecure
}
