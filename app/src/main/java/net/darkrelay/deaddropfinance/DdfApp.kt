package net.darkrelay.deaddropfinance

import android.app.Application
import net.darkrelay.deaddropfinance.lock.AppLock

/** Holds what lives as long as the process: the app lock (it watches the whole app go to the background). */
class DdfApp : Application() {
    lateinit var lock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        lock = AppLock(this).also { it.attach() }
    }
}
