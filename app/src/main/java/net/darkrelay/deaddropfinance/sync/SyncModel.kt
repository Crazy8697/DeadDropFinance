package net.darkrelay.deaddropfinance.sync

import java.time.LocalDate
import net.darkrelay.deaddropfinance.data.Provider

/** An account as a provider describes it. [balance] is in cents, signed like the app's balances. */
data class ProviderAccount(val id: String, val name: String, val mask: String = "", val balance: Long? = null)

/** A transaction as a provider describes it. [amount] is in cents, negative is money out. */
data class ProviderTxn(val id: String, val date: LocalDate, val amount: Long, val description: String, val pending: Boolean)

/** One account's fetched transactions. [complete] false means the provider said the list may be missing rows. */
data class AccountData(val account: ProviderAccount, val transactions: List<ProviderTxn>, val complete: Boolean = true)

/** What a fetch brought back, plus any messages the provider sent that the user should read (plain text). */
data class FetchResult(val accounts: List<AccountData>, val messages: List<String> = emptyList())

/** Where a fix is needed: the screens turn each into a message and a button straight to the fix. */
object FixCode {
    const val TELLER_CERTIFICATE = "teller.certificate"
    const val TELLER_RECONNECT = "teller.reconnect"
    const val SIMPLEFIN_TOKEN = "simplefin.token"
    const val SIMPLEFIN_PAYMENT = "simplefin.payment"
}

/** Everything that can go wrong talking to a provider, in terms the app can act on. */
sealed class SyncError(message: String) : Exception(message) {
    /** The user has to do something: [code] is a [FixCode]. */
    class NeedsFix(val code: String, message: String) : SyncError(message)
    /** The provider said slow down and still did after the retries. Retried later without bothering the user. */
    class RateLimited(message: String) : SyncError(message)
    /** The request budget for today is used up (SimpleFIN). [retryAt] is when it frees up again. */
    class DailyLimit(val retryAt: Long, message: String) : SyncError(message)
    class Network(message: String) : SyncError(message)
    class BadData(message: String) : SyncError(message)
}

/** One provider behind one interface, so a third source later is a new adapter and nothing else. */
interface SyncSource {
    val provider: Provider

    /** The provider's accounts with their balances, no transactions. */
    suspend fun accounts(): FetchResult

    /**
     * Transactions for the wanted accounts: provider account id to the first day wanted, through [to]. Accounts
     * not in [wanted] are left out. Pending transactions are included.
     */
    suspend fun fetch(wanted: Map<String, LocalDate>, to: LocalDate): FetchResult

    /** Days to overlap with the last sync, so late-posting transactions aren't missed. */
    val overlapDays: Long
}
