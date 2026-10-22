package net.darkrelay.deaddropfinance

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The 0.15.2 bug: the installer's "needs your confirmation" answer was never acted on, so no install screen opened.
 * The receiver must open the confirmation it is handed, and only then. Synthetic intents only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UpdateInstallReceiverTest {

    private val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL").putExtra("sample", 1)

    private fun status(code: Int, withIntent: Boolean = true) = Intent().putExtra(PackageInstaller.EXTRA_STATUS, code).apply {
        if (withIntent) putExtra(Intent.EXTRA_INTENT, confirm)
    }

    @Test fun pendingUserActionOpensTheConfirmationInANewTask() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        UpdateInstallReceiver().onReceive(app, status(PackageInstaller.STATUS_PENDING_USER_ACTION))
        val started = shadowOf(app).nextStartedActivity
        assertNotNull("the install confirmation was started", started)
        assertEquals(confirm.action, started.action)
        assertEquals(1, started.getIntExtra("sample", 0))
        assert(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun otherStatusesAndMissingIntentsOpenNothing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        for (code in listOf(PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE, PackageInstaller.STATUS_FAILURE_ABORTED)) {
            assertNull(UpdateInstallReceiver.confirmIntent(status(code)))
        }
        assertNull(UpdateInstallReceiver.confirmIntent(status(PackageInstaller.STATUS_PENDING_USER_ACTION, withIntent = false)))
        UpdateInstallReceiver().onReceive(app, status(PackageInstaller.STATUS_SUCCESS))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun theReceiverIsDeclaredAndNotExported() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val info = app.packageManager.getReceiverInfo(
            android.content.ComponentName(app, UpdateInstallReceiver::class.java), 0,
        )
        assertEquals(false, info.exported)
    }
}
