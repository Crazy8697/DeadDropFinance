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
