package com.ahmadnayfeh.shasha.youtube.playback

import com.ahmadnayfeh.shasha.youtube.PlaybackHandle
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.info
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.subtitle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.stream.StreamType

class StreamExpiryTest {

    @Test
    fun expireIsReadFromQueryAndPath() {
        assertThat(StreamExpiry.expiryOf("https://rr1---sn-a.googlevideo.com/videoplayback?expire=1790655850&ei=x&itag=137"))
            .isEqualTo(1_790_655_850_000L)
        assertThat(StreamExpiry.expiryOf("https://rr1---sn-a.googlevideo.com/videoplayback?ei=x&expire=1790655850"))
            .isEqualTo(1_790_655_850_000L)
        assertThat(StreamExpiry.expiryOf("https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1790655988/ei/abc/file/index.m3u8"))
            .isEqualTo(1_790_655_988_000L)
        assertThat(StreamExpiry.expiryOf("https://www.youtube.com/api/timedtext?v=x&expire=1790650000&lang=en")).isEqualTo(1_790_650_000_000L)
    }

    @Test
    fun urlsWithoutExpiryOrWithLookalikesSayNothing() {
        assertThat(StreamExpiry.expiryOf("https://www.youtube.com/api/timedtext?v=x&lang=en")).isNull()
        assertThat(StreamExpiry.expiryOf("https://h/m.m3u8?notexpire=1790655850")).isNull()
        assertThat(StreamExpiry.expiryOf("https://h/m.m3u8?expire=soon")).isNull()
        assertThat(StreamExpiry.expiryOf("https://h/expired/1790655850/x")).isNull()
    }

    @Test
    fun theEarliestUrlWins() {
        // The fixtures' stream URLs say expire=1 (1970): real ones are hours ahead.
        val streams = info()
        assertThat(PlaybackHandle(streams).expiresAtEpochMs).isEqualTo(1_000L)

        val live = info(
            videoOnly = emptyList(), audio = emptyList(), muxed = emptyList(), type = StreamType.LIVE_STREAM,
            hls = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1790655988/x/index.m3u8",
            dash = "https://manifest.googlevideo.com/api/manifest/dash/expire/1790655000/x",
        )
        assertThat(PlaybackHandle(live).expiresAtEpochMs).isEqualTo(1_790_655_000_000L)

        val withoutExpiry = info(videoOnly = emptyList(), audio = emptyList(), muxed = emptyList(), subtitles = listOf(subtitle("en", auto = false)))
        assertThat(PlaybackHandle(withoutExpiry).expiresAtEpochMs).isNull()
    }
}
