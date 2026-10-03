package com.ahmadnayfeh.shasha.youtube

import com.ahmadnayfeh.shasha.youtube.newpipe.NewPipeVideoSource
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * A channel's sections and their orders against the real YouTube. Skipped unless run with:
 *   ./gradlew :youtube:testDebugUnitTest -Dyoutube.realNetwork=true --tests '*RealNetworkChannelTabsTest*'
 * Every check prints a "REAL-RESULT" line. What is asserted is what the app shows: the orders on the first page (popular by
 * view count, descending; oldest by date, ascending), that the next pages go on in the same order, that the sections the
 * channel has are the ones it lists, and that the default order is what [VideoSource.channelVideos] always gave.
 */
class RealNetworkChannelTabsTest {

    companion object {
        private lateinit var source: VideoSource

        @BeforeClass
        @JvmStatic
        fun setUp() {
            assumeTrue("real-network tests disabled", System.getProperty("youtube.realNetwork") == "true")
            source = NewPipeVideoSource(OkHttpClient())
        }

        private const val RICK_ASTLEY = "UCuAXFkgsw1L7xaCfnd5JJOw"
        private const val SPACETOON = "UCuQKih3Ac3NABADQKQdeV6A"
        private const val COCOMELON = "UCbCmjCuTUZos6Inko4u57UQ"

        private fun report(test: String, outcome: String) = println("REAL-RESULT | $test | $outcome")
    }

    private fun videos(page: ChannelTabPage) = page.items.filterIsInstance<VideoItem>()

    private suspend fun tabsOf(channelId: String): List<ChannelTab> = source.channel(channelId).tabs

    // ---- the sections a channel has ---------------------------------------------------------------------------------------

