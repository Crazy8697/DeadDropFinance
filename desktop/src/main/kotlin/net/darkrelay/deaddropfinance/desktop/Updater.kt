package net.darkrelay.deaddropfinance.desktop

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/**
 * Checking GitHub for a newer Dead Drop Finance for Windows, and updating to it. No UI here.
 *
 * The only network requests the desktop program makes:
 *  1. an anonymous GET of the public repo's release list (GitHub API), a few seconds after startup when
 *     "Check for updates at startup" is on, or from Help > Check for updates;
 *  2. when the user chooses Download and install: that release's .sha256 file and its Setup.
 * Nothing is sent but the request itself: a User-Agent naming the program and its version, and an Accept header.
 *
 * Phone and desktop releases share the repo Crazy8697/DeadDropFinance. Desktop releases are tagged desktop-vX.Y.Z,
 * are never marked latest (so the phone, which asks for releases/latest, never sees them), and carry exactly
 * DeadDropFinance-Setup.exe and DeadDropFinance-Setup.exe.sha256. So this check never uses releases/latest: it reads
 * the release list and takes the newest desktop-v release that is not a draft or a pre-release and carries both files.
 * Every other release (all phone releases) is ignored. Downloads always go to the fixed release download path for
 * that tag, never to a URL the answer supplies, and the Setup is used only if its SHA-256 matches the published one.
 *
 * Test hooks (never set in normal use): DDF_DESKTOP_UPDATE_API (the release list URL, a local fake GitHub),
 * DDF_DESKTOP_UPDATE_DOWNLOAD (the download base), DDF_DESKTOP_UPDATE_DRYRUN=<file> (write the Setup command line
 * there instead of starting it).
 */
object Updater {

    const val REPO = "Crazy8697/DeadDropFinance"
    const val RELEASES_API = "https://api.github.com/repos/$REPO/releases?per_page=100"
    const val DOWNLOAD_BASE = "https://github.com/$REPO/releases/download"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"
    const val TAG_PREFIX = "desktop-v"
    const val SETUP_NAME = "DeadDropFinance-Setup.exe"
    const val SHA_NAME = "$SETUP_NAME.sha256"
    val SETUP_ARGS = listOf("/SILENT", "/SP-", "/SUPPRESSMSGBOXES", "/NORESTART", "/RELAUNCH=1")

    const val CONNECT_TIMEOUT_MS = 5000
    const val READ_TIMEOUT_MS = 10000
    private const val MAX_LIST = 4 * 1024 * 1024
    private const val MAX_SETUP = 1024L * 1024 * 1024

    data class Release(val version: String, val tag: String, val notes: String, val page: String)

    sealed class Result {
        data class Newer(val release: Release) : Result()
        /** The newest desktop release is this version or older (null: no desktop release published yet). */
        data class Current(val newest: String?) : Result()
        data class Failed(val reason: String) : Result()
    }

    class UpdateException(message: String) : Exception(message)

    fun apiUrl(): String = System.getenv("DDF_DESKTOP_UPDATE_API")?.takeIf { it.isNotBlank() } ?: RELEASES_API
    fun downloadBase(): String = System.getenv("DDF_DESKTOP_UPDATE_DOWNLOAD")?.takeIf { it.isNotBlank() } ?: DOWNLOAD_BASE

    fun userAgent(version: String = BuildInfo.VERSION) = "DeadDropFinance-Desktop/$version (+https://darkrelay.net)"

    // ---------------- versions ----------------

    private val VERSION = Regex("""^(\d{1,6})\.(\d{1,6})\.(\d{1,6})$""")

    /** "desktop-v1.2.3" -> [1, 2, 3]; null for anything else (phone tags like v1.3.1, pre-release suffixes). */
    fun desktopVersion(tag: String?): List<Int>? {
        val t = tag?.trim() ?: return null
        if (!t.startsWith(TAG_PREFIX)) return null
        return parse(t.removePrefix(TAG_PREFIX))
    }

    fun parse(v: String?): List<Int>? = VERSION.matchEntire(v?.trim() ?: return null)?.groupValues?.drop(1)?.map { it.toInt() }

    fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    // ---------------- the rules ----------------

