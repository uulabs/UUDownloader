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

    var storageLimit: StorageLimit
        get() = StorageLimit(
            mode = runCatching {
                LimitMode.valueOf(prefs.getString(KEY_LIMIT_MODE, LimitMode.GIGABYTES.name)!!)
            }.getOrDefault(LimitMode.GIGABYTES),
            gigabytes = prefs.getInt(KEY_LIMIT_GB, StorageLimit.DEFAULT_GIGABYTES)
                .coerceIn(StorageLimit.MIN_GIGABYTES, StorageLimit.MAX_GIGABYTES),
            percent = prefs.getInt(KEY_LIMIT_PERCENT, StorageLimit.DEFAULT_PERCENT)
                .coerceIn(StorageLimit.MIN_PERCENT, StorageLimit.MAX_PERCENT),
        )
        set(value) = prefs.edit()
            .putString(KEY_LIMIT_MODE, value.mode.name)
            .putInt(KEY_LIMIT_GB, value.gigabytes)
            .putInt(KEY_LIMIT_PERCENT, value.percent)
            .apply()

    /** Whether the one-time background engine update has already succeeded on this install. */
    var engineUpdated: Boolean
        get() = prefs.getBoolean(KEY_ENGINE_UPDATED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENGINE_UPDATED, value).apply()

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** An app version the user said "later" to, so the prompt does not repeat every launch. */
    var dismissedAppVersion: String?
        get() = prefs.getString(KEY_DISMISSED_APP, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_DISMISSED_APP) else putString(KEY_DISMISSED_APP, value)
        }.apply()

    var dismissedEngineVersion: String?
        get() = prefs.getString(KEY_DISMISSED_ENGINE, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_DISMISSED_ENGINE) else putString(KEY_DISMISSED_ENGINE, value)
        }.apply()

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
        const val KEY_ENGINE_UPDATED = "engine_updated"
        const val KEY_LIMIT_MODE = "limit_mode"
        const val KEY_LIMIT_GB = "limit_gb"
        const val KEY_LIMIT_PERCENT = "limit_percent"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_DISMISSED_APP = "dismissed_app_version"
        const val KEY_DISMISSED_ENGINE = "dismissed_engine_version"
    }
}
