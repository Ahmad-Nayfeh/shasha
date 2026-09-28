package com.ahmadnayfeh.shasha.youtube

import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * Opaque to the app: carries what the playback factory needs to build a Media3 source.
 * Stream URLs inside expire after a few hours; call [VideoSource.stream] again for a fresh handle.
 *
 * @property fallback reserved for an alternative extraction result (e.g. another client) when
 * [info] alone is not playable.
 */
class PlaybackHandle internal constructor(
    internal val info: StreamInfo,
    internal val fallback: Any? = null,
)
