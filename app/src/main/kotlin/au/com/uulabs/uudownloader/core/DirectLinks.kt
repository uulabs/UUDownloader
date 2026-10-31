package au.com.uulabs.uudownloader.core

/** Whether a link is a plain file on a server rather than a page with a player in it. */
object DirectLinks {

    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a")

    fun looksDirect(url: String): Boolean {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        val extension = withoutQuery.substringAfterLast('.', "")
        return extension.lowercase() in EXTENSIONS
    }

    /** A filename suggested by the link itself, used when the server names nothing better. */
    fun suggestedName(url: String): String? {
        val withoutQuery = url.substringBefore('?').substringBefore('#')
        return withoutQuery.substringAfterLast('/')
            .takeIf { it.isNotBlank() && it.contains('.') }
            ?.let { Uri2.decode(it) }
    }
}

/** Percent-decoding for the one place it is needed, without dragging in android.net.Uri. */
internal object Uri2 {
    fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
