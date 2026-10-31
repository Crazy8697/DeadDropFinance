package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 0.13.0: the bill reminders and autopay warnings: the phone's Alerts settings, sent as Windows notifications. */
class ProgramAlertsTest : ProgramScreensBase() {

    @Test fun billRemindersGoOutAsNotificationsOnceAndFollowTheirSetting() {
        show {
            BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Rent", 900_00, today.plusDays(1), reminder = true, reminderDays = 2))
        }
        runBlocking { ProgramAlerts.check(store(), settings, shell.notices, today) }
        assertEquals(1, posted.count { it.first.startsWith("Sample Rent is due") }, posted.toString())
        // once per bill cycle
        runBlocking { ProgramAlerts.check(store(), settings, shell.notices, today) }
        assertEquals(1, posted.count { it.first.startsWith("Sample Rent is due") })
        // Bill reminders off (Settings, Alerts): a new bill's reminder isn't sent
        click("nav.SETTINGS")
        until("Bill reminders")
        click("setting.alertBills")
        assertTrue(!ProgramAppSettings(settings).alertBills)
        runBlocking {
            BillActions(store(), a()) { today }.saveBill(null, BillActions.BillDraft("Sample Gym", 30_00, today.plusDays(1), reminder = true, reminderDays = 2))
            ProgramAlerts.check(store(), settings, shell.notices, today)
        }
        assertTrue(posted.none { it.first.startsWith("Sample Gym") })
    }
}
