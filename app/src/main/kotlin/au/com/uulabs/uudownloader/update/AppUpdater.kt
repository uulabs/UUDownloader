package au.com.uulabs.uudownloader.update

import android.content.Context
import android.content.Intent
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** One file attached to a release. */
data class ReleaseAsset(
    val name: String,
    val url: String,
    val sizeBytes: Long,
)

/** A version published on GitHub that this install does not have. */
data class ReleaseInfo(
    val version: String,
    val notes: String?,
    val assets: List<ReleaseAsset>,
)

/**
 * Tells the user when there is a newer build.
 *
 * The app does not fetch or install APKs by default. An app that does is, structurally, the same
 * thing malware does; Google Play Protect says so on install, and every build of this app that
 * did it was flagged. The standard builds therefore only *report* an update and open the release
 * page — see [SelfUpdate] for the opt-in build that does install itself.
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

        val assets = (json.optJSONArray("assets") ?: return@runCatching null)
        val apks = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            .map {
                ReleaseAsset(
                    name = it.optString("name"),
                    url = it.optString("browser_download_url"),
                    sizeBytes = it.optLong("size", 0L),
                )
            }

        ReleaseInfo(
            version = tag,
            notes = json.optString("body").takeIf { it.isNotBlank() }?.take(600),
            assets = apks,
        )
    }

    /** Latest published yt-dlp tag, for the engine update prompt. */
    fun latestEngineVersion(): Result<String?> = runCatching {
        val json = githubJson("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        json.optString("tag_name").trim().takeIf { it.isNotEmpty() }
    }

    /** Where the user gets the new APK. The standard builds never handle an APK themselves. */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(RELEASES_PAGE))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    internal fun githubJson(url: String): JSONObject = JSONObject(httpGet(url))

    internal fun httpGet(url: String): String {
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
     * "1.2.0" is newer than "1.1.9"; a leading v and any suffix after a dash are ignored.
     *
     * A version with a part that is not a number is not treated as newer: "1.x.9" is not a
     * version this app can reason about, and answering "yes, newer" for it would offer an
     * update that does not exist.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = numericParts(candidate) ?: return false
        val b = numericParts(current) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun numericParts(version: String): List<Int>? =
        version.removePrefix("v").removePrefix("V").substringBefore('-').split('.').map {
            it.toIntOrNull() ?: return null
        }
}
