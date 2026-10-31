package au.com.uulabs.uudownloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import au.com.uulabs.uudownloader.MainActivity
import au.com.uulabs.uudownloader.R

/**
 * All notification shapes in one place, so the progress notification and the finished one
 * cannot drift apart in wording or importance.
 */
object DownloadNotifications {

    const val CHANNEL_ID = "uu_downloads"
    const val ONGOING_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while a video is being saved"
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun ongoing(context: Context, item: DownloadItem): Notification {
        val percent = item.progress?.let { (it * 100).toInt().coerceIn(0, 100) }
        val title = item.title?.take(60) ?: "${item.siteLabel} video"
        val text = buildString {
            append(
                when (item.state) {
                    DownloadState.PREPARING -> "Getting ready"
                    DownloadState.SAVING -> "Saving to your folder"
                    DownloadState.QUEUED -> "Waiting"
                    else -> percent?.let { "$it%" } ?: "Starting"
                }
            )
            item.detail?.let { append(" · ").append(it) }
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_uu)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp(context))
            .addAction(0, "Cancel", cancelIntent(context, item.id))
            .build()
    }

    fun finished(context: Context, item: DownloadItem): Notification {
        val done = item.state == DownloadState.DONE
        val title = when {
            done -> item.displayName
            item.state == DownloadState.CANCELLED -> "Download cancelled"
            else -> "Couldn't download that video"
        }
        val text = when {
            done -> "Saved. Tap to open"
            item.state == DownloadState.CANCELLED -> item.siteLabel
            else -> item.error ?: "The site refused the download"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_uu)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
        if (done && item.savedUri != null) {
            builder.addAction(0, "Open", openFile(context, item.savedUri))
        }
        return builder.build()
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun cancel(context: Context, id: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(id) }
    }

    /** Result notifications get a stable per-item id so two finished downloads do not collide. */
    fun resultId(itemId: String): Int = itemId.hashCode() and 0x7fffffff

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openFile(context: Context, uri: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(uri), "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelIntent(context: Context, itemId: String): PendingIntent {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(DownloadService.ACTION_CANCEL)
            .putExtra(DownloadService.EXTRA_ID, itemId)
        return PendingIntent.getService(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
