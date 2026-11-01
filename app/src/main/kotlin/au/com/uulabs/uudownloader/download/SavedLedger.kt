package au.com.uulabs.uudownloader.download

import org.json.JSONArray
import org.json.JSONObject

/** One file this app wrote and still answers for. */
data class SavedEntry(val id: String, val uri: String, val bytes: Long)

/**
 * What the storage allowance is measured against: every file the app has saved and not since let
 * go of.
 *
 * It is separate from [HistoryStore] because the two answer different questions. History is a
 * short list for the screen and drops its oldest rows; a video does not stop taking up room
 * because its row scrolled away.
 */
class SavedLedger(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {

    /** First run after an update: start from what the old history knew about. */
    @Synchronized
    fun seedIfNew(history: List<DownloadItem>) {
        if (read() != null) return
        save(history.mapNotNull { item ->
            val uri = item.savedUri ?: return@mapNotNull null
            SavedEntry(item.id, uri, item.sizeBytes ?: 0L)
        })
    }

    @Synchronized
    fun entries(): List<SavedEntry> {
        val raw = read() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val json = array.getJSONObject(index)
                    SavedEntry(json.getString("id"), json.getString("uri"), json.optLong("bytes"))
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(entry: SavedEntry) = save(entries().filter { it.id != entry.id } + entry)

    @Synchronized
    fun remove(id: String) = save(entries().filter { it.id != id })

    /** Keeps only what [keep] says is still there. */
    @Synchronized
    fun retain(keep: (SavedEntry) -> Boolean) = save(entries().filter(keep))

    fun totalBytes(): Long = entries().sumOf { it.bytes }

    private fun save(entries: List<SavedEntry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("id", it.id).put("uri", it.uri).put("bytes", it.bytes))
        }
        write(array.toString())
    }
}
