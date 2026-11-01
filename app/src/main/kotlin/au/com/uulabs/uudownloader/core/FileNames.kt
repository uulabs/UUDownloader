package au.com.uulabs.uudownloader.core

object FileNames {

    private val ILLEGAL = Regex("""[\\/:*?"<>|\u0000-\u001f\u007f]""")
    private val BIDI_CONTROLS = Regex("""[\u202a-\u202e\u2066-\u2069]""")
    private val SPACES = Regex("""\s+""")
    private val NOT_EXTENSION = Regex("""[^A-Za-z0-9]""")

    private const val MAX_CHARS = 90

    /** Android filesystems allow 255 bytes in a name. This leaves room for " (12).webm". */
    private const val MAX_BYTES = 200

    /**
     * Turn a video title into something a storage provider will accept.
     *
     * The cap is not cosmetic. It is measured in bytes as well as characters because the
     * filesystem counts bytes: 90 Japanese characters are 270 of them. It also stops on a whole
     * character, because cutting an emoji in half leaves text that cannot be encoded.
     */
    fun fromTitle(title: String?, fallback: String = "UU video", extension: String = "mp4"): String {
        val cleaned = (title ?: "")
            .replace(ILLEGAL, " ")
            .replace(BIDI_CONTROLS, "")
            .replace(SPACES, " ")
            .trim()
            .trim('.', ' ', '-')
        val stem = capped(cleaned).trim().trim('.', ' ', '-').ifEmpty { fallback }
        val ext = extension.replace(NOT_EXTENSION, "").take(10).ifEmpty { "mp4" }
        return "$stem.$ext"
    }

    /**
     * Some providers append an extension derived from the MIME type. Handing them a name that
     * already carries it produces `clip.mp4.mp4`, so the name given to the provider is the stem
     * and this is what the final name is expected to be.
     */
    fun stemOf(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    private fun capped(text: String): String {
        val out = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val encoded = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (out.length + width > MAX_CHARS || bytes + encoded > MAX_BYTES) break
            out.appendCodePoint(codePoint)
            bytes += encoded
            index += width
        }
        return out.toString()
    }
}
