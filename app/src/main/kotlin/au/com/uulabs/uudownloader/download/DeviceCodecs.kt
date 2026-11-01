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

    private fun plays(info: MediaCodecInfo, mime: String, long: Int, short: Int, fps: Int): Boolean = runCatching {
        val video = info.getCapabilitiesForType(mime).videoCapabilities ?: return false
        val fitsOnPaper = video.areSizeAndRateSupported(long, short, fps.toDouble()) ||
            video.areSizeAndRateSupported(short, long, fps.toDouble())
        if (!fitsOnPaper) return false
        // Some phones also publish what they measured. Where they do, that outranks the paper figure.
        val measured = runCatching { video.getAchievableFrameRatesFor(long, short) }.getOrNull()
            ?: return true
        measured.upper >= fps * 0.9
    }.getOrDefault(false)
}
