package net.darkrelay.deaddropfinance

/**
 * What Platform.downloadAndInstallUpdate (the update bar's Download and install) reports as it runs.
 */
sealed interface UpdateInstallEvent {
    /** Downloading, [percent] 0 to 100, or null while the release's total size isn't known yet. */
    data class Downloading(val percent: Int?) : UpdateInstallEvent

    /**
     * Verified and ready, but this app isn't yet allowed to install updates (Android's "install unknown apps"
     * setting). The verified file is kept: calling downloadAndInstallUpdate again, once the user has turned it on,
     * continues straight to installing, with no re-download.
     */
    data object NeedsInstallPermission : UpdateInstallEvent

    /** Handed to Android's own installer, which takes over with its own confirmation screen; or it failed, with a plain reason. */
    data class Done(val ok: Boolean, val message: String?) : UpdateInstallEvent
}

/** The mobile data confirmation is showing: [bytes] is the download's size, or null if it couldn't be found out. */
data class MobileDataAsk(val bytes: Long?)

/** What Download and install does first (0.15.2): ask on a metered network (mobile data), start right away otherwise. */
object UpdateStart {
    fun begin(metered: Boolean, ask: () -> Unit, start: () -> Unit) { if (metered) ask() else start() }

    /** "41 MB" for [bytes] (decimal megabytes, at least 1), or null when the size isn't known. */
    fun megabytes(bytes: Long?): String? = bytes?.takeIf { it > 0 }?.let { "${maxOf(1L, (it + 500_000) / 1_000_000)} MB" }

    fun message(bytes: Long?): String =
        "You're on mobile data. " + (megabytes(bytes)?.let { "This download is about $it." } ?: "This download may be large.")
}
