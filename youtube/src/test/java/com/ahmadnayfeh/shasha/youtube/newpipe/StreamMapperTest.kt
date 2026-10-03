package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.newpipe.StreamMapper.StreamSummary
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.stream.StreamInfo

class StreamMapperTest {

    /** A playable extraction of a video of «Rick Astley», whose page lists [avatars] for the uploader. */
    private fun extraction(avatars: List<Image>): StreamInfo = PlaybackFixtures.info().apply {
        uploaderUrl = PlaybackFixtures.CHANNEL_URL
        uploaderName = "Rick Astley"
        uploaderAvatars = avatars
    }

    private fun avatar(width: Int) = Image("https://yt3.ggpht.com/uploader=s$width", width, width, Image.ResolutionLevel.UNKNOWN)

    private fun vod(adaptive: List<Int> = emptyList(), muxed: List<Int> = emptyList(), audio: Boolean = true, hls: Boolean = false) =
        StreamSummary(isLive = false, hasHls = hls, hasDash = false, adaptiveHeights = adaptive, muxedHeights = muxed, hasAudio = audio)

    @Test
    fun normalVideoWithFullLadderIsPlayable() {
        assertThat(StreamMapper.checkPlayable(vod(adaptive = listOf(2160, 1080, 720, 360, 144), muxed = listOf(360)))).isNull()
    }

    @Test
    fun ladderThatTopsOutAt360IsFine() {
        assertThat(StreamMapper.checkPlayable(vod(adaptive = listOf(360, 240, 144), muxed = listOf(360)))).isNull()
    }

