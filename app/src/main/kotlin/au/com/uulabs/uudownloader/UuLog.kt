package au.com.uulabs.uudownloader

import android.util.Log
import au.com.uulabs.uudownloader.core.LogRedaction

/**
 * One tag for the whole app, because a field report is worth more than a tidy logcat.
 *
 * The first version of this app had no logging at all, and the first failure it produced on
 * someone else's phone was reported as the four characters "i5.f" — an R8-renamed class with
 * no context. Everything that can fail now says what it was doing and what threw.
 *
 * What it does not say in a release build is what was being downloaded. An exception's message
 * is yt-dlp's stderr, which quotes the link, so it goes through [LogRedaction] first.
 */
object UuLog {
    private const val TAG = "UUDL"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String, error: Throwable? = null) {
        runCatching {
            if (BuildConfig.DEBUG) Log.w(TAG, message, error) else Log.w(TAG, withCause(message, error))
        }
    }

    fun e(message: String, error: Throwable? = null) {
        runCatching {
            if (BuildConfig.DEBUG) Log.e(TAG, message, error) else Log.e(TAG, withCause(message, error))
        }
    }

    /** "java.lang.IllegalStateException: message" — always something printable. */
    fun describe(error: Throwable): String {
        val name = error.javaClass.name
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }

    private fun withCause(message: String, error: Throwable?): String =
        if (error == null) message else "$message: ${LogRedaction.clean(describe(error)).take(600)}"
}
