package com.ahmadnayfeh.shasha.youtube.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import java.util.concurrent.ConcurrentHashMap

internal enum class DecoderKind { HARDWARE, SOFTWARE, NONE }

/** Can this device decode a video representation, and how? */
internal fun interface VideoDecoderCheck {
    fun check(codecs: String, width: Int, height: Int, fps: Int): DecoderKind
}

/** Asks the device's MediaCodec list (through Media3, which knows the per-device quirks). Results are cached. */
@OptIn(UnstableApi::class)
internal class MediaCodecVideoDecoderCheck(private val context: Context) : VideoDecoderCheck {

    private val cache = ConcurrentHashMap<String, DecoderKind>()

    override fun check(codecs: String, width: Int, height: Int, fps: Int): DecoderKind =
        cache.getOrPut("$codecs/$width/$height/$fps") { query(codecs, width, height, fps) }

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
            MediaCodecUtil.getDecoderInfos(mimeType, /* secure= */ false, /* tunneling= */ false)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            return DecoderKind.NONE
        }
        var result = DecoderKind.NONE
        for (decoder in decoders) {
            if (!decoder.isFormatSupported(context, format)) continue
            if (decoder.hardwareAccelerated) return DecoderKind.HARDWARE
            result = DecoderKind.SOFTWARE
        }
        return result
    }
}

internal object VideoCodecs {

    /** "avc1.640028" → "avc1"; YouTube's WebM streams just say "vp9". */
    fun family(codecs: String): String = when (val prefix = codecs.substringBefore('.').lowercase()) {
        "vp09" -> "vp9"
        "avc3" -> "avc1"
        "hev1" -> "hvc1"
        else -> prefix
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
