package au.com.uulabs.uudownloader.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.com.uulabs.uudownloader.BuildConfig
import au.com.uulabs.uudownloader.UuLog
import au.com.uulabs.uudownloader.core.DirectLinks
import au.com.uulabs.uudownloader.core.FileNames
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.data.ByteSize
import au.com.uulabs.uudownloader.data.Destination
import au.com.uulabs.uudownloader.data.StorageLimit
import au.com.uulabs.uudownloader.data.UuPrefs
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the downloads and owns the progress notification.
 *
 * The queue is not a queue at all: [DownloadHub] is the single list of jobs and this service
 * simply keeps picking the first one still in [DownloadState.QUEUED] until there are none left.
 * That means a job enqueued while a download is running is picked up without any hand-off, and
 * a share-sheet launch and an in-app launch behave identically.
 *
 * The service enters the foreground once, when it is started, and stays there until the queue is
 * empty. Android will not let an app in the background re-enter the foreground, and sharing a
 * link from another app is exactly when this app is in the background, so dropping out between
 * two jobs would end the queue.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())
    private val pumpLock = Any()

    private var pumping = false
    private var swept = false
    private var prefs: UuPrefs? = null
    private val direct = DirectHttpEngine()

    @Volatile
    private var foregroundStarted = false

    @Volatile
    private var lastStartId = 0

    /** The engine holding the current job, which is the one that can stop it. */
    @Volatile
    private var activeEngine: VideoEngine? = null

    /** Why the job was stopped, when it was not the user who stopped it. */
    @Volatile
    private var stopReason: String? = null

    @Volatile
    private var lastNotifyAt = 0L

    @Volatile
    private var lastNotifiedPercent = -1

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        UuLog.i("service created")
        prefs = UuPrefs(this)
        DownloadNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(::cancelJob)
            else -> enterForeground()
        }
        pump()
        // The hub is in-memory, so there is nothing meaningful to restore after a restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15 allows this kind of service six hours a day. Past that it calls here, and a
     * service still running a few seconds later is treated as a crash, so this has to end the
     * job and leave at once.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        UuLog.w("service: Android's time limit for background downloads was reached")
        val reason = "Android's daily limit for background downloads was reached. Open the app and try again."
        stopReason = reason
        DownloadHub.items.value
            .filter { it.state == DownloadState.QUEUED }
            .forEach { failQueued(it.id, reason) }
        DownloadHub.active()?.let {
            cancelled.add(it.id)
            activeEngine?.cancel(it.id)
        }
        leaveForeground()
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun pump() {
        synchronized(pumpLock) {
            if (pumping) return
            pumping = true
        }
        scope.launch {
            try {
                holdWakeLock()
                sweepAbandonedJobs()
                while (true) {
                    val next = DownloadHub.items.value
                        .firstOrNull { it.state == DownloadState.QUEUED }
                        ?: break
                    runJob(next)
                }
            } finally {
                releaseWakeLock()
                synchronized(pumpLock) { pumping = false }
                if (DownloadHub.items.value.none { it.state.isRunning }) shutDown()
            }
        }
    }

    private fun runJob(item: DownloadItem) {
        val workDir = File(workRoot(), "job-${item.id}")
        workDir.deleteRecursively()
        val waitingForUpdate = YtDlpEngine.shared(this).updating
        mark(item.id) {
            it.copy(
                state = DownloadState.PREPARING,
                detail = if (waitingForUpdate) "Waiting for an engine update" else "Getting ready",
                progress = null,
            )
        }
        publish(force = true)

        UuLog.i("job ${item.id}: start quality=${item.quality}")
        if (BuildConfig.DEBUG) UuLog.i("job ${item.id}: url=${item.url}")

        val limit = prefs?.storageLimit ?: StorageLimit.default()
        val limitBytes = StorageLimit.limitBytes(this, limit)
        stopReason = null
        var watchdog: Job? = null

        try {
            // What the user has since deleted elsewhere no longer takes up any room.
            DownloadHub.reconcile { SavedFiles.exists(this@DownloadService, it.uri) }
            val usedBefore = DownloadHub.retainedBytes()
            // Refuse before spending the user's data on something that cannot be kept.
            if (limitBytes > 0 && usedBefore >= limitBytes) {
                throw DownloadFailure(
                    "Storage limit reached — ${ByteSize.human(usedBefore)} of " +
                        "${ByteSize.human(limitBytes)}. Raise it in Settings or delete older downloads."
                )
            }
            watchdog = scope.launch { watch(item, workDir, usedBefore, limitBytes) }
            val destination = prefs?.destination ?: Destination.Downloads
            val result = fetch(item, workDir)
            val file = result.file

            // Cancelling after the process has exited has nothing left to kill.
            if (cancelled.contains(item.id)) throw CancelledDownload()
            if (limitBytes > 0 && usedBefore + file.length() > limitBytes) {
                throw DownloadFailure(overLimit(limitBytes))
            }

            mark(item.id) {
                it.copy(
                    state = DownloadState.SAVING,
                    detail = result.note ?: "Saving to your folder",
                    progress = 1f,
                    quality = result.quality,
                    title = it.title ?: result.title,
                )
            }
            publish(force = true)

            val extension = file.extension.ifEmpty { "mp4" }
            val title = DownloadHub.items.value.firstOrNull { it.id == item.id }?.title ?: file.nameWithoutExtension
            val preferredName = FileNames.fromTitle(title, extension = extension)
            val saved = when (destination) {
                Destination.Downloads -> MediaStoreWriter.save(this, file, preferredName)
                is Destination.Custom -> FolderWriter.save(this, destination.uri, file, preferredName)
            }
            DownloadHub.finish(item.id, DownloadState.DONE) {
                it.copy(
                    savedName = saved.name,
                    savedUri = saved.uri.toString(),
                    progress = 1f,
                    detail = null,
                    quality = result.quality,
                    note = result.note,
                    sizeBytes = file.length(),
                )
            }
            UuLog.i("job ${item.id}: saved ${file.length()} bytes")
        } catch (e: CancelledDownload) {
            val reason = stopReason
            if (reason != null) {
                UuLog.w("job ${item.id}: stopped by the service")
                DownloadHub.finish(item.id, DownloadState.FAILED) {
                    it.copy(detail = null, error = reason)
                }
            } else {
                UuLog.i("job ${item.id}: cancelled")
                DownloadHub.finish(item.id, DownloadState.CANCELLED) { it.copy(detail = null) }
            }
        } catch (e: Throwable) {
            UuLog.e("job ${item.id}: failed", e)
            // A message-less throwable used to render as the word "wrong"; the class name is
            // at least a fingerprint that can be searched for.
            val message = ProgressText.hint(e.message).ifBlank { UuLog.describe(e) }
            val detail = (e as? DownloadFailure)?.detail ?: e.message ?: UuLog.describe(e)
            DownloadHub.finish(item.id, DownloadState.FAILED) {
                it.copy(
                    detail = null,
                    error = message.take(MAX_ERROR_CHARS),
                    errorDetail = detail.takeIf { text -> text != message },
                )
            }
        } finally {
            watchdog?.cancel()
            activeEngine = null
            workDir.deleteRecursively()
            cancelled.remove(item.id)
            stopReason = null
            showResult(item.id)
        }
    }

    /** A "direct" link that answers with a page still has a video behind it, so yt-dlp gets a go. */
    private fun fetch(item: DownloadItem, workDir: File): DownloadResult {
        val request = DownloadRequest(item.id, item.url, item.quality, workDir)
        val onProgress = { progress: Progress -> onProgress(item.id, progress) }
        val isCancelled = { cancelled.contains(item.id) }
        val engine: VideoEngine = if (DirectLinks.looksDirect(item.url)) direct else YtDlpEngine.shared(this)
        activeEngine = engine
        return try {
            engine.download(request, onProgress, isCancelled)
        } catch (e: NotADirectFile) {
            UuLog.i("job ${item.id}: direct link answered with a page; handing it to yt-dlp")
            val fallback = YtDlpEngine.shared(this)
            activeEngine = fallback
            fallback.download(request, onProgress, isCancelled)
        }
    }

    /**
     * Keeps checking while the job runs, independently of it.
     *
     * Progress lines cannot be relied on for this: a stream downloaded through ffmpeg prints
     * none, and a cancel can land before the process it should kill exists. So a storage breach
     * and a pending cancel are both acted on here, on their own clock.
     */
    private suspend fun watch(item: DownloadItem, workDir: File, usedBefore: Long, limitBytes: Long) {
        while (currentCoroutineContext().isActive) {
            delay(WATCH_INTERVAL_MS)
            if (cancelled.contains(item.id)) {
                activeEngine?.cancel(item.id)
                continue
            }
            if (limitBytes <= 0) continue
            // A merge writes a third file while its two sources still exist. Counting it would
            // stop a job that fits, so what is measured is the streams themselves.
            val inFlight = workDir.listFiles()
                ?.filterNot { it.name.contains(".temp.") }
                ?.sumOf { it.length() }
                ?: 0L
            if (usedBefore + inFlight > limitBytes) {
                UuLog.w("job ${item.id}: over the storage limit, cancelling")
                stopReason = overLimit(limitBytes)
                cancelled.add(item.id)
                activeEngine?.cancel(item.id)
            }
        }
    }

    private fun overLimit(limitBytes: Long): String =
        "Storage limit reached — stopped at ${ByteSize.human(limitBytes)}. " +
            "Raise the limit in Settings or delete older downloads."

    private fun onProgress(id: String, progress: Progress) {
        mark(id) { current ->
            current.copy(
                state = when {
                    current.state == DownloadState.PREPARING -> DownloadState.DOWNLOADING
                    else -> current.state
                },
                progress = progress.percent ?: current.progress,
                detail = progress.line ?: current.detail,
            )
        }
        publish()
    }

    private fun mark(id: String, transform: (DownloadItem) -> DownloadItem) {
        DownloadHub.update(id, transform)
    }

    /**
     * The one call Android requires within seconds of being started, so it happens here on the
     * main thread, before any work, whether or not a job is still waiting by then.
     */
    private fun enterForeground() {
        if (foregroundStarted) return
        val notification = DownloadNotifications.ongoing(this, DownloadHub.active() ?: placeholder())
        try {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            foregroundStarted = true
        } catch (e: RuntimeException) {
            // Android refuses when the daily allowance is spent or the app is not allowed to
            // start from the background. There is no way to run without it, so the jobs say so.
            UuLog.w("service: could not enter the foreground", e)
            val reason = "Android wouldn't let the download run in the background. Open the app and try again."
            DownloadHub.items.value
                .filter { it.state == DownloadState.QUEUED }
                .forEach { failQueued(it.id, reason) }
        }
    }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
    }

    private fun failQueued(id: String, reason: String) {
        DownloadHub.finish(id, DownloadState.FAILED) { it.copy(detail = null, error = reason) }
    }

    private fun publish(force: Boolean = false) {
        val item = DownloadHub.active() ?: return
        val percent = item.progress?.let { (it * 100).toInt() }
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < 700 && percent == lastNotifiedPercent) return
        lastNotifyAt = now
        lastNotifiedPercent = percent ?: -1
        if (foregroundStarted) {
            DownloadNotifications.notify(this, DownloadNotifications.ONGOING_ID, DownloadNotifications.ongoing(this, item))
        }
    }

    private fun showResult(id: String) {
        val finished = DownloadHub.items.value.firstOrNull { it.id == id } ?: return
        DownloadNotifications.notify(
            this,
            DownloadNotifications.resultId(id),
            DownloadNotifications.finished(this, finished),
        )
    }

    /** Stays up if a newer start arrived while the queue was emptying; that start has work. */
    private fun shutDown() {
        if (stopSelfResult(lastStartId)) leaveForeground()
    }

    private fun cancelJob(id: String) {
        val item = DownloadHub.items.value.firstOrNull { it.id == id } ?: return
        if (item.state == DownloadState.QUEUED) {
            DownloadHub.finish(id, DownloadState.CANCELLED)
            if (DownloadHub.active() == null) shutDown()
            return
        }
        cancelled.add(id)
        activeEngine?.cancel(id)
    }

    /** The screen on a phone stays off during a long download, and the CPU must not. */
    private fun holdWakeLock() {
        val power = getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uudownloader:download").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_LIMIT_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * Job folders left by a process that died mid-download. Nothing else would ever clear them,
     * because the next job gets a new id. Only this service runs jobs, so at its first pump the
     * only folders that can be in use are those of jobs in the list.
     */
    private fun sweepAbandonedJobs() {
        if (swept) return
        swept = true
        val running = DownloadHub.items.value.filter { it.state.isRunning }.map { "job-${it.id}" }.toSet()
        workRoot().listFiles()
            ?.filter { it.name.startsWith("job-") && it.name !in running }
            ?.forEach { it.deleteRecursively() }
    }

    /**
     * Larger than the internal cache: a 10-minute 1080p video is a few hundred megabytes, and
     * filling app-private internal storage is a failure the user cannot easily repair.
     */
    private fun workRoot(): File {
        val root = getExternalFilesDir("jobs") ?: File(filesDir, "jobs")
        if (!root.exists()) root.mkdirs()
        return root
    }

    /** What the notification shows when the service starts with no job left to describe. */
    private fun placeholder(): DownloadItem = DownloadItem(
        id = "placeholder",
        url = "",
        platform = Platform.OTHER,
        quality = Quality.NORMAL,
        state = DownloadState.PREPARING,
    )

    companion object {
        const val ACTION_START = "au.com.uulabs.uudownloader.action.START"
        const val ACTION_CANCEL = "au.com.uulabs.uudownloader.action.CANCEL"
        const val EXTRA_ID = "id"

        private const val WATCH_INTERVAL_MS = 1_500L
        private const val WAKE_LOCK_LIMIT_MS = 6 * 60 * 60 * 1000L
        private const val MAX_ERROR_CHARS = 300

        /** Bring the service up. The caller has already put work in [DownloadHub]. False if Android refused. */
        fun start(context: Context): Boolean {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            return runCatching { ContextCompat.startForegroundService(context, intent) }.isSuccess
        }

        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            runCatching { context.startService(intent) }
        }
    }
}
