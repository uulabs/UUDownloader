package au.com.uulabs.uudownloader.core

object FileNames {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private val SPACES = Regex("""\s+""")

    /**
     * Turn a video title into something a storage provider will accept.
     *
     * The 90-character cap is not cosmetic: MediaStore and some SAF providers reject or
     * silently truncate long names, and a truncated name can lose its extension, which leaves
     * a file the gallery will not open.
     */
    fun fromTitle(title: String?, fallback: String = "UU video", extension: String = "mp4"): String {
        val cleaned = (title ?: "")
            .replace(ILLEGAL, " ")
            .replace(SPACES, " ")
            .trim()
            .trim('.', ' ', '-')
        val stem = cleaned.take(90).trim().ifEmpty { fallback }
        val ext = extension.trimStart('.').ifEmpty { "mp4" }
        return "$stem.$ext"
    }

    /**
     * Some providers append an extension derived from the MIME type. Handing them a name that
     * already carries it produces `clip.mp4.mp4`, so the name given to the provider is the stem
     * and this is what the final name is expected to be.
     */
    fun stemOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    fun hasSameExtension(a: String, b: String): Boolean = a.endsWith(".${b.substringAfterLast('.')}", true)
}
