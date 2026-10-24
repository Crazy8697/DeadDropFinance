package net.darkrelay.deaddropfinance.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

/*
 * Bank sync tables (schema version 2). They describe this phone's connections, so unlike the budget tables they are
 * not part of the change log: another phone never gets them. They hold nothing secret; tokens, certificates and access
 * URLs live in SecretStore.
 */

/** The bank sync providers. A new provider is a new value here and a new SyncSource adapter. */
enum class Provider { SIMPLEFIN }

enum class ConnectionStatus { OK, NEEDS_FIX }

/** One bank login (one SimpleFIN access URL). */
@Entity(tableName = "connection")
data class ConnectionEntity(
    @PrimaryKey val id: String = newId(),
    val provider: Provider,
    /** The institution's name as the provider reports it, for display. */
    val name: String,
    /** A provider's own id for the login, if it has one (not a secret). No current provider uses it. */
    @ColumnInfo(name = "enrollment_id") val enrollmentId: String? = null,
    val status: ConnectionStatus = ConnectionStatus.OK,
    /** What to fix, as a short code the screens turn into a button (see SyncError). */
    @ColumnInfo(name = "fix_code") val fixCode: String? = null,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
    @ColumnInfo(name = "last_result") val lastResult: String? = null,
    /** Messages the provider sent back that the user should see (SimpleFIN errlist), plain text. */
    @ColumnInfo(name = "provider_messages") val providerMessages: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** A provider's account and what it is linked to. [accountId] null means "ignore this one". */
@Entity(tableName = "linked_account", indices = [Index("connection_id"), Index("account_id")])
data class LinkedAccountEntity(
    @PrimaryKey val id: String = newId(),
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "provider_account_id") val providerAccountId: String,
    @ColumnInfo(name = "provider_name") val providerName: String,
    /** Last four digits or similar, for telling accounts apart. */
    val mask: String = "",
    @ColumnInfo(name = "account_id") val accountId: String? = null,
    /** The provider's balance in cents, signed like the app's balances (money you have is positive). */
    @ColumnInfo(name = "provider_balance") val providerBalance: Long? = null,
    @ColumnInfo(name = "provider_balance_at") val providerBalanceAt: Long? = null,
    /** When the provider says its balance was true (epoch seconds), so the screens can say how old the bank's data is. */
    @ColumnInfo(name = "provider_balance_date") val providerBalanceDate: Long? = null,
    /** Where the first sync starts; later syncs go from the last sync with an overlap. */
    @ColumnInfo(name = "history_from") val historyFrom: LocalDate? = null,
    @ColumnInfo(name = "last_synced_date") val lastSyncedDate: LocalDate? = null,
    /**
     * The app account was made by linking, so its starting balance is set once from the provider's balance after
     * the first sync (an existing account's starting balance is the user's and is never touched).
     */
    @ColumnInfo(name = "set_start_balance") val setStartBalance: Boolean = false,
    /**
     * The bank sends this account's balance with the wrong sign (1.2.1; some banks report an overdrawn balance as
     * positive through SimpleFIN). Set by the user, kept with the bank's account: a Purge that keeps the connection,
     * a relink and every sync keep it. [providerBalance] stays as the bank sent it; [bankBalance] is what the app reads.
     */
    @ColumnInfo(name = "balance_sign_flipped", defaultValue = "0") val balanceSignFlipped: Boolean = false,
) {
    /** The bank's balance as the app reads it everywhere: with its sign flipped when the user said the bank sends it wrong. */
    val bankBalance: Long? get() = providerBalance?.let { if (balanceSignFlipped) -it else it }
}

/** One line per sync run, so the screens can say what the last one did. */
@Entity(tableName = "sync_log", indices = [Index("connection_id")])
data class SyncLogEntity(
    @PrimaryKey val id: String = newId(),
    @ColumnInfo(name = "connection_id") val connectionId: String,
    val at: Long,
    val ok: Boolean,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    /** Plain-language result, never a secret. */
    val message: String,
)
