package au.com.uulabs.uudownloader.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * A `cookies.txt` the user exported from a browser, imported once and kept in app storage.
 *
 * Reddit, Vimeo, Instagram and most sites that gate video behind an account will not answer an
 * anonymous request. yt-dlp solves that with `--cookies`, and this is the file it points at.
 *
 * It is copied in rather than referenced: a Storage Access Framework grant can be revoked or
 * lost across a reboot, and a download failing at midnight because a permission expired is
 * worse than a copy of a file the user already chose to hand over.
 *
 * These are live signed-in sessions, so the file lives in the no-backup directory and never
 * leaves the phone. yt-dlp also rewrites its cookie file when it exits, so it only ever gets a
 * throwaway copy: otherwise a run in progress could bring back a file the user just removed.
 */
class CookieJar(private val context: Context) {

    private val file: File = File(context.noBackupFilesDir, FILE_NAME)
    private val runDir: File = File(context.noBackupFilesDir, RUN_DIR)

    init {
        // Earlier versions kept it where a device transfer would carry it along.
        val old = File(context.filesDir, FILE_NAME)
        if (old.exists()) {
            if (file.exists() || !old.renameTo(file)) old.delete()
        }
    }

    val exists: Boolean get() = file.exists() && file.length() > 0

    val cookieCount: Int get() = runCatching { countIn(file) }.getOrDefault(0)

    /** A private copy for one run of the engine, or null when nothing was imported. */
    fun runCopy(): File? {
        if (!exists) return null
        return runCatching {
            runDir.mkdirs()
            file.copyTo(File(runDir, UUID.randomUUID().toString()))
        }.getOrNull()
    }

    /** Copies left behind when a process died mid-run. Call when no engine run can be alive. */
    fun sweepRunCopies() {
        runDir.listFiles()?.forEach { it.delete() }
    }

    /** Copies [uri] in and returns how many cookies it holds, or a message explaining the refusal. */
    fun import(uri: Uri): Result<Int> = runCatching {
        val staging = File(context.noBackupFilesDir, "$FILE_NAME.importing")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("That file could not be read")
            input.use {
                staging.outputStream().use { output ->
                    // A huge file is almost certainly not a cookie export, and the only way to
                    // find out cheaply is to stop as soon as it gets too big.
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IllegalStateException("That file is too big to be a cookies file")
                        output.write(buffer, 0, read)
                    }
                }
            }
            val count = countIn(staging)
            if (count == 0) throw IllegalStateException("That file doesn't look like a cookies.txt export")
            if (!staging.renameTo(file)) throw IllegalStateException("Could not store the cookies file")
            count
        } finally {
            staging.delete()
        }
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
        const val RUN_DIR = "cookies-run"
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
