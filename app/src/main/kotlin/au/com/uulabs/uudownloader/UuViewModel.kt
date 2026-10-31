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
import au.com.uulabs.uudownloader.data.UuPrefs
import au.com.uulabs.uudownloader.download.DownloadHub
import au.com.uulabs.uudownloader.download.DownloadService
import au.com.uulabs.uudownloader.download.SourceInfo
import au.com.uulabs.uudownloader.download.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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

    private var probeJob: Job? = null

    private companion object {
        /** Long enough for a healthy network, short enough not to feel like a hang. */
        const val PROBE_BUDGET_MS = 45_000L
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

    fun clearFinished() = DownloadHub.clearFinished()

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

    /** Site name for the chip under the field, e.g. "Instagram", "Pornhub" or "Vimeo". */
    fun siteLabel(): String = link?.siteName ?: Platform.OTHER.label
}
