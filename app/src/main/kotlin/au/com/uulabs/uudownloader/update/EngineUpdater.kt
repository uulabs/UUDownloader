package au.com.uulabs.uudownloader.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches a yt-dlp release and proves it is the one yt-dlp published.
 *
 * The wrapper library's own updater takes a process-wide lock, has no read timeout and checks
 * nothing, so a slow network could hang every download behind it. Here the time is bounded and
 * the checksum decides.
 */
object EngineUpdater {

    private const val BASE = "https://github.com/yt-dlp/yt-dlp/releases/download"
    private const val ASSET = "yt-dlp"
    private const val SUMS = "SHA2-256SUMS"
    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val MAX_MILLIS = 3 * 60 * 1000L
    private const val USER_AGENT = "UU-Downloader-Android"

    private val TAG = Regex("""\d{4}\.\d{1,2}\.\d{1,2}(\.\d+)?""")
    private val SHA256 = Regex("""[0-9a-f]{64}""")

    fun fetchVerified(tag: String, target: File) {
        check(isValidTag(tag)) { "yt-dlp's release feed named an odd version" }
        val deadline = System.currentTimeMillis() + MAX_MILLIS
        val sums = open("$BASE/$tag/$SUMS").let { connection ->
            try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        val expected = checksumFor(sums)
            ?: throw IllegalStateException("yt-dlp's checksum list doesn't include the engine")

        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open("$BASE/$tag/$ASSET")
        try {
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= MAX_BYTES) { "the engine download was far too big" }
                        check(System.currentTimeMillis() < deadline) { "the engine download took too long" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) {
            target.delete()
            throw IllegalStateException("the engine download failed its checksum")
        }
    }

    fun isValidTag(tag: String): Boolean = TAG.matches(tag)

    /** The hash from a `sha256sum` listing for the platform-independent engine file. */
    fun checksumFor(sums: String): String? =
        sums.lineSequence()
            .map { it.trim().split(Regex("""\s+\*?""")) }
            .firstOrNull { it.size == 2 && it[1] == ASSET && SHA256.matches(it[0].lowercase()) }
            ?.get(0)
            ?.lowercase()

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IllegalStateException("GitHub answered $code")
        }
        return connection
    }
}
