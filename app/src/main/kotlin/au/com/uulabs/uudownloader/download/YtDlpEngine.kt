package au.com.uulabs.uudownloader.download

import android.content.Context
import au.com.uulabs.uudownloader.core.QualityChoice
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.UuLog
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import org.json.JSONObject

/** The download was cancelled by the user rather than by the source. */
class CancelledDownload : Exception("cancelled")

/**
 * Downloads through yt-dlp on the bundled Python runtime.
 *
 * This is the only honest way to get YouTube, and it happens to be one code path for TikTok,
 * Instagram, Facebook and X: those sites change their private endpoints constantly and yt-dlp
 * tracks them. Anything hand-written against those endpoints would be dead within weeks.
 *
 * The engine only writes into the job's scratch directory. Handing the finished file to the
 * folder the user chose is [FolderWriter]'s job, because that is where Android's storage rules
 * live.
 */
class YtDlpEngine(
    private val context: Context,
    /** cookies.txt for sites that will not answer an anonymous request; null when none. */
    private val cookies: () -> File? = { null },
) : VideoEngine {

    @Volatile
    private var ready = false

    /** Whether this build carries ffmpeg, and whether it initialised. Both must be true. */
    @Volatile
    private var canMerge = false

    private val initLock = Any()

    /**
     * Unpacking Python takes a few seconds the first time and must not race. It must also stay
     * retryable: a failed unpack should not poison the process, so [ready] is only set once
     * both runtimes are up.
     */
    fun ensureReady() {
        if (ready) return
        synchronized(initLock) {
            if (ready) return
            UuLog.i("yt-dlp init: unpacking python into ${context.noBackupFilesDir}")
            val started = System.currentTimeMillis()
            YoutubeDL.init(context)
            UuLog.i("yt-dlp init: python ready in ${System.currentTimeMillis() - started} ms")
            canMerge = if (BuildConfig.UU_FFMPEG) {
                runCatching { FFmpeg.init(context) }
                    .onFailure { UuLog.w("ffmpeg init failed; falling back to single-file formats", it) }
                    .isSuccess
            } else {
                false
            }
            UuLog.i("yt-dlp init: complete, canMerge=$canMerge")
            ready = true
        }
    }

    fun isReady(): Boolean = ready

    /**
     * yt-dlp's version, unpacking the runtime first if this is the first call.
     *
     * The bundled binary is whatever shipped with the library, and the sites it knows about
     * change weekly. Showing the date is how a user can tell whether "this site stopped
     * working" is their problem or the engine's.
     */
    fun versionOrInit(): String? = runCatching {
        ensureReady()
        // The library only records a version after it has performed an update, so a fresh
        // install knows nothing. Ask the binary itself in that case.
        YoutubeDL.version(context) ?: askBinaryVersion()
    }.getOrNull()

    private fun askBinaryVersion(): String? = runCatching {
        YoutubeDL.execute(YoutubeDLRequest("").addOption("--version"))
            .out
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.first().isDigit() }
    }.getOrNull()

    /** Fetch a newer yt-dlp from its own release feed. Returns the version now in place. */
    fun updateEngine(): String? {
        ensureReady()
        YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
        val now = YoutubeDL.version(context)
        UuLog.i("engine updated to $now")
        return now
    }

    /** yt-dlp's own version, for the About screen. Null until the runtime is unpacked. */
    fun toolVersion(): String? = runCatching {
        if (ready) YoutubeDL.version(context) else null
    }.getOrNull()

    /**
     * A probe is a convenience, never a gate: on a slow phone yt-dlp can spend half a minute
     * reading a page before it says anything, and the user should not be kept waiting to press
     * Download. It runs under a process id so it can be killed the moment it stops being
     * useful.
     */
    override fun probe(url: String): SourceInfo? = runCatching {
        ensureReady()
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-single-json")
        request.addOption("--no-playlist")
        request.addOption("--no-warnings")
        request.addOption("--skip-download")
        cookies()?.let { request.addOption("--cookies", it.absolutePath) }
        val json = YoutubeDL.execute(request, PROBE_PROCESS_ID).out
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") }
            ?.let { JSONObject(it) }
            ?: return null
        SourceInfo(
            title = json.optString("title").takeIf { it.isNotBlank() && it != "null" },
            maxHeight = maxVideoHeight(json),
            durationSeconds = json.optDouble("duration", 0.0).takeIf { it > 0 }?.toLong(),
        )
    }.getOrNull()

    fun cancelProbe() {
        runCatching { YoutubeDL.destroyProcessById(PROBE_PROCESS_ID) }
    }

    private fun maxVideoHeight(json: JSONObject): Int? {
        var best = json.optInt("height", 0)
        val formats = json.optJSONArray("formats") ?: return best.takeIf { it > 0 }
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            // Audio-only entries report no height and are meaningless here.
            if (format.optString("vcodec", "none") == "none") continue
            val height = format.optInt("height", 0)
            if (height > best) best = height
        }
        return best.takeIf { it > 0 }
    }

    override fun download(
        request: DownloadRequest,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        ensureReady()
        val workDir = request.workDir
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw DownloadFailure("Could not prepare a scratch folder")
        }
        val processId = processIdFor(request.jobId)

        val ytRequest = YoutubeDLRequest(request.url)
        val merge = canMerge
        val selector = QualityChoice.selector(request.quality, merge)
        UuLog.i("download ${request.jobId}: format=$selector merge=$merge cookies=${cookies() != null}")
        ytRequest.addOption("-f", selector)
        if (merge) ytRequest.addOption("--merge-output-format", "mp4")
        ytRequest.addOption("--no-playlist")
        ytRequest.addOption("--newline")
        ytRequest.addOption("--no-mtime")
        // A title is not a filename: "|" and "/" in a YouTube title would either break the
        // write or silently relocate it. yt-dlp is asked for something already tame.
        ytRequest.addOption("--restrict-filenames")
        ytRequest.addOption("-o", File(workDir, "%(title).90s.%(ext)s").absolutePath)
        cookies()?.let { ytRequest.addOption("--cookies", it.absolutePath) }

        try {
            YoutubeDL.execute(ytRequest, processId) { percent, _eta, line ->
                onProgress(
                    Progress(
                        percent = percent.takeIf { it >= 0f }?.coerceIn(0f, 100f)?.div(100f),
                        line = ProgressText.detail(line),
                    )
                )
            }
        } catch (e: YoutubeDL.CanceledException) {
            throw CancelledDownload()
        } catch (e: com.yausername.youtubedl_android.YoutubeDLException) {
            if (isCancelled()) throw CancelledDownload()
            // message is yt-dlp's stderr; when it is blank there is nothing else to read.
            UuLog.e("download ${request.jobId}: yt-dlp failed", e)
            throw DownloadFailure(
                ProgressText.errorMessage(e.message)
                    ?: UuLog.describe(e)
                    ?: "The site refused the download",
                e,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancelledDownload()
        } catch (e: Throwable) {
            UuLog.e("download ${request.jobId}: engine threw", e)
            throw e
        }

        return finishedFileIn(workDir)
    }

    /**
     * Cancel a running download. yt-dlp is a child process, so the only way to stop it
     * promptly is to kill that process; checking a flag between phases is far too late.
     */
    override fun cancel(jobId: String) {
        runCatching { YoutubeDL.destroyProcessById(processIdFor(jobId)) }
    }

    private companion object {
        const val PROBE_PROCESS_ID = "uudl-probe"
    }

    /**
     * Whatever yt-dlp left behind that is actually the video: not a `.part` fragment, not the
     * `.ytdl` journal. The largest remaining file is the merged result.
     */
    private fun finishedFileIn(workDir: File): File {
        val candidate = workDir.listFiles()
            ?.filter { it.isFile }
            ?.filterNot {
                it.name.endsWith(".part") || it.name.endsWith(".ytdl") || it.name.contains(".temp.")
            }
            ?.maxByOrNull { it.length() }
        if (candidate == null || candidate.length() == 0L) {
            throw DownloadFailure("The download finished but produced no file")
        }
        return candidate
    }
}
