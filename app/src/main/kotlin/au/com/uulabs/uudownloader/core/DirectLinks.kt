package au.com.uulabs.uudownloader.core

/** Whether a link is a plain file on a server rather than a page with a player in it. */
object DirectLinks {

    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv", "mp3", "m4a")

    /** Content types that are never the media file a direct link promised. */
    private val PAGE_TYPES = setOf("text/html", "application/xhtml+xml", "application/json", "text/xml", "application/xml")

    /** "https://host.mov" is a website on the .mov domain, so only the path can name a file. */
    fun looksDirect(url: String): Boolean =
        lastPathSegment(url).substringAfterLast('.', "").lowercase() in EXTENSIONS

    /** A filename suggested by the link itself, used when the server names nothing better. */
    fun suggestedName(url: String): String? =
        lastPathSegment(url)
            .takeIf { it.isNotBlank() && it.contains('.') }
            ?.let { Uri2.decode(it) }

    /**
     * Whether a response to a "direct" link is really a web page.
     *
     * Plenty of sites answer a `.mp4` URL with a sign-in page or a player page. Saving that as a
     * video would report success and leave a file nothing can open. The content type is checked
     * first, and the first bytes as well, because servers mislabel pages too.
     */
    fun looksLikePage(contentType: String?, head: ByteArray, length: Int = head.size): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type in PAGE_TYPES) return true
        val start = String(head, 0, length.coerceIn(0, minOf(head.size, 256)), Charsets.ISO_8859_1)
            .removePrefix("\u00EF\u00BB\u00BF")
            .trimStart()
            .lowercase()
        return start.startsWith("<!doctype") || start.startsWith("<html") || start.startsWith("<head")
    }

    private fun lastPathSegment(url: String): String {
        val afterScheme = url.substringAfter("://", url).substringBefore('?').substringBefore('#')
        val slash = afterScheme.indexOf('/')
        if (slash < 0) return ""
        return afterScheme.substring(slash + 1).substringAfterLast('/')
    }
}

/** Percent-decoding for the one place it is needed, without dragging in android.net.Uri. */
internal object Uri2 {
    fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)
}
