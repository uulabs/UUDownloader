package au.com.uulabs.uudownloader.download

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class SavedFile(val uri: Uri, val name: String)

object MimeTypes {
    fun of(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").ifEmpty { "mp4" }
}

/**
 * Puts a finished file into the folder the user chose, and returns the handle the app keeps.
 *
 * Everything here goes through the Storage Access Framework because that is the only way to
 * write into a folder the user picked on modern Android. A filesystem path would work on
 * exactly one device that happens to have the same layout, and nowhere else.
 */
object FolderWriter {

    fun save(
        context: Context,
        treeUri: Uri,
        source: File,
        preferredName: String,
    ): SavedFile {
        val resolver = context.contentResolver
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw DownloadFailure("That folder is no longer available. Choose it again.")

        if (!tree.exists()) throw DownloadFailure("That folder no longer exists. Choose it again.")
        if (!tree.canWrite()) throw DownloadFailure("UU Downloader can't write to that folder")

        val mime = MimeTypes.of(preferredName)
        val extension = MimeTypes.extensionOf(preferredName)
        val stem = uniqueStem(tree, stemOf(preferredName), extension)

        // The provider derives the extension from the MIME type. Handing it a name that
        // already carries one produces "clip.mp4.mp4" on most providers, so only the stem is
        // given and the result is checked afterwards.
        val created = tree.createFile(mime, stem)
            ?: throw DownloadFailure("The folder refused a new file")

        var finalName = created.name ?: "$stem.$extension"
        if (!finalName.endsWith(".$extension", ignoreCase = true)) {
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, created.uri, "$stem.$extension")
            }.getOrNull()
            if (renamed != null) finalName = "$stem.$extension"
        }

        try {
            val output = resolver.openOutputStream(created.uri, "w")
                ?: throw DownloadFailure("The folder refused the file contents")
            output.use { out ->
                source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
            }
        } catch (e: Exception) {
            runCatching { created.delete() }
            throw if (e is DownloadFailure) e else DownloadFailure("Could not write the file: ${e.message}", e)
        }

        return SavedFile(created.uri, finalName)
    }

    private fun stemOf(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).ifEmpty { "UU video" }

    /**
     * "clip (2).mp4" rather than overwriting the copy the user already has. Two different
     * videos are far more common than two intentional overwrites.
     */
    private fun uniqueStem(tree: DocumentFile, stem: String, extension: String): String {
        if (tree.findFile("$stem.$extension") == null) return stem
        for (index in 2..99) {
            val candidate = "$stem ($index)"
            if (tree.findFile("$candidate.$extension") == null) return candidate
        }
        return "$stem ${System.currentTimeMillis()}"
    }
}
