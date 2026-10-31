package net.darkrelay.deaddropfinance.desktop

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertTrue

/**
 * 0.13.0: the phone's setup wizard in the program (More, Setup; no app lock page, as the program has none) and
 * Import setup with its example file.
 */
class ProgramSetupTest : ProgramScreensBase() {

    @Test fun theWizardRunsWithoutAnAppLockPageAndAddsTheStartersPicked() {
        show()
        click("nav.SETTINGS")
        click("settings.setup")
        until("Setup, step 1 of 4")
        assertTrue(shows("lives on this computer"))
        assertTrue(!shows("app lock"))
        clickText("Start", exact = true)
        until("Old School")
        clickText("I'll enter things myself")
        until("Starter envelopes")
        toggleFor("Eating out")
        clickText("Add 1", exact = true)
        until("You're set.")
        assertTrue(!shows("App lock is"))
        // the button (the page's title says Finish too)
        clickLowest("Finish")
        // back where it was opened from: the program's Settings
        until("Setup and data")
        refresh()
        assertTrue(b().envelopes.any { it.name == "Eating out" })
    }

    @Test fun theExampleIsExportedAndImportedBackWithItsPreview() {
        // the example names an account the importer expects to be there (it never makes accounts)
        show { saveAccount(null, "Sample Checking", net.darkrelay.deaddropfinance.data.AccountType.BANK, 0, today.minusDays(10)) }
        saveTo = tmp.newFile("deaddropfinance-setup-example.json")
        click("nav.SETTINGS")
        click("settings.importSetup")
        clickText("Export example", exact = true)
        compose.waitUntil(5_000) { saveTo!!.length() > 0 }
        assertTrue(saveTo!!.readText().trim().startsWith("{"), "the example from docs/IMPORT.md")
        val before = b().envelopes.size
        picked = saveTo
        clickText("Pick a JSON file", exact = true)
        until("Will add 17")
        assertTrue(shows("Sample Power Co"))
        clickText("Import", exact = true)
        compose.waitUntil(8_000) { refresh(); b().envelopes.size > before }
        assertTrue(runBlocking { store().dao.billsNow() }.isNotEmpty() || b().envelopes.size > before)
    }
}
