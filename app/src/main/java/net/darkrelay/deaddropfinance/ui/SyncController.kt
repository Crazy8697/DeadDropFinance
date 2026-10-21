package net.darkrelay.deaddropfinance.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.sync.Paste
import net.darkrelay.deaddropfinance.sync.SyncError
import net.darkrelay.deaddropfinance.sync.SyncService

/** The door screens' actions, shared by the setup wizard and More, Bank sync. */
class SyncController(private val sync: SyncService, private val platform: Platform, private val scope: CoroutineScope) {

    /** Cleans the pasted token, claims it once, and saves only the access URL. [after] gets the connection id. */
    fun connectSimpleFin(ui: ConnectUi, existingId: String?, after: (String) -> Unit) {
        ui.error = null
        val token = when (val r = Paste.setupToken(ui.token)) {
            is Paste.Result.Bad -> { ui.tokenMessage = r.message; return }
            is Paste.Result.Ok -> r.value
        }
        ui.busy = true
        scope.launch {
            try {
                val id = sync.connectSimpleFin(token, existingId)
                ui.clearSecrets()
                after(id)
            } catch (e: SyncError) {
                ui.clearSecrets()                      // a claimed or refused token is no use again
                ui.error = e.message
            } catch (e: Exception) {
                ui.error = "Couldn't reach SimpleFIN. Check the connection and try again."
            } finally {
                ui.busy = false
            }
        }
    }

    /** Links the accounts as picked, then runs the first sync. [done] gets the result line. */
    fun applyLinks(connectionId: String, links: List<LinkedAccountEntity>, accounts: List<AccountEntity>, picks: Map<String, LinkPick>, busy: (Boolean) -> Unit, done: (String) -> Unit) {
        busy(true)
        scope.launch {
            try {
                sync.link(toChoices(links, accounts, picks))
                val r = sync.syncConnection(connectionId, SyncService.Trigger.MANUAL)
                platform.scheduleSync(true)
                done(r.message)
            } finally {
                busy(false)
            }
        }
    }
}
