package au.com.uulabs.uudownloader.core

/**
 * The video formats that matter, best compression first. Sites name them differently, so each is
 * a pattern: YouTube says "vp9" for one stream and "vp09.00.41.08" for the next, and plenty of
 * sites just say "h264".
 */
enum class VideoCodec(val ytDlpPattern: String) {
    AV1("^av01"),
    VP9("^vp0?9"),
    HEVC("^(hev1|hvc1|hevc|h265)"),
    H264("^(avc1|avc3|h264)"),
}

/**
 * How large a picture a phone can decode smoothly in one codec. Sizes are the short edge, because
 * a portrait 1080x1920 clip asks exactly as much of a decoder as a landscape 1920x1080 one.
 */
data class CodecLimit(
    val codec: VideoCodec,
    val shortEdgeAt30: Int,
    val shortEdgeAt60: Int,
)

/**
 * What this phone can actually play, which is what "best quality" has to mean.
 *
 * The best stream a site offers is often 4K AV1. Many phones have no hardware decoder for that, so
 * saving it leaves a huge file that stutters or will not open at all. These limits turn the
 * phone's own answer into a request for the highest quality it can really play.
 */
data class PlaybackLimits(val codecs: List<CodecLimit>) {

    /** The tallest picture this phone can play in any codec. */
    val maxShortEdge: Int
        get() = codecs.maxOfOrNull { maxOf(it.shortEdgeAt30, it.shortEdgeAt60) } ?: 0

    /**
     * yt-dlp alternatives, tried in order. It takes the first that exists, so they run from the
     * largest picture down, and within a size prefer a codec that also manages 60 frames a second.
     *
     * Each size band has a floor as well as a ceiling. Without one, a 480p VP9 stream would satisfy
     * a "VP9 up to 4K" request and beat the 1080p H.264 stream sitting right beside it.
     */
    fun alternatives(stream: String, suffix: String = "", maxEdge: Int = Int.MAX_VALUE): List<String> =
        SIZE_BANDS.filter { it.shortEdge <= maxEdge }.flatMap { band ->
            codecs
                .filter { band.shortEdge <= maxOf(it.shortEdgeAt30, it.shortEdgeAt60) }
                .sortedWith(compareBy({ band.shortEdge > it.shortEdgeAt60 }, { it.codec.ordinal }))
                .map { limit ->
                    val fps = if (band.shortEdge <= limit.shortEdgeAt60) "" else "[fps<=?30]"
                    "$stream${band.filter}[vcodec~='${limit.codec.ytDlpPattern}'][dynamic_range=?SDR]$fps$suffix"
                }
        }

    /**
     * For a source with nothing in a codec this phone can decode. The file may be hard to play,
     * but it should not also be four times the size the phone could ever show.
     */
    val sizeCap: String?
        get() = SIZE_BANDS.firstOrNull { it.shortEdge <= maxShortEdge }
            ?.let { "[width<=${it.longEdge}][height<=${it.longEdge}]" }

    /** The sizes a decoder is asked about, each as long edge by short edge. */
    data class SizeBand(val longEdge: Int, val shortEdge: Int, val below: Int) {
        val filter: String
            get() = "[width>$below][height>$below][width<=$longEdge][height<=$longEdge]"
    }

    companion object {
        val SIZE_BANDS = listOf(
            SizeBand(3840, 2160, 1440),
            SizeBand(2560, 1440, 1080),
            SizeBand(1920, 1080, 720),
            SizeBand(1280, 720, 480),
            SizeBand(854, 480, 360),
            SizeBand(640, 360, 240),
            SizeBand(426, 240, 144),
            SizeBand(256, 144, 0),
        )
    }
}
