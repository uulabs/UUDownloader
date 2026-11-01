package au.com.uulabs.uudownloader.download

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import au.com.uulabs.uudownloader.data.Destination
import java.io.File

/**
 * Writes into the phone's Downloads folder, in its own sub-folder, through MediaStore.
 *
 * This exists because it is the only place an app can write to on modern Android without the
 * user first granting a folder, and it is where a downloaded video belongs anyway: visible in
 * Files, in the gallery, over USB, and to any other app.
 *
 * The item is inserted as pending and only published once the bytes are all there, so a
 * cancelled or failed download never leaves a half-file the user can tap and wonder about.
 */
object MediaStoreWriter {

    fun save(context: Context, source: File, preferredName: String): SavedFile {
        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val isVideo = MimeTypes.of(preferredName).startsWith("video/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, preferredName)
            // Without these the row reports 0 and every gallery shows 1 January 1970.
            // DATE_TAKEN is milliseconds, the other two are seconds.
            if (isVideo) put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
            put(MediaStore.MediaColumns.MIME_TYPE, MimeTypes.of(preferredName))
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DOWNLOADS}/" +
                    Destination.FOLDER_NAME,
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        // Video goes into the video collection under Movies; only that collection stores
        // DATE_TAKEN, and without it galleries date the file from the epoch. Other media keeps
        // the Downloads collection so it stays with downloads where it belongs.
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val uri = resolver.insert(collection, values)
            ?: throw DownloadFailure("Couldn't create the file in Downloads")

        try {
            val output = resolver.openOutputStream(uri)
                ?: throw DownloadFailure("Couldn't open the new file")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            }
            resolver.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is DownloadFailure) e else DownloadFailure("Could not save the file: ${e.message}", e)
        }

        return SavedFile(uri, displayName(context, uri) ?: preferredName)
    }

    /** MediaStore renames a duplicate ("clip (1).mp4"); the app should report the real name. */
    private fun displayName(context: Context, uri: android.net.Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
}
