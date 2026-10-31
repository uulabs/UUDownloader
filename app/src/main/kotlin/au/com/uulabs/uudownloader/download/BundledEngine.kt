package au.com.uulabs.uudownloader.download

import android.content.Context
import au.com.uulabs.uudownloader.UuLog
import java.io.File

/**
 * Ships a current yt-dlp instead of the one the wrapper library bundles.
 *
 * `youtubedl-android` carries a snapshot of yt-dlp and only unpacks it when no binary is on
 * disk. That snapshot is whatever was current when the library was released, and a year later
 * it is the first thing YouTube refuses — the failure this app spent a whole afternoon on.
 *
 * So the app carries its own copy and writes it into the library's directory before the library
 * looks. The marker beside it records what was installed, which is what stops this from ever
 * replacing a *newer* engine the user fetched with the Update button.
 */
object BundledEngine {

    private const val ENGINE_ASSET = "ytdlp"
    private const val VERSION_ASSET = "ytdlp.version"
    private const val MARKER = ".uu-engine"
    private const val BINARY = "yt-dlp"

    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open(VERSION_ASSET).bufferedReader().use { it.readLine()?.trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun ensureInstalled(context: Context) {
        val version = bundledVersion(context) ?: return
        val dir = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")
        val binary = File(dir, BINARY)
        val marker = File(dir, MARKER)

        val installed = runCatching { marker.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (binary.exists() && installed != null && !isOlder(installed, version)) return

        try {
            dir.mkdirs()
            context.assets.open(ENGINE_ASSET).use { input ->
                binary.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            marker.writeText(version)
            UuLog.i("engine: installed bundled yt-dlp $version (was ${installed ?: "library default"})")
        } catch (e: Exception) {
            // A failed copy leaves the library's own engine in place, which is a working
            // (if older) fallback — never a reason to fail the download before it starts.
            UuLog.w("engine: could not install bundled yt-dlp", e)
        }
    }

    /** Called after the Update button succeeds, so a later launch does not undo it. */
    fun recordInstalled(context: Context, version: String) {
        runCatching {
            val dir = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")
            dir.mkdirs()
            File(dir, MARKER).writeText(version)
        }
    }

    /** "2026.10.26" is a date, so a component-wise compare is the whole story. */
    private fun isOlder(installed: String, bundled: String): Boolean {
        val a = installed.split('.').mapNotNull { it.toIntOrNull() }
        val b = bundled.split('.').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left < right
        }
        return false
    }
}
