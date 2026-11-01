package au.com.uulabs.uudownloader.update

import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** A version published on GitHub that this install does not have. */
data class ReleaseInfo(
    val version: String,
    val notes: String?,
)

/**
 * Tells the user when there is a newer build. It does not fetch one.
 *
 * An app that downloads an APK and hands it to the system installer is, structurally, the same
 * thing malware does. Google Play Protect treats it that way, and it flagged every build of this
 * app that did it — including the one signed with a proper key. So the check stays and the
 * installer goes: the app opens the release page, the browser downloads the file, and Android's
 * own installer takes over. One extra tap, and the app is no longer a dropper.
 */
object AppUpdater {

    private const val REPO = "uulabs/UUDownloader"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
    private const val USER_AGENT = "UU-Downloader-Android"

    fun checkForUpdate(currentVersion: String): Result<ReleaseInfo?> = runCatching {
        val json = githubJson(LATEST)
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isBlank()) return@runCatching null
        if (!isNewer(tag, currentVersion)) return@runCatching null
        ReleaseInfo(
            version = tag,
            notes = json.optString("body").takeIf { it.isNotBlank() }?.take(600),
        )
    }

    /** Latest published yt-dlp tag, for the engine update prompt. */
    fun latestEngineVersion(): Result<String?> = runCatching {
        val json = githubJson("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        json.optString("tag_name").trim().takeIf { it.isNotEmpty() }
    }

    /** Where the user gets the new APK. The app itself never handles an APK. */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private fun githubJson(url: String): JSONObject = JSONObject(httpGet(url))

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code == 404) throw IllegalStateException("No release has been published yet")
            if (code !in 200..299) throw IllegalStateException("GitHub answered $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * "1.2.0" is newer than "1.1.9". A v prefix and a suffix are ignored, so "1.5.0-2" and
     * "1.5.0" are the same release. A version that is not numbers is never newer.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(current) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numbers(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").removePrefix("V").substringBefore('-').split('.')
        return parts.map { it.toIntOrNull() ?: return null }
    }
}
