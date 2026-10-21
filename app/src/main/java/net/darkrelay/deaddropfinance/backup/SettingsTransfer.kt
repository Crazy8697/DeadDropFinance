package net.darkrelay.deaddropfinance.backup

import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONObject

/**
 * The settings a backup carries: the user's own choices (sync, sorting, alerts, app lock, hide screen, update
 * checks). Not setup progress, alert bookkeeping, a skipped version or notification reading (that needs Android's
 * permission on each phone). [files] are the settings files by name: "sync", "lock" and "updates".
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
                "background", "interval_hours", "auto_approve", "alert_spending", "alert_low", "alert_safe_to_spend",
                "alert_lock_screen_amounts", "alert_bills", "alert_autopay",
            ),
            "lock" to listOf("lock_enabled", "lock_timeout_minutes", "hide_screen"),
            "updates" to listOf("check_at_startup"),
        )
    }
}