    /** The release list's JSON -> what to offer. Pure, so it is tested directly. */
    fun evaluate(body: String, installed: String): Result {
        val mine = parse(installed) ?: return Result.Failed("This program's own version isn't readable.")
        val list = try {
            Json.parse(body) as? List<*>
        } catch (e: Exception) {
            null
        } ?: return Result.Failed("GitHub's answer wasn't readable.")
        var best: Pair<List<Int>, Map<*, *>>? = null
        for (item in list) {
            val rel = item as? Map<*, *> ?: continue
            if (rel["draft"] == true || rel["prerelease"] == true) continue
            val v = desktopVersion(rel["tag_name"] as? String) ?: continue
            val names = (rel["assets"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet()
            if (SETUP_NAME !in names || SHA_NAME !in names) continue
            if (best == null || compare(v, best.first) > 0) best = v to rel
        }
        val (v, rel) = best ?: return Result.Current(null)
        val version = v.joinToString(".")
        if (compare(v, mine) <= 0) return Result.Current(version)
        val tag = rel["tag_name"] as String
        return Result.Newer(
            Release(
                version = version,
                tag = tag,
                notes = (rel["body"] as? String).orEmpty().trim(),
                page = "$RELEASES_PAGE/tag/$tag",
            )
        )
    }

    // ---------------- the check ----------------

    fun check(installed: String = BuildInfo.VERSION, url: String = apiUrl()): Result = try {
        val conn = open(url, "application/vnd.github+json", installed)
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readNBytes(MAX_LIST + 1) }
                    if (bytes.size > MAX_LIST) Result.Failed("GitHub's answer was too large.")
                    else evaluate(String(bytes, Charsets.UTF_8), installed)
                }
                403, 429 -> Result.Failed("GitHub's limit for checks from this network was reached. Try again later.")
                else -> Result.Failed("GitHub answered with an error ($code).")
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: java.net.SocketTimeoutException) {
        Result.Failed("GitHub didn't answer in time.")
    } catch (e: java.io.IOException) {
        Result.Failed("No connection to GitHub (offline, or blocked).")
    } catch (e: Exception) {
        Result.Failed("The update check failed: ${e.javaClass.simpleName}.")
    }

    private fun open(url: String, accept: String, installed: String = BuildInfo.VERSION): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", userAgent(installed))
        conn.setRequestProperty("Accept", accept)
        return conn
    }

    // ---------------- download and verify ----------------

    private fun fetchBytes(url: String, limit: Int): ByteArray {
        val conn = open(url, "application/octet-stream")
        try {
            if (conn.responseCode != 200) throw UpdateException("GitHub answered with an error (${conn.responseCode}).")
            val b = conn.inputStream.use { it.readNBytes(limit + 1) }
            if (b.size > limit) throw UpdateException("The checksum file is too large.")
            return b
        } finally {
            conn.disconnect()
        }
    }

    fun expectedHash(release: Release, base: String = downloadBase()): String {
        val text = try {
            String(fetchBytes("$base/${release.tag}/$SHA_NAME", 4096), Charsets.US_ASCII)
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("Couldn't get the release's checksum.")
        }
        return Regex("""\b([0-9a-fA-F]{64})\b""").find(text)?.groupValues?.get(1)?.lowercase()
            ?: throw UpdateException("The release's .sha256 file holds no SHA-256 checksum. Nothing is installed.")
    }

    /** The release's Setup into [folder], verified against its .sha256. A partial or wrong file is deleted. */
    fun download(
        release: Release,
        folder: File,
        progress: (Long, Long) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
        base: String = downloadBase(),
    ): File {
        val want = expectedHash(release, base)
        folder.mkdirs()
        val dest = File(folder, SETUP_NAME)
        val part = File(folder, "$SETUP_NAME.part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val conn = open("$base/${release.tag}/$SETUP_NAME", "application/octet-stream")
            try {
                if (conn.responseCode != 200) throw UpdateException("The download failed: GitHub answered ${conn.responseCode}.")
                val total = conn.contentLengthLong
                var done = 0L
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (cancelled()) throw UpdateException("The download was cancelled.")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            md.update(buf, 0, n)
                            done += n
                            if (done > MAX_SETUP) throw UpdateException("The download is far larger than any Setup.")
                            progress(done, total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (got != want) {
                throw UpdateException("The download doesn't match the release's SHA-256 checksum. It was deleted; nothing is installed.")
            }
            if (dest.exists() && !dest.delete()) throw UpdateException("An older download is in the way and can't be replaced.")
            if (!part.renameTo(dest)) throw UpdateException("The download couldn't be saved.")
            return dest
        } catch (e: UpdateException) {
            throw e
        } catch (e: Exception) {
            throw UpdateException("The download failed (offline, or the connection dropped).")
        } finally {
            if (part.exists()) part.delete()
        }
    }

    fun setupCommand(setup: File): List<String> = listOf(setup.absolutePath) + SETUP_ARGS

    /**
     * Starts the verified Setup with Inno Setup's silent switches: it upgrades the installed copy in place (same AppId),
     * asks for elevation itself (one UAC prompt), keeps the user's data, and with /RELAUNCH=1 starts the program again
     * as the original, unelevated user. The caller exits right after. The dry-run hook writes the command instead.
     */
    fun launchSetup(setup: File): Boolean {
        val cmd = setupCommand(setup)
        System.getenv("DDF_DESKTOP_UPDATE_DRYRUN")?.takeIf { it.isNotBlank() }?.let {
            File(it).writeText(cmd.joinToString("\n"))
            return true
        }
        return try {
            ProcessBuilder(cmd).start()
            true
        } catch (e: Exception) {
            false
        }
    }
}
