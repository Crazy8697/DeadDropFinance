package net.darkrelay.deaddropfinance

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.darkrelay.deaddropfinance.backup.RecoveryKey
import net.darkrelay.deaddropfinance.serversync.Box
import net.darkrelay.deaddropfinance.serversync.HttpTransport
import net.darkrelay.deaddropfinance.serversync.LocalNetwork
import net.darkrelay.deaddropfinance.serversync.ServerClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Restore through the real system file picker (1.7.3), on the Android 17 emulator, driven end to end like a user: the
 * app's screens and DocumentsUI's, tapped by their text. Two backup files of the two kinds, saved to Downloads with the
 * app's own Back up now: one made before the app joined an account (its own recovery key), one made after (the account's
 * key, made by another device). Each is restored logged in (the "Replace the server's budget?" screen must appear, and the
 * server's budget must become the backup) and logged out (local only).
 *
 * The other device, the one that made the account, is played here with the shared ServerClient: it makes the account
 * under its own recovery key and later hands that key to the app, sealed, as the program does. Synthetic data only, a
 * throwaway test server only: instrumentation arguments ddfLanUrl (as the emulator reaches it) and ddfSetupCode (that
 * server's one-time setup code). Start from a cleared app and empty Downloads (tools\emulator.ps1 -Instrument does both).
 */
@RunWith(AndroidJUnit4::class)
class RestorePickerOnDeviceTest {

    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ua = ins.uiAutomation
    private val ctx = ins.targetContext
    private val pkg = ctx.packageName

    // ---- driving the screens by their text (the app's and the picker's alike)

    private fun shell(cmd: String) { ua.executeShellCommand(cmd).use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() } }

    private fun nodes(root: AccessibilityNodeInfo?, match: (AccessibilityNodeInfo) -> Boolean, out: MutableList<AccessibilityNodeInfo> = mutableListOf()): List<AccessibilityNodeInfo> {
        if (root == null) return out
        if (match(root)) out += root
        for (i in 0 until root.childCount) nodes(root.getChild(i), match, out)
        return out
    }

    private fun textOf(n: AccessibilityNodeInfo) = (n.text ?: n.contentDescription)?.toString()

    private fun find(text: String, exact: Boolean = true): List<AccessibilityNodeInfo> =
        nodes(ua.rootInActiveWindow, { n -> textOf(n)?.let { if (exact) it == text else it.contains(text) } == true })
            .filter { n -> Rect().also { n.getBoundsInScreen(it) }.let { it.width() > 0 && it.height() > 0 } }

    private fun waitFor(text: String, exact: Boolean = true, ms: Long = 20_000): List<AccessibilityNodeInfo> {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val f = runCatching { find(text, exact) }.getOrDefault(emptyList())
            if (f.isNotEmpty()) return f
            Thread.sleep(300)
        }
        throw AssertionError("not on screen: \"$text\"; showing: ${screen().take(600)}")
    }

    private fun screen(): String = runCatching { nodes(ua.rootInActiveWindow, { textOf(it) != null }).joinToString(" | ") { textOf(it)!! } }.getOrDefault("")

    private fun tap(n: AccessibilityNodeInfo) {
        val r = Rect().also { n.getBoundsInScreen(it) }
        shell("input tap ${r.centerX()} ${r.centerY()}")
        Thread.sleep(700)
    }

    private fun tap(text: String, last: Boolean = false, exact: Boolean = true) = waitFor(text, exact).let { tap(if (last) it.last() else it.first()) }

    private fun type(text: String) { shell("input text $text"); Thread.sleep(300) }

    private fun key(code: Int) { shell("input keyevent $code"); Thread.sleep(500) }

    private fun scrollDown() { shell("input swipe 540 1800 540 700 300"); Thread.sleep(600) }

    /** Scrolls until [text] shows (forms longer than the screen), then taps it. */
    private fun scrollTap(text: String, last: Boolean = false) {
        repeat(6) { if (find(text).isNotEmpty()) return tap(text, last); scrollDown() }
        tap(text, last)
    }

    // ---- the app's steps

    private fun addAccount(name: String) {
        tap("More", last = true)
        tap("Accounts")
        tap("Add")
        tap("Name")
        type(name)
        key(111)
        scrollTap("Save")
        waitFor(name)
        key(4)
    }

    /** More > Backup and restore > Back up now, saved in Downloads as [fileName] with the real save dialog. */
    private fun backUpAs(fileName: String) {
        tap("More", last = true)
        tap("Backup and restore")
        tap("Back up now")
        // the first backup makes the recovery key and shows it once
        Thread.sleep(1500)
        if (find("I saved it").isNotEmpty()) tap("I saved it")
        waitFor("SAVE")
        val nameField = nodes(ua.rootInActiveWindow, { it.className?.toString() == "android.widget.EditText" }).last()
        tap(nameField)
        shell("input keycombination 113 29")   // Ctrl+A: the suggested name, all of it
        key(67)
        type(fileName)
        tap("SAVE")
        waitFor("Backed up:", exact = false)
        key(4)
    }

    /** Restore from file through the picker; logged in, through the warning. Returns the message shown at the end. */
    private fun restore(fileName: String, loggedIn: Boolean): String {
        tap("More", last = true)
        tap("Backup and restore")
        tap("Restore from file")
        tap(fileName)
        waitFor("It opens with the recovery key saved on this phone", exact = false)
        tap("Replace everything and restore")
        if (loggedIn) {
            waitFor("Replace the server's budget?")
            waitFor("This replaces the budget on your server. Every device on this account will get this backup instead of what it has now.", exact = false)
            tap("Replace server budget")
        } else {
            Thread.sleep(800)
            assertTrue("no server warning when logged out", find("Replace the server's budget?").isEmpty())
        }
        val msg = textOf(waitFor("Restored:", exact = false, ms = 30_000).first())!!
        key(4)
        return msg
    }

    @Test fun bothBackupKindsRestoreThroughThePickerLoggedInAndOut() {
        val url = InstrumentationRegistry.getArguments().getString("ddfLanUrl")
        val code = InstrumentationRegistry.getArguments().getString("ddfSetupCode")
        assumeTrue("set ddfLanUrl and ddfSetupCode (a fresh throwaway test server)", !url.isNullOrBlank() && !code.isNullOrBlank())
        ua.grantRuntimePermission(pkg, LocalNetwork.PERMISSION)
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(pkg)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        // setup, then a budget of its own and the backup made before joining (this device's own recovery key)
        val first = waitFor("Start").first()
        tap(first); tap("Next", last = true); tap("I'll enter things myself"); tap("Next", last = true); tap("Finish", last = true)
        waitFor("Safe to spend")
        addAccount("BeforeJoin")
        backUpAs("before-join.ddfbak")

        // the other device makes the account under its own recovery key
        val net = HttpTransport(url!!)
        val serverKey = ServerClient.serverKey(net).publicKey
        val programKey = RecoveryKey.generate()
        val program = ServerClient(net, serverKey, Box.keyPair())
        val pw = "picker-" + java.util.UUID.randomUUID().toString().take(8)
        program.setup(code!!, "picker", pw, "Picker program", "desktop", RecoveryKey.id(programKey))

        // the app logs in as a second device, then the account's key reaches it
        tap("More", last = true)
        tap("Not connected. Connect to server")
        tap("Server address"); type(url.removePrefix("http://")); key(111)
        tap("Connect", last = true)
        waitFor("Check the server's fingerprint")
        tap("Log in")
        tap("Username"); type("picker")
        tap("Password"); type(pw); key(111)
        // the Log in button is below the form, past the Log in tab: scroll to the form's end first
        repeat(6) { if (find("The password is never kept on this phone.").isEmpty()) scrollDown() }
        tap("Log in", last = true)
        // this phone has a budget of its own, so the app backs it up first (to Downloads/DeadDropFinance) and asks
        tap("Back up and log in")
        waitFor("Logged in.", exact = false)
        val app = program.recoveryStatus().needing.single()
        program.recoveryPut(app.deviceId, RecoveryKey.id(programKey), Box.seal(programKey, app.publicKey))
        tap("Sync Now")
        waitFor("The account's recovery key reached this phone", exact = false)
        tap("OK")
        key(4)

        // a budget that differs, and the backup made after joining (the account's key)
        addAccount("AfterJoin")
        backUpAs("after-join.ddfbak")

        fun serverAccounts(): Int = program.records("", "", 500).records.count { it.entityType == "account" && !it.deleted }

        // logged in: the warning, then the server's budget becomes each backup
        val a = restore("before-join.ddfbak", loggedIn = true)
        assertTrue(a, a.contains("1 accounts") && a.contains("The server's budget is now this backup"))
        assertEquals("the server has the before-join budget", 1, serverAccounts())
        val b = restore("after-join.ddfbak", loggedIn = true)
        assertTrue(b, b.contains("2 accounts") && b.contains("The server's budget is now this backup"))
        assertEquals("the server has the after-join budget", 2, serverAccounts())

        // logged out: each restores on this phone alone
        tap("More", last = true)
        tap("Server")
        // the section's title and its button both say Log out; the button is the last one, at the screen's end
        repeat(5) { scrollDown() }
        tap("Log out", last = true)
        waitFor("Log out of the server?")
        tap("Log out", last = true)
        waitFor("Connect to server")
        key(4)
        val c = restore("before-join.ddfbak", loggedIn = false)
        assertTrue(c, c.contains("1 accounts") && !c.contains("server"))
        val d = restore("after-join.ddfbak", loggedIn = false)
        assertTrue(d, d.contains("2 accounts") && !d.contains("server"))
        assertEquals("logged out, the server was left alone", 2, serverAccounts())
    }
}
