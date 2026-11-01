package au.com.uulabs.uudownloader.core

/**
 * What the app asks yt-dlp for, and how it reads the answer.
 *
 * This is plain text in and out, so the decisions that decide which file a user ends up with can
 * be tested without a phone or a Python runtime.
 */
object EngineOptions {

    /** Written by yt-dlp beside the video, so nothing has to guess which file is the result. */
    const val PATH_FILE = ".uu-path"
    const val TITLE_FILE = ".uu-title"
    const val COUNT_FILE = ".uu-count"

    private const val LIVE_FILTER = "!is_live"

    /**
     * Which YouTube clients to try, in order.
     *
     * YouTube answers "Sign in to confirm you're not a bot" based on the network, and may serve
     * the same video to a different client from the same address. Any other site gets one try.
     */
    fun clientLadder(url: String): List<String?> =
        if (SocialLinks.parse(url)?.platform == Platform.YOUTUBE) {
            listOf(null, "tv", "web_safari", "ios", "android_vr")
        } else {
            listOf(null)
        }

    /** The arguments for one download attempt. The URL is added by the caller. */
    fun downloadCommand(
        quality: Quality,
        canMerge: Boolean,
        client: String?,
        workDir: String,
        cookies: String?,
    ): List<String> = buildList {
        val dir = workDir.replace("%", "%%")
        add("-f"); add(QualityChoice.selector(quality, canMerge))
        if (canMerge) {
            add("--merge-output-format"); add("mp4")
        }
        add("--no-playlist")
        // --no-playlist only covers a video that happens to sit in a playlist. A link that is
        // itself a playlist or a channel would otherwise download all of it.
        add("--playlist-items"); add("1")
        // A live stream never ends, so it would record until something stopped it.
        add("--match-filter"); add(LIVE_FILTER)
        add("--newline")
        add("--no-mtime")
        add("--socket-timeout"); add("30")
        add("--retries"); add("5")
        // A title is not a filename: "|" and "/" in a YouTube title would either break the write
        // or silently relocate it. The real title comes back through TITLE_FILE.
        add("--restrict-filenames")
        add("-o"); add("$dir/%(title).90s.%(ext)s")
        add("--print-to-file"); add("after_move:%(filepath)s"); add("$dir/$PATH_FILE")
        add("--print-to-file"); add("after_move:%(title)s"); add("$dir/$TITLE_FILE")
        add("--print-to-file"); add("after_move:%(playlist_count|)s"); add("$dir/$COUNT_FILE")
        cookies?.let {
            add("--cookies"); add(it)
        }
        client?.let {
            add("--extractor-args"); add("youtube:player_client=$it")
        }
    }

    fun probeCommand(cookies: String?): List<String> = buildList {
        add("--dump-single-json")
        add("--no-playlist")
        add("--playlist-items"); add("1")
        add("--no-warnings")
        add("--skip-download")
        add("--socket-timeout"); add("20")
        cookies?.let {
            add("--cookies"); add(it)
        }
    }

    /**
     * Whether the refusal is YouTube distrusting the request rather than the video being gone.
     *
     * It says this in a dozen ways depending on the client that asked, and all of them are worth
     * answering with a different client.
     */
    fun isBotCheck(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return BOT_CHECK_PHRASES.any { text.contains(it) } || HTTP_403.containsMatchIn(text)
    }

    /** A refusal no other quality could change, so asking again only wastes the user's time. */
    fun isFinalRefusal(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return !isBotCheck(text) && FINAL_REFUSALS.any { text.contains(it) }
    }

    /** yt-dlp exits normally and saves nothing when the live filter rejects a video. */
    fun wasFilteredOut(stdout: String?): Boolean = stdout.orEmpty().contains("does not pass filter")

    /** How many videos a link holds, when it holds more than one and the site says so. */
    fun playlistCount(printed: String?): Int? =
        printed?.lineSequence()?.firstOrNull()?.trim()?.toIntOrNull()?.takeIf { it > 1 }

    /** yt-dlp prints NA for a field the site did not provide. */
    fun printedValue(printed: String?): String? =
        printed?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "NA" }

    private val HTTP_403 = Regex("""\b403\b""")

    private val BOT_CHECK_PHRASES = listOf(
        "not a bot",
        "not a robot",
        "sign in to confirm",
        "page needs to be reloaded",
        "requires authentication",
        "requires sign-in",
        "login required",
        "sign in to continue",
        "forbidden",
        "unable to extract",
        "no video formats found",
    )

    private val FINAL_REFUSALS = listOf(
        "private",
        "removed",
        "has been deleted",
        "no longer available",
        "not available in your country",
        "geo restricted",
        "geo-restricted",
        "account authentication is required",
        "http error 404",
    )
}
