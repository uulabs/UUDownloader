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

    private val LOGIN = Regex("""\blog[ -]?in\b|\blogged[ -]in\b|\bsign in to\b""", RegexOption.IGNORE_CASE)
    private val PRIVATE = Regex(
        """\bprivate (video|post|account)\b|\b(video|post|account)\b[^.]{0,30}\bprivate\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REMOVED = Regex(
        """has been removed|has been deleted|no longer available|http error 404|\b(video|post) not found\b""",
        RegexOption.IGNORE_CASE,
    )
    private val NETWORK = Regex(
        """name or service not known|name resolution|no address associated|network is unreachable|""" +
            """timed out|connection reset|connection refused""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * What to tell the user, given what the site told us.
     *
     * Each answer has to be earned by the site's own words. Saying "removed" because an error
     * contained "not found" would blame the video for a missing tool or a dead connection, so
     * anything not recognised is shown as it is.
     */
    fun hint(raw: String?): String {
        val text = raw.orEmpty()
        return when {
            text.contains("page needs to be reloaded", true) ->
                "YouTube refused this network. The app will have already tried other clients — " +
                    "update the download engine in Settings, or add your browser cookies if it still refuses."
            text.contains("confirm you're not a bot", true) ||
                text.contains("confirm you\u2019re not a bot", true) ||
                text.contains("sign in to confirm", true) ->
                "YouTube is asking this network to sign in. Update the download engine in Settings, " +
                    "or add your browser cookies if it still refuses."
            text.contains("requires authentication", true) ||
                text.contains("authentication is required", true) ||
                text.contains("--cookies", true) ||
                LOGIN.containsMatchIn(text) ->
                "This site wants a signed-in session. Add your browser cookies in Settings, or use a link anyone can open."
            text.contains("not available in your country", true) ||
                text.contains("geo restricted", true) ||
                text.contains("geo-restricted", true) ->
                "The site won't serve this video in your region."
            PRIVATE.containsMatchIn(text) -> "That video is private."
            REMOVED.containsMatchIn(text) -> "That video has been removed."
            NETWORK.containsMatchIn(text) -> "Couldn't reach the site. Check the connection and try again."
            else -> text
        }
    }

    /** The most useful single sentence out of a yt-dlp error blob. */
    fun errorMessage(stderr: String?): String? {
        val line = stderr?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("ERROR:", true) }
            ?: return null
        return line.removePrefix("ERROR:").trim().ifEmpty { null }
    }
}
