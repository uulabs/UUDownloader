package au.com.uulabs.uudownloader.data

import android.content.Context
import android.os.StatFs

/** How the storage allowance is expressed. */
enum class LimitMode { GIGABYTES, PERCENT }

/**
 * How much room UU Downloader may occupy.
 *
 * The point is not to police the phone: it is to answer "why did a download stop" before it
 * happens. A folder that quietly fills the last gigabyte of a phone is how people end up with
 * a device that cannot take a photo.
 *
 * A percentage is taken of the device's total internal storage rather than of what is free,
 * because a limit that moves every time something else is installed is not a limit.
 */
data class StorageLimit(val mode: LimitMode, val gigabytes: Int, val percent: Int) {

    companion object {
        const val DEFAULT_GIGABYTES = 5
        const val DEFAULT_PERCENT = 10
        const val MIN_GIGABYTES = 1
        const val MAX_GIGABYTES = 200
        const val MIN_PERCENT = 1
        const val MAX_PERCENT = 90

        fun default() = StorageLimit(LimitMode.GIGABYTES, DEFAULT_GIGABYTES, DEFAULT_PERCENT)

        /** Total internal storage, which a percentage is measured against. */
        fun deviceTotalBytes(context: Context): Long = runCatching {
            StatFs(context.filesDir.absolutePath).totalBytes
        }.getOrDefault(0L)

        /** What is left on the phone right now, for the one line that gives the rest context. */
        fun deviceFreeBytes(context: Context): Long = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)

        fun limitBytes(context: Context, limit: StorageLimit): Long =
            limit.bytes(deviceTotalBytes(context))
    }

    /** The allowance in bytes, for [totalInternalBytes] of device storage. */
    fun bytes(totalInternalBytes: Long): Long = when (mode) {
        LimitMode.GIGABYTES -> gigabytes.toLong() * 1_000_000_000L
        LimitMode.PERCENT -> totalInternalBytes / 100L * percent
    }

    fun describe(): String = when (mode) {
        LimitMode.GIGABYTES -> "$gigabytes GB"
        LimitMode.PERCENT -> "$percent% of the phone's storage"
    }

    /**
     * What a percentage is a percentage *of*.
     *
     * "10%" on its own is not a limit anybody can picture; against the phone's capacity it is a
     * number of gigabytes, and that is the sentence shown under the control.
     */
    fun explain(totalInternalBytes: Long): String = when (mode) {
        LimitMode.GIGABYTES -> "of ${ByteSize.human(totalInternalBytes)} on this phone"
        LimitMode.PERCENT ->
            "$percent% of ${ByteSize.human(totalInternalBytes)} = ${ByteSize.human(bytes(totalInternalBytes))}"
    }

}

object ByteSize {
    fun human(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
        else -> String.format("%.0f KB", bytes / 1_000.0)
    }
}
