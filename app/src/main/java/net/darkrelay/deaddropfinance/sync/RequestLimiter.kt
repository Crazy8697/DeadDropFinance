package net.darkrelay.deaddropfinance.sync

import net.darkrelay.deaddropfinance.data.BudgetDao
import net.darkrelay.deaddropfinance.data.MetaEntity

/** Where the limiter keeps its list of request times. */
interface LimiterStorage {
    suspend fun read(key: String): String?
    suspend fun write(key: String, value: String)
}

class MetaLimiterStorage(private val dao: BudgetDao) : LimiterStorage {
    override suspend fun read(key: String) = dao.meta(key)
    override suspend fun write(key: String, value: String) = dao.putMeta(MetaEntity(key, value))
}

/**
 * A rolling request budget per connection. SimpleFIN's developer notes say to make 24 requests or fewer per day,
 * and that going beyond the warning level disables the access token. The app stays under with [max] requests in any
 * [windowMs] (default 20 in 24 hours), so manual refreshes and retries can't use the budget up by accident.
 * Every request that reaches the Bridge is counted, including ones that fail.
 */
class RequestLimiter(
    private val storage: LimiterStorage,
    private val key: String,
    private val max: Int = DEFAULT_MAX,
    private val windowMs: Long = DAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Counts one request, or throws [SyncError.DailyLimit] saying when the budget frees up. */
    suspend fun take() {
        val now = clock()
        val times = load(now)
        if (times.size >= max) throw SyncError.DailyLimit(times.min() + windowMs, "SimpleFIN allows only a few requests a day, and today's are used up.")
        storage.write(key, (times + now).joinToString(","))
    }

    suspend fun used(): Int = load(clock()).size

    private suspend fun load(now: Long): List<Long> =
        storage.read(key)?.split(",")?.mapNotNull { it.toLongOrNull() }.orEmpty().filter { it > now - windowMs && it <= now + 60_000 }

    companion object {
        const val DEFAULT_MAX = 20
        const val DAY_MS = 24L * 60 * 60 * 1000
        fun keyFor(connectionId: String) = "simplefin.requests.$connectionId"
    }
}
