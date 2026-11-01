package au.com.uulabs.uudownloader.download

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import au.com.uulabs.uudownloader.core.CodecLimit
import au.com.uulabs.uudownloader.core.PlaybackLimits
import au.com.uulabs.uudownloader.core.VideoCodec

/**
 * Asks this phone which video it can play, using its own list of decoders.
 *
 * A hardware decoder is what counts. Android also ships software decoders that accept nearly
 * anything on paper, including 4K AV1, and then stutter through it on a phone's CPU. The one
 * exception is H.264 at modest sizes, which any phone's CPU can manage.
 */
object DeviceCodecs {

    private const val SOFTWARE_H264_SHORT_EDGE = 720

    private val MIME_TYPES = mapOf(
        VideoCodec.AV1 to "video/av01",
        VideoCodec.VP9 to "video/x-vnd.on2.vp9",
        VideoCodec.HEVC to "video/hevc",
        VideoCodec.H264 to "video/avc",
    )

    /** Null when the phone would not say, in which case nothing is ruled out. */
    val limits: PlaybackLimits? by lazy { read() }

    private fun read(): PlaybackLimits? = runCatching {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        val codecs = VideoCodec.entries.mapNotNull { codec -> limitFor(codec, decoders) }
        PlaybackLimits(codecs).takeIf { codecs.isNotEmpty() }
    }.getOrNull()

    private fun limitFor(codec: VideoCodec, decoders: List<MediaCodecInfo>): CodecLimit? {
        val mime = MIME_TYPES.getValue(codec)
        val supporting = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        val hardware = supporting.filter { it.isHardwareAccelerated }
        val at30 = largestShortEdge(hardware, mime, 30)
        val at60 = largestShortEdge(hardware, mime, 60)
        val softwareH264 = if (codec == VideoCodec.H264) {
            minOf(largestShortEdge(supporting, mime, 30), SOFTWARE_H264_SHORT_EDGE)
        } else {
            0
        }
        val best30 = maxOf(at30, softwareH264)
        return if (best30 > 0 || at60 > 0) CodecLimit(codec, best30, at60) else null
    }

    private fun largestShortEdge(decoders: List<MediaCodecInfo>, mime: String, fps: Int): Int =
        PlaybackLimits.SIZE_BANDS
            .firstOrNull { band -> decoders.any { plays(it, mime, band.longEdge, band.shortEdge, fps) } }
            ?.shortEdge
            ?: 0

    /**
     * Whether this decoder can really manage a landscape picture of this size.
     *
     * Landscape is what gets asked, because the paper limits are not symmetrical. One real phone
     * allows 1920 wide by 3840 tall, so a portrait 1440x2560 passes while the landscape video
     * this check is meant to clear would be refused outright.
     *
     * Paper limits alone are not enough either. Measured frame rates decide where the phone
     * publishes them, then its performance points, and with neither only 1080p30 is taken on trust.
     */
    private fun plays(info: MediaCodecInfo, mime: String, long: Int, short: Int, fps: Int): Boolean = runCatching {
        val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return false
        if (!video.areSizeAndRateSupported(long, short, fps.toDouble())) return false
        val measured = video.getAchievableFrameRatesFor(long, short)
        if (measured != null) return measured.upper >= fps * 0.9
        val points = video.supportedPerformancePoints
        if (!points.isNullOrEmpty()) {
            val wanted = MediaCodecInfo.VideoCapabilities.PerformancePoint(long, short, fps)
            return points.any { it.covers(wanted) }
        }
        short <= 1080 && fps <= 30
    }.getOrDefault(false)
}
