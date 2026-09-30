package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.newpipe.StreamMapper
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.audio
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.info
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.muxed
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.subtitle
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.videoOnly
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class PlaybackPlannerTest {

    private val allHardware = VideoDecoderCheck { _, _, _, _ -> DecoderKind.HARDWARE }

    private fun planner(check: VideoDecoderCheck = allHardware) = PlaybackPlanner(check)

    private fun manifestOf(part: MediaPart): DashManifest =
        DashManifestParser().parse(Uri.parse("shasha-manifest://t"), (part as MediaPart.Dash).manifest.byteInputStream())

    private fun DashManifest.representationIds(type: Int) =
        getPeriod(0).adaptationSets.filter { it.type == type }.flatMap { set -> set.representations.map { it.format.id } }

    @Test
    fun vodIsOneManifestWithEveryDecodableQualityUpToTheCap() {
        val plan = planner().plan(info(), maxHeight = 1080, subtitleLanguage = null)

        assertThat(plan.parts).hasSize(1)
        assertThat(plan.isLive).isFalse()
        val manifest = manifestOf(plan.parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO))
            .containsExactly("v137", "v136", "v135", "v160", "v248", "v247", "v244")
        assertThat(manifest.representationIds(C.TRACK_TYPE_AUDIO)).containsExactly("a140", "a139", "a251", "a250")
        assertThat(manifest.durationMs).isEqualTo(PlaybackFixtures.DURATION_MS)
        // mp4/avc1 and webm/vp9 cannot share an adaptation set; ExoPlayer adapts inside one of them.
        val videoSets = manifest.getPeriod(0).adaptationSets.filter { it.type == C.TRACK_TYPE_VIDEO }
        assertThat(videoSets.map { set -> set.representations.map { it.format.containerMimeType }.distinct() })
            .containsExactly(listOf(MimeTypes.VIDEO_MP4), listOf(MimeTypes.VIDEO_WEBM))
    }

    @Test
    fun noCapKeepsTheWholeLadder() {
        val manifest = manifestOf(planner().plan(info(), maxHeight = 0, subtitleLanguage = null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsAtLeast("v313", "v271", "v137")
    }

    @Test
    fun capBelowTheLadderKeepsTheLowestQuality() {
        val manifest = manifestOf(planner().plan(info(), maxHeight = 100, subtitleLanguage = null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v160")
    }

    @Test
    fun verticalVideosAreCappedByTheirShortSide() {
        val vertical = listOf(
            videoOnly(137, "avc1.640028", 1080, 1920),
            videoOnly(271, "vp9", 1440, 2560),
            videoOnly(136, "avc1.4d401f", 720, 1280),
        )
        val manifest = manifestOf(planner().plan(info(videoOnly = vertical), 1080, null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v137", "v136")
    }

    @Test
    fun onlyHardwareDecodableCodecsWhenTheDeviceHasThem() {
        // A typical TV: H.264 in hardware, VP9 only in software, AV1 in software only.
        val tv = VideoDecoderCheck { codecs, _, _, _ ->
            if (codecs.startsWith("avc1")) DecoderKind.HARDWARE else DecoderKind.SOFTWARE
        }
        val ladder = PlaybackFixtures.standardLadder() + videoOnly(399, "av01.0.08M.08", 1920, 1080)
        val manifest = manifestOf(planner(tv).plan(info(videoOnly = ladder), 1080, null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v137", "v136", "v135", "v160")
    }

    @Test
    fun av1IsUsedOnlyWithAHardwareDecoder() {
        val ladder = listOf(videoOnly(399, "av01.0.08M.08", 1920, 1080), videoOnly(248, "vp9", 1920, 1080))
        val softwareAv1 = VideoDecoderCheck { _, _, _, _ -> DecoderKind.SOFTWARE }
        val soft = manifestOf(planner(softwareAv1).plan(info(videoOnly = ladder), 1080, null).parts.single())
        assertThat(soft.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v248")

        val hardwareAv1 = manifestOf(planner().plan(info(videoOnly = ladder), 1080, null).parts.single())
        assertThat(hardwareAv1.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v399", "v248")
    }

    @Test
    fun h264IsTheLastResortWhenTheDeviceReportsNoDecoder() {
        val nothing = VideoDecoderCheck { _, _, _, _ -> DecoderKind.NONE }
        val manifest = manifestOf(planner(nothing).plan(info(), 1080, null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v137", "v136", "v135", "v160")
    }

    @Test
    fun hdrRepresentationsAreDropped() {
        val ladder = listOf(videoOnly(248, "vp09.02.51.10.01.09.16.09.00", 1920, 1080), videoOnly(247, "vp9", 1280, 720))
        val manifest = manifestOf(planner().plan(info(videoOnly = ladder), 1080, null).parts.single())
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).containsExactly("v247")
        assertThat(VideoCodecs.isHdr("av01.0.12M.10.0.110.09.16.09.0")).isTrue()
        assertThat(VideoCodecs.isHdr("av01.0.08M.08")).isFalse()
        assertThat(VideoCodecs.isHdr("vp09.00.40.08")).isFalse()
    }

    @Test
    fun originalAudioTrackIsChosenAmongDubs() {
        val audio = listOf(
            audio(140, "mp4a.40.2", 130_000, trackId = "de.3", type = AudioTrackType.DUBBED),
            audio(251, "opus", 150_000, trackId = "de.3", type = AudioTrackType.DUBBED),
            audio(140, "mp4a.40.2", 130_000, trackId = "en.4", type = AudioTrackType.ORIGINAL),
            audio(251, "opus", 150_000, trackId = "en.4", type = AudioTrackType.ORIGINAL),
        )
        val chosen = planner().preferredAudio(audio)
        assertThat(chosen.map { it.audioTrackId }).containsExactly("en.4", "en.4")
        assertThat(chosen.map { it.id }).containsExactly("251", "140").inOrder() // best bitrate first

        val manifest = manifestOf(planner().plan(info(audio = audio), 1080, null).parts.single())
        val audioSets = manifest.getPeriod(0).adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
        assertThat(audioSets.flatMap { set -> set.representations.map { it.format.language } }.distinct()).containsExactly("en")
    }

    @Test
    fun drcVariantsAreSkippedWhenNormalOnesExist() {
        val audio = listOf(audio(140, "mp4a.40.2", 130_000, drc = true), audio(251, "opus", 150_000, drc = true), audio(140, "mp4a.40.2", 129_000))
        assertThat(planner().preferredAudio(audio).map { it.content }).containsExactly(audio[2].content)
        assertThat(planner().preferredAudio(audio.take(2))).hasSize(2)
    }

    @Test
    fun videoWithoutIndexRangesIsPlayedProgressivelyNextToTheManifestAudio() {
        val ladder = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false))
        val plan = planner().plan(info(videoOnly = ladder), 1080, null)

        assertThat(plan.parts).hasSize(2)
        val manifest = manifestOf(plan.parts[0])
        assertThat(manifest.representationIds(C.TRACK_TYPE_VIDEO)).isEmpty()
        assertThat(manifest.representationIds(C.TRACK_TYPE_AUDIO)).isNotEmpty()
        // NPE's progressive manifest creator needs the ranges too, so this is a plain progressive read.
        assertThat(plan.parts[1]).isEqualTo(MediaPart.Progressive(PlaybackFixtures.url(137), "progressive 137"))
    }

    @Test
    fun muxedAbove360IsTheLastResortWithoutSeparateAudio() {
        val plan = planner().plan(info(videoOnly = emptyList(), muxed = listOf(muxed(22, 720), muxed(18, 360))), 1080, null)
        assertThat(plan.parts).containsExactly(MediaPart.Progressive(PlaybackFixtures.url(22), "muxed 720p"))
        // The quality cap still applies among muxed files.
        val capped = planner().plan(info(videoOnly = emptyList(), muxed = listOf(muxed(22, 720), muxed(18, 360))), 480, null)
        assertThat(capped.parts).containsExactly(MediaPart.Progressive(PlaybackFixtures.url(18), "muxed 360p"))
    }

    @Test
    fun muxed360AloneGoesToTheErrorScreenInsteadOfPlayingSilentlyAt360p() {
        val e = assertThrows(SourceException::class.java) {
            planner().plan(info(videoOnly = emptyList(), muxed = listOf(muxed(18, 360))), 1080, null)
        }
        assertThat(e.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(e.technicalName).isEqualTo("MuxedOnly360p")
    }

    @Test
    fun muxed360PlaysOnlyWhenLowQualityIsAllowed() {
        val kids = info(videoOnly = emptyList(), audio = emptyList(), muxed = listOf(muxed(18, 360)))
        assertThat(planner().plan(kids, 1080, null, allowLowQuality = true).parts)
            .containsExactly(MediaPart.Progressive(PlaybackFixtures.url(18), "muxed 360p"))
        val refused = assertThrows(SourceException::class.java) { planner().plan(kids, 1080, null, allowLowQuality = false) }
        assertThat(refused.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        // Allowed or not, a video with its ladder plays the ladder.
        assertThat(planner().plan(info(), 1080, null, allowLowQuality = true).parts.single()).isInstanceOf(MediaPart.Dash::class.java)
    }

    /**
     * The owner's decision for "made for kids" videos: VideoSource.stream(allowLowQuality = true) turns the muxed-only
     * shape into a playable handle, marked with its height; asked without it, the same answer is LOW_QUALITY_ONLY, and
     * a video with a ladder is never marked.
     */
    @Test
    fun muxedOnlyShapesGetAPlayableHandleOnlyWhenAllowed() {
        val kids = info(videoOnly = emptyList(), audio = emptyList(), muxed = listOf(muxed(18, 360))).apply { uploaderUrl = PlaybackFixtures.CHANNEL_URL }
        val refused = assertThrows(SourceException::class.java) { StreamMapper.toDetails(kids) }
        assertThat(refused.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(refused.technicalName).isEqualTo("MuxedOnly360p")

        val allowed = StreamMapper.toDetails(kids, allowLowQuality = true)
        assertThat(allowed.lowQualityHeight).isEqualTo(360)
        assertThat(allowed.playback.allowLowQuality).isTrue()
        assertThat(allowed.videoTracks.map { it.height }).containsExactly(360)
        assertThat(planner().plan(allowed.playback.info, 1080, null, allowed.playback.allowLowQuality).parts)
            .containsExactly(MediaPart.Progressive(PlaybackFixtures.url(18), "muxed 360p"))

        val normal = StreamMapper.toDetails(info().apply { uploaderUrl = PlaybackFixtures.CHANNEL_URL }, allowLowQuality = true)
        assertThat(normal.lowQualityHeight).isNull()
        assertThat(normal.playback.allowLowQuality).isFalse()
        // Broken stays broken: nothing to play is not "low quality".
        val empty = info(videoOnly = emptyList(), audio = emptyList(), muxed = emptyList()).apply { uploaderUrl = PlaybackFixtures.CHANNEL_URL }
        assertThat(assertThrows(SourceException::class.java) { StreamMapper.toDetails(empty, allowLowQuality = true) }.kind)
            .isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
    }

    @Test
    fun hlsIsPreferredOverMuxedWhenThereIsNoAdaptiveVideo() {
        // E.g. visionOS formats turned SABR-only (no URLs) while its HLS manifest stays.
        val hls = "https://manifest.googlevideo.com/api/manifest/hls_variant/expire/1790655988/x"
        listOf(listOf(muxed(18, 360)), listOf(muxed(22, 720), muxed(18, 360))).forEach { muxed ->
            val plan = planner().plan(info(videoOnly = emptyList(), audio = emptyList(), muxed = muxed, hls = hls), 1080, null)
            assertThat(plan.parts).containsExactly(MediaPart.Hls(hls, live = false))
        }
    }

    @Test
    fun videoWithoutAudioIsNeverPlayedMute() {
        // ANDROID's muxed itag 18 is always there, but it is not an audio source for the adaptive video.
        val e = assertThrows(SourceException::class.java) {
            planner().plan(info(audio = emptyList(), muxed = listOf(muxed(18, 360))), 1080, null)
        }
        assertThat(e.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(e.technicalName).isEqualTo("NoAudioStreams")

        // HLS carries the sound: it replaces the silent ladder.
        val hls = "https://manifest.googlevideo.com/api/manifest/hls_variant/x"
        assertThat(planner().plan(info(audio = emptyList(), hls = hls), 1080, null).parts)
            .containsExactly(MediaPart.Hls(hls, live = false))

        val postLive = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false, postLive = true))
        val ended = assertThrows(SourceException::class.java) {
            planner().plan(info(videoOnly = postLive, audio = emptyList(), type = StreamType.POST_LIVE_STREAM), 1080, null)
        }
        assertThat(ended.technicalName).isEqualTo("NoAudioStreams")
    }

    /**
     * StreamMapper.checkPlayable decides at extraction time (e.g. for the diagnostics screen) what the
     * planner decides when the player opens: for every shape of streams, both accept, or both reject
     * with the same error. And whatever is played has sound and is more than a muxed 360p file, unless
     * low quality was allowed and the details say so (StreamDetails.lowQualityHeight: the app shows it).
     */
    @Test
    fun extractionCheckAndPlannerAgreeOnEveryShape() {
        val hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/x"
        val shapes = buildList {
            for (ladder in listOf(emptyList(), PlaybackFixtures.standardLadder()))
                for (audio in listOf(emptyList(), PlaybackFixtures.standardAudio()))
                    for (muxed in listOf(emptyList(), listOf(muxed(18, 360)), listOf(muxed(22, 720), muxed(18, 360))))
                        for (hls in listOf("", hlsUrl)) add(info(videoOnly = ladder, audio = audio, muxed = muxed, hls = hls))
            for (hls in listOf("", "https://h/m.m3u8")) for (dash in listOf("", "https://d/m.mpd")) {
                add(info(type = StreamType.LIVE_STREAM, hls = hls, dash = dash))
            }
        }
        for (info in shapes) for (allow in listOf(false, true)) {
            val shape = "${info.streamType} adaptive=${info.videoOnlyStreams.size} audio=${info.audioStreams.size} " +
                "muxed=${info.videoStreams.map { it.height }} hls=${info.hlsUrl.isNotEmpty()} dash=${info.dashMpdUrl.isNotEmpty()} allowLowQuality=$allow"
            val summary = StreamMapper.summaryOf(info)
            val verdict = StreamMapper.checkPlayable(summary, allow)
            // What StreamMapper.toDetails gives the handle: the permission only for a result that is low quality.
            val marked = allow && StreamMapper.lowQualityHeight(summary) != null
            val planned = runCatching { planner().plan(info, 1080, null, allowLowQuality = marked) }
            assertWithMessage(shape).that(planned.isSuccess).isEqualTo(verdict == null)
            if (verdict != null) {
                val error = planned.exceptionOrNull() as SourceException
                assertWithMessage(shape).that(error.kind).isEqualTo(verdict.kind)
                assertWithMessage(shape).that(error.technicalName).isEqualTo(verdict.technicalName)
                continue
            }
            val parts = planned.getOrThrow().parts
            val muxed360 = parts.any { it is MediaPart.Progressive && it.kind == "muxed 360p" }
            assertWithMessage("$shape played $parts").that(muxed360).isEqualTo(marked)
            val dash = parts.filterIsInstance<MediaPart.Dash>().map(::manifestOf)
            if (dash.isNotEmpty()) {
                assertWithMessage("$shape: video has sound").that(dash.flatMap { it.representationIds(C.TRACK_TYPE_AUDIO) }).isNotEmpty()
            }
        }
    }

    @Test
    fun otfStreamsGetLazyManifests() {
        val ladder = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false, otf = true))
        val audio = listOf(audio(140, "mp4a.40.2", 130_000, ranges = false).let {
            // OTF audio: DASH delivery, not a URL.
            org.schabi.newpipe.extractor.stream.AudioStream.Builder()
                .setId("140").setContent(it.content, false).setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(128).setItagItem(it.itagItem).setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.DASH)
                .build()
        })
        val plan = planner().plan(info(videoOnly = ladder, audio = audio), 1080, null)
        assertThat(plan.parts.map { (it as MediaPart.LazyDash).kind }).containsExactly("otf 137", "otf 140").inOrder()
    }

    @Test
    fun postLiveUsesLazyDvrManifests() {
        val ladder = listOf(videoOnly(137, "avc1.640028", 1920, 1080, ranges = false, postLive = true))
        val audio = listOf(audio(140, "mp4a.40.2", 130_000, ranges = false))
        val plan = planner().plan(info(videoOnly = ladder, audio = audio, type = StreamType.POST_LIVE_STREAM), 1080, null)
        assertThat(plan.parts.map { (it as MediaPart.LazyDash).kind }).containsExactly("post-live 137", "post-live 140").inOrder()
    }

    @Test
    fun nothingPlayableFallsBackToHlsThenFails() {
        val empty = info(videoOnly = emptyList(), muxed = emptyList(), hls = "https://manifest.googlevideo.com/api/manifest/hls_variant/x")
        assertThat(planner().plan(empty, 1080, null).parts)
            .containsExactly(MediaPart.Hls("https://manifest.googlevideo.com/api/manifest/hls_variant/x", live = false))

        val e = assertThrows(SourceException::class.java) {
            planner().plan(info(videoOnly = emptyList(), muxed = emptyList()), 1080, null)
        }
        assertThat(e.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(e.technicalName).isEqualTo("NoPlayableStreams")
    }

    @Test
    fun livePrefersHlsThenDash() {
        val hls = planner().plan(info(type = StreamType.LIVE_STREAM, hls = "https://h/m.m3u8", dash = "https://d/m.mpd"), 1080, "en")
        assertThat(hls.parts).containsExactly(MediaPart.Hls("https://h/m.m3u8", live = true))
        assertThat(hls.isLive).isTrue()
        assertThat(hls.subtitle).isNull()

        val dash = planner().plan(info(type = StreamType.LIVE_STREAM, dash = "https://d/m.mpd"), 1080, null)
        assertThat(dash.parts).containsExactly(MediaPart.LiveDash("https://d/m.mpd"))

        val e = assertThrows(SourceException::class.java) { planner().plan(info(type = StreamType.LIVE_STREAM), 1080, null) }
        assertThat(e.technicalName).isEqualTo("NoLiveManifest")
    }

    @Test
    fun subtitlesPreferHumanMadeThenRegionalMatch() {
        val subs = listOf(subtitle("en", auto = true), subtitle("en", auto = false), subtitle("de-DE", auto = false), subtitle("fr", auto = false, MediaFormat.TRANSCRIPT3))
        val info = info(subtitles = subs)

        val en = planner().plan(info, 1080, "en").subtitle!!
        assertThat(en.url).isEqualTo(subs[1].content)
        assertThat(en.mimeType).isEqualTo(MimeTypes.APPLICATION_TTML)
        assertThat(en.language).isEqualTo("en")

        assertThat(planner().plan(info, 1080, "de").subtitle!!.language).isEqualTo("de-DE")
        assertThat(planner().plan(info, 1080, "fr").subtitle).isNull() // srv3 is not a format Media3 parses
        assertThat(planner().plan(info, 1080, "ar").subtitle).isNull()
        assertThat(planner().plan(info, 1080, null).subtitle).isNull()
    }
}
