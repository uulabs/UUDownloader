package au.com.uulabs.uudownloader.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import au.com.uulabs.uudownloader.core.DirectLinks
import au.com.uulabs.uudownloader.core.FileNames
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.data.CookieJar
import au.com.uulabs.uudownloader.data.Destination
import au.com.uulabs.uudownloader.data.UuPrefs
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the downloads and owns the progress notification.
 *
 * The queue is not a queue at all: [DownloadHub] is the single list of jobs and this service
 * simply keeps picking the first one still in [DownloadState.QUEUED] until there are none left.
 * That means a job enqueued while a download is running is picked up without any hand-off, and
 * a share-sheet launch and an in-app launch behave identically.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())
    private val pumpLock = Any()

    private var pumping = false
    private var prefs: UuPrefs? = null
    private var ytDlp: YtDlpEngine? = null
    private val direct = DirectHttpEngine()

    private var lastNotifyAt = 0L
    private var lastNotifiedPercent = -1

    override fun onCreate() {
        super.onCreate()
        prefs = UuPrefs(this)
        DownloadNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(::cancelJob)
            else -> publishForeground()
        }
        pump()
        // The hub is in-memory, so there is nothing meaningful to restore after a restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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
                while (true) {
                    val next = DownloadHub.items.value
                        .firstOrNull { it.state == DownloadState.QUEUED }
                        ?: break
                    runJob(next)
                }
            } finally {
                synchronized(pumpLock) { pumping = false }
                if (DownloadHub.items.value.none { it.state.isRunning }) shutDown()
            }
        }
    }

    private fun runJob(item: DownloadItem) {
        val workDir = File(workRoot(), "job-${item.id}")
        workDir.deleteRecursively()
        mark(item.id) { it.copy(state = DownloadState.PREPARING, detail = "Getting ready", progress = null) }
        publishForeground(force = true)

        try {
            val destination = prefs?.destination ?: Destination.Downloads
            val file = engineFor(item.url).download(
                request = DownloadRequest(item.id, item.url, item.quality, workDir),
                onProgress = { progress -> onProgress(item.id, progress) },
                isCancelled = { cancelled.contains(item.id) },
            )

            mark(item.id) { it.copy(state = DownloadState.SAVING, detail = "Saving to your folder", progress = 1f) }
            publishForeground(force = true)

            val extension = file.extension.ifEmpty { "mp4" }
            val title = DownloadHub.items.value.firstOrNull { it.id == item.id }?.title ?: file.nameWithoutExtension
            val preferredName = FileNames.fromTitle(title, extension = extension)
            val saved = when (destination) {
                Destination.Downloads -> MediaStoreWriter.save(this, file, preferredName)
                is Destination.Custom -> FolderWriter.save(this, destination.uri, file, preferredName)
            }
            DownloadHub.finish(item.id, DownloadState.DONE) {
                it.copy(savedName = saved.name, savedUri = saved.uri.toString(), progress = 1f, detail = null)
            }
        } catch (e: CancelledDownload) {
            DownloadHub.finish(item.id, DownloadState.CANCELLED) { it.copy(detail = null) }
        } catch (e: Throwable) {
            val message = ProgressText.hint(e.message).ifBlank { "Something went wrong" }
            DownloadHub.finish(item.id, DownloadState.FAILED) {
                it.copy(detail = null, error = message.take(180))
            }
        } finally {
            workDir.deleteRecursively()
            cancelled.remove(item.id)
            showResult(item.id)
        }
    }

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
        publishForeground()
    }

    private fun mark(id: String, transform: (DownloadItem) -> DownloadItem) {
        DownloadHub.update(id, transform)
    }

    private fun publishForeground(force: Boolean = false) {
        val item = DownloadHub.active() ?: return
        val percent = item.progress?.let { (it * 100).toInt() }
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyAt < 700 && percent == lastNotifiedPercent) return
        lastNotifyAt = now
        lastNotifiedPercent = percent ?: -1
        val notification = DownloadNotifications.ongoing(this, item)
        if (force) {
            startForeground(DownloadNotifications.ONGOING_ID, notification)
        } else {
            DownloadNotifications.notify(this, DownloadNotifications.ONGOING_ID, notification)
        }
    }

    private fun showResult(id: String) {
        val finished = DownloadHub.items.value.firstOrNull { it.id == id } ?: return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        DownloadNotifications.notify(
            this,
            DownloadNotifications.resultId(id),
            DownloadNotifications.finished(this, finished),
        )
    }

    private fun shutDown() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cancelJob(id: String) {
        val item = DownloadHub.items.value.firstOrNull { it.id == id } ?: return
        if (item.state == DownloadState.QUEUED) {
            DownloadHub.finish(id, DownloadState.CANCELLED)
            if (DownloadHub.active() == null) shutDown()
            return
        }
        cancelled.add(id)
        engineFor(item.url).cancel(id)
    }

    private fun engineFor(url: String): VideoEngine =
        if (DirectLinks.looksDirect(url)) direct else ytDlpEngine()

    private fun ytDlpEngine(): YtDlpEngine =
        ytDlp ?: YtDlpEngine(applicationContext) { CookieJar(applicationContext).path() }
            .also { ytDlp = it }

    /**
     * Larger than the internal cache: a 10-minute 1080p video is a few hundred megabytes, and
     * filling app-private internal storage is a failure the user cannot easily repair.
     */
    private fun workRoot(): File {
        val root = getExternalFilesDir("jobs") ?: File(filesDir, "jobs")
        if (!root.exists()) root.mkdirs()
        return root
    }

    companion object {
        const val ACTION_START = "au.com.uulabs.uudownloader.action.START"
        const val ACTION_CANCEL = "au.com.uulabs.uudownloader.action.CANCEL"
        const val EXTRA_ID = "id"

        /** Bring the service up. The caller has already put work in [DownloadHub]. */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun cancel(context: Context, id: String) {
            val intent = Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_ID, id)
            runCatching { context.startService(intent) }
        }

        /** A stand-in used only when the notification must appear before the first job exists. */
        internal fun placeholder(): DownloadItem = DownloadItem(
            id = "placeholder",
            url = "",
            platform = Platform.OTHER,
            quality = Quality.NORMAL,
            state = DownloadState.PREPARING,
        )
    }
}
