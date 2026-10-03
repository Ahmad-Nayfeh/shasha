package com.ahmadnayfeh.shasha.youtube.download

import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.audio
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures.videoOnly
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamType

class DownloadOptionsTest {

    private fun options(
        videos: List<org.schabi.newpipe.extractor.stream.VideoStream> = PlaybackFixtures.standardLadder(),
        audios: List<org.schabi.newpipe.extractor.stream.AudioStream> = PlaybackFixtures.standardAudio(),
        type: StreamType = StreamType.VIDEO_STREAM,
    ) = DownloadPlanner.options(PlaybackFixtures.info(videoOnly = videos, audio = audios, type = type))

    @Test
    fun oneH264Mp4PerQualityBestFirst() {
        val result = options()

        // The ladder also has VP9 (WebM) at every height and 1440p/2160p in VP9 only: none of those is offered.
        assertThat(result.videos.map { it.height }).containsExactly(1080, 720, 480, 144).inOrder()
        assertThat(result.videos.map { it.video.mimeType }.distinct()).containsExactly("video/mp4")
        assertThat(result.videos.all { it.video.codecs.startsWith("avc1") }).isTrue()
        assertThat(result.videos.map { it.video.itag }).containsExactly(137, 136, 135, 160).inOrder()
    }

    @Test
    fun audioIsTheBestM4aNeverOpus() {
        val result = options()

        assertThat(result.audio!!.mimeType).isEqualTo("audio/mp4")
        assertThat(result.audio!!.codecs).isEqualTo("mp4a.40.2")
        assertThat(result.audio!!.itag).isEqualTo(140)
        // Every video option carries that same audio.
        assertThat(result.videos.map { it.audio.itag }.distinct()).containsExactly(140)
    }

    @Test
    fun withoutM4aThereIsNothingToMergeAndNoVideoOption() {
        val result = options(audios = listOf(audio(251, "opus", 150_000), audio(250, "opus", 80_000)))

        assertThat(result.audio).isNull()
        assertThat(result.videos).isEmpty()
        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun withoutH264ThereIsOnlyAudio() {
        val result = options(videos = listOf(videoOnly(248, "vp9", 1920, 1080), videoOnly(247, "vp9", 1280, 720)))

        assertThat(result.videos).isEmpty()
        assertThat(result.audio).isNotNull()
        assertThat(result.isEmpty).isFalse()
    }

    @Test
    fun theHigherFrameRateWinsAtTheSameQuality() {
        val ladder = PlaybackFixtures.standardLadder() + videoOnly(299, "avc1.64002a", 1920, 1080, fps = 60, bitrate = 7_000_000)

        val result = options(videos = ladder)

        val best = result.videos.first()
        assertThat(best.height).isEqualTo(1080)
        assertThat(best.fps).isEqualTo(60)
        assertThat(best.video.itag).isEqualTo(299)
        assertThat(result.videos.count { it.height == 1080 }).isEqualTo(1)
    }

    @Test
    fun aVerticalVideoIsLabelledByItsShortSide() {
        val result = options(videos = listOf(videoOnly(137, "avc1.640028", width = 1080, height = 1920)))

        assertThat(result.videos.single().height).isEqualTo(1080)
    }

    @Test
    fun theOriginalAudioTrackIsChosenAmongDubs() {
        val dubs = listOf(
            audio(140, "mp4a.40.2", 130_000, trackId = "es.3", type = AudioTrackType.DUBBED),
            audio(140, "mp4a.40.2", 129_000, trackId = "en.4", type = AudioTrackType.ORIGINAL),
            audio(140, "mp4a.40.2", 131_000, trackId = "fr.3", type = AudioTrackType.DUBBED),
        )

        val picked = DownloadPlanner.preferredAudio(dubs)

        assertThat(picked.map { it.audioTrackId }.distinct()).containsExactly("en.4")
    }

    @Test
    fun aLiveStreamHasNoDownloadOptions() {
        val result = options(type = StreamType.LIVE_STREAM)

        assertThat(result.isEmpty).isTrue()
    }

    @Test
    fun videoForPicksTheBestAtOrBelowTheRequestElseTheLowest() {
        val result = options()

        assertThat(result.videoFor(1080)!!.height).isEqualTo(1080)
        assertThat(result.videoFor(900)!!.height).isEqualTo(720)
        assertThat(result.videoFor(720)!!.height).isEqualTo(720)
        assertThat(result.videoFor(400)!!.height).isEqualTo(144)
        assertThat(result.videoFor(100)!!.height).isEqualTo(144) // nothing that low: the lowest there is
        assertThat(options(videos = emptyList()).videoFor(720)).isNull()
    }

    @Test
    fun theSizeIsTheSumOfBothFilesOrUnknown() {
        val known = VideoDownloadOption(
            720, 30,
            DownloadStream("https://x/v", "video/mp4", "avc1", 1, 9_000, 136),
            DownloadStream("https://x/a", "audio/mp4", "mp4a.40.2", 1, 1_000, 140),
        )
        val unknown = known.copy(audio = known.audio.copy(sizeBytes = null))

        assertThat(known.sizeBytes).isEqualTo(10_000)
        assertThat(unknown.sizeBytes).isNull()
    }

    @Test
    fun theLengthComesFromTheClenParameter() {
        assertThat(DownloadPlanner.contentLengthOf("https://r1.googlevideo.com/videoplayback?itag=140&clen=3456789&c=ANDROID")).isEqualTo(3_456_789L)
        assertThat(DownloadPlanner.contentLengthOf("https://r1.googlevideo.com/videoplayback?itag=140")).isNull()
        assertThat(DownloadPlanner.contentLengthOf("https://r1.googlevideo.com/videoplayback?clen=abc")).isNull()
        assertThat(DownloadPlanner.contentLengthOf("not a url")).isNull()
    }
}
