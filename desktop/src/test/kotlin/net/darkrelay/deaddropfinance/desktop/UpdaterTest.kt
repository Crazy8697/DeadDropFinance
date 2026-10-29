package net.darkrelay.deaddropfinance.desktop

import net.darkrelay.deaddropfinance.UpdateCheck
import net.darkrelay.deaddropfinance.UpdateClient
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

class UpdaterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun gh(rs: List<FakeGitHub.Rel> = FakeGitHub.mixed()) = FakeGitHub().also { it.releases = rs }

    // ---------------- the rules ----------------

    @Test fun picksTheNewestCompleteDesktopReleaseAndIgnoresPhoneReleases() {
        FakeGitHub().use { f ->
            val r = Updater.evaluate(f.list(FakeGitHub.mixed()), "0.5.0")
            val n = assertIs<Updater.Result.Newer>(r)
            // not the draft 0.7.0, not the pre-release 0.6.1-beta, not v1.4.0 / v1.3.1 (phone), not 0.5.9 (one file)
            assertEquals("0.6.0", n.release.version)
            assertEquals("desktop-v0.6.0", n.release.tag)
            assertEquals("https://github.com/Crazy8697/DeadDropFinance/releases/tag/desktop-v0.6.0", n.release.page)
        }
    }

    @Test fun neverReoffersTheInstalledVersionOrAnOlderOne() {
        FakeGitHub().use { f ->
            assertEquals(Updater.Result.Current("0.6.0"), Updater.evaluate(f.list(FakeGitHub.mixed()), "0.6.0"))
            assertEquals(Updater.Result.Current("0.6.0"), Updater.evaluate(f.list(FakeGitHub.mixed()), "0.6.2"))
        }
    }

    @Test fun onlyPhoneReleasesMeansNothingToOffer() {
        FakeGitHub().use { f ->
            val phoneOnly = FakeGitHub.mixed().filter { it.tag.startsWith("v") }
            assertEquals(Updater.Result.Current(null), Updater.evaluate(f.list(phoneOnly), "0.5.0"))
            assertEquals(Updater.Result.Current(null), Updater.evaluate("[]", "0.5.0"))
        }
    }

    @Test fun desktopTagsOnly() {
        assertEquals(listOf(0, 5, 0), Updater.desktopVersion("desktop-v0.5.0"))
        for (t in listOf("v1.3.1", "1.3.1", "desktop-0.5.0", "desktop-v0.5", "desktop-v0.5.0-rc1", "Desktop-v0.5.0", "", null)) {
            assertEquals(null, Updater.desktopVersion(t), "tag $t")
        }
    }

    @Test fun unreadableAnswersFailWithoutThrowing() {
        for (body in listOf("", "{", "not json", "{\"tag_name\":\"desktop-v9.9.9\"}", "[1,2", "null")) {
            assertIs<Updater.Result.Failed>(Updater.evaluate(body, "0.5.0"), body)
        }
    }

    // ---------------- against the fake GitHub ----------------

    @Test fun checkAgainstFakeGitHub() {
        gh().use { f ->
            assertEquals("0.6.0", assertIs<Updater.Result.Newer>(Updater.check("0.5.0", f.listUrl)).release.version)
            assertEquals(Updater.Result.Current("0.6.0"), Updater.check("0.6.0", f.listUrl))
            // the request: GET of the release list, a User-Agent and an Accept header, nothing about the user
            val req = f.requests.first()
            assertEquals("GET", req.method)
            assertEquals("/repos/Crazy8697/DeadDropFinance/releases?per_page=100", req.path)
            assertEquals(listOf("DeadDropFinance-Desktop/0.5.0 (+https://darkrelay.net)"), req.headers["User-agent"])
            assertEquals(listOf("application/vnd.github+json"), req.headers["Accept"])
            assertFalse(f.requests.any { it.path.endsWith("/releases/latest") }, "the desktop never asks for releases/latest")
            assertFalse(req.headers.keys.any { it.equals("Authorization", true) || it.equals("Cookie", true) })
        }
    }

    @Test fun thePhonesOwnCheckStillSeesOnlyPhoneReleases() {
        gh().use { f ->
            // the phone's unchanged UpdateClient + UpdateCheck, on the same fake repo with desktop releases in it
            val r = UpdateClient.check(f.latestUrl, "1.3.0")
            assertEquals("1.3.1", assertIs<UpdateCheck.Result.Available>(r).release.version)
            assertEquals(UpdateCheck.Result.UpToDate, UpdateClient.check(f.latestUrl, "1.3.1"))
            assertTrue(f.requests.all { it.path == "/repos/Crazy8697/DeadDropFinance/releases/latest" })
            // and if a desktop release were ever marked latest by mistake, the phone still offers nothing from it
            val desktop = FakeGitHub.Rel("desktop-v9.0.0", FakeGitHub.DESKTOP + FakeGitHub.PHONE)
            assertEquals(UpdateCheck.Result.UpToDate, UpdateCheck.evaluate(f.json(desktop), "1.3.1"))
        }
    }

    @Test fun failuresNeverThrow() {
        gh().use { f ->
            f.listStatus = 500
            assertIs<Updater.Result.Failed>(Updater.check("0.5.0", f.listUrl))
            f.listStatus = 403
            assertTrue(assertIs<Updater.Result.Failed>(Updater.check("0.5.0", f.listUrl)).reason.contains("limit"))
            f.listStatus = 200
            f.listBody = "<html>proxy login</html>"
            assertIs<Updater.Result.Failed>(Updater.check("0.5.0", f.listUrl))
        }
        // nothing listening
        val dead = FakeGitHub().let { val u = it.listUrl; it.close(); u }
        assertIs<Updater.Result.Failed>(Updater.check("0.5.0", dead))
        assertIs<Updater.Result.Failed>(Updater.check("0.5.0", "not a url"))
    }

    // ---------------- download and verify ----------------

    private val rel = Updater.Release("0.6.0", "desktop-v0.6.0", "", "")

    @Test fun downloadVerifiesTheChecksum() {
        gh().use { f ->
            val setup = ByteArray(700_000) { (it % 251).toByte() }
            f.publishSetup("desktop-v0.6.0", setup)
            run {
                val dir = tmp.newFolder()
                var last = 0L
                val got = Updater.download(rel, dir, { done, _ -> last = done }, base = f.downloadBase)
                assertEquals(Updater.SETUP_NAME, got.name)
                assertTrue(got.readBytes().contentEquals(setup))
                assertEquals(setup.size.toLong(), last)
                assertEquals(listOf(Updater.SETUP_NAME), dir.list()!!.toList(), "no .part left behind")
                assertTrue(f.requests.any { it.path == "/Crazy8697/DeadDropFinance/releases/download/desktop-v0.6.0/DeadDropFinance-Setup.exe.sha256" })
            }
        }
    }

    @Test fun aWrongOrMissingChecksumInstallsNothing() {
        gh().use { f ->
            f.publishSetup("desktop-v0.6.0", "real".toByteArray(), shaOf = "other".toByteArray())
            run {
                val dir = tmp.newFolder()
                try {
                    Updater.download(rel, dir, base = f.downloadBase)
                    fail("a wrong checksum must refuse")
                } catch (e: Updater.UpdateException) {
                    assertTrue(e.message!!.contains("SHA-256"))
                }
                assertEquals(0, dir.list()!!.size, "the download was deleted")
                f.files.remove("desktop-v0.6.0/${Updater.SHA_NAME}")
                try {
                    Updater.download(rel, dir, base = f.downloadBase)
                    fail("a missing checksum must refuse")
                } catch (e: Updater.UpdateException) {
                    // told in plain words
                }
                assertEquals(0, dir.list()!!.size)
                f.files["desktop-v0.6.0/${Updater.SHA_NAME}"] = "no checksum here".toByteArray()
                try {
                    Updater.download(rel, dir, base = f.downloadBase)
                    fail("an empty checksum file must refuse")
                } catch (e: Updater.UpdateException) {
                    assertTrue(e.message!!.contains("no SHA-256"))
                }
            }
        }
    }

    @Test fun theSetupIsStartedSilentlyWithRelaunch() {
        val cmd = Updater.setupCommand(java.io.File("C:/t/DeadDropFinance-Setup.exe"))
        assertEquals(listOf("/SILENT", "/SP-", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH=1"), cmd.drop(1))
    }
}
