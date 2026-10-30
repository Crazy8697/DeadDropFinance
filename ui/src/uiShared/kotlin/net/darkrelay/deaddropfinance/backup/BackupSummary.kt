package net.darkrelay.deaddropfinance.backup

/** What a backup held, counted when it's written or restored (shown after a restore). */
data class BackupSummary(val accounts: Int, val envelopes: Int, val transactions: Int, val receipts: Int, val photos: Int)

/** A backup's suggested file name, the same on the phone and the program. */
object BackupNames {
    fun fileName(date: java.time.LocalDate) = "deaddrop-$date.ddfbak"
}
