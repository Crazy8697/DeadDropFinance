package net.darkrelay.deaddropfinance.alerts

import android.app.Application
import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.darkrelay.deaddropfinance.domain.Alerts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The posted notification: private on the lock screen with a count-only public version, and Approve needs an unlocked phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SpendAlertsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun notice(lock: Boolean, approve: List<String> = listOf("t1", "t2")) = Alerts.Notice(
        Alerts.Kind.SPENDING, "2 new charges, -\$17.00", listOf("SAMPLE MARKET -\$10.00: suggested Food", "SAMPLE CAFE -\$7.00: not sorted yet"),
        Alerts.PUBLIC_TITLE, "2 new charges synced", approve, lock,
    )

    @Test fun privateByDefaultWithACountOnlyPublicVersion() {
        val n = SpendAlerts(ctx).notification(notice(lock = false))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        val pub = n.publicVersion
        assertNotNull(pub)
        val text = "${pub.extras.getCharSequence(Notification.EXTRA_TITLE)} ${pub.extras.getCharSequence(Notification.EXTRA_TEXT)}"
        assertEquals("Dead Drop Finance 2 new charges synced", text)
        assertFalse(text.contains("$"))
        assertEquals(SpendAlerts.CHANNEL_SPENDING, n.channelId)
    }

    @Test fun amountsOnTheLockScreenOnlyWhenTurnedOn() {
        assertEquals(Notification.VISIBILITY_PUBLIC, SpendAlerts(ctx).notification(notice(lock = true)).visibility)
    }

    @Test fun approveNeedsTheUnlockedPhoneAndChangeOpensTheApp() {
        val n = SpendAlerts(ctx).notification(notice(lock = false))
        assertEquals(listOf("Approve 2", "Change"), n.actions.map { it.title.toString() })
        assertTrue(n.actions[0].isAuthenticationRequired)
        assertNotNull(n.contentIntent)
        assertNull(SpendAlerts(ctx).notification(notice(lock = false, approve = emptyList())).actions)
    }
}
