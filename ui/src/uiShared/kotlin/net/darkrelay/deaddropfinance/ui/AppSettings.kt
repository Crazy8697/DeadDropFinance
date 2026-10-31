package net.darkrelay.deaddropfinance.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue

/**
 * The app's own settings that AppRoot reads and writes (not in the budget). The phone keeps them in SharedPreferences
 * (SyncSettings); the program keeps them in its settings file.
 */
interface AppSettings {
    var setupDone: Boolean
    var setupAfterPurge: Boolean
    var setupCardDismissed: Boolean
    var autoApprove: Boolean
    var alertAmountsOnLockScreen: Boolean
    var alertsAsked: Boolean
    var alertBills: Boolean
    var alertAutopay: Boolean
    var lastBackupAt: Long
    var lastBackupName: String
    /** The bill reminders and autopay warnings already sent (BillAlerts). */
    var reminded: Set<String>

    /**
     * 1.9.1: the reports the phone's Home shows as cards, in order (report kind names): this phone's own, never synced
     * and not carried in a backup. Empty (the default) is Home as it was. The program has no Home, so it keeps none.
     */
    var homeReports: List<String>
        get() = emptyList()
        set(@Suppress("UNUSED_PARAMETER") v) {}
}

/** Where a notification opens the app (MainActivity's extra; the program's notice). */
object OpenTargets {
    const val BILLS = "bills"
    const val HOME = "home"
}

/** The startup update check waits this long, so it never slows the first screen. */
const val STARTUP_UPDATE_DELAY_MS = 3000L

/**
 * The words for where these screens run (0.13.0): "phone" and "app" on the phone; "computer" and "program" when the
 * desktop program shows them. [appLock] is false where there's no app lock (the program).
 */
data class DeviceWords(val device: String = "phone", val product: String = "app", val appLock: Boolean = true) {
    /** "Tap" on the phone, "Click" with a mouse. */
    val tap: String get() = if (device == "phone") "Tap" else "Click"
    /** A camera to photograph a receipt: the phone's; the program takes an image or PDF file (both read it, 0.14.0). */
    val camera: Boolean get() = device == "phone"
    /** Where logging out of the server is. */
    val serverPlace: String get() = if (device == "phone") "More > Server" else "Sync"
    /** Where backups are made (0.14.0): the phone's More, the program's Settings. */
    val backupPlace: String get() = if (device == "phone") "More, Backup and restore" else "Settings, Backup and restore"
}

val LocalDeviceWords = androidx.compose.runtime.staticCompositionLocalOf { DeviceWords() }

/**
 * The desktop program shows AppRoot inside its own window (0.13.0): no bottom tabs, no sync banners or setup forced at
 * start (the program shows its own), and only what the program doesn't have itself. AppRoot opens at [start];
 * each [requests] value opens that screen instead (a button elsewhere in the program); Back from the first screen
 * calls [onExit].
 */
class ProgramHost(
    val start: Nav,
    val requests: kotlinx.coroutines.flow.Flow<Nav> = kotlinx.coroutines.flow.emptyFlow(),
    val onExit: () -> Unit = {},
    /** A sidebar item (0.14.0, Receipts): the program's top bar names the screen, so its first screen has no title or Back. */
    val sidebar: Boolean = false,
    /** Reports' budget month from the program's own month stepper (0.15.0); null: the screens step it themselves. */
    val month: (() -> Int)? = null,
    /** The program's date (0.15.0), the one its own screens use; null: today. */
    val today: (() -> java.time.LocalDate)? = null,
    /** 0.15.1: where a sub-screen's title and Back go, the program's top bar; null: the screens draw their own. */
    val titles: HostTitles? = null,
)

/**
 * A sub-screen's title and Back for the program's top bar (0.15.1): the screen shows neither, so the program shows
 * "Reports > Price history" and its Back. Set by ScreenTitle while the screen is shown, cleared when it goes.
 */
class HostTitles {
    class Entry(val owner: Any, val title: String, val back: (() -> Unit)?)

    var current by androidx.compose.runtime.mutableStateOf<Entry?>(null)
        private set

    fun show(owner: Any, title: String, back: (() -> Unit)?) { current = Entry(owner, title, back) }

    fun clear(owner: Any) { if (current?.owner === owner) current = null }
}

/** The program's [HostTitles] while it shows these screens (0.15.1); null on the phone. */
val LocalHostTitles = androidx.compose.runtime.staticCompositionLocalOf<HostTitles?> { null }

/** The first screen of a sidebar item in the program (0.14.0): its title and Back aren't drawn (the top bar has the name). */
val LocalHostedRoot = androidx.compose.runtime.staticCompositionLocalOf { false }
