package net.darkrelay.deaddropfinance.desktop

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateModelTest {

    @get:Rule val tmp = TemporaryFolder()

    private val rel = Updater.Release("0.6.0", "desktop-v0.6.0", "Notes", "https://github.com/Crazy8697/DeadDropFinance/releases/tag/desktop-v0.6.0")

    @Test fun theStartupCheckIsQuietUnlessThereIsSomethingToOffer() {
        val seen = mutableListOf<UpdateState>()
        for (r in listOf(Updater.Result.Current("0.5.0"), Updater.Result.Current(null), Updater.Result.Failed("offline"))) {
            val m = UpdateModel("0.5.0", check = { r }, canInstall = true).also { it.onChange = { s -> seen += s } }
            m.runCheck(manual = false)
            assertEquals(UpdateState.Hidden, m.state)
        }
        assertEquals(emptyList(), seen, "nothing was shown at all")
        val m = UpdateModel("0.5.0", check = { Updater.Result.Newer(rel) }, canInstall = true)
        m.runCheck(manual = false)
        assertEquals(UpdateState.Available(rel), m.state)
    }

    @Test fun theManualCheckAlwaysAnswers() {
        val shown = mutableListOf<UpdateState>()
        val m = UpdateModel("0.5.0", check = { Updater.Result.Current("0.5.0") }).also { it.onChange = { s -> shown += s } }
        m.runCheck(manual = true)
        assertEquals(UpdateState.Checking, shown.first())
        assertEquals(UpdateState.Message("Dead Drop Finance 0.5.0 is the newest version.", false), m.state)
        val f = UpdateModel("0.5.0", check = { Updater.Result.Failed("No connection to GitHub (offline, or blocked).") })
        f.runCheck(manual = true)
        val msg = assertIs<UpdateState.Message>(f.state)
        assertTrue(msg.error)
        assertTrue(msg.text.contains("offline"))
    }

    @Test fun aCheckThatThrowsIsAFailureNotACrash() {
        val m = UpdateModel("0.5.0", check = { throw IllegalStateException("boom") })
        m.runCheck(manual = false)
        assertEquals(UpdateState.Hidden, m.state)
        m.runCheck(manual = true)
        assertTrue(assertIs<UpdateState.Message>(m.state).error)
    }

    @Test fun installDownloadsVerifiesAndStartsSetup() {
        val setup = File(tmp.root, Updater.SETUP_NAME).apply { writeText("x") }
        var launched: File? = null
        val states = mutableListOf<UpdateState>()
        val m = UpdateModel(
            "0.5.0",
            check = { Updater.Result.Newer(rel) },
            download = { _, p -> p(1, 2); p(2, 2); setup },
            launch = { launched = it; true },
            canInstall = true,
        ).also { it.onChange = { s -> states += s } }
        m.runCheck(manual = false)
        assertTrue(m.install())
        assertEquals(setup, launched)
        assertEquals(UpdateState.Downloading(rel, 2, 2), states.filterIsInstance<UpdateState.Downloading>().last())
        assertEquals(UpdateState.Installing(rel), m.state)
    }

    @Test fun aFailedDownloadOrLaunchIsToldInTheBarAndChangesNothing() {
        val bad = UpdateModel("0.5.0", check = { Updater.Result.Newer(rel) },
            download = { _, _ -> throw Updater.UpdateException("The download doesn't match the release's SHA-256 checksum.") },
            launch = { error("must not start") }, canInstall = true)
        bad.runCheck(false)
        assertFalse(bad.install())
        assertTrue(assertIs<UpdateState.Message>(bad.state).text.contains("SHA-256"))

        val noStart = UpdateModel("0.5.0", check = { Updater.Result.Newer(rel) },
            download = { _, _ -> File(tmp.root, "s.exe") }, launch = { false }, canInstall = true)
        noStart.runCheck(false)
        assertFalse(noStart.install())
        assertTrue(assertIs<UpdateState.Message>(noStart.state).error)

        val crash = UpdateModel("0.5.0", check = { Updater.Result.Newer(rel) },
            download = { _, _ -> throw RuntimeException("x") },
            canInstall = true)
        crash.runCheck(false)
        assertFalse(crash.install())
        assertIs<UpdateState.Message>(crash.state)
    }

    @Test fun installWithNothingOfferedDoesNothing() {
        val m = UpdateModel("0.5.0", check = { Updater.Result.Current("0.5.0") }, launch = { error("must not start") })
        assertFalse(m.install())
        assertEquals(UpdateState.Hidden, m.state)
    }

    @Test fun aRunFromSourceNeverInstalls() {
        assertFalse(UpdateModel.isInstalled(), "tests run from source: no jpackage.app-path")
    }
}
