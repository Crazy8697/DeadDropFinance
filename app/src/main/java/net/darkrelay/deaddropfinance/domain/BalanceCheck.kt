package net.darkrelay.deaddropfinance.domain

import java.time.LocalDate

/**
 * The balance check for a linked account: the bank's reported balance at its as-of date against the app's posted
 * balance at the end of that same date (pending transactions, and anything dated after the as-of date, are left out
 * on the app's side, as the bank's balance doesn't have them yet). Transactions dated on the as-of day itself may or
 * may not be in the bank's figure, so the day before is tried too. Nothing is ever changed here.
 */
object BalanceCheck {

    sealed interface Result {
        val bank: Long
        val app: Long
        val asOf: LocalDate?

        /** The app agrees with the bank. */
        data class Match(override val bank: Long, override val app: Long, override val asOf: LocalDate?) : Result

        /** They differ by [difference] (bank minus app): what an adjustment would add to the app's side. */
        data class Mismatch(override val bank: Long, override val app: Long, override val asOf: LocalDate?) : Result {
            val difference get() = bank - app
        }

        /**
         * The bank's figure is the app's with the sign flipped: the provider reported the balance with the wrong sign
         * (seen with an overdrawn account at one bank through SimpleFIN). The app's math is trusted; nothing to adjust.
         */
        data class FlippedSign(override val bank: Long, override val app: Long, override val asOf: LocalDate?) : Result
    }

    fun check(b: Budget, accountId: String, bank: Long, asOf: LocalDate?): Result {
        val candidates = if (asOf == null) listOf(b.postedBalance(accountId))
        else listOf(b.postedBalanceOn(accountId, asOf), b.postedBalanceOn(accountId, asOf.minusDays(1))).distinct()
        candidates.firstOrNull { it == bank }?.let { return Result.Match(bank, it, asOf) }
        candidates.firstOrNull { it != 0L && bank == -it }?.let { return Result.FlippedSign(bank, it, asOf) }
        return Result.Mismatch(bank, candidates.first(), asOf)
    }
}
