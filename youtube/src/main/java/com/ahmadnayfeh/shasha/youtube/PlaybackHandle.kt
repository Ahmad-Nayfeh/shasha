package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.playback.StreamExpiry
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Opaque to the app: carries what the playback factory needs to build a Media3 source.
 * Stream URLs inside expire after a few hours; call [VideoSource.stream] again for a fresh handle.
 *
 * @property fallback reserved for an alternative extraction result (e.g. another client) when
 * [info] alone is not playable.
 * @property allowLowQuality the planner may play a muxed file of 360p or less (StreamDetails.lowQualityHeight
 * says so); otherwise such a result is LOW_QUALITY_ONLY.
 */
class PlaybackHandle internal constructor(
    internal val info: StreamInfo,
    internal val fallback: Any? = null,
    internal val allowLowQuality: Boolean = false,
) {
    /**
     * When the stream URLs stop working (epoch ms, from googlevideo's `expire` parameter, about 6 h after
     * extraction), or null when the URLs do not say. After it, googlevideo answers HTTP 403: extract again
     * (a new handle) before starting a player close to this time.
     */
    val expiresAtEpochMs: Long? by lazy(LazyThreadSafetyMode.PUBLICATION) { StreamExpiry.of(info) }
}
