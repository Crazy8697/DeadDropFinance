package net.darkrelay.deaddropfinance.desktop

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections

/**
 * A local stand-in for GitHub, on 127.0.0.1 and a free port, for the update tests. It serves the repo's release list,
 * releases/latest (the release marked latest, as GitHub does: desktop releases are published with --latest=false, so
 * it is the newest phone release) and release downloads. All data is invented. Every request is recorded.
 */
class FakeGitHub : AutoCloseable {

    data class Rel(
        val tag: String,
        val assets: List<String>,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val latest: Boolean = false,
        val body: String = "Notes for $tag",
    )

    data class Request(val method: String, val path: String, val headers: Map<String, List<String>>)

    @Volatile var releases: List<Rel> = emptyList()
    /** Download bodies by "tag/name". */
    val files = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    @Volatile var listStatus = 200
    @Volatile var listBody: String? = null
    val requests: MutableList<Request> = Collections.synchronizedList(mutableListOf())

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    val base: String get() = "http://127.0.0.1:${server.address.port}"
    val listUrl: String get() = "$base/repos/${Updater.REPO}/releases?per_page=100"
    val latestUrl: String get() = "$base/repos/${Updater.REPO}/releases/latest"
    val downloadBase: String get() = "$base/${Updater.REPO}/releases/download"

    init {
        server.createContext("/") { ex ->
            requests += Request(ex.requestMethod, ex.requestURI.toString(), ex.requestHeaders.toMap())
            val path = ex.requestURI.path
            val (status, body) = when {
                path == "/repos/${Updater.REPO}/releases" -> listStatus to (listBody ?: list(releases)).toByteArray()
                path == "/repos/${Updater.REPO}/releases/latest" ->
                    releases.firstOrNull { it.latest && !it.draft && !it.prerelease }?.let { 200 to json(it).toByteArray() }
                        ?: (404 to "{\"message\":\"Not Found\"}".toByteArray())
                path.startsWith("/${Updater.REPO}/releases/download/") -> {
                    val key = path.removePrefix("/${Updater.REPO}/releases/download/")
                    files[key]?.let { 200 to it } ?: (404 to "Not Found".toByteArray())
                }
                else -> 404 to "Not Found".toByteArray()
            }
            ex.sendResponseHeaders(status, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    fun json(r: Rel): String {
        val assets = r.assets.joinToString(",") {
            "{\"name\":\"$it\",\"browser_download_url\":\"https://elsewhere.example.com/$it\",\"size\":1}"
        }
        return "{\"tag_name\":\"${r.tag}\",\"name\":\"${r.tag}\",\"draft\":${r.draft},\"prerelease\":${r.prerelease}," +
            "\"html_url\":\"https://github.com/${Updater.REPO}/releases/tag/${r.tag}\",\"body\":\"${r.body}\"," +
            "\"assets\":[$assets]}"
    }

    fun list(rs: List<Rel>) = rs.joinToString(",", "[", "]") { json(it) }

    /** Puts a Setup (invented bytes) and its .sha256 up for [tag]; [shaOf] lets a test publish a wrong checksum. */
    fun publishSetup(tag: String, bytes: ByteArray, shaOf: ByteArray = bytes) {
        files["$tag/${Updater.SETUP_NAME}"] = bytes
        files["$tag/${Updater.SHA_NAME}"] = "${sha256(shaOf)} ${Updater.SETUP_NAME}\n".toByteArray()
    }

    override fun close() = server.stop(0)

    companion object {
        val PHONE = listOf("DeadDropFinance.apk", "DeadDropFinance.apk.sha256")
        val DESKTOP = listOf(Updater.SETUP_NAME, Updater.SHA_NAME)

        fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

        /** A repo like the real one will be: phone releases (the newest marked latest) and desktop releases. */
        fun mixed() = listOf(
            Rel("desktop-v0.7.0", DESKTOP, draft = true),
            Rel("desktop-v0.6.1-beta", DESKTOP, prerelease = true),
            Rel("v1.4.0", PHONE, prerelease = true),
            Rel("v1.3.1", PHONE, latest = true),
            Rel("desktop-v0.6.0", DESKTOP),
            Rel("v1.3.0", PHONE),
            Rel("desktop-v0.5.9", listOf(Updater.SETUP_NAME)),
            Rel("desktop-v0.5.0", DESKTOP),
            Rel("v0.9.0", PHONE),
        )
    }
}
