package au.com.uulabs.uudownloader.download

import android.content.Context
import au.com.uulabs.uudownloader.UuLog
import au.com.uulabs.uudownloader.update.AppUpdater
import java.io.File

/**
 * Owns the yt-dlp file on disk: what is installed, and how it gets replaced.
 *
 * `youtubedl-android` carries a snapshot of yt-dlp and only unpacks it when no binary is on disk.
 * That snapshot is whatever was current when the library was released, and a year later it is the
 * first thing YouTube refuses. So the app carries its own copy and writes it into the library's
 * directory before the library looks.
 *
 * The marker beside it is the record of what is installed, which stops a bundled copy from ever
 * replacing a *newer* engine the user fetched. Every replacement is a rename, so a failed copy or
 * a dead battery can never leave half an engine where the library will run it.
 */
object BundledEngine {

    private const val ENGINE_ASSET = "ytdlp"
    private const val VERSION_ASSET = "ytdlp.version"
    private const val MARKER = ".uu-engine"
    private const val BINARY = "yt-dlp"

    fun directory(context: Context): File = File(context.noBackupFilesDir, "youtubedl-android/yt-dlp")

    fun bundledVersion(context: Context): String? = runCatching {
        context.assets.open(VERSION_ASSET).bufferedReader().use { it.readLine()?.trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The version the marker records, or null when this app has not installed one. */
    fun installedVersion(context: Context): String? = runCatching {
        File(directory(context), MARKER).readText().trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun ensureInstalled(context: Context) {
        val version = bundledVersion(context) ?: return
        val dir = directory(context)
        val binary = File(dir, BINARY)
        val installed = installedVersion(context)
        if (binary.exists() && installed != null && !AppUpdater.isNewer(version, installed)) return

        val incoming = File(dir, "$BINARY.bundled")
        try {
            dir.mkdirs()
            context.assets.open(ENGINE_ASSET).use { input ->
                incoming.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            }
            if (!incoming.renameTo(binary)) throw IllegalStateException("could not move the engine into place")
            File(dir, MARKER).writeText(version)
            UuLog.i("engine: installed bundled yt-dlp $version (was ${installed ?: "library default"})")
        } catch (e: Exception) {
            // Whatever was there is untouched, and if nothing was the library unpacks its own.
            UuLog.w("engine: could not install bundled yt-dlp", e)
        } finally {
            incoming.delete()
        }
    }

    /** Puts [incoming] in place, keeping the old engine until [commit] or [rollBack]. */
    fun swapIn(context: Context, incoming: File, version: String) {
        val dir = directory(context)
        val binary = File(dir, BINARY)
        val previous = File(dir, "$BINARY.previous")
        previous.delete()
        if (binary.exists() && !binary.renameTo(previous)) {
            throw IllegalStateException("could not set the current engine aside")
        }
        if (!incoming.renameTo(binary)) {
            previous.renameTo(binary)
            throw IllegalStateException("could not move the new engine into place")
        }
        File(dir, "$MARKER.previous").writeText(installedVersion(context).orEmpty())
        File(dir, MARKER).writeText(version)
    }

    fun commit(context: Context) {
        val dir = directory(context)
        File(dir, "$BINARY.previous").delete()
        File(dir, "$MARKER.previous").delete()
    }

    fun rollBack(context: Context) {
        val dir = directory(context)
        val previous = File(dir, "$BINARY.previous")
        if (previous.exists()) previous.renameTo(File(dir, BINARY))
        val previousMarker = File(dir, "$MARKER.previous")
        val version = runCatching { previousMarker.readText() }.getOrDefault("")
        File(dir, MARKER).writeText(version)
        previousMarker.delete()
    }
}
