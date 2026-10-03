package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Search against the real YouTube: how far its pages go, and the Shorts it finds. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest -Dyoutube.realNetwork=true --tests '*RealNetworkSearchTest*'
 * Every check prints a "REAL-RESULT" line.
 *
 * Why the paging check exists («the search shows only a fixed amount»): YouTube answers a plain search in pages of 2 to 14
 * videos (the rest of each page is shelves, the Shorts' among them, which the extractor does not read) and ends it after 15 to 40
 * pages with its own «No more results»; a search for videos alone comes in pages of 20 and goes about three times as deep.
 * The extractor itself keeps paging for as long as YouTube has pages: a list that stops earlier is the app's.
 */
class RealNetworkSearchTest {

    companion object {
        private lateinit var source: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            source = NewPipeVideoSource(OkHttpClient())
        }

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")

        /** Pages are asked for gently: YouTube answers a burst with HTTP 403. */
        private const val PAUSE_MS = 700L
        private const val MAX_PAGES = 45
    }

    private class Paged(val pages: Int, val unique: Int, val shorts: Int, val endedByYouTube: Boolean)

    private suspend fun walk(query: String, filter: SearchFilter): Paged {
        val seen = LinkedHashMap<String, Boolean>()
        var page = source.search(query, filter, null)
        var pages = 1
        while (true) {
            page.items.filterIsInstance<VideoItem>().forEach { seen.putIfAbsent(it.videoId, it.isShortForm) }
            val next = page.next ?: return Paged(pages, seen.size, seen.values.count { it }, endedByYouTube = true)
            if (pages >= MAX_PAGES) return Paged(pages, seen.size, seen.values.count { it }, endedByYouTube = false)
            delay(PAUSE_MS)
            page = source.search(query, filter, next)
            pages++
        }
    }

    @Test
    fun aPlainSearchPagesOnUntilYouTubeHasNoMore() = runBlocking {
        for (query in listOf("minecraft", "كرتون أطفال")) {
            val all = walk(query, SearchFilter.ALL)
            report("search $query/ALL", "${all.pages} pages, ${all.unique} different videos (${all.shorts} of them Shorts), ended by YouTube=${all.endedByYouTube}")
            assertWithMessage("$query: the plain search must go on for several pages").that(all.pages).isAtLeast(4)
            assertWithMessage("$query: and give well over a screenful").that(all.unique).isAtLeast(40)
            delay(2_000)
        }
    }

    @Test
    fun aSearchForVideosAlonePagesDeeper() = runBlocking {
        val videos = walk("minecraft", SearchFilter.VIDEOS)
        report("search minecraft/VIDEOS", "${videos.pages} pages, ${videos.unique} different videos, ended by YouTube=${videos.endedByYouTube}")
        assertThat(videos.pages).isAtLeast(8)
        assertThat(videos.unique).isAtLeast(150)
    }

    @Test
    fun shortsForAQuery_pageByPage() = runBlocking {
        for (query in listOf("minecraft", "كرتون")) {
            val first = source.searchShorts(query)
            report(
                "searchShorts $query",
                "${first.shorts.size} Shorts on the first page, next page=${first.next != null}: " +
                    first.shorts.take(2).map { "${it.videoId} «${it.title.take(30)}» ${it.viewCount} views" },
            )
            assertThat(first.shorts).isNotEmpty()
            assertThat(first.shorts.all { it.isShortForm }).isTrue()
            assertThat(first.shorts.map { it.videoId }).containsNoDuplicates()
            assertThat(first.shorts.all { it.title.isNotBlank() && YouTubeLinkParser.isVideoId(it.videoId) }).isTrue()
            assertWithMessage("a Short has a picture").that(first.shorts.count { it.thumbnailUrl != null }).isAtLeast(first.shorts.size * 9 / 10)
            assertThat(first.next).isNotNull()

            delay(PAUSE_MS)
            val second = source.searchShorts(query, first.next)
            val fresh = second.shorts.map { it.videoId }.filterNot { id -> first.shorts.any { it.videoId == id } }
            report("searchShorts $query page 2", "${second.shorts.size} Shorts, ${fresh.size} new, next page=${second.next != null}")
            assertThat(fresh).isNotEmpty()
            delay(2_000)
        }
    }

    @Test
    fun aSearchWithoutShortsIsEmptyNotAnError() = runBlocking {
        val none = source.searchShorts("qzkxjvbnwyq plmoknijbuhv zzqqxxjj")
        report("searchShorts nonsense", "${none.shorts.size} Shorts, next=${none.next}")
        assertThat(none.shorts).isEmpty()
        assertThat(none.next).isNull()
    }
}
