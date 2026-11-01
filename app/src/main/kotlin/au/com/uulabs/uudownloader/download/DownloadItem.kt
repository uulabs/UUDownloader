package au.com.uulabs.uudownloader.download

import au.com.uulabs.uudownloader.core.Platform
import au.com.uulabs.uudownloader.core.Quality

enum class DownloadState {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    SAVING,
    DONE,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
    val isRunning: Boolean get() = !isTerminal
}

/**
 * One download, from the moment a link is handed to the app to the moment the file is in the
 * folder the user picked.
 */
data class DownloadItem(
    val id: String,
    val url: String,
    val platform: Platform,
    val quality: Quality,
    val state: DownloadState,
    /** The site's own name when it is not one of the six named platforms. */
    val siteName: String? = null,
    val title: String? = null,
    /** 0f..1f, or null while the size is not known yet. */
    val progress: Float? = null,
    /** Free-form line shown under the progress bar: speed, ETA, "merging". */
    val detail: String? = null,
    val savedName: String? = null,
    val savedUri: String? = null,
    /** Bytes written to the user's folder, once the file is finished. */
    val sizeBytes: Long? = null,
    val error: String? = null,
    /** Everything the engine said about a failure, for the "What went wrong" dialog. */
    val errorDetail: String? = null,
    /** Something the user should know about the file that was saved, e.g. a quality fallback. */
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val siteLabel: String get() = siteName ?: platform.label

    val displayName: String
        get() = savedName ?: title ?: "$siteLabel video"
}
