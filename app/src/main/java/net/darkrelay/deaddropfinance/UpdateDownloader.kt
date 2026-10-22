package net.darkrelay.deaddropfinance

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the release APK (Download and install, on the update bar) and verifies it against the release's own
 * published checksum before anything is installed. Plain JVM, no Android classes, so it's testable against a local
 * server like UpdateClient. Both URLs are always the app's own fixed GitHub release paths (UpdateCheck.DOWNLOAD_URL
 * and SHA256_DOWNLOAD_URL), never a URL a server answer supplies. Never throws.
 */
object UpdateDownloader {

    sealed class Result {
        data class Ok(val file: File) : Result()
        data class Failed(val message: String) : Result()
    }

    /** A verified download is refused past this size: generously above any real build, to bound memory and disk use. */
    const val MAX_APK_BYTES = 300L * 1024 * 1024

    /**
     * Downloads [apkUrl] to [destination] (via a ".part" temp file, so a half-finished download is never mistaken
     * for a whole one), checks it against the hash published at [sha256Url], and deletes it on any mismatch or
     * failure. [onProgress] gets bytes downloaded so far and the total if the server reported one (-1 otherwise),
     * called from this thread.
     */
    fun download(
        apkUrl: String,
        sha256Url: String,
        destination: File,
        userAgent: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000,
        onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val tmp = File(destination.parentFile, destination.name + ".part")
        return try {
            val expected = fetchExpectedHash(sha256Url, userAgent, connectTimeoutMs, readTimeoutMs)
                ?: return Result.Failed("Couldn't read the checksum for this release. Try again later.")
            destination.delete()
            tmp.delete()
            fetchApk(apkUrl, tmp, userAgent, connectTimeoutMs, readTimeoutMs, onProgress)
            val actual = sha256Hex(tmp)
            if (!actual.equals(expected, ignoreCase = true)) {
                tmp.delete()
                return Result.Failed("The download doesn't match this release. Try again.")
            }
            destination.delete() // renameTo can fail if the destination already exists on some filesystems
            if (!tmp.renameTo(destination)) {
                destination.delete()
                return Result.Failed("Couldn't save the download.")
            }
            Result.Ok(destination)
        } catch (t: Throwable) {
            tmp.delete()
            destination.delete()
            Result.Failed("Couldn't download the update. Check your connection and try again.")
        }
    }

    /** The release file's size in bytes as the server reports it (a HEAD request), or null if it can't be told. Never throws. */
    fun size(apkUrl: String, userAgent: String, connectMs: Int = 15_000, readMs: Int = 15_000): Long? = try {
        val conn = URL(apkUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.contentLengthLong.takeIf { it > 0 } else null
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        null
    }

    private fun fetchExpectedHash(url: String, userAgent: String, connectMs: Int, readMs: Int): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { it.readNBytes(4096) }
            val hex = String(bytes, Charsets.UTF_8).trim().split(Regex("\\s+")).firstOrNull() ?: return null
            return if (HEX64.matches(hex)) hex else null
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchApk(url: String, dest: File, userAgent: String, connectMs: Int, readMs: Int, onProgress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("status ${conn.responseCode}")
            val total = conn.contentLengthLong
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    onProgress(0, total)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("the download is larger than expected")
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
}
