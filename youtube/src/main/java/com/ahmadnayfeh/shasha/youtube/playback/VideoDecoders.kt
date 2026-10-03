package com.ahmadnayfeh.shasha.youtube.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

internal enum class DecoderKind { HARDWARE, SOFTWARE, NONE }

/** Can this device decode a video representation, and how? */
internal fun interface VideoDecoderCheck {
    fun check(codecs: String, width: Int, height: Int, fps: Int): DecoderKind

    /**
     * A representation failed to decode on screen: whatever was answered for it is not trusted again, the next
     * [check] asks the device afresh. (The planner also leaves it out: [DecoderFailures].)
     */
    fun forget(codecs: String, width: Int, height: Int) = Unit
}

/**
 * Asks the device's MediaCodec list (through Media3, which knows the per-device quirks). Results are cached.
 *
 * A portrait picture (taller than wide) must also pass [takesExactSize]: Media3 alone is too generous with it.
 *
 * @param decoderInfos the device's decoders for a MIME type, best first (a seam for tests).
 */
@OptIn(UnstableApi::class)
internal class MediaCodecVideoDecoderCheck(
    private val context: Context,
    private val decoderInfos: (mimeType: String) -> List<MediaCodecInfo> = { mimeType ->
        MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false)
    },
) : VideoDecoderCheck {

    private val cache = ConcurrentHashMap<String, DecoderKind>()

    override fun check(codecs: String, width: Int, height: Int, fps: Int): DecoderKind =
        cache.getOrPut("$codecs/$width/$height/$fps") { query(codecs, width, height, fps) }

    override fun forget(codecs: String, width: Int, height: Int) {
        cache.keys.removeAll { it.startsWith("$codecs/$width/$height/") }
    }

    private fun query(codecs: String, width: Int, height: Int, fps: Int): DecoderKind {
        val mimeType = MimeTypes.getVideoMediaMimeType(codecs) ?: return DecoderKind.NONE
        val format = Format.Builder()
            .setSampleMimeType(mimeType)
            .setCodecs(codecs)
            .setWidth(if (width > 0) width else Format.NO_VALUE)
            .setHeight(if (height > 0) height else Format.NO_VALUE)
            .setFrameRate(if (fps > 0) fps.toFloat() else Format.NO_VALUE.toFloat())
            .build()
        val decoders = try {
            decoderInfos(mimeType)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            return DecoderKind.NONE
        }
        val portrait = width > 0 && height > width
        var result = DecoderKind.NONE
        for (decoder in decoders) {
            if (!decoder.isFormatSupported(context, format)) continue
            if (portrait && !decoder.takesExactSize(width, height, fps)) continue
            if (decoder.hardwareAccelerated) return DecoderKind.HARDWARE
            result = DecoderKind.SOFTWARE
        }
        return result
    }
}

/**
 * Whether this decoder's own Android capabilities take a [width]x[height] picture as it is (at [fps] when it is known),
 * the picture not turned.
 *
 * [MediaCodecInfo.isFormatSupported] says yes to a portrait picture also when only the rotated size is listed (Media3's
 * "rotated vertical resolution" leniency, MediaCodecInfo.isVideoSizeAndRateSupported), and, from Android 10, when a
 * performance point of the decoder covers its pixel count (they do not look at the picture's shape). Some TV SoCs
 * (MediaTek in TCL TVs) accept the format that way and then fail to decode such streams (ERROR_CODE_DECODING_FAILED on
 * a Short at "1080p", which is 1080x1920). This is the test Media3 applies to a landscape picture, without those second
 * chances: a size that fails it is not decodable here.
 *
 * Like Media3 it aligns the size up to the decoder's block alignment first (a 1080 wide picture is coded 1088 wide:
 * ExoPlayer issue 6551, "don't ever fail due to alignment").
 */
@OptIn(UnstableApi::class)
internal fun MediaCodecInfo.takesExactSize(width: Int, height: Int, fps: Int): Boolean {
    val video = capabilities?.videoCapabilities ?: return false
    val size = alignVideoSize(width, height) ?: return false
    // Below 1 fps Android's areSizeAndRateSupported is wrong on some versions, so only the size is asked (as Media3 does).
    return if (fps < 1) video.isSizeSupported(size.x, size.y) else video.areSizeAndRateSupported(size.x, size.y, fps.toDouble())
}

internal object VideoCodecs {

    /** "avc1.640028" → "avc1"; YouTube's WebM streams just say "vp9". */
    fun family(codecs: String): String = when (val prefix = codecs.substringBefore('.').lowercase()) {
        "vp09" -> "vp9"
        "avc3" -> "avc1"
        "hev1" -> "hvc1"
        else -> prefix
    }

    /** The family of a format Media3 played: by its codecs string, else by its sample MIME type; null when it says neither. */
    fun family(format: Format): String? = format.codecs?.takeIf { it.isNotBlank() }?.let { family(it) } ?: when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "avc1"
        MimeTypes.VIDEO_VP9 -> "vp9"
        MimeTypes.VIDEO_AV1 -> "av01"
        MimeTypes.VIDEO_H265 -> "hvc1"
        else -> null
    }

    /**
     * HDR representations (VP9 profile 2/3, 10/12-bit AV1) look washed out on SDR TVs and need
     * decoders weak TVs rarely have; YouTube always offers SDR ones next to them.
     */
    fun isHdr(codecs: String): Boolean {
        val parts = codecs.lowercase().split('.')
        return when (parts.firstOrNull()) {
            "vp09" -> parts.getOrNull(1)?.toIntOrNull()?.let { it >= 2 } ?: false
            "av01" -> parts.getOrNull(3)?.toIntOrNull()?.let { it > 8 } ?: false
            else -> false
        }
    }
}
