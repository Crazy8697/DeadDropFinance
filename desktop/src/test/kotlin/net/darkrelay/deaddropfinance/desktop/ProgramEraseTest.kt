package net.darkrelay.deaddropfinance.desktop

import java.io.File
import kotlinx.coroutines.runBlocking
import net.darkrelay.deaddropfinance.data.DesktopDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 0.13.0: Erase everything and Purge data in the program (More), after the phone's confirm screens (a typed word),
 * on the program's own files: the phone's EraseWordingTest and PurgeWizardTest cases where they apply.
 */
class ProgramEraseTest : ProgramScreensBase() {

    @Test fun eraseAsksForTheWordThenEverythingGoesAndANewEmptyBudgetOpens() {
        show()
        val dir = dataDir()
        File(dir, "receipts").mkdirs(); File(dir, "receipts/sample-0.enc").writeText("x")
        File(dir, "secrets.bin").writeText("x")
        click("nav.MORE")
        clickText("Erase everything and start over")
        until("This deletes everything Dead Drop Finance keeps on this computer, for good:")
        assertTrue(shows("The program then starts over like a fresh install, at the setup wizard."))
        typeInto("Type ERASE to confirm", "ERASE")
        clickLowest("Erase everything")
        assertEquals(1, erases, "the program's erase runs after the phone's confirm")
        // what the program does then (Main): everything goes, and a new empty budget opens
        val fresh = runBlocking { ProgramReset.eraseNow(model, dir, settings) { e -> AppModel(e).also { it.refresh() } } }
        try {
            assertEquals("Erased. Nothing of your budget is left on this computer.", fresh.message?.first)
            assertTrue(fresh.budget!!.accounts.isEmpty())
            assertFalse(File(dir, "receipts").exists())
            assertFalse(File(dir, "secrets.bin").exists())
            assertTrue((fresh.engine!!.opened as DesktopDatabase.Opened.Ok).file.exists(), "a new budget file")
        } finally {
            fresh.engine?.close()
        }
    }

    @Test fun purgeAsksForTheWordThenEveryRowGoesAndTheSetupWizardOpens() {
        show()
        val dir = dataDir()
        File(dir, "receipts").mkdirs(); File(dir, "receipts/sample-0.enc").writeText("x")
        click("nav.MORE")
        clickText("Purge data", exact = true)
        until("Type PURGE to confirm")
        typeInto("Type PURGE to confirm", "PURGE")
        clickLowest("Purge data")
        compose.waitUntil(8_000) { model.budget?.accounts?.isEmpty() == true }
        assertTrue(b().transactions.isEmpty())
        assertFalse(File(dir, "receipts").exists())
        // the setup wizard, as after the phone's purge
        until("Setup, step 1 of 4")
        assertEquals(Screen.HOME, model.screen)
    }
}
