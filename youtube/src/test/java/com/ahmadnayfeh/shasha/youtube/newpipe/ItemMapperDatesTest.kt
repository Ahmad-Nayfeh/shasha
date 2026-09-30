package com.ahmadnayfeh.shasha.youtube.newpipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.time.Instant

/** The home feed treats rounded list-page dates differently from RSS's exact ones: the flag says which is which. */
class ItemMapperDatesTest {
    private fun item(textual: String, date: Instant, approximation: Boolean) =
        StreamInfoItem(0, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "Title", StreamType.VIDEO_STREAM).apply {
            textualUploadDate = textual
            uploadDate = DateWrapper(date, approximation)
        }

    @Test
    fun listPageDates_areApproximate() {
        val twoWeeks = Instant.parse("2026-09-15T00:00:00Z")
        val video = ItemMapper.toVideo(item("2 weeks ago", twoWeeks, approximation = true))!!
        assertThat(video.publishedAtEpochMs).isEqualTo(twoWeeks.toEpochMilli())
        assertThat(video.publishedAtIsApproximate).isTrue()
        // "5 hours ago" is not flagged by NewPipe, yet it is rounded too (up to an hour).
        assertThat(ItemMapper.toVideo(item("5 hours ago", twoWeeks, approximation = false))!!.publishedAtIsApproximate).isTrue()
    }

    @Test
    fun rssDates_areExact() {
        val published = Instant.parse("2026-09-27T08:30:00Z")
        val video = ItemMapper.toVideo(item("2026-09-27T08:30:00+00:00", published, approximation = false), exactDates = true)!!
        assertThat(video.publishedAtEpochMs).isEqualTo(published.toEpochMilli())
        assertThat(video.publishedAtIsApproximate).isFalse()
    }
}
