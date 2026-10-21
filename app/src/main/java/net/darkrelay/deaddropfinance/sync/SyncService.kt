package net.darkrelay.deaddropfinance.sync

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.AccountType
import net.darkrelay.deaddropfinance.data.BudgetStore
import net.darkrelay.deaddropfinance.data.ConnectionEntity
import net.darkrelay.deaddropfinance.data.ConnectionStatus
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.data.Provider
import net.darkrelay.deaddropfinance.data.SecretStore
import net.darkrelay.deaddropfinance.data.SyncLogEntity
import net.darkrelay.deaddropfinance.data.TransactionKind

/**
 * Bank sync for the whole app: connecting (a SimpleFIN claim), linking provider accounts to app accounts, syncing,
 * and disconnecting. Every connection runs from this phone with the user's own credentials; nothing goes through
 * DarkRelay. One sync runs at a time. Providers are adapters behind SyncSource; SimpleFIN is the only one.
 */
class SyncService(
    private val store: BudgetStore,
    private val secrets: SecretStore,
    val settings: SyncSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val httpFor: () -> Http = { Http() },
    private val allowLoopbackHttp: Boolean = false,
    private val limiterMax: Int = RequestLimiter.DEFAULT_MAX,
    /** Tests only: a different adapter for a connection. */
    private val sourceFor: ((ConnectionEntity) -> SyncSource)? = null,
) {
    /**
     * Syncs run here, off the main thread and not tied to any screen: leaving a screen (or the app locking) never
     * cancels a sync halfway, and nothing a sync does can throw into the UI.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<String, Deferred<Result>>()

    /** Stops every sync (Erase everything closes the database right after). */
    fun shutdown() = scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    private val runningLock = Mutex()
    private val engine = SyncEngine(store)
    private val lock = Mutex()
    private fun today() = java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    /** A problem the user has to fix, shown once per session per connection, with a button straight to the fix. */
    data class FixPrompt(val connectionId: String, val code: String, val message: String)

    private val promptedThisSession = mutableSetOf<String>()
    private val _prompt = MutableStateFlow<FixPrompt?>(null)
    val prompt: StateFlow<FixPrompt?> = _prompt
    fun dismissPrompt() { _prompt.value = null }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** A masked hint of a connection's token or access URL: "...last 4", never the value. */
    fun secretHint(conn: ConnectionEntity): String? = when (conn.provider) {
        Provider.SIMPLEFIN -> secrets.hint(SecretStore.simplefinUrl(conn.id))
    }

    // ---- connecting -------------------------------------------------------------------------------------------

    /** Claims a SimpleFIN setup token (once), saves only the access URL, and lists the accounts to link. */
    suspend fun connectSimpleFin(setupToken: String, existingId: String? = null): String = withContext(Dispatchers.IO) {
        val access = SimpleFinSource.claim(setupToken, httpFor(), allowLoopbackHttp)
        // a new token for an existing connection keeps its links and synced history
        val conn = existingId?.let { store.sync.connection(it) }?.copy(status = ConnectionStatus.OK, fixCode = null)
            ?: ConnectionEntity(provider = Provider.SIMPLEFIN, name = "SimpleFIN", createdAt = clock())
        promptedThisSession.remove(conn.id)
        secrets.put(SecretStore.simplefinUrl(conn.id), access)
        store.sync.upsertConnection(conn)
        try {
            refreshAccounts(conn.id)
        } catch (e: SyncError) {
            store.sync.upsertConnection(conn.copy(lastResult = e.message))
        }
        conn.id
    }

    fun source(conn: ConnectionEntity): SyncSource = sourceFor?.invoke(conn) ?: when (conn.provider) {
        Provider.SIMPLEFIN -> SimpleFinSource(
            secrets.get(SecretStore.simplefinUrl(conn.id)) ?: throw SyncError.NeedsFix(FixCode.SIMPLEFIN_TOKEN, "This connection's SimpleFIN login is missing. Connect again with a new setup token."),
            httpFor(), RequestLimiter(MetaLimiterStorage(store.dao), RequestLimiter.keyFor(conn.id), limiterMax, clock = clock), zone, allowLoopbackHttp,
        )
    }

    /** Reads the provider's account list and keeps one link row per account (new ones start unlinked). */
    suspend fun refreshAccounts(connectionId: String): List<LinkedAccountEntity> = withContext(Dispatchers.IO) { refreshAccountsNow(connectionId) }

    private suspend fun refreshAccountsNow(connectionId: String): List<LinkedAccountEntity> {
        val conn = store.sync.connection(connectionId) ?: return emptyList()
        val r = source(conn).accounts()
        val existing = store.sync.linkedOf(connectionId).associateBy { it.providerAccountId }
        for (d in r.accounts) {
            val a = d.account
            val old = existing[a.id]
            store.sync.upsertLinked(
                old?.copy(
                    providerName = a.name, mask = a.mask, providerBalance = a.balance ?: old.providerBalance, providerBalanceAt = clock(),
                    providerBalanceDate = a.balanceDate ?: old.providerBalanceDate,
                )
                    ?: LinkedAccountEntity(
                        connectionId = connectionId, providerAccountId = a.id, providerName = a.name, mask = a.mask, providerBalance = a.balance,
                        providerBalanceAt = clock(), providerBalanceDate = a.balanceDate,
                    ),
            )
        }
        store.sync.upsertConnection(conn.copy(providerMessages = r.messages.joinToString("\n").ifEmpty { null }))
        return store.sync.linkedOf(connectionId)
    }

    // ---- linking ------------------------------------------------------------------------------------------------

    enum class History { NEWEST, DAYS_30, DAYS_90, YEAR }

    sealed class Target {
        data class Existing(val accountId: String) : Target()
        /** A new account; [trackOnly] makes it "Track only, not in the budget". */
        data class New(val name: String, val type: AccountType = AccountType.BANK, val trackOnly: Boolean = false) : Target()
        /** Don't sync this one. If it was linked, [removeFromBudget] archives its app account (taking it off the budget). */
        data class Ignore(val removeFromBudget: Boolean = true) : Target()
    }

    data class LinkChoice(val linkedId: String, val target: Target, val history: History = History.NEWEST)

    /** Where the first sync starts: the newest transaction already in the account, or 30 days back if it's empty. */
    suspend fun historyStart(accountId: String?, history: History): LocalDate {
        val t = today()
        return when (history) {
            History.DAYS_30 -> t.minusDays(30)
            History.DAYS_90 -> t.minusDays(90)
            History.YEAR -> t.minusYears(1)
            History.NEWEST -> accountId?.let { id -> store.sync.transactionsOf(id).maxOfOrNull { it.date } } ?: t.minusDays(30)
        }
    }

    /** Applies the user's link choices. Never deletes, replaces or duplicates an existing account. */
    suspend fun link(choices: List<LinkChoice>) = withContext(Dispatchers.IO) {
        for (c in choices) {
            val l = store.sync.linkedNow().firstOrNull { it.id == c.linkedId } ?: continue
            when (val t = c.target) {
                is Target.Ignore -> {
                    store.sync.upsertLinked(l.copy(accountId = null))
                    if (t.removeFromBudget) l.accountId?.let { archive(it) }
                }
                is Target.Existing -> store.sync.upsertLinked(
                    l.copy(accountId = t.accountId, historyFrom = historyStart(t.accountId, c.history), lastSyncedDate = null, setStartBalance = false),
                )
                is Target.New -> {
                    val start = historyStart(null, c.history)
                    val id = store.write {
                        put(null, AccountEntity(name = t.name.trim().ifEmpty { l.providerName }, type = t.type, startingBalance = 0, startDate = start,
                            trackOnly = t.trackOnly, sortOrder = store.dao.accountsNow().size, createdAt = now, updatedAt = now)).id
                    }
                    store.sync.upsertLinked(l.copy(accountId = id, historyFrom = start, lastSyncedDate = null, setStartBalance = true))
                }
            }
        }
    }

    // ---- syncing ------------------------------------------------------------------------------------------------

    enum class Trigger { OPEN, BACKGROUND, MANUAL }

    data class Result(val connectionId: String, val ok: Boolean, val outcome: SyncEngine.Outcome, val message: String)

    suspend fun syncAll(trigger: Trigger): List<Result> {
        val out = mutableListOf<Result>()
        for (c in store.sync.connectionsNow()) {
            if (trigger != Trigger.MANUAL) {
                // SimpleFIN's Bridge updates about daily and asks for 24 requests a day or fewer: automatic syncs space out more
                val gap = if (c.provider == Provider.SIMPLEFIN) SyncSettings.SIMPLEFIN_AUTO_GAP_MS else SyncSettings.OPEN_MIN_GAP_MS
                if (clock() - settings.lastAttempt(c.id) < gap) continue
                if (trigger == Trigger.BACKGROUND && c.status == ConnectionStatus.NEEDS_FIX) continue
            }
            out += syncConnection(c.id, trigger)
        }
        return out
    }

    /**
     * Syncs one connection. A sync already running for it (a background one, say) is joined instead of starting a
     * second: overlapping requests make one run. Never throws (except to a caller that's cancelled while waiting).
     */
    suspend fun syncConnection(connectionId: String, trigger: Trigger = Trigger.MANUAL): Result {
        val job = runningLock.withLock {
            running[connectionId]?.takeIf { it.isActive } ?: scope.async {
                try {
                    lock.withLock {
                        _busy.value = true
                        try { syncLocked(connectionId, trigger) } finally { _busy.value = false }
                    }
                } finally {
                    runningLock.withLock { if (running[connectionId]?.isActive != true) running.remove(connectionId) }
                }
            }.also { running[connectionId] = it }
        }
        return job.await()
    }

    private suspend fun syncLocked(connectionId: String, trigger: Trigger): Result {
        val conn = store.sync.connection(connectionId) ?: return Result(connectionId, false, SyncEngine.Outcome(), "That connection is gone.")
        settings.setLastAttempt(conn.id, clock())
        var total = SyncEngine.Outcome()
        try {
            val links = store.sync.linkedOf(conn.id).filter { it.accountId != null }
            val src = source(conn)
            val today = today()
            if (links.isEmpty()) {
                refreshAccountsNow(conn.id)
                return finish(conn, true, total, "No accounts are linked yet.", emptyList())
            }
            val wanted = links.associate { l ->
                l.providerAccountId to minOf(l.lastSyncedDate?.minusDays(src.overlapDays) ?: l.historyFrom ?: today.minusDays(30), today)
            }
            // A long history goes window by window, each saved as it lands: if a sync stops halfway (the request
            // limit, the network, the app closing), the next one starts from the last saved window, with no doubles.
            val messages = linkedSetOf<String>()
            val latest = HashMap<String, LinkedAccountEntity>()
            links.forEach { latest[it.providerAccountId] = it }
            var windowStart = wanted.values.min()
            while (!windowStart.isAfter(today)) {
                val windowEnd = minOf(windowStart.plusDays(src.maxRangeDays - 1), today)
                val w = wanted.filterValues { !it.isAfter(windowEnd) }.mapValues { maxOf(it.value, windowStart) }
                if (w.isNotEmpty()) {
                    val fetched = src.fetch(w, windowEnd)
                    messages += fetched.messages
                    for (d in fetched.accounts) {
                        val l = latest[d.account.id] ?: continue
                        val from = w[d.account.id] ?: continue
                        val acct = l.accountId ?: continue
                        val account = store.dao.account(acct)
                        if (account == null || account.deleted) continue
                        total += engine.apply(conn.provider, l.providerAccountId, acct, d, from, windowEnd, offBudget = !account.onBudget)
                        val nl = l.copy(
                            providerName = d.account.name, providerBalance = d.account.balance ?: l.providerBalance, providerBalanceAt = clock(),
                            providerBalanceDate = d.account.balanceDate ?: l.providerBalanceDate, lastSyncedDate = windowEnd,
                        )
                        store.sync.upsertLinked(nl)
                        latest[d.account.id] = nl
                    }
                }
                windowStart = windowEnd.plusDays(1)
            }
            // auto-approve (off by default): rule matches among the new rows are sorted as they arrive
            if (settings.autoApprove && total.newIds.isNotEmpty()) {
                runCatching { net.darkrelay.deaddropfinance.domain.Sorting(store).autoApprove(total.newIds) }
            }
            // receipts waiting for their charge: attached when the match is clear
            if (total.newIds.isNotEmpty()) runCatching { net.darkrelay.deaddropfinance.domain.Receipts(store).matchWaiting() }
            // a new account's starting balance is set once the whole history is in
            for (l in latest.values) {
                val acct = l.accountId ?: continue
                val bal = l.providerBalance ?: continue
                if (l.setStartBalance && l.lastSyncedDate == today) {
                    setStartingBalance(acct, bal)
                    store.sync.upsertLinked(l.copy(setStartBalance = false))
                }
            }
            return finish(conn, true, total, summary(total), messages.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SyncError) {
            return failed(conn, e, trigger, total)
        } catch (e: SecretStore.Unavailable) {
            return failed(conn, SyncError.NeedsFix(FixCode.SIMPLEFIN_TOKEN, e.message ?: "Saved credentials can't be read."), trigger, total)
        } catch (e: Throwable) {
            // anything else is a bug in the app: recorded and shown, never a crash
            return failed(conn, SyncError.BadData("Sync stopped because of a problem in the app (${e.javaClass.simpleName}). Nothing was lost; it will try again."), trigger, total)
        }
    }

    /** A new account's starting balance makes its posted balance equal the provider's balance after the first sync. */
    private suspend fun setStartingBalance(accountId: String, providerBalance: Long) {
        val b = snapshot()
        store.write {
            val a = store.dao.account(accountId) ?: return@write
            val moved = b.postedBalance(accountId) - a.startingBalance       // what the counted transactions add up to
            put(a, a.copy(startingBalance = providerBalance - moved, updatedAt = now))
        }
    }

    private suspend fun snapshot() = net.darkrelay.deaddropfinance.domain.Budget(
        store.dao.accountsNow(), store.dao.envelopesNow(), store.dao.transactionsNow(), store.dao.linesNow(), store.dao.movesNow(),
    )

    private fun summary(o: SyncEngine.Outcome): String {
        val parts = mutableListOf<String>()
        parts += if (o.added == 1) "1 new transaction" else "${o.added} new transactions"
        if (o.linked > 0) parts += "${o.linked} matched to ones already here"
        if (o.updated > 0) parts += "${o.updated} updated"
        if (o.removed > 0) parts += "${o.removed} pending removed"
        return parts.joinToString(", ") + "."
    }

    private suspend fun finish(conn: ConnectionEntity, ok: Boolean, o: SyncEngine.Outcome, msg: String, providerMessages: List<String>): Result {
        val now = clock()
        val text = (listOf(msg) + o.notes).joinToString(" ")
        store.sync.upsertConnection(
            conn.copy(status = ConnectionStatus.OK, fixCode = null, lastSyncAt = now, lastResult = text, providerMessages = providerMessages.joinToString("\n").ifEmpty { null }),
        )
        store.sync.insertLog(SyncLogEntity(connectionId = conn.id, at = now, ok = ok, added = o.added, updated = o.updated + o.linked, removed = o.removed, message = text))
        store.sync.pruneLog(now - LOG_KEEP_MS)
        return Result(conn.id, ok, o, text)
    }

    private suspend fun failed(conn: ConnectionEntity, e: SyncError, trigger: Trigger, partial: SyncEngine.Outcome = SyncEngine.Outcome()): Result {
        val now = clock()
        val text = when (e) {
            is SyncError.NeedsFix -> e.message ?: "This connection needs attention."
            is SyncError.DailyLimit -> "${e.message} It will sync again after ${java.time.Instant.ofEpochMilli(e.retryAt).atZone(zone).toLocalTime().withSecond(0).withNano(0)}."
            is SyncError.RateLimited, is SyncError.Network -> "Couldn't sync right now. It will try again later."
            is SyncError.BadData -> e.message ?: "The provider sent something that couldn't be read."
        } + if (partial.added + partial.linked + partial.updated + partial.removed > 0) " What came in before it stopped is saved: ${summary(partial)}" else ""
        val fix = e as? SyncError.NeedsFix
        store.sync.upsertConnection(
            conn.copy(status = if (fix != null) ConnectionStatus.NEEDS_FIX else conn.status, fixCode = fix?.code ?: conn.fixCode, lastResult = text),
        )
        store.sync.insertLog(SyncLogEntity(connectionId = conn.id, at = now, ok = false, added = partial.added, updated = partial.updated + partial.linked, removed = partial.removed, message = text))
        if (fix != null && trigger != Trigger.BACKGROUND && promptedThisSession.add(conn.id)) {
            _prompt.value = FixPrompt(conn.id, fix.code, text)
        }
        return Result(conn.id, false, partial, text)
    }

    // ---- linking changes, disconnecting -------------------------------------------------------------------------

    /**
     * Re-points one provider account (from the account screen). Null stops syncing it; then [removeFromBudget]
     * (the default) archives the app account it fed, so its balance leaves Ready to Assign.
     */
    suspend fun setLink(linkedId: String, accountId: String?, removeFromBudget: Boolean = true) = withContext(Dispatchers.IO) {
        val l = store.sync.linkedNow().firstOrNull { it.id == linkedId } ?: return@withContext
        if (l.accountId == accountId) return@withContext
        store.sync.upsertLinked(
            l.copy(accountId = accountId, historyFrom = accountId?.let { historyStart(it, History.NEWEST) }, lastSyncedDate = null, setStartBalance = false),
        )
        if (accountId == null && removeFromBudget) l.accountId?.let { archive(it) }
    }

    private suspend fun archive(accountId: String) = store.write {
        val a = store.dao.account(accountId) ?: return@write
        if (!a.archived) put(a, a.copy(archived = true, updatedAt = now))
    }

    /**
     * Deletes the connection's secrets and stops syncing it. [removeTransactions] false (the default) keeps the
     * synced transactions as ordinary ones; true removes them (tombstones, as every delete). [removeFromBudget] true
     * (the default) archives the app accounts it fed, so their balances leave Ready to Assign; false keeps them in
     * the budget, tracked by hand.
     */
    suspend fun disconnect(connectionId: String, removeTransactions: Boolean = false, removeFromBudget: Boolean = true) = withContext(Dispatchers.IO) {
        val conn = store.sync.connection(connectionId) ?: return@withContext
        val linked = store.sync.linkedOf(connectionId)
        val prefixes = linked.map { SyncEngine.externalPrefix(conn.provider, it.providerAccountId) }
        try {
            secrets.removeWhere(SecretStore.forConnection(connectionId))
        } catch (e: SecretStore.Unavailable) {
            // unreadable: left as it is (never overwritten); the connection is still removed
        }
        store.write {
            val synced = store.dao.transactionsNow().filter { t -> prefixes.any { p -> t.externalId?.startsWith(p) == true } }
            for (t in synced) {
                if (removeTransactions) {
                    put(t, t.copy(deleted = true, updatedAt = now))
                    store.dao.linesOf(t.id).forEach { put(it, it.copy(deleted = true, updatedAt = now)) }
                } else {
                    put(t, t.copy(externalId = null, updatedAt = now))
                }
            }
        }
        if (removeFromBudget) linked.mapNotNull { it.accountId }.distinct().forEach { archive(it) }
        store.sync.deleteLinkedOf(connectionId)
        store.sync.deleteLogOf(connectionId)
        store.sync.deleteConnection(connectionId)
        store.dao.putMeta(net.darkrelay.deaddropfinance.data.MetaEntity(RequestLimiter.keyFor(connectionId), ""))
        settings.forget(connectionId)
        promptedThisSession.remove(connectionId)
        if (_prompt.value?.connectionId == connectionId) _prompt.value = null
    }

    // ---- balance check ------------------------------------------------------------------------------------------

    /** Posted balance of an app account (pending rows and history before its starting balance left out). */
    suspend fun postedBalance(accountId: String): Long = snapshot().postedBalance(accountId)

    companion object {
        const val LOG_KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
