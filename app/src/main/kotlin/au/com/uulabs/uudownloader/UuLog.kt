package au.com.uulabs.uudownloader

import android.util.Log

/**
 * One tag for the whole app, because a field report is worth more than a tidy logcat.
 *
 * The first version of this app had no logging at all, and the first failure it produced on
 * someone else's phone was reported as the four characters "i5.f" — an R8-renamed class with
 * no context. Everything that can fail now says what it was doing and what threw.
 */
object UuLog {
    private const val TAG = "UUDL"

    fun i(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    fun w(message: String, error: Throwable? = null) {
        runCatching { Log.w(TAG, message, error) }
    }

    fun e(message: String, error: Throwable? = null) {
        runCatching { Log.e(TAG, message, error) }
    }

    /** "java.lang.IllegalStateException: message" — always something printable. */
    fun describe(error: Throwable): String {
        val name = error.javaClass.name
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) name else "$name: $message"
    }
}
