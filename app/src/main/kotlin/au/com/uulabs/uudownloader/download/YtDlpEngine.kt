package au.com.uulabs.uudownloader.download

import android.content.Context
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.UuLog
import au.com.uulabs.uudownloader.core.EngineOptions
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.data.CookieJar
import au.com.uulabs.uudownloader.update.AppUpdater
import au.com.uulabs.uudownloader.update.EngineUpdater
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import org.json.JSONObject

/** What the engine update found. */
sealed interface EngineUpdate {
    val version: String

    data class AlreadyCurrent(override val version: String) : EngineUpdate
    data class Updated(override val version: String) : EngineUpdate
}

/**
 * Downloads through yt-dlp on the bundled Python runtime.
 *
 * This is the only honest way to get YouTube, and it happens to be one code path for TikTok,
 * Instagram, Facebook and X: those sites change their private endpoints constantly and yt-dlp
 * tracks them. Anything hand-written against those endpoints would be dead within weeks.
 *
 * There is one instance per process, shared by the screen and the service, because the things it
 * guards are per process too: the unpacked runtime, the file on disk, and whether an update is
 * replacing it.
 *
 * The engine only writes into the job's scratch directory. Handing the finished file to the
 * folder the user chose is [FolderWriter]'s job, because that is where Android's storage rules
 * live.
 */
