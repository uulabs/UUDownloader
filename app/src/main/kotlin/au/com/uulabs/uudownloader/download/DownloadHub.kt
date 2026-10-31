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

    fun attach(store: HistoryStore) {
        history = store
        if (_items.value.isEmpty()) {
            _items.value = store.load().sortedByDescending { it.createdAt }
        }
    }

    /** True once the folder exists and a download can start without another decision. */
    val isBusy: Boolean get() = _items.value.any { it.state.isRunning }

    fun active(): DownloadItem? = _items.value.firstOrNull { it.state.isRunning }

    fun enqueue(url: String, platform: Platform, quality: Quality, title: String? = null): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            platform = platform,
            quality = quality,
            state = DownloadState.QUEUED,
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
        finished?.let { history?.record(it) }
    }

    fun remove(id: String) {
        _items.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _items.update { list -> list.filter { it.state.isRunning } }
    }
}
