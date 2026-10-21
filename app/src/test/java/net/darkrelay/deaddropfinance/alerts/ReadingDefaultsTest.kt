package net.darkrelay.deaddropfinance.alerts

import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import net.darkrelay.deaddropfinance.sync.SyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Stage 10 switches start where they should, and the reader takes only a notification's own words (invented here). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ReadingDefaultsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun readingIsOffAndChannelsOnByDefault() {
        val st = SyncSettings(ctx.getSharedPreferences("reading-defaults", Context.MODE_PRIVATE).also { it.edit().clear().commit() })
        assertFalse("nothing is read until the user turns it on", st.notifReading)
        assertTrue(st.notifPackages.isEmpty())
        assertTrue("the channel is on; each bill's own reminder starts off", st.alertBills)
        assertTrue(st.alertAutopay)
        assertTrue(st.reminded.isEmpty())
    }

    @Test fun theTextIsTitleAndBodyAndTheInboxIsMemoryOnly() {
        val n = Notification().apply {
            extras = Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE, "Sample Bank")
                putCharSequence(Notification.EXTRA_TEXT, "A purchase of \$5.00 at SAMPLE SHOP")
            }
        }
        assertEquals("Sample Bank. A purchase of \$5.00 at SAMPLE SHOP", BankNotificationListener.textOf(n))
        assertNull(BankNotificationListener.textOf(Notification().apply { extras = Bundle() }))
        val inbox = BankNotificationListener.Inbox
        inbox.put("com.example.samplebank", "text")
        assertEquals(setOf("com.example.samplebank"), inbox.packages())
        BankNotificationListener.setEnabled(ctx, false)
        assertTrue("turning reading off forgets what was waiting", inbox.packages().isEmpty())
    }
}
