package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.TestStreams
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamRepositoryTest {
    /** A source whose stream() fails in a chosen way (StreamDetails cannot be built outside :youtube). */
    private class FailingSource(private val failure: () -> Throwable) : VideoSource {
        var calls = 0
        val gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        override val extractorVersion = "test"
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            calls++
            gate.await()
            throw failure()
        }
        override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage = error("unused")
        override suspend fun channel(channelId: String): ChannelDetails = error("unused")
        override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = error("unused")
        override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage = error("unused")
        override suspend fun recentVideos(channelId: String): List<VideoItem> = error("unused")
        override fun parseLink(text: String): YouTubeLink? = null
        override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = error("unused")
    }

    @Test
    fun sourceExceptionsPassThroughAndAreNotCached() = runTest {
        val source = FailingSource { SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", "gone", null) }
        val repo = StreamRepository(source)
        repeat(2) {
            val e = runCatching { repo.stream("x") }.exceptionOrNull()
            assertThat(e).isInstanceOf(SourceException::class.java)
            assertThat((e as SourceException).kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        }
        assertThat(source.calls).isEqualTo(2)
    }

    @Test
    fun unexpectedFailuresBecomeExtractionBroken() = runTest {
        val repo = StreamRepository(FailingSource { IllegalStateException("boom") })
        val e = runCatching { repo.stream("x") }.exceptionOrNull() as SourceException
        assertThat(e.kind).isEqualTo(SourceErrorKind.EXTRACTION_BROKEN)
        assertThat(e.technicalName).isEqualTo("IllegalStateException")
    }

    @Test
    fun concurrentCallsShareOneExtraction() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : VideoSource by FailingSource({ error("unused") }) {
            override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
                calls++
                gate.await()
                throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null)
            }
        }
        val repo = StreamRepository(source)
        val a = async { runCatching { repo.stream("x") } }
        val b = async { runCatching { repo.stream("x") } }
        testScheduler.advanceUntilIdle()
        gate.complete(Unit)
        assertThat((a.await().exceptionOrNull() as SourceException).kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat((b.await().exceptionOrNull() as SourceException).kind).isEqualTo(SourceErrorKind.NETWORK)
        assertThat(calls).isEqualTo(1)
    }

    /** A "made for kids" video as :youtube answers it: LOW_QUALITY_ONLY, or its 360p file when that is allowed. */
    private class KidsSource : VideoSource by FailingSource({ error("unused") }) {
        val asked = mutableListOf<Boolean>()
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            asked += allowLowQuality
            if (!allowLowQuality) throw SourceException(SourceErrorKind.LOW_QUALITY_ONLY, "MuxedOnly360p", "360p only", null)
            return TestStreams.details(video(videoId), lowQualityHeight = 360)
        }
    }

    @Test
    fun allowLowQualityReachesTheSource_andALowQualityResultOnlyServesCallsThatAllowIt() = runTest {
        val source = KidsSource()
        val repo = StreamRepository(source)

        assertThat(repo.stream("kids", allowLowQuality = true).lowQualityHeight).isEqualTo(360)
        assertThat(repo.stream("kids", allowLowQuality = true).lowQualityHeight).isEqualTo(360) // cached
        // A call that does not allow it extracts for itself, and gets the error screen's answer.
        val refused = runCatching { repo.stream("kids") }.exceptionOrNull() as SourceException
        assertThat(refused.kind).isEqualTo(SourceErrorKind.LOW_QUALITY_ONLY)
        assertThat(source.asked).containsExactly(true, false).inOrder()
        assertThat(repo.cached("kids")).isNull()
        assertThat(repo.cached("kids", allowLowQuality = true)?.lowQualityHeight).isEqualTo(360)
    }

    @Test
    fun aNormalResultServesEveryCall() = runTest {
        val asked = mutableListOf<Boolean>()
        val source = object : VideoSource by FailingSource({ error("unused") }) {
            override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
                asked += allowLowQuality
                return TestStreams.details(video(videoId))
            }
        }
        val repo = StreamRepository(source)
        assertThat(repo.stream("normal", allowLowQuality = true).lowQualityHeight).isNull()
        assertThat(repo.stream("normal").lowQualityHeight).isNull()
        assertThat(asked).containsExactly(true)
    }

    @Test
    fun asSourceExceptionKeepsSourceExceptions() {
        val original = SourceException(SourceErrorKind.BLOCKED_403, "HTTP 403", null, null)
        assertThat(original.asSourceException()).isSameInstanceAs(original)
        assertThrows(SourceException::class.java) { throw RuntimeException().asSourceException() }
    }

    private companion object {
        fun video(id: String) = VideoItem(
            videoId = id, title = "Video $id", channelId = "UCkids0000000000000000000", channelName = "Kids",
            durationSeconds = 300, publishedAtEpochMs = null, thumbnailUrl = null, viewCount = null,
            isLive = false, isShortForm = false, url = "https://www.youtube.com/watch?v=$id",
        )
    }
}