    @Test
    fun theChannelsListTheSectionsTheyReallyHave() = runBlocking {
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "Spacetoon" to SPACETOON, "CoComelon" to COCOMELON)) {
            val tabs = tabsOf(id)
            report("tabs $name", tabs.toString())
            assertThat(tabs).contains(ChannelTab.VIDEOS)
            // The fixed order, and nothing twice.
            assertThat(tabs).isInStrictOrder(compareBy<ChannelTab> { it.ordinal })
            // Every section it lists has a first page that answers, and the ones that offer orders say which.
            for (tab in tabs) {
                val page = source.channelTab(id, tab)
                report("page $name/$tab", "${page.items.size} items, sorts ${page.sorts}, more=${page.next != null}")
                assertThat(page.sorts.first()).isEqualTo(ChannelSort.LATEST)
                if (!tab.listsVideos) assertThat(page.sorts).containsExactly(ChannelSort.LATEST)
            }
        }
    }

    @Test
    fun aSectionTheChannelDoesNotHaveIsEmpty() = runBlocking {
        // Rick Astley has no Courses, and CoComelon is not a news channel: whatever a channel lacks reads as empty, not as an error.
        val tabs = tabsOf(RICK_ASTLEY)
        for (tab in ChannelTab.entries.filter { it !in tabs }) {
            val page = source.channelTab(RICK_ASTLEY, tab)
            report("missing section Rick Astley/$tab", "${page.items.size} items")
            assertThat(page.items).isEmpty()
        }
    }

    // ---- YouTube's own order is the default one -------------------------------------------------------------------------------

    @Test
    fun theDefaultOrderIsWhatTheChannelVideosAlwaysWere() = runBlocking {
        for (id in listOf(RICK_ASTLEY, SPACETOON, COCOMELON)) {
            val old = source.channelVideos(id).items.filterIsInstance<VideoItem>().map { it.videoId }
            val tab = videos(source.channelTab(id, ChannelTab.VIDEOS)).map { it.videoId }
            report("default order $id", "channelVideos ${old.size}, channelTab ${tab.size}")
            assertThat(tab).isNotEmpty()
            // YouTube may add a video between the two requests: the same list but for the very newest.
            assertThat(tab.take(old.size - 1).drop(1)).isEqualTo(old.take(old.size - 1).drop(1))

            val oldShorts = source.channelShorts(id).map { it.videoId }
            val tabShorts = videos(source.channelTab(id, ChannelTab.SHORTS)).map { it.videoId }
            report("default shorts $id", "channelShorts ${oldShorts.size}, channelTab ${tabShorts.size}")
            if (oldShorts.isNotEmpty()) assertThat(tabShorts.take(oldShorts.size - 1).drop(1)).isEqualTo(oldShorts.take(oldShorts.size - 1).drop(1))
        }
    }

    // ---- the orders ------------------------------------------------------------------------------------------------------------

    @Test
    fun popularVideos_areByViewCount_descending_onTheFirstPageAndAfter() = runBlocking {
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "Spacetoon" to SPACETOON, "CoComelon" to COCOMELON)) {
            val latest = source.channelTab(id, ChannelTab.VIDEOS)
            assertWithMessage("$name offers the popular order").that(latest.sorts).contains(ChannelSort.POPULAR)

            val popular = source.channelTab(id, ChannelTab.VIDEOS, ChannelSort.POPULAR)
            val counts = videos(popular).mapNotNull { it.viewCount }
            report("popular videos $name", "${counts.size} items, most viewed ${counts.take(3)}, first ${videos(popular).first().title.take(40)}")
            assertThat(counts.size).isAtLeast(5)
            assertThat(counts).isInOrder(descending())
            assertThat(videos(popular).map { it.videoId }).containsNoDuplicates()
            // Not the newest list again.
            assertThat(videos(popular).first().videoId).isNotEqualTo(videos(latest).first().videoId)

            popular.next?.let { next ->
                val more = source.channelTab(id, ChannelTab.VIDEOS, ChannelSort.POPULAR, next)
                val moreCounts = videos(more).mapNotNull { it.viewCount }
                report("popular videos $name page 2", "${moreCounts.size} items, ${moreCounts.take(3)}")
                assertThat(moreCounts).isNotEmpty()
                assertThat(moreCounts).isInOrder(descending())
                // The second page goes on where the first ended.
                assertThat(moreCounts.first()).isAtMost(counts.last())
                assertThat(videos(more).map { it.videoId }.intersect(videos(popular).map { it.videoId }.toSet())).isEmpty()
                assertThat(more.sorts).isEqualTo(popular.sorts)
            }
        }
    }

    @Test
    fun oldestVideos_areByDate_ascending_onTheFirstPageAndAfter() = runBlocking {
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "Spacetoon" to SPACETOON, "CoComelon" to COCOMELON)) {
            val oldest = source.channelTab(id, ChannelTab.VIDEOS, ChannelSort.OLDEST)
            val dates = videos(oldest).mapNotNull { it.publishedAtEpochMs }
            report("oldest videos $name", "${dates.size} items, first ${videos(oldest).first().title.take(40)}")
            assertThat(dates.size).isAtLeast(5)
            assertThat(dates).isInOrder()
            assertThat(videos(oldest).map { it.videoId }).containsNoDuplicates()

            // The newest list starts with newer videos than the oldest one does.
            val latest = source.channelTab(id, ChannelTab.VIDEOS)
            assertThat(videos(latest).first().publishedAtEpochMs!!).isGreaterThan(dates.first())

            oldest.next?.let { next ->
                val more = source.channelTab(id, ChannelTab.VIDEOS, ChannelSort.OLDEST, next)
                val moreDates = videos(more).mapNotNull { it.publishedAtEpochMs }
                report("oldest videos $name page 2", "${moreDates.size} items")
                assertThat(moreDates).isNotEmpty()
                assertThat(moreDates).isInOrder()
                assertThat(moreDates.first()).isAtLeast(dates.last())
                assertThat(videos(more).map { it.videoId }.intersect(videos(oldest).map { it.videoId }.toSet())).isEmpty()
            }
        }
    }

    @Test
    fun popularShorts_areByViewCount_descending_andMarkedAsShorts() = runBlocking {
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "Spacetoon" to SPACETOON, "CoComelon" to COCOMELON)) {
            if (ChannelTab.SHORTS !in tabsOf(id)) {
                report("popular shorts $name", "no Shorts section")
                continue
            }
            val latest = source.channelTab(id, ChannelTab.SHORTS)
            assertThat(videos(latest).all { it.isShortForm }).isTrue()
            if (ChannelSort.POPULAR !in latest.sorts) {
                report("popular shorts $name", "the section does not offer it (${latest.sorts})")
                continue
            }
            val popular = source.channelTab(id, ChannelTab.SHORTS, ChannelSort.POPULAR)
            val counts = videos(popular).mapNotNull { it.viewCount }
            report("popular shorts $name", "${counts.size} items, most viewed ${counts.take(3)}")
            assertThat(counts.size).isAtLeast(5)
            assertThat(counts).isInOrder(descending())
            assertThat(videos(popular).all { it.isShortForm }).isTrue()
        }
    }

    @Test
    fun oldestShorts_areTheEarliestUploaded() = runBlocking {
        // Shorts carry no date in the list: the uploads' own dates (the player's details) say which is older.
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "CoComelon" to COCOMELON)) {
            if (ChannelTab.SHORTS !in tabsOf(id)) continue
            val latest = source.channelTab(id, ChannelTab.SHORTS)
            if (ChannelSort.OLDEST !in latest.sorts) {
                report("oldest shorts $name", "the section does not offer it (${latest.sorts})")
                continue
            }
            val oldest = source.channelTab(id, ChannelTab.SHORTS, ChannelSort.OLDEST)
            val newestShort = videos(latest).first()
            val oldestShort = videos(oldest).first()
            val newestDate = runCatching { source.stream(newestShort.videoId, allowLowQuality = true).video.publishedAtEpochMs }.getOrNull()
            val oldestDate = runCatching { source.stream(oldestShort.videoId, allowLowQuality = true).video.publishedAtEpochMs }.getOrNull()
            report("oldest shorts $name", "newest ${newestShort.videoId} $newestDate, oldest ${oldestShort.videoId} $oldestDate")
            assertThat(oldestShort.videoId).isNotEqualTo(newestShort.videoId)
            if (newestDate != null && oldestDate != null) assertThat(oldestDate).isLessThan(newestDate)
            assertThat(videos(oldest).all { it.isShortForm }).isTrue()
        }
    }

    @Test
    fun liveStreams_areListed_andSortedWhereTheChannelHasEnough() = runBlocking {
        for ((name, id) in listOf("Spacetoon" to SPACETOON, "CoComelon" to COCOMELON, "Rick Astley" to RICK_ASTLEY)) {
            if (ChannelTab.LIVE !in tabsOf(id)) {
                report("live $name", "no live section")
                continue
            }
            val latest = source.channelTab(id, ChannelTab.LIVE)
            report("live $name", "${latest.items.size} items, sorts ${latest.sorts}")
            assertThat(latest.items).isNotEmpty()
            assertThat(latest.items.all { it is VideoItem }).isTrue()
            if (ChannelSort.POPULAR in latest.sorts) {
                val popular = videos(source.channelTab(id, ChannelTab.LIVE, ChannelSort.POPULAR))
                val counts = popular.mapNotNull { it.viewCount }
                report("live popular $name", "${counts.size} items, ${counts.take(3)}")
                assertThat(counts).isInOrder(descending())
            }
            if (ChannelSort.OLDEST in latest.sorts) {
                val oldest = videos(source.channelTab(id, ChannelTab.LIVE, ChannelSort.OLDEST))
                val dates = oldest.mapNotNull { it.publishedAtEpochMs }
                report("live oldest $name", "${dates.size} items")
                assertThat(dates).isInOrder()
            }
            // A section with no chips refuses what it does not offer: never a guess.
            if (latest.sorts == listOf(ChannelSort.LATEST)) {
                val refused = runCatching { source.channelTab(id, ChannelTab.LIVE, ChannelSort.POPULAR) }.exceptionOrNull()
                assertThat(refused).isInstanceOf(SourceException::class.java)
                assertThat((refused as SourceException).kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
            }
        }
    }

    @Test
    fun playlists_areListedWithTheirIdsAndPages() = runBlocking {
        for ((name, id) in listOf("Rick Astley" to RICK_ASTLEY, "Spacetoon" to SPACETOON, "CoComelon" to COCOMELON)) {
            if (ChannelTab.PLAYLISTS !in tabsOf(id)) {
                report("playlists $name", "no playlists section")
                continue
            }
            val page = source.channelTab(id, ChannelTab.PLAYLISTS)
            val playlists = page.items.filterIsInstance<PlaylistItem>()
            report("playlists $name", "${playlists.size} playlists, more=${page.next != null}, first ${playlists.firstOrNull()?.title?.take(40)}")
            assertThat(playlists).isNotEmpty()
            assertThat(page.items).hasSize(playlists.size)
            assertThat(playlists.all { YouTubeLinkParser.isPlaylistId(it.playlistId) }).isTrue()
            assertThat(playlists.all { it.title.isNotBlank() }).isTrue()
            assertThat(playlists.map { it.playlistId }).containsNoDuplicates()
            assertThat(page.sorts).containsExactly(ChannelSort.LATEST)

            // A playlist of the channel opens as a playlist.
            val opened = source.playlist(playlists.first().playlistId)
            assertThat(opened.videos).isNotEmpty()

            page.next?.let { next ->
                val more = source.channelTab(id, ChannelTab.PLAYLISTS, page = next).items.filterIsInstance<PlaylistItem>()
                report("playlists $name page 2", "${more.size} playlists")
                assertThat(more.map { it.playlistId }.intersect(playlists.map { it.playlistId }.toSet())).isEmpty()
            }
            val refused = runCatching { source.channelTab(id, ChannelTab.PLAYLISTS, ChannelSort.POPULAR) }.exceptionOrNull()
            assertThat(refused).isInstanceOf(SourceException::class.java)
        }
    }

    @Test
    fun smallChannels_offerOnlyTheOrdersTheyHave_andNeverFail() = runBlocking {
        // Found through the search, so that no particular channel has to stay small: channels of a few subscribers have few videos,
        // and YouTube shows no order chips for a handful of them.
        val found = listOf("my first vlog", "school project video", "family trip 2019")
            .flatMap { source.search(it, SearchFilter.CHANNELS).items }
            .filterIsInstance<ChannelItem>()
            .filter { (it.subscriberCount ?: Long.MAX_VALUE) in 0..3_000 }
            .distinctBy { it.channelId }
            .take(4)
        assumeTrue("no small channel found by the search", found.isNotEmpty())
        for (channel in found) {
            val tabs = tabsOf(channel.channelId)
            val outcome = StringBuilder("${channel.channelId} tabs $tabs:")
            for (tab in tabs) {
                val page = source.channelTab(channel.channelId, tab)
                outcome.append(" $tab=${page.items.size}${if (page.sorts.size > 1) "+orders" else ""}")
                if (tab.listsVideos && ChannelSort.POPULAR in page.sorts) {
                    val counts = videos(source.channelTab(channel.channelId, tab, ChannelSort.POPULAR)).mapNotNull { it.viewCount }
                    assertThat(counts).isInOrder(descending())
                } else if (tab.listsVideos) {
                    val refused = runCatching { source.channelTab(channel.channelId, tab, ChannelSort.POPULAR) }.exceptionOrNull()
                    assertThat(refused).isInstanceOf(SourceException::class.java)
                }
            }
            report("small channel", outcome.toString())
        }
    }

    @Test
    fun aTopicChannel_listsWhateverSectionsItHas_withoutFailing() = runBlocking {
        val topic = source.search("Rick Astley - Topic", SearchFilter.CHANNELS).items
            .filterIsInstance<ChannelItem>().firstOrNull { it.name.endsWith("- Topic") }
        assumeTrue("no topic channel found by the search", topic != null)
        val tabs = tabsOf(topic!!.channelId)
        val outcome = StringBuilder("${topic.name} tabs $tabs:")
        for (tab in tabs) {
            val page = source.channelTab(topic.channelId, tab)
            outcome.append(" $tab=${page.items.size}")
        }
        report("topic channel", outcome.toString())
    }

    private fun descending(): Comparator<Long> = compareByDescending<Long> { it }
}
