package net.darkrelay.deaddropfinance.sync

import java.time.LocalDate
import java.time.ZoneId
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Bank sync for the whole app: connecting (SimpleFIN claim, Teller enrollment), linking provider accounts to app
 * accounts, syncing, and disconnecting. Every connection runs from this phone with the user's own credentials;
 * nothing goes through DarkRelay. One sync runs at a time.
 */
class SyncService(
    private val store: BudgetStore,
    private val secrets: SecretStore,
    val settings: SyncSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val httpFor: (SSLSocketFactory?) -> Http = { Http(it) },
    private val tellerBaseUrl: String = TellerSource.BASE_URL,
    private val tellerTrust: Array<TrustManager>? = null,
    private val allowLoopbackHttp: Boolean = false,
    private val limiterMax: Int = RequestLimiter.DEFAULT_MAX,
) {
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

    // ---- Teller application credentials -----------------------------------------------------------------------

    /** Checks and saves the user's Teller certificate and private key (PEM). Throws TellerCredentials.Invalid. */
    fun saveTellerCredentials(certPem: String, keyPem: String) {
        TellerCredentials.parse(certPem, keyPem)
        secrets.put(SecretStore.TELLER_CERT, certPem)
        secrets.put(SecretStore.TELLER_KEY, keyPem)
    }

    fun hasTellerCredentials() = secrets.has(SecretStore.TELLER_CERT) && secrets.has(SecretStore.TELLER_KEY)

    /** When the saved certificate expires, for the screen (the certificate itself is never shown). */
    fun tellerCertificateExpires(): java.util.Date? = try {
        val cert = secrets.get(SecretStore.TELLER_CERT)
        val key = secrets.get(SecretStore.TELLER_KEY)
        if (cert == null || key == null) null else TellerCredentials.parse(cert, key).expires
    } catch (e: Exception) {
        null
    }

    /** A masked hint of a connection's token or access URL: "...last 4", never the value. */
    fun secretHint(conn: ConnectionEntity): String? = when (conn.provider) {
        Provider.TELLER -> secrets.hint(SecretStore.tellerToken(conn.id))
        Provider.SIMPLEFIN -> secrets.hint(SecretStore.simplefinUrl(conn.id))
    }

    private fun tellerSsl(): SSLSocketFactory {
        val cert = secrets.get(SecretStore.TELLER_CERT)
        val key = secrets.get(SecretStore.TELLER_KEY)
        if (cert == null || key == null) throw SyncError.NeedsFix(FixCode.TELLER_CERTIFICATE, "Your Teller certificate isn't saved on this phone. Import it from your Teller dashboard.")
        val creds = try { TellerCredentials.parse(cert, key) } catch (e: TellerCredentials.Invalid) { throw SyncError.NeedsFix(FixCode.TELLER_CERTIFICATE, e.message ?: "Your Teller certificate can't be used.") }
        return creds.sslSocketFactory(tellerTrust)
    }

    // ---- connecting -------------------------------------------------------------------------------------------

    /** Claims a SimpleFIN setup token (once), saves only the access URL, and lists the accounts to link. */
    suspend fun connectSimpleFin(setupToken: String, existingId: String? = null): String {
        val access = SimpleFinSource.claim(setupToken, httpFor(null), allowLoopbackHttp)
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
        return conn.id
    }

    /** Saves a Teller enrollment from Teller Connect. For a reconnect ([existingId]) the same connection is kept. */
    suspend fun connectTeller(accessToken: String, enrollmentId: String, institution: String, existingId: String? = null): String {
        val old = existingId?.let { store.sync.connection(it) }
        val conn = old?.copy(status = ConnectionStatus.OK, fixCode = null, enrollmentId = enrollmentId)
            ?: ConnectionEntity(provider = Provider.TELLER, name = institution.ifBlank { "Bank" }, enrollmentId = enrollmentId, createdAt = clock())
        secrets.put(SecretStore.tellerToken(conn.id), accessToken)
        store.sync.upsertConnection(conn)
        promptedThisSession.remove(conn.id)
        try {
            refreshAccounts(conn.id)
        } catch (e: SyncError) {
            store.sync.upsertConnection(conn.copy(lastResult = e.message))
        }
        return conn.id
    }

    fun source(conn: ConnectionEntity): SyncSource = when (conn.provider) {
        Provider.SIMPLEFIN -> SimpleFinSource(
            secrets.get(SecretStore.simplefinUrl(conn.id)) ?: throw SyncError.NeedsFix(FixCode.SIMPLEFIN_TOKEN, "This connection's SimpleFIN login is missing. Connect again with a new setup token."),
            httpFor(null), RequestLimiter(MetaLimiterStorage(store.dao), RequestLimiter.keyFor(conn.id), limiterMax, clock = clock), zone, allowLoopbackHttp,
        )
        Provider.TELLER -> TellerSource(
            secrets.get(SecretStore.tellerToken(conn.id)) ?: throw SyncError.NeedsFix(FixCode.TELLER_RECONNECT, "This bank connection's Teller token is missing. Reconnect the bank."),
            httpFor(tellerSsl()), tellerBaseUrl,
        )
    }

    /** Reads the provider's account list and keeps one link row per account (new ones start unlinked). */
    suspend fun refreshAccounts(connectionId: String): List<LinkedAccountEntity> {
        val conn = store.sync.connection(connectionId) ?: return emptyList()
        val r = source(conn).accounts()
        val existing = store.sync.linkedOf(connectionId).associateBy { it.providerAccountId }
        for (d in r.accounts) {
            val a = d.account
            val old = existing[a.id]
            store.sync.upsertLinked(
                old?.copy(providerName = a.name, mask = a.mask, providerBalance = a.balance ?: old.providerBalance, providerBalanceAt = clock())
                    ?: LinkedAccountEntity(connectionId = connectionId, providerAccountId = a.id, providerName = a.name, mask = a.mask, providerBalance = a.balance, providerBalanceAt = clock()),
            )
        }
        store.sync.upsertConnection(conn.copy(providerMessages = r.messages.joinToString("\n").ifEmpty { null }))
        return store.sync.linkedOf(connectionId)
    }

    // ---- linking ------------------------------------------------------------------------------------------------

    enum class History { NEWEST, DAYS_30, DAYS_90, YEAR }

    sealed class Target {
        data class Existing(val accountId: String) : Target()
        data class New(val name: String, val type: AccountType = AccountType.BANK) : Target()
        data object Ignore : Target()
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
    suspend fun link(choices: List<LinkChoice>) {
        for (c in choices) {
            val l = store.sync.linkedNow().firstOrNull { it.id == c.linkedId } ?: continue
            when (val t = c.target) {
                Target.Ignore -> store.sync.upsertLinked(l.copy(accountId = null))
                is Target.Existing -> store.sync.upsertLinked(
                    l.copy(accountId = t.accountId, historyFrom = historyStart(t.accountId, c.history), lastSyncedDate = null, setStartBalance = false),
                )
                is Target.New -> {
                    val start = historyStart(null, c.history)
                    val id = store.write {
                        put(null, AccountEntity(name = t.name.trim().ifEmpty { l.providerName }, type = t.type, startingBalance = 0, startDate = start,
                            sortOrder = store.dao.accountsNow().size, createdAt = now, updatedAt = now)).id
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

    suspend fun syncConnection(connectionId: String, trigger: Trigger = Trigger.MANUAL): Result = lock.withLock {
        _busy.value = true
        try { syncLocked(connectionId, trigger) } finally { _busy.value = false }
    }

    private suspend fun syncLocked(connectionId: String, trigger: Trigger): Result {
        val conn = store.sync.connection(connectionId) ?: return Result(connectionId, false, SyncEngine.Outcome(), "That connection is gone.")
        settings.setLastAttempt(conn.id, clock())
        val links = store.sync.linkedOf(conn.id).filter { it.accountId != null }
        try {
            val src = source(conn)
            val today = today()
            if (links.isEmpty()) {
                refreshAccounts(conn.id)
                return finish(conn, true, SyncEngine.Outcome(), "No accounts are linked yet.", emptyList())
            }
            val wanted = links.associate { l ->
                l.providerAccountId to (l.lastSyncedDate?.minusDays(src.overlapDays) ?: l.historyFrom ?: today.minusDays(30))
            }
            val fetched = src.fetch(wanted, today)
            var total = SyncEngine.Outcome()
            for (d in fetched.accounts) {
                val l = links.firstOrNull { it.providerAccountId == d.account.id } ?: continue
                val acct = l.accountId ?: continue
                if (store.dao.account(acct)?.deleted != false) continue
                total += engine.apply(conn.provider, l.providerAccountId, acct, d, wanted.getValue(l.providerAccountId))
                var nl = l.copy(providerName = d.account.name, providerBalance = d.account.balance ?: l.providerBalance, providerBalanceAt = clock(), lastSyncedDate = today)
                if (l.setStartBalance && d.account.balance != null) {
                    setStartingBalance(acct, d.account.balance)
                    nl = nl.copy(setStartBalance = false)
                }
                store.sync.upsertLinked(nl)
            }
            val msg = summary(total)
            return finish(conn, true, total, msg, fetched.messages)
        } catch (e: SyncError) {
            return failed(conn, e, trigger)
        } catch (e: SecretStore.Unavailable) {
            return failed(conn, SyncError.NeedsFix(if (conn.provider == Provider.TELLER) FixCode.TELLER_RECONNECT else FixCode.SIMPLEFIN_TOKEN, e.message ?: "Saved credentials can't be read."), trigger)
        }
    }

    /** A new account's starting balance makes its posted balance equal the provider's balance after the first sync. */
    private suspend fun setStartingBalance(accountId: String, providerBalance: Long) = store.write {
        val a = store.dao.account(accountId) ?: return@write
        val posted = store.sync.transactionsOf(accountId).filter { it.kind == TransactionKind.NORMAL && !it.pending }.sumOf { it.amount }
        val transfers = store.dao.transactionsNow().filter { it.kind == TransactionKind.TRANSFER }
            .sumOf { t -> (if (t.transferAccountId == accountId) t.amount else 0L) - (if (t.accountId == accountId) t.amount else 0L) }
        put(a, a.copy(startingBalance = providerBalance - posted - transfers, updatedAt = now))
    }

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

    private suspend fun failed(conn: ConnectionEntity, e: SyncError, trigger: Trigger): Result {
        val now = clock()
        val text = when (e) {
            is SyncError.NeedsFix -> e.message ?: "This connection needs attention."
            is SyncError.DailyLimit -> "${e.message} It will sync again after ${java.time.Instant.ofEpochMilli(e.retryAt).atZone(zone).toLocalTime().withSecond(0).withNano(0)}."
            is SyncError.RateLimited, is SyncError.Network -> "Couldn't sync right now. It will try again later."
            is SyncError.BadData -> e.message ?: "The provider sent something that couldn't be read."
        }
        val fix = e as? SyncError.NeedsFix
        store.sync.upsertConnection(
            conn.copy(status = if (fix != null) ConnectionStatus.NEEDS_FIX else conn.status, fixCode = fix?.code ?: conn.fixCode, lastResult = text),
        )
        store.sync.insertLog(SyncLogEntity(connectionId = conn.id, at = now, ok = false, message = text))
        if (fix != null && trigger != Trigger.BACKGROUND && promptedThisSession.add(conn.id)) {
            _prompt.value = FixPrompt(conn.id, fix.code, text)
        }
        return Result(conn.id, false, SyncEngine.Outcome(), text)
    }

    // ---- linking changes, disconnecting -------------------------------------------------------------------------

    /** Re-points one provider account (from the account screen). Null stops syncing it. */
    suspend fun setLink(linkedId: String, accountId: String?) {
        val l = store.sync.linkedNow().firstOrNull { it.id == linkedId } ?: return
        if (l.accountId == accountId) return
        store.sync.upsertLinked(
            l.copy(accountId = accountId, historyFrom = accountId?.let { historyStart(it, History.NEWEST) }, lastSyncedDate = null, setStartBalance = false),
        )
    }

    /**
     * Deletes the connection's secrets and stops syncing it. [removeTransactions] false (the default) keeps the
     * synced transactions as ordinary ones; true removes them (tombstones, as every delete).
     */
    suspend fun disconnect(connectionId: String, removeTransactions: Boolean = false) {
        val conn = store.sync.connection(connectionId) ?: return
        val prefixes = store.sync.linkedOf(connectionId).map { SyncEngine.externalPrefix(conn.provider, it.providerAccountId) }
        secrets.removeWhere(SecretStore.forConnection(connectionId))
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
        store.sync.deleteLinkedOf(connectionId)
        store.sync.deleteLogOf(connectionId)
        store.sync.deleteConnection(connectionId)
        store.dao.putMeta(net.darkrelay.deaddropfinance.data.MetaEntity(RequestLimiter.keyFor(connectionId), ""))
        settings.forget(connectionId)
        promptedThisSession.remove(connectionId)
        if (_prompt.value?.connectionId == connectionId) _prompt.value = null
    }

    // ---- balance check ------------------------------------------------------------------------------------------

    /** Posted balance of an app account (pending rows left out, since the provider's balance leaves them out too). */
    suspend fun postedBalance(accountId: String): Long {
        val a = store.dao.account(accountId) ?: return 0
        var b = a.startingBalance
        for (t in store.dao.transactionsNow()) {
            when {
                t.kind == TransactionKind.TRANSFER && t.accountId == accountId -> b -= t.amount
                t.kind == TransactionKind.TRANSFER && t.transferAccountId == accountId -> b += t.amount
                t.kind == TransactionKind.NORMAL && t.accountId == accountId && !t.pending -> b += t.amount
            }
        }
        return b
    }

    companion object {
        const val LOG_KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