class YtDlpEngine private constructor(
    private val context: Context,
    private val jar: CookieJar,
) : VideoEngine {

    @Volatile
    private var ready = false

    /** Whether this build carries ffmpeg, and whether it initialised. Both must be true. */
    @Volatile
    private var canMerge = false

    /** True only while the engine file is being replaced, when new runs have to wait. */
    @Volatile
    var updating: Boolean = false
        private set

    private val initLock = Any()

    /** Runs share the engine file. Replacing it needs every one of them to have finished. */
    private val engineLock = ReentrantReadWriteLock()

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
            jar.sweepRunCopies()
            UuLog.i("yt-dlp init: unpacking python")
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
            UuLog.i("playback limits: ${DeviceCodecs.limits?.codecs ?: "unknown"}")
            ready = true
        }
    }

    /**
     * yt-dlp's version, unpacking the runtime first if this is the first call.
     *
     * The sites it knows about change weekly, and showing the date is how a user can tell whether
     * "this site stopped working" is their problem or the engine's.
     */
    fun versionOrInit(): String? = runCatching {
        ensureReady()
        BundledEngine.installedVersion(context) ?: engineLock.read { askBinaryVersion() }
    }.getOrNull()

    private fun askBinaryVersion(): String? = runCatching {
        YoutubeDL.execute(YoutubeDLRequest("").addOption("--version"))
            .out
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.first().isDigit() }
    }.getOrNull()

    /**
     * Replace yt-dlp with the latest stable release.
     *
     * The file is about to run with access to the user's cookies, so it is checked against the
     * checksums yt-dlp publishes before it goes anywhere near the engine directory. Afterwards it
     * has to run and name the right version, or the previous engine goes back. Every network call
     * is bounded, so this cannot hang, and it holds no lock until the file is ready to swap.
     */
    fun updateEngine(): EngineUpdate {
        ensureReady()
        val installed = versionOrInit()
        val latest = AppUpdater.latestEngineVersion().getOrThrow()
            ?: throw IllegalStateException("yt-dlp's release feed named no version")
        if (installed != null && !AppUpdater.isNewer(latest, installed)) {
            return EngineUpdate.AlreadyCurrent(installed)
        }

        val incoming = File(BundledEngine.directory(context), "yt-dlp.new")
        try {
            EngineUpdater.fetchVerified(latest, incoming)
            cancelProbe()
            if (!engineLock.writeLock().tryLock(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("a download is running, so try again when it has finished")
            }
            updating = true
            try {
                BundledEngine.swapIn(context, incoming, latest)
                val running = askBinaryVersion()
                if (running != latest) {
                    BundledEngine.rollBack(context)
                    throw IllegalStateException("the new engine would not start, so the old one was kept")
                }
                BundledEngine.commit(context)
            } finally {
                updating = false
                engineLock.writeLock().unlock()
            }
        } finally {
            incoming.delete()
        }
        UuLog.i("engine updated to $latest")
        return EngineUpdate.Updated(latest)
    }

    /**
     * A probe is a convenience, never a gate: on a slow phone yt-dlp can spend half a minute
     * reading a page before it says anything, and the user should not be kept waiting to press
     * Download. It runs under a process id so it can be killed the moment it stops being
     * useful.
     */
    override fun probe(url: String): SourceInfo? = runCatching {
        ensureReady()
        val output = engineLock.read {
            withRunCookies { cookies ->
                val request = YoutubeDLRequest(url)
                    .addCommands(EngineOptions.probeCommand(cookies?.absolutePath))
                YoutubeDL.execute(request, PROBE_PROCESS_ID).out
            }
        }
        val json = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("{") }
            ?.let { JSONObject(it) }
            ?: return null
        // A playlist link answers with its entries; the first one is what would be saved.
        val source = json.optJSONArray("entries")?.optJSONObject(0) ?: json
        SourceInfo(
            title = source.optString("title").takeIf { it.isNotBlank() && it != "null" },
            maxHeight = maxVideoHeight(source),
            durationSeconds = source.optDouble("duration", 0.0).takeIf { it > 0 }?.toLong(),
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

        val first = tryLadder(request, request.quality, onProgress, isCancelled)
        first.fetched?.let { return it.toResult(request.quality, qualityNote = null) }

        // HQ is the adaptive path — separate video and audio streams — and that is the one
        // YouTube gates when it distrusts an address. Normal asks for a single already-muxed
        // file, which it still serves. Saving 720p and saying so beats handing back an error
        // for a video the user can see in front of them.
        if (request.quality == Quality.HIGH && !EngineOptions.isFinalRefusal(first.failure?.message)) {
            UuLog.w("download ${request.jobId}: HQ refused; saving Normal instead")
            onProgress(Progress(percent = null, line = "HQ refused — saving Normal"))
            val fallback = tryLadder(request, Quality.NORMAL, onProgress, isCancelled)
            fallback.fetched?.let {
                return it.toResult(Quality.NORMAL, qualityNote = "HQ was refused, so this is the best single file")
            }
            throw fallback.failure ?: DownloadFailure(REFUSED)
        }
        throw first.failure ?: DownloadFailure(REFUSED)
    }

    private fun Fetched.toResult(quality: Quality, qualityNote: String?): DownloadResult {
        val partial = playlistCount?.let { "This link holds $it videos; this is the first" }
        return DownloadResult(
            file = file,
            quality = quality,
            note = listOfNotNull(qualityNote, partial).joinToString(" · ").ifEmpty { null },
            title = title,
        )
    }

    /** Every client for one quality. */
    private fun tryLadder(
        request: DownloadRequest,
        quality: Quality,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): LadderResult {
        val ladder = EngineOptions.clientLadder(request.url)
        var failure: DownloadFailure? = null
        for ((index, client) in ladder.withIndex()) {
            try {
                return LadderResult(fetched = attempt(request, quality, client, onProgress, isCancelled), failure = null)
            } catch (e: DownloadFailure) {
                failure = e
                if (!EngineOptions.isBotCheck(e.message) || index == ladder.lastIndex) break
                val next = ladder[index + 1]
                UuLog.w("download ${request.jobId}: ${client ?: "default"} client refused; retrying as ${next ?: "default"}")
                onProgress(Progress(percent = null, line = "Trying another route"))
            }
        }
        return LadderResult(fetched = null, failure = failure)
    }

    private fun attempt(
        request: DownloadRequest,
        quality: Quality,
        client: String?,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): Fetched {
        // Killing a process only stops a process that exists. A cancel that lands during setup or
        // between two attempts is only ever seen here.
        if (isCancelled()) throw CancelledDownload()

        // Nothing from an earlier attempt survives into this one: a video-only stream left behind
        // by a refused HQ run must not be mistaken for the result of the next run.
        val workDir = request.workDir
        workDir.listFiles()?.forEach { it.deleteRecursively() }
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw DownloadFailure("Could not prepare a scratch folder")
        }
        val processId = processIdFor(request.jobId)

        val merge = canMerge
        UuLog.i("download ${request.jobId}: quality=$quality merge=$merge client=${client ?: "default"}")

        val stdout = try {
            engineLock.read {
                withRunCookies { cookies ->
                    val command = YoutubeDLRequest(request.url).addCommands(
                        EngineOptions.downloadCommand(
                            quality, merge, client, workDir.absolutePath, cookies?.absolutePath, DeviceCodecs.limits,
                        )
                    )
                    YoutubeDL.execute(command, processId) { percent, _, line ->
                        onProgress(
                            Progress(
                                percent = percent.takeIf { it >= 0f }?.coerceIn(0f, 100f)?.div(100f),
                                line = ProgressText.detail(line),
                            )
                        )
                    }.out
                }
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
                detail = e.message,
            )
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancelledDownload()
        } catch (e: Throwable) {
            UuLog.e("download ${request.jobId}: engine threw", e)
            throw e
        }

        if (isCancelled()) throw CancelledDownload()
        return readResult(workDir, stdout)
    }

    /**
     * The file yt-dlp itself says it finished, never a guess from what is in the folder. It has to
     * be inside the job's folder and have something in it, or the job did not produce a video.
     */
    private fun readResult(workDir: File, stdout: String?): Fetched {
        val printed = EngineOptions.printedValue(sidecar(workDir, EngineOptions.PATH_FILE))
        val file = printed?.let(::File)?.takeIf {
            it.isFile && it.length() > 0 && it.canonicalFile.parentFile == workDir.canonicalFile
        }
        if (file == null) {
            if (EngineOptions.wasFilteredOut(stdout)) {
                throw DownloadFailure("This is a live stream. It can be saved once it has ended.")
            }
            throw DownloadFailure("The download finished but produced no file")
        }
        return Fetched(
            file = file,
            title = EngineOptions.printedValue(sidecar(workDir, EngineOptions.TITLE_FILE)),
            playlistCount = EngineOptions.playlistCount(sidecar(workDir, EngineOptions.COUNT_FILE)),
        )
    }

    private fun sidecar(workDir: File, name: String): String? =
        runCatching { File(workDir, name).readText() }.getOrNull()

    /** yt-dlp writes back to its cookie file, so each run gets a copy it can scribble on. */
    private inline fun <T> withRunCookies(block: (File?) -> T): T {
        val copy = jar.runCopy()
        try {
            return block(copy)
        } finally {
            copy?.delete()
        }
    }

    /**
     * Cancel a running download. yt-dlp is a child process, so the only way to stop it
     * promptly is to kill that process; checking a flag between phases is far too late.
     */
    override fun cancel(jobId: String) {
        runCatching { YoutubeDL.destroyProcessById(processIdFor(jobId)) }
    }

    private class Fetched(val file: File, val title: String?, val playlistCount: Int?)

    private class LadderResult(val fetched: Fetched?, val failure: DownloadFailure?)

    companion object {
        private const val PROBE_PROCESS_ID = "uudl-probe"
        private const val REFUSED = "The site refused the download"

        @Volatile
        private var instance: YtDlpEngine? = null

        fun shared(context: Context): YtDlpEngine =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext.let { app ->
                    YtDlpEngine(app, CookieJar(app)).also { instance = it }
                }
            }
    }
}
