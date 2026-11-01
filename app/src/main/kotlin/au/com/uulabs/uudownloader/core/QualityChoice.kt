package au.com.uulabs.uudownloader.core

/** How good the saved file should be. */
enum class Quality(val label: String) {
    /** The version that always exists: one file, audio already muxed, at most 720 lines. */
    NORMAL("Normal"),

    /** The best picture the source will hand over, merged into one MP4. */
    HIGH("HQ"),
}

object QualityChoice {

    /** Above this many lines the two choices produce genuinely different files. */
    const val NORMAL_MAX_HEIGHT = 720

    /**
     * A "HQ" button that produces the same bytes as "Normal" is a lie the user pays time for.
     * Offer the choice only when the source has something taller than the normal cap, and this
     * phone could actually play it.
     *
     * An unknown maximum is *not* treated as "no HQ": the user still gets the choice, and the
     * format selector picks the best available either way.
     */
    fun offersHigh(maxHeight: Int?, limits: PlaybackLimits? = null): Boolean {
        if (limits != null && limits.maxShortEdge <= NORMAL_MAX_HEIGHT) return false
        return maxHeight == null || maxHeight > NORMAL_MAX_HEIGHT
    }

    /** The choices to show for a source with [maxHeight] lines, best-labelled first. */
    fun options(maxHeight: Int?, limits: PlaybackLimits? = null): List<Quality> =
        if (offersHigh(maxHeight, limits)) Quality.entries.toList() else listOf(Quality.NORMAL)

    /**
     * yt-dlp format selector for a quality.
     *
     * With [limits] known, the first choices are whatever this phone can play, largest first. The
     * rest is what has always been there, so a source that offers nothing on that list still
     * saves something: a file that may be hard to play beats an error, and it is what the user
     * meant by "download this".
     *
     * Every selector ends in a plain "best" fallback.
     *
     * [canMerge] is false in a build without ffmpeg. yt-dlp cannot join a video stream to an
     * audio one on its own, and asking it to try produces a hard failure rather than a
     * fallback, so the selector has to ask for a single already-muxed file instead. That caps
     * YouTube at its best pre-muxed stream (usually 720p) and costs the other sites nothing,
     * because they serve one file anyway.
     */
    fun selector(quality: Quality, canMerge: Boolean, limits: PlaybackLimits? = null): String {
        val playable = when {
            limits == null -> emptyList()
            quality == Quality.HIGH && canMerge ->
                limits.alternatives("bv*", suffix = "+$AUDIO") +
                    listOfNotNull(limits.sizeCap?.let { "bv*$it+$AUDIO" })
            quality == Quality.HIGH -> limits.alternatives("b")
            canMerge ->
                limits.alternatives("b", maxEdge = NORMAL_MAX_HEIGHT) +
                    limits.alternatives("bv*", suffix = "+$AUDIO", maxEdge = NORMAL_MAX_HEIGHT)
            else -> limits.alternatives("b", maxEdge = NORMAL_MAX_HEIGHT)
        }
        val single = "b[height<=$NORMAL_MAX_HEIGHT][ext=mp4]/b[height<=$NORMAL_MAX_HEIGHT]"
        val general = when {
            !canMerge && quality == Quality.HIGH -> "b[ext=mp4]/b"
            !canMerge -> "$single/b[ext=mp4]/b"
            // Plenty of videos no longer offer a single file at all, so Normal can join two.
            quality == Quality.NORMAL -> "$single/bv*[height<=$NORMAL_MAX_HEIGHT]+$AUDIO/b[ext=mp4]/b"
            else -> "bv*[height<=2160]+$AUDIO/b[height<=2160]/b"
        }
        return (playable + general).joinToString("/")
    }

    /** AAC first: it plays everywhere, and an old phone may not manage Opus inside an MP4. */
    private const val AUDIO = "(ba[ext=m4a]/ba)"
}
