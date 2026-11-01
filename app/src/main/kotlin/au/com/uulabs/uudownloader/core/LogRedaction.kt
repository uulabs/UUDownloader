package au.com.uulabs.uudownloader.core

/**
 * What a release build may say about a failure in the system log.
 *
 * What someone downloads is their own business, and logcat ends up in bug reports. The kind of
 * failure is worth keeping; the address and the video id are not.
 */
object LogRedaction {
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val EXTRACTOR_PREFIX = Regex("""\[[\w:.-]+\]\s+[^\s:]+:""")

    fun clean(text: String): String =
        text.replace(URL, "<url>").replace(EXTRACTOR_PREFIX, "[site] <id>:")
}
