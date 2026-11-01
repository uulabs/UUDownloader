package au.com.uulabs.uudownloader.download

import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last handful of downloads, so the list survives the app being closed.
 *
 * Only terminal items are kept: a queued item that was never resumed would come back as a
 * permanently spinning row after a restart, which reads as a hang rather than as history.
 */
class HistoryStore(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
    private val limit: Int = 30,
) {

    fun load(): List<DownloadItem> {
        val raw = read() ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                runCatching { fromJson(array.getJSONObject(index)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun record(item: DownloadItem) {
        if (!item.state.isTerminal) return
        val kept = (listOf(item) + load().filter { it.id != item.id }).take(limit)
        val array = JSONArray()
        kept.forEach { array.put(toJson(it)) }
        write(array.toString())
    }

    @Synchronized
    fun remove(id: String) {
        val kept = load().filterNot { it.id == id }
        if (kept.isEmpty()) {
            write(null)
            return
        }
        val array = JSONArray()
        kept.forEach { array.put(toJson(it)) }
        write(array.toString())
    }

    @Synchronized
    fun clear() = write(null)

    private fun toJson(item: DownloadItem) = JSONObject().apply {
        put("id", item.id)
        put("url", item.url)
        put("platform", item.platform.name)
        put("siteName", item.siteName ?: JSONObject.NULL)
        put("quality", item.quality.name)
        put("state", item.state.name)
        put("title", item.title ?: JSONObject.NULL)
        put("savedName", item.savedName ?: JSONObject.NULL)
        put("savedUri", item.savedUri ?: JSONObject.NULL)
        put("sizeBytes", item.sizeBytes ?: JSONObject.NULL)
        put("error", item.error ?: JSONObject.NULL)
        put("errorDetail", item.errorDetail?.take(MAX_DETAIL_CHARS) ?: JSONObject.NULL)
        put("note", item.note ?: JSONObject.NULL)
        put("createdAt", item.createdAt)
    }

    private fun fromJson(json: JSONObject) = DownloadItem(
        id = json.getString("id"),
        url = json.getString("url"),
        platform = Platform.valueOf(json.getString("platform")),
        quality = Quality.valueOf(json.getString("quality")),
        state = DownloadState.valueOf(json.getString("state")),
        siteName = json.stringOrNull("siteName"),
        title = json.stringOrNull("title"),
        savedName = json.stringOrNull("savedName"),
        savedUri = json.stringOrNull("savedUri"),
        sizeBytes = if (json.isNull("sizeBytes")) null else json.optLong("sizeBytes"),
        error = json.stringOrNull("error"),
        errorDetail = json.stringOrNull("errorDetail"),
        note = json.stringOrNull("note"),
        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
    )

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private companion object {
        const val MAX_DETAIL_CHARS = 4_000
    }
}
