package net.darkrelay.deaddropfinance.desktop

import java.io.File

/**
 * What the update bar shows, and the steps behind its buttons. No UI here, so it is tested directly. The bar is the
 * only place updates are ever shown: never a popup, never a window of its own.
 */
sealed class UpdateState {
    object Hidden : UpdateState()
    object Checking : UpdateState()
    data class Available(val release: Updater.Release) : UpdateState()
    data class Downloading(val release: Updater.Release, val done: Long, val total: Long) : UpdateState()
    data class Installing(val release: Updater.Release) : UpdateState()
    /** A plain line: the result of Help > Check for updates, or why an update failed. */
    data class Message(val text: String, val error: Boolean) : UpdateState()
}

class UpdateModel(
    private val installed: String = BuildInfo.VERSION,
    private val check: () -> Updater.Result = { Updater.check(installed) },
    private val download: (Updater.Release, (Long, Long) -> Unit) -> File = { r, p ->
        Updater.download(r, File(System.getProperty("java.io.tmpdir"), "DeadDropFinance-update"), p)
    },
    private val launch: (File) -> Boolean = Updater::launchSetup,
    /** Only an installed copy updates itself; a run from source only points at the release page. */
    val canInstall: Boolean = isInstalled(),
    /** Skip this version (0.13.0), as on the phone: the version skipped, and saving a new one. */
    private val skipped: () -> String? = { null },
    private val onSkip: (String) -> Unit = {},
) {
    @Volatile var state: UpdateState = UpdateState.Hidden
        private set

    var onChange: (UpdateState) -> Unit = {}

    private fun set(s: UpdateState) {
        state = s
        onChange(s)
    }

    /** The check. [manual]: from Help > Check for updates, so "up to date" and failures are shown too; the startup
     *  check stays quiet unless there is something to offer. Never throws. */
    fun runCheck(manual: Boolean) {
        if (state is UpdateState.Downloading || state is UpdateState.Installing) return
        if (manual) set(UpdateState.Checking)
        val r = try {
            check()
        } catch (e: Exception) {
            Updater.Result.Failed("The update check failed.")
        }
        when (r) {
            // a skipped version isn't offered at startup until a newer one comes; Check for updates still offers it
            is Updater.Result.Newer -> if (manual || offered(r.release.version)) set(UpdateState.Available(r.release))
            is Updater.Result.Current -> if (manual) set(UpdateState.Message("Dead Drop Finance $installed is the newest version.", false))
            is Updater.Result.Failed -> if (manual) set(UpdateState.Message("Couldn't check for updates. ${r.reason}", true))
        }
    }

    /** Download and install: download, verify, start Setup. -> true when Setup started (the program then exits). */
    fun install(): Boolean {
        val rel = (state as? UpdateState.Available)?.release ?: return false
        return try {
            set(UpdateState.Downloading(rel, 0, -1))
            val setup = download(rel) { done, total -> set(UpdateState.Downloading(rel, done, total)) }
            set(UpdateState.Installing(rel))
            if (launch(setup)) {
                true
            } else {
                set(UpdateState.Message("Setup for Dead Drop Finance ${rel.version} couldn't be started. Nothing was changed.", true))
                false
            }
        } catch (e: Updater.UpdateException) {
            set(UpdateState.Message(e.message ?: "The update failed. Nothing was changed.", true))
            false
        } catch (e: Exception) {
            set(UpdateState.Message("The update failed. Nothing was changed.", true))
            false
        }
    }

    fun dismiss() = set(UpdateState.Hidden)

    /** Skip this version: hidden now, and not offered at startup again (the phone's UpdateCheck.shouldOffer). */
    fun skip() {
        val rel = (state as? UpdateState.Available)?.release ?: return
        onSkip(rel.version)
        set(UpdateState.Hidden)
    }

    private fun offered(version: String): Boolean {
        val s = skipped()
        if (s.isNullOrBlank()) return true
        return (net.darkrelay.deaddropfinance.UpdateCheck.compareVersions(version, s) ?: 1) > 0
    }

    companion object {
        /** The jpackage launcher of an installed copy sets jpackage.app-path; a run from source doesn't. */
        fun isInstalled(): Boolean = !System.getProperty("jpackage.app-path").isNullOrBlank()
    }
}
