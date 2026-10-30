package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.domain.BillActions
import net.darkrelay.deaddropfinance.domain.ParkedBuys
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.13.0: Parked buys in the program (the phone's screen, from More) with the parking lot wait, and the bill
 * reminders and autopay warnings: the phone's settings in More, Alerts, sent as Windows notifications.
 */
class ProgramParkedAndAlertsTest : ProgramScreensBase() {

    private fun parked() = runBlocking { store().dao.parkedBuysNow() }

    @Test fun theParkingLotWaitIsSetInMoreAndASomethingIsParkedAndDropped() {
        show()
        click("nav.MORE")
        clickText("1 week", exact = true)
        compose.waitUntil(5_000) { runBlocking { ParkedBuys(store(), a()).defaultWait() } == 7 }
        clickText("Parked buys", exact = true)
        clickText("Park something", exact = true)
        typeInto("What is it?", "Sample Headphones")
        typeInto("Price", "80.00")
        clickText("Park it", exact = true)
        compose.waitUntil(5_000) { parked().any { it.name == "Sample Headphones" } }
        until("Sample Headphones")
        clickText("Drop it", exact = true)
        compose.waitUntil(5_000) { ParkedBuys.waiting(parked()).none { it.name == "Sample Headphones" } }
    }

    @Test fun aParkedBuyWhoseWaitIsOverIsBoughtFromRollover() {
        show()
        // parked two days ago with a one day wait: ready now
        runBlocking {
            val then = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
            ParkedBuys(store(), a(), { today.minusDays(2) }, { then }).park("Sample Lamp", 30_00, 1)
            a().move(null, b().rollover!!.id, 50_00, date = today)
        }
        click("nav.MORE")
        clickText("Parked buys", exact = true)
        until("Sample Lamp")
        clickText("Buy from Rollover")
        until("Bought.")
        assertTrue(ParkedBuys.ready(parked(), System.currentTimeMillis()).none { it.name == "Sample Lamp" })
    }

    @Test fun billRemindersGoOutAsNotificationsOnceAndFollowTheirSetting() {
        show {
            BillActions(store(), this) { today }.saveBill(null, BillActions.BillDraft("Sample Rent", 900_00, today.plusDays(1), reminder = true, reminderDays = 2))
        }
        runBlocking { ProgramAlerts.check(store(), settings, shell.notices, today) }
        assertEquals(1, posted.count { it.first.startsWith("Sample Rent is due") }, posted.toString())
        // once per bill cycle
        runBlocking { ProgramAlerts.check(store(), settings, shell.notices, today) }
        assertEquals(1, posted.count { it.first.startsWith("Sample Rent is due") })
        // Bill reminders off (More, Alerts): a new bill's reminder isn't sent
        click("nav.MORE")
        until("Bill reminders")
        toggleFor("Bill reminders")
        assertTrue(!ProgramAppSettings(settings).alertBills)
        runBlocking {
            BillActions(store(), a()) { today }.saveBill(null, BillActions.BillDraft("Sample Gym", 30_00, today.plusDays(1), reminder = true, reminderDays = 2))
            ProgramAlerts.check(store(), settings, shell.notices, today)
        }
        assertTrue(posted.none { it.first.startsWith("Sample Gym") })
    }
}
