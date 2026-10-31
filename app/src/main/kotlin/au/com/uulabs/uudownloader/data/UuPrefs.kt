package au.com.uulabs.uudownloader.data

import android.content.Context
import android.net.Uri
import au.com.uulabs.uudownloader.core.Quality

/**
 * Everything the app remembers between launches: where videos go, how good they should be,
 * and the last few that finished. Nothing else survives, and nothing here is a secret.
 */
class UuPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("uu_downloader", Context.MODE_PRIVATE)

    var destination: Destination
        get() {
            val stored = prefs.getString(KEY_DEST_URI, null)
            if (stored != null) {
                val parsed = runCatching { Uri.parse(stored) }.getOrNull()
                if (parsed != null) {
                    return Destination.Custom(
                        uri = parsed,
                        name = prefs.getString(KEY_DEST_LABEL, null) ?: parsed.lastPathSegment ?: "Folder",
                    )
                }
            }
            return Destination.Downloads
        }
        set(value) = prefs.edit().apply {
            when (value) {
                Destination.Downloads -> {
                    remove(KEY_DEST_URI)
                    remove(KEY_DEST_LABEL)
                }
                is Destination.Custom -> {
                    putString(KEY_DEST_URI, value.uri.toString())
                    putString(KEY_DEST_LABEL, value.name)
                }
            }
        }.apply()

    var defaultQuality: Quality
        get() = runCatching {
            Quality.valueOf(prefs.getString(KEY_QUALITY, Quality.HIGH.name)!!)
        }.getOrDefault(Quality.HIGH)
        set(value) = prefs.edit().putString(KEY_QUALITY, value.name).apply()

    var historyJson: String?
        get() = prefs.getString(KEY_HISTORY, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_HISTORY) else putString(KEY_HISTORY, value)
        }.apply()

    private companion object {
        const val KEY_DEST_URI = "dest_uri"
        const val KEY_DEST_LABEL = "dest_label"
        const val KEY_QUALITY = "default_quality"
        const val KEY_HISTORY = "history"
    }
}
