package com.ahmadnayfeh.shasha.youtube.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.audio
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.info
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.muxed
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.subtitle
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.videoOnly
import com.google.common.truth.Truth.assertThat
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
    fun onlyMuxedStreamsMeansProgressiveMuxedWithoutSeparateAudio() {
        val plan = planner().plan(info(videoOnly = emptyList(), muxed = listOf(muxed(18, 360))), 1080, null)
        assertThat(plan.parts).containsExactly(MediaPart.Progressive(PlaybackFixtures.url(18), "muxed 360p"))
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
        val plan = planner().plan(info(videoOnly = ladder, audio = emptyList(), type = StreamType.POST_LIVE_STREAM), 1080, null)
        assertThat(plan.parts.map { (it as MediaPart.LazyDash).kind }).containsExactly("post-live 137")
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
