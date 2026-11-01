package au.com.uulabs.uudownloader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import au.com.uulabs.uudownloader.UuLog

/**
 * Hands a saved video to the device's player.
 *
 * The flags matter more than they look. With `FLAG_ACTIVITY_NEW_TASK` alone, Android brought
 * the player's existing task to the front and the player ignored the new intent entirely: open
 * a video, then tap a different row, and Photos showed the first video again. Its own row was
 * being passed correctly — the app was launching `/downloads/292` and `/downloads/293` on
 * consecutive taps — but the second launch never created a new activity to receive it.
 *
 * `FLAG_ACTIVITY_CLEAR_TASK` makes each open a fresh one, which is what tapping a row means.
 */
object VideoLauncher {

    fun open(context: Context, uri: String) {
        UuLog.i("open video: $uri")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "video/*")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        runCatching { context.startActivity(intent) }
    }

    fun pendingIntent(context: Context, uri: String, requestCode: Int): android.app.PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), "video/*")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
            )
        return android.app.PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
