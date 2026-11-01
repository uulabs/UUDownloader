package au.com.uulabs.uudownloader.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import au.com.uulabs.uudownloader.UuLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** A version published on GitHub that this install does not have. */
data class ReleaseInfo(
    val version: String,
    val notes: String?,
    val assetName: String,
    val assetUrl: String,
    val sizeBytes: Long,
)

/**
 * Updates the app from its own GitHub releases.
 *
 * Nothing is installed silently: the APK is downloaded to the cache, handed to the system
 * package installer, and the user still has to press Install on Android's own dialog. There is
 * no bypass here, and no attempt to make one — the updater's whole job is to stop someone
 * having to find the download link by hand.
 */
object AppUpdater {

    private const val REPO = "uulabs/UUDownloader"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val USER_AGENT = "UU-Downloader-Android"

    fun checkForUpdate(currentVersion: String): Result<ReleaseInfo?> = runCatching {
        val json = githubJson(LATEST)
        val tag = json.optString("tag_name").removePrefix("v").removePrefix("V")
        if (tag.isBlank()) return@runCatching null
        if (!isNewer(tag, currentVersion)) return@runCatching null

        val assets = json.optJSONArray("assets") ?: return@runCatching null
        val apks = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
        val chosen = pickAsset(apks) ?: return@runCatching null

        ReleaseInfo(
            version = tag,
            notes = json.optString("body").takeIf { it.isNotBlank() }?.take(600),
            assetName = chosen.optString("name"),
            assetUrl = chosen.optString("browser_download_url"),
            sizeBytes = chosen.optLong("size", 0L),
        )
    }

    /** The right APK for this phone, preferring its own architecture over the universal one. */
    private fun pickAsset(apks: List<JSONObject>): JSONObject? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val wanted = when {
            abi.contains("arm64") -> "arm64"
            abi.contains("armeabi") || abi.contains("v7") -> "armv7"
            else -> null
        }
        val exact = wanted?.let { tag -> apks.firstOrNull { it.optString("name").contains(tag, true) } }
        return exact
            ?: apks.firstOrNull { it.optString("name").contains("universal", true) }
            ?: apks.firstOrNull()
    }

    fun download(context: Context, info: ReleaseInfo, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, info.assetName)

        // A first attempt can stop at Android's "allow installs from this source" prompt. The
        // bytes are already here, so that detour must not cost another 60 MB.
        if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
            UuLog.i("app update: reusing the cached ${info.assetName}")
            return target
        }
        if (target.exists()) target.delete()

        val connection = (URL(info.assetUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/octet-stream")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download failed (${connection.responseCode})")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var copied = 0L
            var last = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - last > 300) {
                            last = now
                            onProgress(if (total > 0) (copied.toFloat() / total) else 0f)
                        }
                    }
                }
            }
            UuLog.i("app update: downloaded ${info.assetName} ($copied bytes)")
            return target
        } finally {
            connection.disconnect()
        }
    }

    /** Whether Android will even show the installer for this app. */
    fun canInstall(context: Context): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(true)

    /** Send the user straight to the toggle they need, rather than to Settings at large. */
    fun openInstallPermission(context: Context) {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * Does the downloaded APK carry the same signing key as the installed app?
     *
     * Android refuses an in-place update across different keys, and the failure arrives as a
     * generic installer error with no explanation. This app changed keys once — from the debug
     * certificate to a real one, to stop Play Protect calling it an unknown publisher — so the
     * check is not theoretical: without it, the first update after that change fails silently.
     */
    fun signedLikeInstalled(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installed = pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            .signatures?.firstOrNull()?.toByteArray()
        @Suppress("DEPRECATION")
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
            ?.signatures?.firstOrNull()?.toByteArray()
        installed != null && candidate != null && installed.contentEquals(candidate)
    }.getOrDefault(true)

    /** The page a user lands on when the app cannot install the update itself. */
    fun openReleasePage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/$REPO/releases/latest"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Hand the APK to the system installer. Android still asks the user to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Latest published yt-dlp tag, for the engine update prompt. */
    fun latestEngineVersion(): Result<String?> = runCatching {
        val json = githubJson("https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")
        json.optString("tag_name").trim().takeIf { it.isNotEmpty() }
    }

    internal fun githubJson(url: String): JSONObject = JSONObject(httpGet(url))

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

    /** "1.2.0" is newer than "1.1.9"; a v prefix and any suffix are ignored. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
