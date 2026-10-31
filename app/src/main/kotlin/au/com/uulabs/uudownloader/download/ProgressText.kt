package au.com.uulabs.uudownloader.download

/**
 * Turns yt-dlp's chatter into one short line a notification can show.
 *
 * yt-dlp prints a full progress line roughly five times a second. Passing that straight to a
 * notification would be unreadable and would also re-layout on every tick, so the useful parts
 * (speed, ETA, "merging") are pulled out and everything else is dropped.
 */
object ProgressText {

    // yt-dlp writes "12.34MiB" and "3.21MiB/s", so the unit is not a single letter.
    private val SPEED = Regex("""at\s+([\d.]+\s*(?:[KMGTP]i?)?B/s)""")
    private val SIZE = Regex("""of\s+([\d.]+\s*(?:[KMGTP]i?)?B)""")
    private val ETA = Regex("""ETA\s+([\d:]+)""")

    fun detail(rawLine: String?): String? {
        val line = rawLine?.trim().orEmpty()
        if (line.isEmpty()) return null
        if (line.startsWith("[download]")) {
            val parts = buildList {
                SIZE.find(line)?.groupValues?.get(1)?.let { add(it) }
                SPEED.find(line)?.groupValues?.get(1)?.let { add(it) }
                ETA.find(line)?.groupValues?.get(1)?.let { add("ETA $it") }
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
        return when {
            line.contains("[Merger]", true) || line.contains("Merging", true) -> "Finishing up"
            line.contains("[ExtractAudio]", true) -> "Extracting audio"
            line.contains("[FixupM3u8]", true) || line.contains("[Fixup", true) -> "Fixing container"
            line.startsWith("[generic]", true) -> null
            line.startsWith("[info]", true) || line.startsWith("[youtube]", true) -> null
            else -> line.removePrefix("[download] ").take(60).takeIf { it.isNotBlank() }
        }
    }

    /** The most useful single sentence out of a yt-dlp error blob. */
    fun errorMessage(stderr: String?): String? {
        val line = stderr?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("ERROR:", true) }
            ?: return null
        return line.removePrefix("ERROR:").trim().take(160).ifEmpty { null }
    }
}