    @Test
    fun onlyMuxed360WithoutAdaptiveIsLowQualityOnly() {
        val error = StreamMapper.checkPlayable(vod(muxed = listOf(360)))
        assertThat(error?.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(error?.technicalName).isEqualTo("MuxedOnly360p")
        assertThat(StreamMapper.checkPlayable(vod(muxed = listOf(144, 240)))?.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
    }

    @Test
    fun onlyMuxed360IsPlayableWhenLowQualityIsAllowed() {
        val kids = vod(muxed = listOf(360, 240))
        assertThat(StreamMapper.checkPlayable(kids, allowLowQuality = true)).isNull()
        assertThat(StreamMapper.lowQualityHeight(kids)).isEqualTo(360)
        assertThat(StreamMapper.lowQualityHeight(vod(muxed = listOf(144)))).isEqualTo(144)
        // Allowing low quality changes nothing else: a broken extraction stays broken.
        assertThat(StreamMapper.checkPlayable(vod(), allowLowQuality = true)?.technicalName).isEqualTo("NoPlayableStreams")
        val mute = StreamMapper.checkPlayable(vod(adaptive = listOf(1080), muxed = listOf(360), audio = false), allowLowQuality = true)
        assertThat(mute?.technicalName).isEqualTo("NoAudioStreams")
    }

    @Test
    fun onlyTheMuxedOnlyShapeIsLowQuality() {
        assertThat(StreamMapper.lowQualityHeight(vod(adaptive = listOf(1080, 360), muxed = listOf(360)))).isNull()
        assertThat(StreamMapper.lowQualityHeight(vod(adaptive = listOf(360, 240), muxed = listOf(360)))).isNull()
        assertThat(StreamMapper.lowQualityHeight(vod(muxed = listOf(360), hls = true))).isNull()
        assertThat(StreamMapper.lowQualityHeight(vod(muxed = listOf(720, 360)))).isNull()
        assertThat(StreamMapper.lowQualityHeight(vod())).isNull()
        val live = StreamSummary(isLive = true, hasHls = true, hasDash = false, adaptiveHeights = emptyList(), muxedHeights = listOf(360), hasAudio = true)
        assertThat(StreamMapper.lowQualityHeight(live)).isNull()
    }

    @Test
    fun muxedAbove360IsAcceptedAsLastResort() {
        assertThat(StreamMapper.checkPlayable(vod(muxed = listOf(720, 360)))).isNull()
    }

    @Test
    fun hlsForVodMeansNotOnlyMuxed() {
        // The planner plays the HLS manifest (the whole ladder) instead of the 360p muxed file.
        assertThat(StreamMapper.checkPlayable(vod(muxed = listOf(360), hls = true))).isNull()
    }

    @Test
    fun nothingPlayableIsBroken() {
        assertThat(StreamMapper.checkPlayable(vod())?.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        val noAudio = StreamMapper.checkPlayable(vod(adaptive = listOf(1080), audio = false))
        assertThat(noAudio?.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(noAudio?.technicalName).isEqualTo("NoAudioStreams")
    }

    @Test
    fun videoWithoutAudioIsBrokenEvenNextToMuxedStreams() {
        // ANDROID always adds muxed itag 18, but the player never takes the sound from it: a mute picture.
        val error = StreamMapper.checkPlayable(vod(adaptive = listOf(1080, 720), muxed = listOf(360), audio = false))
        assertThat(error?.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(error?.technicalName).isEqualTo("NoAudioStreams")
        // HLS carries sound: the planner plays it instead.
        assertThat(StreamMapper.checkPlayable(vod(adaptive = listOf(1080), muxed = listOf(360), audio = false, hls = true))).isNull()
    }

    @Test
    fun liveNeedsAManifest() {
        val live = StreamSummary(isLive = true, hasHls = false, hasDash = false, adaptiveHeights = listOf(1080), muxedHeights = emptyList(), hasAudio = true)
        assertThat(StreamMapper.checkPlayable(live)?.technicalName).isEqualTo("NoLiveManifest")
        assertThat(StreamMapper.checkPlayable(live.copy(hasHls = true))).isNull()
        assertThat(StreamMapper.checkPlayable(live.copy(hasDash = true))).isNull()
    }

    @Test
    fun subtitleLabelsAreArabic() {
        assertThat(StreamMapper.languageLabel("ar", null)).isEqualTo("العربية")
        assertThat(StreamMapper.languageLabel("en", "English")).isEqualTo("الإنجليزية")
        assertThat(StreamMapper.languageLabel("zzz", "Klingon")).isEqualTo("Klingon")
    }

    @Test
    fun htmlDescriptionsBecomePlainText() {
        val html = "Line one<br>Line <a href=\"https://x\">two</a> &amp; three<br/>&quot;quoted&quot; &#39;single&#39; &#x1F600; &amp;lt;"
        assertThat(HtmlText.toPlain(html)).isEqualTo("Line one\nLine two & three\n\"quoted\" 'single' 😀 &lt;")
    }

    // --- the uploader's picture, from the video's own page ---------------------------------------------------------------

    @Test
    fun theUploadersPictureIsTheOneAChannelsOwnPictureWouldBe() {
        // The smallest one that is wide enough (176 px, as for a channel's avatar), not the biggest.
        val details = StreamMapper.toDetails(extraction(listOf(avatar(48), avatar(176), avatar(900))))

        assertThat(details.uploaderAvatarUrl).isEqualTo("https://yt3.ggpht.com/uploader=s176")
        // The channel the picture belongs to is the video's: the same page names it.
        assertThat(details.video.channelId).isEqualTo("UCuAXFkgsw1L7xaCfnd5JJOw")
        assertThat(details.video.channelName).isEqualTo("Rick Astley")
    }

    @Test
    fun whenNoPictureIsBigEnoughTheLargestIsTaken() {
        val details = StreamMapper.toDetails(extraction(listOf(avatar(48), avatar(88))))

        assertThat(details.uploaderAvatarUrl).isEqualTo("https://yt3.ggpht.com/uploader=s88")
    }

    @Test
    fun aVideoPageWithoutAPictureHasNone() {
        assertThat(StreamMapper.toDetails(extraction(emptyList())).uploaderAvatarUrl).isNull()
    }
}
