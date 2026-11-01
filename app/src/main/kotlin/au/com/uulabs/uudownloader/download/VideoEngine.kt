package au.com.uulabs.uudownloader.download

import au.com.uulabs.uudownloader.core.Quality
import java.io.File

/** What the source can offer, so the screen can decide whether "HQ" means anything. */
data class SourceInfo(
    val title: String?,
    val maxHeight: Int?,
    val durationSeconds: Long?,
)

/** Progress tick from the engine, already in the shape the notification needs. */
data class Progress(
    val percent: Float?,
    val line: String?,
)

/**
 * What came back. Usually the quality that was asked for; occasionally a source refuses that
 * one and a lower quality is the honest outcome, which the caller has to be able to say.
 */
data class DownloadResult(
    val file: File,
    val quality: Quality,
    val note: String? = null,
    /** The source's own title, which the scratch file's restricted name has lost. */
    val title: String? = null,
)

/** One download job: what to fetch, how good, where the engine may write, and its identity. */
data class DownloadRequest(
    val jobId: String,
    val url: String,
    val quality: Quality,
    val workDir: File,
)

interface VideoEngine {

    /** Ask the source what it has. Never throws; an unknown answer is a valid answer. */
    fun probe(url: String): SourceInfo?

    /**
     * Download into [DownloadRequest.workDir] and return the finished file. Blocking; call from
     * a worker thread. Throws [DownloadFailure] on a source problem and [CancelledDownload] if
     * [isCancelled] reports so or the job was killed.
     */
    fun download(
        request: DownloadRequest,
        onProgress: (Progress) -> Unit,
        isCancelled: () -> Boolean,
    ): DownloadResult

    /** Stop a job that is already running. Must be safe to call from any thread. */
    fun cancel(jobId: String) {}

    /** Name for the job's child process, so cancellation can find it. */
    fun processIdFor(jobId: String): String = "uudl-$jobId"
}

/**
 * The source refused or the job could not finish. [message] is the one line worth reading and
 * [detail] is everything the engine said, for the person trying to work out why.
 */
class DownloadFailure(
    message: String,
    cause: Throwable? = null,
    val detail: String? = null,
) : Exception(message, cause)

/** The download was cancelled by the user rather than by the source. */
class CancelledDownload : Exception("cancelled")

/** A "direct" link answered with a web page, so it needs the engine that understands pages. */
class NotADirectFile : Exception("the link answered with a page")
