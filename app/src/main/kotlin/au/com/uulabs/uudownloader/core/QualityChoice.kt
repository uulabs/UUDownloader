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
     * Offer the choice only when the source actually has something taller than the normal cap.
     *
     * An unknown maximum is *not* treated as "no HQ": the user still gets the choice, and the
     * format selector picks the best available either way.
     */
    fun offersHigh(maxHeight: Int?): Boolean = maxHeight == null || maxHeight > NORMAL_MAX_HEIGHT

    /** The choices to show for a source with [maxHeight] lines, best-labelled first. */
    fun options(maxHeight: Int?): List<Quality> =
        if (offersHigh(maxHeight)) Quality.entries.toList() else listOf(Quality.NORMAL)

    /**
     * yt-dlp format selector for a quality.
     *
     * Every selector ends in a plain "best" fallback: a muxed file at a lower resolution is a
     * better outcome than an error, and it is what the user meant by "download this".
     *
     * [canMerge] is false in a build without ffmpeg. yt-dlp cannot join a video stream to an
     * audio one on its own, and asking it to try produces a hard failure rather than a
     * fallback, so the selector has to ask for a single already-muxed file instead. That caps
     * YouTube at its best pre-muxed stream (usually 720p) and costs the other sites nothing,
     * because they serve one file anyway.
     */
    fun selector(quality: Quality, canMerge: Boolean): String = when {
        !canMerge && quality == Quality.HIGH -> "b[ext=mp4]/b"
        !canMerge -> "b[height<=$NORMAL_MAX_HEIGHT][ext=mp4]/b[height<=$NORMAL_MAX_HEIGHT]/b[ext=mp4]/b"
        quality == Quality.NORMAL ->
            "b[height<=$NORMAL_MAX_HEIGHT][ext=mp4]/b[height<=$NORMAL_MAX_HEIGHT]/b[ext=mp4]/b"
        else -> "bv*[height<=2160]+ba/b[height<=2160]/b"
    }
}
