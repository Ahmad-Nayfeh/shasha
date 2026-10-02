package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.MAX_RELATED
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertThrows
import org.junit.Test
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.CopyOnWriteArrayList

/**
 * «الفيديوهات المقترحة» from the extractor's related items: what is kept of them, and that the player's own extraction
 * answers [com.ahmadnayfeh.shasha.youtube.VideoSource.related] without a second request. No network: the requests the
 * source would make for a watch page go through a counting stand-in.
 */
class RelatedVideosTest {

    private fun stream(id: String, channel: String? = "UCrelatedrelatedrelated0", duration: Long = 300, url: String = "https://www.youtube.com/watch?v=$id") =
        StreamInfoItem(0, url, "Video $id", StreamType.VIDEO_STREAM).apply {
            uploaderUrl = channel?.let { "https://www.youtube.com/channel/$it" }
            uploaderName = "Channel"
            this.duration = duration
        }

    private val self = "selfvideo01"

    @Test
    fun keepsVideosOnly_inYoutubesOrder_withoutTheVideoItselfOrRepeats() {
        val mix = PlaylistInfoItem(0, "https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk", "Mix").apply {
            playlistType = PlaylistInfo.PlaylistType.MIX_STREAM
        }
        val channel = ChannelInfoItem(0, "https://www.youtube.com/channel/UCchannelchannelchannel0", "A channel")
        val items: List<InfoItem> = listOf(
            stream("related0001"),
            mix,
            stream(self),
            channel,
            stream("related0002", url = "https://www.youtube.com/shorts/related0002"),
            stream("related0001"),
            stream("related0003", channel = null),
        )
        val videos = ItemMapper.relatedVideos(items, self)
        assertThat(videos.map { it.videoId }).containsExactly("related0001", "related0002", "related0003").inOrder()
        assertThat(videos[0].channelId).isEqualTo("UCrelatedrelatedrelated0")
        assertThat(videos[0].durationSeconds).isEqualTo(300)
        // A short stays recognisable as one: the app decides whether it may show.
        assertThat(videos[1].isShortForm).isTrue()
        // Without a channel the app cannot place it (a child never sees it).
        assertThat(videos[2].channelId).isNull()
        assertThat(ItemMapper.relatedVideos(null, self)).isEmpty()
    }

    @Test
    fun atMostMaxRelated() {
        val many = (1..60).map { stream("rel" + it.toString().padStart(8, '0')) }
        assertThat(ItemMapper.relatedVideos(many, self)).hasSize(MAX_RELATED)
    }

    @Test
    fun theExtractionsOwnRelatedItems_answerWithoutAnyRequest() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<String>()
        val source = NewPipeVideoSource(OkHttpClient(), fetchRelated = { id -> requests += id; emptyList() })
        val info = StreamInfo(0, "https://www.youtube.com/watch?v=$self", "https://www.youtube.com/watch?v=$self", StreamType.VIDEO_STREAM, self, "Self", 0)
        info.relatedItems = listOf(stream("related0001"), stream("related0002"))
        // The bare StreamInfo has no streams: the extraction fails here, its related items are kept all the same.
        val failure = assertThrows(SourceException::class.java) { source.toDetails(self, info, allowLowQuality = false) }
        assertThat(failure.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)

        assertThat(source.related(self).map { it.videoId }).containsExactly("related0001", "related0002").inOrder()
        assertThat(source.related(self)).hasSize(2)
        assertThat(requests).isEmpty()
    }

    @Test
    fun aVideoNothingExtracted_isAskedForOnce_thenKept() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<String>()
        var now = 1_000_000L
        val source = NewPipeVideoSource(
            OkHttpClient(),
            relatedCache = RelatedCache(maxAgeMs = 60_000, clock = { now }),
            fetchRelated = { id ->
                requests += id
                listOf(stream("related0009"), stream(id))
            },
        )
        assertThat(source.related("watched0001").map { it.videoId }).containsExactly("related0009")
        assertThat(source.related("watched0001").map { it.videoId }).containsExactly("related0009")
        assertThat(requests).containsExactly("watched0001")
        // A few hours later (here: a minute) the answer is asked for again.
        now += 60_001
        source.related("watched0001")
        assertThat(requests).containsExactly("watched0001", "watched0001")
    }

    @Test
    fun aFailedRequest_isTheUsualSourceException_andNothingIsKept() = runBlocking<Unit> {
        var fail = true
        val source = NewPipeVideoSource(OkHttpClient(), fetchRelated = {
            if (fail) throw java.net.UnknownHostException("www.youtube.com")
            listOf(stream("related0005"))
        })
        val error = runCatching { source.related("watched0002") }.exceptionOrNull()
        assertThat(error).isInstanceOf(SourceException::class.java)
        assertThat((error as SourceException).kind).isEqualTo(SourceErrorKind.NETWORK)
        fail = false
        assertThat(source.related("watched0002").map { it.videoId }).containsExactly("related0005")
    }

    @Test
    fun notAVideoId_isRefusedWithoutARequest() {
        val requests = CopyOnWriteArrayList<String>()
        val source = NewPipeVideoSource(OkHttpClient(), fetchRelated = { id -> requests += id; emptyList() })
        val error = runCatching { runBlocking { source.related("not a video") } }.exceptionOrNull()
        assertThat((error as SourceException).kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(requests).isEmpty()
    }

    @Test
    fun cache_keepsTheNewest_andForgetsOldAnswers() {
        var now = 0L
        val cache = RelatedCache(maxEntries = 2, maxAgeMs = 100, clock = { now })
        cache.put("a", emptyList())
        cache.put("b", emptyList())
        cache.put("c", emptyList())
        assertThat(cache.get("a")).isNull()
        assertThat(cache.get("b")).isNotNull()
        now = 101
        assertThat(cache.get("c")).isNull()
        // A clock set back is not trusted either.
        cache.put("d", emptyList())
        now = 50
        assertThat(cache.get("d")).isNull()
    }
}
