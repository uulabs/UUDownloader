package au.com.uulabs.uudownloader.download

import au.com.uulabs.uudownloader.core.DirectLinks
import au.com.uulabs.uudownloader.core.FileNames
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * For a link that is already the file: a CDN mp4, a webm someone pasted. No Python, no site logic,
 * no waiting for a runtime to unpack.
 *
 * It only ever saves media. If the server answers with a page, it throws [NotADirectFile] before
 * writing anything and the caller hands the job to yt-dlp, which knows what to do with a page.
 */
class DirectHttpEngine : VideoEngine {

    override fun probe(url: String): SourceInfo? =
        SourceInfo(title = DirectLinks.suggestedName(url), maxHeight = null, durationSeconds = null)

    override fun download(
        request: DownloadRequest,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadResult {
        if (isCancelled()) throw CancelledDownload()
        val workDir = request.workDir
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw DownloadFailure("Could not prepare a scratch folder")
        }
        val suggested = DirectLinks.suggestedName(request.url) ?: "video.mp4"
        val target = File(workDir, FileNames.fromTitle(FileNames.stemOf(suggested), extension = suggested.substringAfterLast('.', "mp4")))

        val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DownloadFailure("The server answered $code")

            val total = connection.contentLengthLong
            var copied = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var read = input.read(buffer)
                if (read < 0) throw DownloadFailure("The server sent no data")
                if (DirectLinks.looksLikePage(connection.contentType, buffer, read)) throw NotADirectFile()

                target.outputStream().use { output ->
                    while (read >= 0) {
                        if (isCancelled()) throw CancelledDownload()
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) {
                            lastReport = now
                            onProgress(
                                Progress(
                                    percent = if (total > 0) copied.toFloat() / total else null,
                                    line = if (total > 0) "${megabytes(copied)} of ${megabytes(total)}" else megabytes(copied),
                                )
                            )
                        }
                        read = input.read(buffer)
                    }
                }
            }
            if (copied == 0L) throw DownloadFailure("The server sent no data")
            if (total > 0 && copied != total) {
                throw DownloadFailure("The connection ended early: ${megabytes(copied)} of ${megabytes(total)}")
            }
            onProgress(Progress(percent = 1f, line = null))
            return DownloadResult(target, request.quality)
        } finally {
            connection.disconnect()
        }
    }

    private fun megabytes(bytes: Long): String =
        String.format("%.1f MB", bytes / 1_000_000.0)

    private companion object {
        // CDNs commonly refuse an unfamiliar user agent, so this one reads as a phone's browser.
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0 Mobile Safari/537.36"
    }
}
