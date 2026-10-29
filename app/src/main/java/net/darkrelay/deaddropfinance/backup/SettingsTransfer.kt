package net.darkrelay.deaddropfinance.backup

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The settings a backup carries: the user's own choices (sorting, alerts, app lock, hide screen, update
 * checks). Not setup progress, alert bookkeeping or a skipped version. [files] are the settings files by name:
 * "sync", "lock" and "updates". A backup from before 1.5.0 may also carry the spend alert switches of the bank
 * notification reader (removed in 1.5.0): they are not carried keys any more, so a restore ignores them.
 */
class SettingsTransfer(private val files: Map<String, SharedPreferences>) {

    fun export(): JSONObject {
        val o = JSONObject()
        for ((file, keys) in CARRIED) {
            val p = files[file] ?: continue
            val all = p.all
            val f = JSONObject()
            for (k in keys) when (val v = all[k]) {
                is Boolean, is Int -> f.put(k, v)
                else -> {}
            }
            o.put(file, f)
        }
        return o
    }

    /** Sets what [o] holds, for the carried keys only; anything else in it is ignored. */
    fun import(o: JSONObject) {
        for ((file, keys) in CARRIED) {
            val p = files[file] ?: continue
            val f = o.optJSONObject(file) ?: continue
            p.edit(commit = true) {
                for (k in keys) {
                    if (!f.has(k)) continue
                    when (val v = f.get(k)) {
                        is Boolean -> putBoolean(k, v)
                        is Int -> putInt(k, v)
                        else -> {}
                    }
                }
            }
        }
    }

    companion object {
        val CARRIED = mapOf(
            "sync" to listOf(
                "auto_approve", "alert_lock_screen_amounts", "alert_bills", "alert_autopay",
            ),
            "lock" to listOf("lock_enabled", "lock_timeout_minutes", "hide_screen"),
            "updates" to listOf("check_at_startup"),
        )
    }
}
