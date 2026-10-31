package au.com.uulabs.uudownloader.download

import android.content.Context
import au.com.uulabs.uudownloader.core.Quality
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

    /**
     * True while a self-update is running.
     *
     * The library takes a monitor for both `init` and `updateYoutubeDL`, and the update's
     * metadata fetch has no read timeout. A download that starts during an update therefore
     * waits — and the only kind thing to do is say so on the progress line instead of sitting
     * on "Getting ready" forever.
     */
    @Volatile
    var updating: Boolean = false
        private set

    /** The refusal from the last attempt, kept so the caller can report it if all of them fail. */
    @Volatile
    private var lastFailure: DownloadFailure =
        DownloadFailure("The site refused the download")

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
            // Before the library looks, put our engine where it will be found; it only
            // unpacks its own copy when the file is missing.
            BundledEngine.ensureInstalled(context)
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

    private fun webUpdateMark(context: android.content.Context, version: String?) {
        version?.let { BundledEngine.recordInstalled(context, it) }
    }

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
        updating = true
        try {
            ensureReady()
            YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
            val now = YoutubeDL.version(context)
            webUpdateMark(context, now)
            UuLog.i("engine updated to $now")
            return now
        } finally {
            updating = false
        }
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
    ): DownloadResult {
        ensureReady()

        val tried = tryLadder(request, request.quality, onProgress, isCancelled)
        if (tried != null) return DownloadResult(tried, request.quality)

        // HQ is the adaptive path — separate video and audio streams — and that is the one
        // YouTube gates when it distrusts an address. Normal asks for a single already-muxed
        // file, which it still serves. Saving 720p and saying so beats handing back an error
        // for a video the user can see in front of them.
        if (request.quality == Quality.HIGH) {
            UuLog.w("download ${request.jobId}: HQ refused on every client; saving Normal instead")
            onProgress(Progress(percent = null, line = "HQ refused — saving Normal"))
            val fallback = tryLadder(request, Quality.NORMAL, onProgress, isCancelled)
            if (fallback != null) {
                return DownloadResult(
                    file = fallback,
                    quality = Quality.NORMAL,
                    note = "HQ was refused, so this is the best single file",
                )
            }
        }

        throw lastFailure
    }

    /** Every client for one quality; null when all of them were refused. */
    private fun tryLadder(
        request: DownloadRequest,
        quality: Quality,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): File? {
        val ladder = clientLadder(request.url)
        for ((index, client) in ladder.withIndex()) {
            try {
                return attempt(request, quality, client, onProgress, isCancelled)
            } catch (e: CancelledDownload) {
                throw e
            } catch (e: DownloadFailure) {
                lastFailure = e
                if (!isBotCheck(e) || index == ladder.lastIndex) return null
                val next = ladder[index + 1]
                UuLog.w(
                    "download ${request.jobId}: ${client ?: "default"} client refused " +
                        "(${e.message?.take(60)}); retrying as ${next ?: "default"}"
                )
                request.workDir.listFiles()?.forEach { it.deleteRecursively() }
                onProgress(Progress(percent = null, line = "Trying another route"))
            }
        }
        return null
    }

    /**
     * Which YouTube clients to try, in order.
     *
     * YouTube refuses a request it thinks is automated with "Sign in to confirm you're not a
     * bot" — pinned to the network, not to the video or the app. The same video will be handed
     * out to a different client identifier from the same address, so the honest response is to
     * ask again the way a television or a phone would, before telling the user they need an
     * account. Non-YouTube URLs get exactly one attempt.
     */
    private fun clientLadder(url: String): List<String?> =
        if (isYouTube(url)) listOf(null, "tv", "web_safari", "ios", "android_vr") else listOf(null)

    private fun isYouTube(url: String): Boolean {
        val host = url.substringAfter("://", "").substringBefore('/').lowercase()
        return host.endsWith("youtube.com") || host.endsWith("youtu.be")
    }

    /**
     * Whether the refusal is YouTube being suspicious rather than the video being gone.
     *
     * YouTube says the same thing in a dozen ways depending on the client that asked:
     * "Sign in to confirm you're not a bot", "The page needs to be reloaded", "This content
     * requires authentication", a bare 403. They all mean the request was not trusted, and all
     * of them are worth answering with a different client — so the matcher has to know every
     * phrasing, not the one that happened to appear first.
     */
    private fun isBotCheck(error: DownloadFailure): Boolean {
        val text = error.message.orEmpty().lowercase()
        return BOT_CHECK_PHRASES.any { text.contains(it) }
    }

    private fun attempt(
        request: DownloadRequest,
        quality: Quality,
        client: String?,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        val workDir = request.workDir
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw DownloadFailure("Could not prepare a scratch folder")
        }
        val processId = processIdFor(request.jobId)

        val merge = canMerge
        val selector = QualityChoice.selector(quality, merge)
        UuLog.i(
            "download ${request.jobId}: format=$selector merge=$merge " +
                "client=${client ?: "default"} cookies=${cookies() != null}"
        )

        val ytRequest = YoutubeDLRequest(request.url)
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
        client?.let { ytRequest.addOption("--extractor-args", "youtube:player_client=$it") }

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
            UuLog.e("download ${request.jobId}: yt-dlp failed (client=${client ?: "default"})", e)
            throw DownloadFailure(
                ProgressText.errorMessage(e.message) ?: UuLog.describe(e),
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

        val BOT_CHECK_PHRASES = listOf(
            "confirm you're not a bot",
            "confirm you’re not a bot",
            "not a bot",
            "sign in to confirm",
            "page needs to be reloaded",
            "requires authentication",
            "requires sign-in",
            "login required",
            "sign in to continue",
            "forbidden",
            "403",
            "unable to extract",
            "no video formats found",
        )
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
