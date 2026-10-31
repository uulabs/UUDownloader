package au.com.uulabs.uudownloader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import au.com.uulabs.uudownloader.core.QualityChoice
import au.com.uulabs.uudownloader.core.SocialLink
import au.com.uulabs.uudownloader.core.SocialLinks
import au.com.uulabs.uudownloader.data.CookieJar
import au.com.uulabs.uudownloader.data.Destination
import au.com.uulabs.uudownloader.data.StorageLimit
import au.com.uulabs.uudownloader.data.UuPrefs
import au.com.uulabs.uudownloader.download.DownloadHub
import au.com.uulabs.uudownloader.download.DownloadItem
import au.com.uulabs.uudownloader.download.DownloadService
import au.com.uulabs.uudownloader.download.SavedFileRemover
import au.com.uulabs.uudownloader.update.AppUpdater
import au.com.uulabs.uudownloader.update.ReleaseInfo
import au.com.uulabs.uudownloader.download.SourceInfo
import au.com.uulabs.uudownloader.download.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Something newer exists, and the user should be told once. */
sealed interface UpdatePrompt {
    data class App(val info: ReleaseInfo) : UpdatePrompt
    data class Engine(val version: String) : UpdatePrompt
}

/** What the updates row is doing right now. */
enum class UpdateBusy { NONE, CHECKING, DOWNLOADING }

/** What happened to a link that arrived from the share sheet. */
enum class ShareOutcome {
    /** Nothing recognisable in the intent. */
    NONE,

    /** The link is in the field, but the user still has to choose a folder or press Download. */
    PREFILLED,

    /** The download has started and the user is free to leave. */
    STARTED,
}

class UuViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UuPrefs(app)
    private val jar = CookieJar(app)
    private val ytDlp by lazy { YtDlpEngine(app) { jar.path() } }

    val items: StateFlow<List<au.com.uulabs.uudownloader.download.DownloadItem>> = DownloadHub.items

    var urlText by mutableStateOf("")
        private set

    var link by mutableStateOf<SocialLink?>(null)
        private set

    var sourceInfo by mutableStateOf<SourceInfo?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var quality by mutableStateOf(prefs.defaultQuality)
        private set

    var destination by mutableStateOf(prefs.destination)
        private set

    var notice by mutableStateOf<String?>(null)
        private set

    var cookiesAdded by mutableStateOf(jar.exists)
        private set

    var cookieCount by mutableStateOf(if (jar.exists) jar.cookieCount else 0)
        private set

    var engineVersion by mutableStateOf<String?>(null)
        private set

    var appUpdate by mutableStateOf<ReleaseInfo?>(null)
        private set

    var updateBusy by mutableStateOf(UpdateBusy.NONE)
        private set

    var updateProgress by mutableStateOf<Float?>(null)
        private set

    /** One line of plain feedback for the updates row. */
    var updateNote by mutableStateOf<String?>(null)
        private set

    /** Set when a launch check finds something newer than what is installed. */
    var updatePrompt by mutableStateOf<UpdatePrompt?>(null)
        private set

    var updatingEngine by mutableStateOf(false)
        private set

    private var probeJob: Job? = null

    private companion object {
        /** Long enough for a healthy network, short enough not to feel like a hang. */
        const val PROBE_BUDGET_MS = 45_000L

        /** The update's own network calls have no read timeout; the UI must not rely on them. */
        const val UPDATE_TIMEOUT_MS = 90_000L

        /** Twice a day is enough to be current without leaning on GitHub's API. */
        const val UPDATE_INTERVAL_MS = 12 * 60 * 60 * 1000L
    }

    fun onUrlChanged(value: String) {
        urlText = value
        val parsed = SocialLinks.parse(value)
        if (parsed?.url != link?.url) {
            link = parsed
            sourceInfo = null
            notice = null
            parsed?.let(::scheduleProbe)
        }
    }

    fun clearUrl() {
        probeJob?.cancel()
        urlText = ""
        link = null
        sourceInfo = null
        checking = false
    }

    fun chooseDestination(value: Destination) {
        prefs.destination = value
        destination = value
        notice = null
    }

    fun useDefaultDestination() = chooseDestination(Destination.Downloads)

    fun selectQuality(value: Quality) {
        quality = value
        prefs.defaultQuality = value
    }

    fun dismissNotice() {
        notice = null
    }

    /**
     * Ask GitHub, at most twice a day, whether the app or its engine has moved on.
     *
     * Never while a download is running: the engine update takes the wrapper's lock, and a
     * prompt that arrives mid-download is a prompt nobody can act on.
     */
    fun checkForUpdatesOnLaunch() {
        if (DownloadHub.items.value.any { it.state.isRunning }) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < UPDATE_INTERVAL_MS) return
        prefs.lastUpdateCheck = now

        viewModelScope.launch {
            val appResult = withContext(Dispatchers.IO) {
                AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
            }
            val info = appResult.getOrNull()
            if (info != null && info.version != prefs.dismissedAppVersion) {
                appUpdate = info
                updatePrompt = UpdatePrompt.App(info)
                return@launch
            }

            val installedEngine = engineVersion ?: withContext(Dispatchers.IO) { ytDlp.versionOrInit() }
            engineVersion = installedEngine ?: engineVersion
            if (installedEngine == null) return@launch

            val latest = withContext(Dispatchers.IO) {
                AppUpdater.latestEngineVersion().getOrNull()
            } ?: return@launch
            if (AppUpdater.isNewer(latest, installedEngine) && latest != prefs.dismissedEngineVersion) {
                updatePrompt = UpdatePrompt.Engine(latest)
            }
        }
    }

    fun dismissUpdatePrompt() {
        when (val prompt = updatePrompt) {
            is UpdatePrompt.App -> prefs.dismissedAppVersion = prompt.info.version
            is UpdatePrompt.Engine -> prefs.dismissedEngineVersion = prompt.version
            null -> return
        }
        updatePrompt = null
    }

    /** Act on whatever the prompt offered; the prompt closes either way. */
    fun acceptUpdatePrompt(onMessage: (String) -> Unit) {
        when (val prompt = updatePrompt) {
            is UpdatePrompt.App -> {
                updatePrompt = null
                installAppUpdate()
            }
            is UpdatePrompt.Engine -> {
                updatePrompt = null
                updateEngine(onMessage)
            }
            null -> Unit
        }
    }

    fun checkAppUpdate() {
        if (updateBusy != UpdateBusy.NONE) return
        updateBusy = UpdateBusy.CHECKING
        updateNote = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                AppUpdater.checkForUpdate(BuildConfig.VERSION_NAME)
            }
            updateBusy = UpdateBusy.NONE
            result.fold(
                onSuccess = { info ->
                    appUpdate = info
                    if (info == null) updateNote = "Up to date"
                },
                onFailure = { updateNote = it.message ?: "Couldn't reach GitHub" },
            )
        }
    }

    fun installAppUpdate() {
        val info = appUpdate ?: return
        if (updateBusy != UpdateBusy.NONE) return

        // Asking Android for the permission first is cheaper than downloading 60 MB to find
        // out that the installer will not open.
        if (!AppUpdater.canInstall(getApplication())) {
            updateNote = "Allow UU Downloader to install apps, then press Install again"
            AppUpdater.openInstallPermission(getApplication())
            return
        }
        updateBusy = UpdateBusy.DOWNLOADING
        updateProgress = 0f
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.download(getApplication(), info) { p -> updateProgress = p } }
            }
            updateBusy = UpdateBusy.NONE
            updateProgress = null
            result.fold(
                onSuccess = { file ->
                    val opened = runCatching { AppUpdater.install(getApplication(), file) }
                    if (opened.isFailure) updateNote = "Couldn't open the installer"
                },
                onFailure = { updateNote = it.message ?: "Download failed" },
            )
        }
    }

    /** Read the engine version once, off the main thread; the first call unpacks Python. */
    fun loadEngineVersion() {
        if (engineVersion != null) return
        viewModelScope.launch {
            engineVersion = withContext(Dispatchers.IO) { ytDlp.versionOrInit() } ?: "unknown"
        }
    }

    /**
     * Update yt-dlp from its own release feed.
     *
     * This is the one thing that keeps a downloader honest over time: the sites change and the
     * engine has to follow. [onDone] receives a message to show.
     */
    fun updateEngine(onDone: (String) -> Unit) {
        if (updatingEngine) return
        updatingEngine = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeout(UPDATE_TIMEOUT_MS) { ytDlp.updateEngine() } }
            }
            updatingEngine = false
            result.fold(
                onSuccess = { version ->
                    engineVersion = version ?: engineVersion
                    onDone("Engine updated to ${version ?: "the latest version"}")
                },
                onFailure = { error ->
                    onDone("Couldn't update: ${error.message ?: "no connection"}")
                },
            )
        }
    }

    /** Import a browser cookies.txt. Returns null on success, or a message for the user. */
    fun importCookies(uri: Uri): String? {
        val result = jar.import(uri)
        return result.fold(
            onSuccess = { count ->
                cookiesAdded = true
                cookieCount = count
                notice = null
                // A site that refused before may answer now, so ask again for the current link.
                link?.let(::scheduleProbe)
                null
            },
            onFailure = { error -> error.message ?: "That file could not be used" },
        )
    }

    fun removeCookies() {
        jar.clear()
        cookiesAdded = false
        cookieCount = 0
    }

    fun availableQualities(): List<Quality> =
        if (checking) Quality.entries.toList() else QualityChoice.options(sourceInfo?.maxHeight)

    val canDownload: Boolean
        get() = link != null && !DownloadHub.isBusy

    fun startDownload(): Boolean {
        val current = link ?: return false
        DownloadHub.enqueue(
            url = current.url,
            platform = current.platform,
            quality = quality,
            title = sourceInfo?.title,
            siteName = current.siteName,
        )
        DownloadService.start(getApplication())
        probeJob?.cancel()
        ytDlp.cancelProbe()
        checking = false
        return true
    }

    fun cancel(id: String) {
        DownloadService.cancel(getApplication(), id)
    }

    var storageLimit by mutableStateOf(prefs.storageLimit)
        private set

    var usageBytes by mutableStateOf(DownloadHub.retainedBytes())
        private set

    fun updateStorageLimit(limit: StorageLimit) {
        prefs.storageLimit = limit
        storageLimit = limit
    }

    fun refreshUsage() {
        usageBytes = DownloadHub.retainedBytes()
    }

    /** Forget one finished download, optionally deleting the file it left behind. */
    fun forget(item: DownloadItem, deleteFile: Boolean) {
        if (deleteFile) SavedFileRemover.delete(getApplication(), item.savedUri)
        DownloadHub.forget(item.id)
        refreshUsage()
    }

    /** Forget everything finished. A running job is left alone; it has no file yet. */
    fun forgetAll(deleteFiles: Boolean) {
        if (deleteFiles) {
            DownloadHub.items.value
                .filter { it.state.isTerminal }
                .forEach { SavedFileRemover.delete(getApplication(), it.savedUri) }
        }
        DownloadHub.forgetFinished()
        refreshUsage()
    }

    /**
     * A link arrives from the share sheet. With a folder already configured the app starts the
     * download and the caller is free to close the screen: the whole point is that the user
     * stays in Instagram.
     */
    fun handleIncomingText(text: CharSequence?): ShareOutcome {
        val url = SocialLinks.extractUrl(text?.toString()) ?: return ShareOutcome.NONE
        onUrlChanged(url)
        return if (startDownload()) ShareOutcome.STARTED else ShareOutcome.PREFILLED
    }

    /**
     * Ask the source what it has, without ever making the user wait for the answer.
     *
     * The Download button is live during this. If the probe comes back saying the best the
     * source has is 720 lines, "HQ" quietly disappears, because two buttons that produce the
     * same file is a worse lie than no button.
     */
    private fun scheduleProbe(target: SocialLink) {
        probeJob?.cancel()
        ytDlp.cancelProbe()
        if (DownloadHub.isBusy) return
        probeJob = viewModelScope.launch {
            delay(900)
            checking = true

            // The probe blocks inside a child process, where a coroutine timeout has nothing
            // to cancel: the watchdog has to kill the process itself.
            val watchdog = launch(Dispatchers.IO) {
                delay(PROBE_BUDGET_MS)
                ytDlp.cancelProbe()
            }
            val info = try {
                withContext(Dispatchers.IO) { ytDlp.probe(target.url) }
            } finally {
                watchdog.cancel()
            }

            checking = false
            if (link?.url != target.url) return@launch
            if (info != null) {
                sourceInfo = info
                if (!QualityChoice.offersHigh(info.maxHeight) && quality == Quality.HIGH) {
                    quality = Quality.NORMAL
                }
            }
        }
    }

    /** Site name for the chip under the field, e.g. "Instagram" or "Vimeo". */
    fun siteLabel(): String = link?.siteName ?: Platform.OTHER.label
}
