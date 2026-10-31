package au.com.uulabs.uudownloader.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * A `cookies.txt` the user exported from a browser, imported once and kept in app storage.
 *
 * Reddit, Vimeo, Instagram and most sites that gate video behind an account will not answer an
 * anonymous request. yt-dlp solves that with `--cookies`, and this is the file it points at.
 *
 * It is copied in rather than referenced: a Storage Access Framework grant can be revoked or
 * lost across a reboot, and a download failing at midnight because a permission expired is
 * worse than a copy of a file the user already chose to hand over.
 */
class CookieJar(private val context: Context) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    val exists: Boolean get() = file.exists() && file.length() > 0

    /** The handle to pass to the engine, or null when nothing was imported. */
    fun path(): File? = file.takeIf { exists }

    val cookieCount: Int get() = runCatching { countIn(file) }.getOrDefault(0)

    /** Copies [uri] in and returns how many cookies it holds, or a message explaining the refusal. */
    fun import(uri: Uri): Result<Int> = runCatching {
        val staging = File(context.filesDir, "$FILE_NAME.importing")
        context.contentResolver.openInputStream(uri)?.use { input ->
            staging.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        } ?: throw IllegalStateException("That file could not be read")

        // A huge file is almost certainly not a cookie export; refuse rather than copy a video.
        if (staging.length() > MAX_BYTES) {
            staging.delete()
            throw IllegalStateException("That file is too big to be a cookies file")
        }

        val count = countIn(staging)
        if (count == 0) {
            staging.delete()
            throw IllegalStateException("That file doesn't look like a cookies.txt export")
        }
        if (file.exists()) file.delete()
        if (!staging.renameTo(file)) {
            staging.delete()
            throw IllegalStateException("Could not store the cookies file")
        }
        count
    }

    fun clear() {
        file.delete()
    }

    /**
     * Netscape cookie files are tab separated with seven fields. Counting those lines is enough
     * to tell a real export from a JSON download or a picked video.
     */
    private fun countIn(target: File): Int {
        if (!target.exists()) return 0
        var count = 0
        target.bufferedReader().useLines { lines ->
            lines.take(20_000).forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                if (line.count { it == '\t' } >= 6) count++
            }
        }
        return count
    }

    private companion object {
        const val FILE_NAME = "cookies.txt"
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
