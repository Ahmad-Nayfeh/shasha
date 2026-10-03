package com.ahmadnayfeh.shasha.youtube.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
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
        assertThat(factory.create(details(info(videoOnly = emptyList(), muxed = listOf(muxed(22, 720)))), 1080, null))
            .isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(factory.create(details(info(videoOnly = emptyList(), hls = "https://manifest.googlevideo.com/hls_variant.m3u8")), 1080, null))
            .isInstanceOf(HlsMediaSource::class.java)
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
        // Muxed 360p alone: the error screen, never a silent 360p video.
        val lowQuality = assertThrows(SourceException::class.java) {
            factory.create(details(info(videoOnly = emptyList(), muxed = listOf(muxed(18, 360)))), 1080, null)
        }
        assertThat(lowQuality.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
    }

    @Test
    fun aLowQualityStreamExtractedWithPermissionPlaysItsMuxedFile() {
        val kids = info(videoOnly = emptyList(), audio = emptyList(), muxed = listOf(muxed(18, 360)))
        val source = factory.create(details(kids, lowQualityHeight = 360), 1080, null)
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(source.mediaItem.localConfiguration!!.uri.toString()).isEqualTo(PlaybackFixtures.url(18))
        assertThat(source.mediaItem.mediaId).isEqualTo(PlaybackFixtures.VIDEO_ID)
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

    // --- the TV's vertical cap, the safe plan, and what a decoder failed on ----------------------------------------------

    private val shorts get() = details(info(videoOnly = PlaybackFixtures.portraitLadder()))

    /** The video tracks ExoPlayer chooses among for [source], as "codec WxH". */
    private fun videoTracks(source: MediaSource): List<String> {
        var timeline: Timeline? = null
        val meter = DefaultBandwidthMeter.Builder(RuntimeEnvironment.getApplication()).build()
        source.prepareSource({ _, t -> if (!t.isEmpty) timeline = t }, PlayerId.UNSET, meter)
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (timeline == null && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                source.maybeThrowSourceInfoRefreshError()
                Thread.sleep(10)
            }
            val periodId = MediaSource.MediaPeriodId(timeline!!.getUidOfPeriod(0))
            val period = source.createPeriod(periodId, DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), 0)
            var tracks: List<String>? = null
            period.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) {
                        tracks = (0 until mediaPeriod.trackGroups.length).map { mediaPeriod.trackGroups[it] }
                            .filter { it.type == C.TRACK_TYPE_VIDEO }
                            .flatMap { group -> (0 until group.length).map { group.getFormat(it) } }
                            .map { "${it.codecs?.substringBefore('.')} ${it.width}x${it.height}" }
                    }

                    override fun onContinueLoadingRequested(loader: MediaPeriod) = Unit
                },
                0,
            )
            source.releasePeriod(period)
            return checkNotNull(tracks) { "the period was not prepared" }
        } finally {
            source.releaseSource { _, _ -> }
        }
    }

    @Test
    fun theTvsVerticalCapReachesThePlayersTracks_thePhoneKeepsTheFullLadder() {
        val phone = videoTracks(factory.create(shorts, maxHeight = 1080, subtitleLanguage = null))
        assertThat(phone).containsAtLeast("avc1 1080x1920", "vp9 1080x1920", "avc1 720x1280")

        val tv = videoTracks(factory.create(shorts, maxHeight = 1080, subtitleLanguage = null, maxPortraitShortSide = 720))
        assertThat(tv).containsExactly("avc1 720x1280", "vp9 720x1280", "avc1 480x854", "vp9 480x854", "avc1 360x640", "avc1 144x256")
        // A landscape video on the same TV is not cut.
        assertThat(videoTracks(factory.create(details(info()), maxHeight = 1080, subtitleLanguage = null, maxPortraitShortSide = 720)))
            .contains("avc1 1920x1080")
    }

    @Test
    fun theSafePlanReachesThePlayersTracks() {
        val safe = videoTracks(factory.create(shorts, maxHeight = 1080, subtitleLanguage = null, maxPortraitShortSide = 720, safe = true))
        assertThat(safe).containsExactly("avc1 480x854", "avc1 360x640", "avc1 144x256")
        val landscape = videoTracks(factory.create(details(info()), maxHeight = 1080, subtitleLanguage = null, safe = true))
        assertThat(landscape).containsExactly("avc1 1280x720", "avc1 854x480", "avc1 256x144")
    }

    /** What ExoPlayer's renderer throws when the device's decoder fails on [format]. */
    private fun rendererError(format: Format?, code: Int = PlaybackException.ERROR_CODE_DECODING_FAILED) =
        ExoPlaybackException.createForRenderer(IllegalStateException("codec died"), "MediaCodecVideoRenderer", 0, format, C.FORMAT_HANDLED, null, false, code)

    private fun videoFormat(codecs: String?, width: Int, height: Int, mime: String? = MimeTypes.VIDEO_H264) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setWidth(width).setHeight(height).build()

    @Test
    fun aDecoderFailureIsRememberedForEveryVideoThatFollows() {
        val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE })
        factory.recordDecoderFailure(rendererError(videoFormat("avc1.4d401f", 720, 1280)))

        // The next Short starts directly with what works: no H.264 from the picture that failed up, VP9 and the smaller ones stay.
        assertThat(videoTracks(factory.create(shorts, maxHeight = 1080, subtitleLanguage = null)))
            .containsExactly("vp9 1080x1920", "vp9 720x1280", "avc1 480x854", "vp9 480x854", "avc1 360x640", "avc1 144x256")
        // Landscape H.264 is not concerned.
        assertThat(videoTracks(factory.create(details(info()), maxHeight = 1080, subtitleLanguage = null))).contains("avc1 1920x1080")
    }

    @Test
    fun theFailedPictureIsTakenOutOfTheDecoderCache() {
        val forgotten = mutableListOf<String>()
        val check = object : VideoDecoderCheck {
            override fun check(codecs: String, width: Int, height: Int, fps: Int) = DecoderKind.HARDWARE

            override fun forget(codecs: String, width: Int, height: Int) {
                forgotten += "$codecs ${width}x$height"
            }
        }
        YouTubeMediaSourceFactory(OkHttpClient(), check).recordDecoderFailure(rendererError(videoFormat("avc1.4d401f", 720, 1280)))
        assertThat(forgotten).containsExactly("avc1.4d401f 720x1280")
    }

    @Test
    fun theFailedPictureIsTheRenderersFormat_elseTheOneThePlayerWasPlaying() {
        val failures = DecoderFailures()
        val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }, failures)

        factory.recordDecoderFailure(rendererError(videoFormat("avc1.640028", 1080, 1920)), playing = videoFormat("vp09.00.40.08", 720, 1280))
        assertThat(failures.rulesOut("avc1.640028", 1080, 1920)).isTrue()
        assertThat(failures.rulesOut("vp9", 720, 1280)).isFalse()

        // No format on the error (ExoPlayer did not say): the player's current video format.
        factory.recordDecoderFailure(rendererError(null), playing = videoFormat("vp09.00.40.08", 720, 1280))
        assertThat(failures.rulesOut("vp9", 720, 1280)).isTrue()

        // A video format that names no size (the renderer had not seen a picture yet): the one being played says it.
        assertThat(failures.rulesOut("avc1.4d401f", 480, 854)).isFalse()
        factory.recordDecoderFailure(
            rendererError(videoFormat("avc1.4d401f", Format.NO_VALUE, Format.NO_VALUE)),
            playing = videoFormat("avc1.4d401f", 480, 854),
        )
        assertThat(failures.rulesOut("avc1.4d401f", 480, 854)).isTrue()
    }

    @Test
    fun aFormatWithoutACodecStringIsKnownByItsMimeType() {
        val failures = DecoderFailures()
        val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }, failures)
        factory.recordDecoderFailure(rendererError(videoFormat(null, 720, 1280, MimeTypes.VIDEO_VP9)))
        assertThat(failures.rulesOut("vp09.00.40.08", 720, 1280)).isTrue()
    }

    @Test
    fun aDecoderTheSystemTookBackForAnotherAppRemembersNothing() {
        val failures = DecoderFailures()
        val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }, failures)
        // The picture is not to blame (it would play on the next try): leaving it out of every later plan would only cost quality.
        factory.recordDecoderFailure(
            rendererError(videoFormat("avc1.640028", 1920, 1080), PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED),
            playing = videoFormat("avc1.640028", 1920, 1080),
        )
        assertThat(failures.rulesOut("avc1.640028", 1920, 1080)).isFalse()
    }

    @Test
    fun anAudioDecoderOrAnUnknownPictureRemembersNothing() {
        val failures = DecoderFailures()
        val factory = YouTubeMediaSourceFactory(OkHttpClient(), VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }, failures)

        // The audio decoder failed while a picture was on screen (the player hands over its current video format): not the picture's fault.
        factory.recordDecoderFailure(
            rendererError(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs("mp4a.40.2").build()),
            playing = videoFormat("avc1.640028", 1080, 1920),
        )
        factory.recordDecoderFailure(rendererError(null))
        factory.recordDecoderFailure(IllegalStateException("not a renderer error"))
        factory.recordDecoderFailure(rendererError(videoFormat(null, 720, 1280, mime = null))) // says neither codec nor MIME type

        assertThat(failures.rulesOut("avc1.640028", 1080, 1920)).isFalse()
        assertThat(failures.rulesOut("mp4a.40.2", 1080, 1920)).isFalse()
        assertThat(videoTracks(factory.create(shorts, maxHeight = 1080, subtitleLanguage = null))).contains("avc1 1080x1920")
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
