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
     * Both selectors end in a plain "best" fallback: a muxed file at a lower resolution is a
     * better outcome than a 1440p video with no sound, and it is what the user meant by
     * "download this".
     */
    fun selector(quality: Quality): String = when (quality) {
        Quality.NORMAL ->
            "b[height<=$NORMAL_MAX_HEIGHT][ext=mp4]/b[height<=$NORMAL_MAX_HEIGHT]/b[ext=mp4]/b"
        Quality.HIGH ->
            "bv*[height<=2160]+ba/b[height<=2160]/b"
    }
}
