package net.darkrelay.deaddropfinance.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows Hello unlock (0.11.0): a Settings option, off by default. Turning it on asks for a Hello check first, so it is
 * only on once it has worked here; on a computer without Hello set up it says so and stays off. While it's on, the
 * program opens locked: nothing of the budget shows until Hello says it's you. The budget's own encryption (DPAPI) is
 * unchanged; this is a gate in front of it. [verifier] is WindowsHello for real, a stand-in in the tests.
 */
class HelloLock(private val settings: Settings, private val verifier: HelloVerifier = WindowsHello) {
    var enabled by mutableStateOf(settings.helloUnlock)
        private set
    /** Locked at the start while the option is on, until a Hello check passes. */
    var locked by mutableStateOf(settings.helloUnlock)
        private set
    var message by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
        private set

    /** The lock screen's check (and the automatic one on opening). Blocks until Hello answers, so it runs off the UI thread. */
    fun unlock(hwnd: Long) {
        if (!locked || checking) return
        checking = true
        try {
            val r = verifier.verify("Unlock Dead Drop Finance", hwnd)
            if (r == HelloVerifier.Result.VERIFIED) {
                locked = false
                message = null
            } else {
                message = why(r) + " The budget stays locked."
            }
        } finally {
            checking = false
        }
    }

    /** Settings: on only after Hello is set up here and a check passes; off at once. */
    fun set(on: Boolean, hwnd: Long) {
        if (!on) {
            enabled = false
            settings.helloUnlock = false
            message = "Windows Hello unlock is off."
            return
        }
        if (checking) return
        checking = true
        try {
            val a = verifier.availability()
            if (a != HelloVerifier.Availability.AVAILABLE) {
                message = when (a) {
                    HelloVerifier.Availability.NOT_SET_UP -> NOT_SET_UP
                    HelloVerifier.Availability.DISABLED -> "Windows Hello is turned off on this computer by its administrator, so this stays off."
                    // Windows says "no device" when Hello has no PIN, face or fingerprint set up here
                    else -> "Windows Hello isn't available on this computer: it isn't set up, or there is no Hello sign-in (PIN, face or fingerprint). Set it up in Windows Settings > Accounts > Sign-in options first, so this stays off."
                }
                return
            }
            val r = verifier.verify("Turn on Windows Hello unlock for Dead Drop Finance", hwnd)
            if (r == HelloVerifier.Result.VERIFIED) {
                enabled = true
                settings.helloUnlock = true
                message = "Windows Hello unlock is on. The program asks for it each time it opens."
            } else {
                message = why(r) + " Windows Hello unlock stays off."
            }
        } finally {
            checking = false
        }
    }

    private fun why(r: HelloVerifier.Result) = when (r) {
        HelloVerifier.Result.CANCELED -> "Windows Hello was cancelled."
        HelloVerifier.Result.FAILED -> "Windows Hello didn't recognize you."
        HelloVerifier.Result.NOT_SET_UP -> NOT_SET_UP
        HelloVerifier.Result.UNAVAILABLE -> "Windows Hello can't be reached right now."
        HelloVerifier.Result.VERIFIED -> ""
    }

    companion object {
        const val NOT_SET_UP = "Windows Hello isn't set up on this computer. Set it up in Windows Settings > Accounts > Sign-in options first."
    }
}

/** The lock screen while Windows Hello unlock is on: nothing of the budget, only Unlock and Exit. */
@androidx.compose.runtime.Composable
fun HelloLockScreen(lock: HelloLock, onUnlock: () -> Unit, onExit: () -> Unit) {
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = C.Cyan, background = C.Bg, surface = C.Panel)) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(C.Bg).testTag("helloLock"),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                T(PROGRAM_NAME, 22.sp, weight = androidx.compose.ui.text.font.FontWeight.Bold)
                T("Locked. Unlock with Windows Hello to see the budget.", 14.sp, C.Dim, modifier = androidx.compose.ui.Modifier.padding(top = 8.dp))
                lock.message?.let { T(it, 13.sp, C.Warn, modifier = androidx.compose.ui.Modifier.padding(top = 10.dp).testTag("hello.lockMessage")) }
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.padding(top = 18.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Btn(if (lock.checking) "Waiting for Windows Hello..." else "Unlock", onUnlock, enabled = !lock.checking, tag = "hello.unlock")
                    Btn("Exit", onExit, ghost = true, tag = "hello.exit")
                }
            }
        }
    }
}
