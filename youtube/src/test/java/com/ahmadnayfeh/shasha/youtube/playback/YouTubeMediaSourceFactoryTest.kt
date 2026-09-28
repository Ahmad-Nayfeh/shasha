package com.ahmadnayfeh.shasha.youtube.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.audio
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.details
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.info
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.muxed
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.subtitle
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.videoOnly
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Duration

/** Offline: nothing here reaches the network (manifests are served from memory). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class YouTubeMediaSourceFactoryTest {

    private val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE })

    @Test
    fun vodIsADashSourceWhoseTimelineComesFromTheGeneratedManifest() {
        val source = factory.create(details(info()), maxHeight = 1080, subtitleLanguage = null)

        assertThat(source).isInstanceOf(DashMediaSource::class.java)
        assertThat(source.mediaItem.mediaId).isEqualTo(PlaybackFixtures.VIDEO_ID)
        assertThat(source.mediaItem.mediaMetadata.title.toString()).isEqualTo("Never Gonna Give You Up")
        val (timeline, error) = prepare(source)
        assertThat(error).isNull()
        assertThat(timeline!!.getWindow(0, Timeline.Window()).durationMs).isEqualTo(PlaybackFixtures.DURATION_MS)
        assertThat(timeline.getWindow(0, Timeline.Window()).isSeekable).isTrue()
    }

    @Test
    fun subtitlesAreMergedIn() {
        val source = factory.create(details(info(subtitles = listOf(subtitle("en", auto = false)))), 1080, "en")

        assertThat(source).isInstanceOf(MergingMediaSource::class.java)
        assertThat(source.mediaItem.mediaId).isEqualTo(PlaybackFixtures.VIDEO_ID)
        val (timeline, error) = prepare(source)
        assertThat(error).isNull()
        assertThat(timeline!!.getWindow(0, Timeline.Window()).durationMs).isEqualTo(PlaybackFixtures.DURATION_MS)
    }

    @Test
    fun otherShapes() {
        assertThat(factory.create(details(info(videoOnly = emptyList())), 1080, null))
            .isInstanceOf(ProgressiveMediaSource::class.java)
        val live = factory.create(details(info(type = StreamType.LIVE_STREAM, hls = "https://manifest.googlevideo.com/hls.m3u8")), 1080, null)
        assertThat(live).isInstanceOf(HlsMediaSource::class.java)
        assertThat(live.mediaItem.liveConfiguration.targetOffsetMs).isEqualTo(10_000)
        assertThat(factory.create(details(info(type = StreamType.LIVE_STREAM, dash = "https://manifest.googlevideo.com/d.mpd")), 1080, null))
            .isInstanceOf(DashMediaSource::class.java)
        val separate = factory.create(details(info(videoOnly = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false)))), 1080, null)
        assertThat(separate).isInstanceOf(MergingMediaSource::class.java)
    }

    @Test
    fun nothingPlayableThrowsASourceException() {
        val e = assertThrows(SourceException::class.java) {
            factory.create(details(info(videoOnly = emptyList(), muxed = emptyList())), 1080, null)
        }
        assertThat(e.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
    }

    @Test
    fun a403WhileBuildingAnOtfManifestSurfacesAsBlocked403() {
        val otfAudio = audio(140, "mp4a.40.2", 130_000, ranges = false).let {
            AudioStream.Builder().setId("140").setContent(it.content, false).setMediaFormat(it.format)
                .setItagItem(it.itagItem).setDeliveryMethod(DeliveryMethod.DASH).build()
        }
        val otf = info(
            videoOnly = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false, otf = true)),
            audio = listOf(otfAudio),
            muxed = listOf(muxed()),
        )
        val source = factory.create(details(otf), 1080, null)

        val error = withNewPipeDownloader(answering = 403) { prepare(source, timeoutMs = 30_000).second }

        assertThat(error).isNotNull()
        val classified = factory.classifyPlaybackError(error!!)
        assertThat(classified.kind).isEqualTo(SourceErrorKind.BLOCKED_403)
        assertThat(classified.technicalName).isEqualTo("HTTP 403")
    }

    /** Prepares [source] like ExoPlayer would and waits for a timeline with a known duration or an error. */
    private fun prepare(source: MediaSource, timeoutMs: Long = 10_000): Pair<Timeline?, Throwable?> {
        var timeline: Timeline? = null
        val meter = DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build()
        source.prepareSource(
            { _, t -> if (!t.isEmpty && t.getWindow(0, Timeline.Window()).durationUs != C.TIME_UNSET) timeline = t },
            PlayerId.UNSET,
            meter,
        )
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (timeline == null && System.currentTimeMillis() < deadline) {
                // Media3 schedules load retries with delays on this looper: advance Robolectric's clock.
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                source.maybeThrowSourceInfoRefreshError()
                Thread.sleep(10)
            }
        } catch (e: Exception) {
            return null to e
        } finally {
            source.releaseSource { _, _ -> }
        }
        return timeline to null
    }

    /** NPE's manifest creators use NewPipe's global downloader: swap in a fake and restore the old one. */
    private fun <T> withNewPipeDownloader(answering: Int, block: () -> T): T {
        val previous = NewPipe.getDownloader()
        val localization = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        NewPipe.init(object : Downloader() {
            override fun execute(request: Request) = Response(answering, "Forbidden", emptyMap(), "", request.url())
        })
        try {
            return block()
        } finally {
            NewPipe.init(previous, localization, country)
        }
    }
}
