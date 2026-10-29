package net.darkrelay.deaddropfinance.serversync

/**
 * Plain SQL inside one database transaction (platform.kt opens it on each platform). Server sync writes a synced row
 * from its column values, the way backup restore does, because a change from the server carries columns, not an entity.
 * Values are Long, String or null; a Boolean is written as 0 or 1.
 */
interface RawSql {
    suspend fun exec(sql: String, args: List<Any?> = emptyList())
    suspend fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>
}

internal fun bindable(v: Any?): Any? = when (v) {
    null -> null
    is Boolean -> if (v) 1L else 0L
    is Int -> v.toLong()
    is Long, is String -> v
    else -> v.toString()
}
