package au.com.uulabs.uudownloader.download

import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The one list of downloads, shared by the screen and the foreground service.
 *
 * Both live in the same process, so a small in-memory hub is honest here: there is no second
 * source of truth to reconcile. Only *finished* rows are mirrored to [HistoryStore], because
 * those are the only ones worth showing after a restart.
 */
object DownloadHub {

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private var history: HistoryStore? = null
    private var ledger: SavedLedger? = null

    fun attach(store: HistoryStore, saved: SavedLedger) {
        history = store
        ledger = saved
        val kept = store.load()
        saved.seedIfNew(kept)
        if (_items.value.isEmpty()) {
            _items.value = kept.sortedByDescending { it.createdAt }
        }
    }

    /** True once the folder exists and a download can start without another decision. */
    val isBusy: Boolean get() = _items.value.any { it.state.isRunning }

    fun active(): DownloadItem? = _items.value.firstOrNull { it.state.isRunning }

    fun enqueue(
        url: String,
        platform: Platform,
        quality: Quality,
        title: String? = null,
        siteName: String? = null,
    ): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            platform = platform,
            quality = quality,
            state = DownloadState.QUEUED,
            siteName = siteName,
            title = title,
        )
        _items.update { listOf(item) + it }
        return item
    }

    fun update(id: String, transform: (DownloadItem) -> DownloadItem) {
        _items.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    /** Terminal transition; this is what gets remembered. */
    fun finish(id: String, state: DownloadState, transform: (DownloadItem) -> DownloadItem = { it }) {
        var finished: DownloadItem? = null
        _items.update { list ->
            list.map {
                if (it.id != id) it
                else transform(it).copy(state = state).also { done -> finished = done }
            }
        }
        finished?.let { done ->
            history?.record(done)
            val uri = done.savedUri
            if (done.state == DownloadState.DONE && uri != null) {
                ledger?.add(SavedEntry(done.id, uri, done.sizeBytes ?: 0L))
            }
        }
    }

    /** Drop a finished row and stop answering for its file, without touching the file on disk. */
    fun forget(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
        history?.remove(id)
        ledger?.remove(id)
    }

    /**
     * Every finished row at once, except those in [keep]. A running job is left alone: it has no
     * file yet. Files the list no longer shows are let go of as well.
     */
    fun forgetFinished(keep: Set<String> = emptySet()) {
        _items.update { list -> list.filter { it.state.isRunning || it.id in keep } }
        if (keep.isEmpty()) history?.clear() else history?.let { store ->
            store.load().filterNot { it.id in keep }.forEach { store.remove(it.id) }
        }
        ledger?.retain { it.id in keep }
    }

    /** Every file this app still answers for, including those too old to have a row. */
    fun savedEntries(): List<SavedEntry> = ledger?.entries().orEmpty()

    /** Drop whatever the user has since deleted some other way. Touches the disk. */
    fun reconcile(stillThere: (SavedEntry) -> Boolean) {
        ledger?.retain(stillThere)
    }

    /** Bytes this app knows it has written into the user's folder. */
    fun retainedBytes(): Long = ledger?.totalBytes() ?: 0L
}
