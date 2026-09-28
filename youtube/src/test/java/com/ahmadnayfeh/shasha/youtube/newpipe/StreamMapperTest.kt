package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.newpipe.StreamMapper.StreamSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamMapperTest {

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
    fun muxedAbove360IsAcceptedAsLastResort() {
        assertThat(StreamMapper.checkPlayable(vod(muxed = listOf(720, 360)))).isNull()
    }

    @Test
    fun hlsForVodMeansNotOnlyMuxed() {
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
}
