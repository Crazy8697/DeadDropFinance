package net.darkrelay.deaddropfinance.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.darkrelay.deaddropfinance.data.AccountEntity
import net.darkrelay.deaddropfinance.data.LinkedAccountEntity
import net.darkrelay.deaddropfinance.sync.Paste
import net.darkrelay.deaddropfinance.sync.SyncError
import net.darkrelay.deaddropfinance.sync.SyncService
import net.darkrelay.deaddropfinance.sync.TellerCredentials
import net.darkrelay.deaddropfinance.sync.TellerFiles

/** The door screens' actions, shared by the setup wizard and More, Bank sync. */
class SyncController(private val sync: SyncService, private val platform: Platform, private val scope: CoroutineScope) {

    fun prefill(ui: ConnectUi) {
        if (ui.appId.isBlank()) ui.appId = sync.settings.tellerAppId.orEmpty()
        ui.environment = sync.settings.tellerEnvironment
    }

    /** teller.zip, or the certificate and private key one at a time. Saved once both are there and they fit. */
    fun importTellerFiles(ui: ConnectUi, saved: () -> Unit) {
        platform.pickFile(arrayOf("application/zip", "application/x-pem-file", "application/x-x509-ca-cert", "text/plain", "application/octet-stream"), 256 * 1024) { _, bytes ->
            if (bytes == null) { ui.certMessage = "That file couldn't be read (or it's too big to be a Teller file)."; return@pickFile }
            val f = TellerFiles.read(bytes)
            if (f.certificate == null && f.privateKey == null) {
                ui.certMessage = "That file has no certificate or private key in it. Choose teller.zip, or the .pem files from it."
                return@pickFile
            }
            f.certificate?.let { ui.pendingCertificate = it }
            f.privateKey?.let { ui.pendingKey = it }
            val cert = ui.pendingCertificate
            val key = ui.pendingKey
            when {
                cert == null -> ui.certMessage = "Private key read. Now choose the certificate file."
                key == null -> ui.certMessage = "Certificate read. Now choose the private key file."
                else -> try {
                    sync.saveTellerCredentials(cert, key)
                    ui.certMessage = null
                    ui.pendingCertificate = null
                    ui.pendingKey = null
                    saved()
                } catch (e: TellerCredentials.Invalid) {
                    ui.certMessage = e.message
                    ui.pendingCertificate = null
                    ui.pendingKey = null
                }
            }
        }
    }

    /** Checks the application ID and certificate, then opens Teller Connect. [after] gets the connection id. */
    fun connectTeller(ui: ConnectUi, reconnectId: String?, enrollmentId: String?, after: (String) -> Unit) {
        ui.error = null
        when (val r = Paste.appId(ui.appId)) {
            is Paste.Result.Bad -> { ui.appIdMessage = r.message; return }
            is Paste.Result.Ok -> { ui.appId = r.value; ui.appIdMessage = null }
        }
        if (!sync.hasTellerCredentials()) {
            ui.certMessage = "Import your certificate and private key first (step 2)."
            return
        }
        sync.settings.tellerAppId = ui.appId
        sync.settings.tellerEnvironment = ui.environment
        platform.openTellerConnect(ui.appId, ui.environment, enrollmentId) { ok, failure ->
            if (ok == null) { ui.error = failure; return@openTellerConnect }
            ui.busy = true
            scope.launch {
                try {
                    after(sync.connectTeller(ok.accessToken, ok.enrollmentId, ok.institution, reconnectId))
                } catch (e: Exception) {
                    ui.error = e.message ?: "The bank connection couldn't be saved."
                } finally {
                    ui.busy = false
                }
            }
        }
    }

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
