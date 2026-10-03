package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

/**
 * A channel's sections and their orders, end to end but offline: NewPipeExtractor really reads the answers, which a fake
 * YouTube (a [Downloader]) gives in the shapes the real one does (captured 2026-10): a section's first answer with its chip
 * bar, a chip's answer in two reload actions, the next pages as appended items. What the real YouTube answers is checked by
 * RealNetworkChannelTabsTest.
 */
class ChannelTabLoaderTest {

    private val channel = "UCuAXFkgsw1L7xaCfnd5JJOw"
    private val taps = BrowseTaps()
    private val youtube = FakeYouTube(taps)
    private val pages = PageStore()
    private val loader = ChannelTabLoader(ServiceList.YouTube, taps, pages)

    private var previousDownloader: Downloader? = null
    private var previousLocalization: Localization? = null
    private var previousCountry: ContentCountry? = null

    @Before
    fun fakeYouTube() {
        // NewPipe's state is global to the process: borrow it, and give it back.
        previousDownloader = NewPipe.getDownloader()
        previousLocalization = runCatching { NewPipe.getPreferredLocalization() }.getOrNull()
        previousCountry = runCatching { NewPipe.getPreferredContentCountry() }.getOrNull()
        NewPipe.init(youtube, Localization("ar", "SA"), ContentCountry("SA"))
    }

    @After
    fun giveNewPipeBack() {
        val downloader = previousDownloader ?: return
        NewPipe.init(downloader, previousLocalization ?: Localization("ar", "SA"), previousCountry ?: ContentCountry("SA"))
    }

    private fun videos(items: List<*>) = items.filterIsInstance<VideoItem>()

    // ---- YouTube's own order ---------------------------------------------------------------------------------------------

    @Test
    fun theFirstPageComesWithTheOrdersTheSectionOffers() {
        val page = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)

        assertThat(videos(page.items).map { it.videoId }).containsExactly("latestvid01", "latestvid02").inOrder()
        assertThat(page.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
        assertThat(page.next).isNotNull()
        val first = videos(page.items).first()
        assertThat(first.title).isEqualTo("Newest video")
        assertThat(first.channelId).isEqualTo(channel)
        assertThat(first.durationSeconds).isEqualTo(214L)
        assertThat(first.viewCount).isEqualTo(3_200_000L)
        assertThat(first.isShortForm).isFalse()
        // One request for the page, none more for the orders.
        assertThat(youtube.browsePosts).hasSize(1)
    }

    @Test
    fun theNextPagesGoOnInTheSameOrder_andEndWhereTheListEnds() {
        val first = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        val second = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, first.next)

        assertThat(videos(second.items).map { it.videoId }).containsExactly("latestvid03").inOrder()
        assertThat(second.next).isNull()
        assertThat(second.sorts).isEqualTo(first.sorts)
        assertThat(youtube.continuationsPosted).containsExactly("next-latest-1")
    }

    // ---- the other orders ------------------------------------------------------------------------------------------------

    @Test
    fun aChosenOrderUsesTheChipsTokenWithoutAskingForTheChipsAgain() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        youtube.browsePosts.clear()

        val popular = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null)

        assertThat(videos(popular.items).map { it.videoId }).containsExactly("popularv001", "popularv002").inOrder()
        assertThat(popular.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
        assertThat(popular.next).isNotNull()
        // Just the token: not the section's first page again.
        assertThat(youtube.continuationsPosted).containsExactly("tok-popular")
        assertThat(youtube.browsePosts).hasSize(1)
        // The items are the channel's own, like the first page's.
        assertThat(videos(popular.items).first().channelId).isEqualTo(channel)
    }

    @Test
    fun theOldestListPagesOn() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        val oldest = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.OLDEST, null)
        val more = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.OLDEST, oldest.next)

        assertThat(videos(oldest.items).map { it.videoId }).containsExactly("oldestvid01", "oldestvid02").inOrder()
        assertThat(videos(more.items).map { it.videoId }).containsExactly("oldestvid03").inOrder()
        assertThat(more.next).isNull()
        assertThat(youtube.continuationsPosted).containsExactly("tok-oldest", "next-oldest-1").inOrder()
    }

    @Test
    fun anOrderChosenFirst_asksForTheChipsOnce() {
        // Nothing kept (a new process): the section's first page is read for its chips, then the token is posted.
        val popular = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null)

        assertThat(videos(popular.items).map { it.videoId }).containsExactly("popularv001", "popularv002").inOrder()
        assertThat(youtube.browsePosts).hasSize(2)
        assertThat(youtube.continuationsPosted).containsExactly("tok-popular")
    }

    @Test
    fun aTokenThatWentStaleIsReplacedOnce() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        youtube.browsePosts.clear()
        youtube.rejectTokensOnce = setOf("tok-popular")
        youtube.nextChipTokens = mapOf("Popular" to "tok-popular-2")

        val popular = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null)

        assertThat(videos(popular.items).map { it.videoId }).containsExactly("popularv001", "popularv002").inOrder()
        // The old token failed, the section's page was read again, its new token worked.
        assertThat(youtube.continuationsPosted).containsExactly("tok-popular", "tok-popular-2").inOrder()
    }

    @Test
    fun aTokenThatKeepsFailing_fails() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        youtube.rejectAlways = setOf("tok-popular")

        assertThrows(Exception::class.java) { loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null) }
    }

    @Test
    fun aKeptTokenIsOnlyKeptAWhile() {
        var now = 1_000_000L
        val timed = ChannelTabLoader(ServiceList.YouTube, taps, pages) { now }
        timed.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        youtube.browsePosts.clear()

        now += ChannelTabLoader.KEEP_MS + 1
        timed.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null)

        // Too old to trust: the section's page was read again for new chips.
        assertThat(youtube.browsePosts).hasSize(2)
    }

    @Test
    fun anAnswerInAnotherOrderThanAskedIsNeverHandedOut() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        // YouTube says «Latest» is the selected chip in the answer to the «Popular» chip's token.
        youtube.selectedChipInSortedAnswers = "Latest"

        assertThrows(ParsingException::class.java) { loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null) }
    }

    @Test
    fun aSortedAnswerWithoutAListIsNeverHandedOutAsAnEmptyOne() {
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        youtube.sortedAnswersWithoutBody = true

        assertThrows(ParsingException::class.java) { loader.load(channel, ChannelTab.VIDEOS, ChannelSort.OLDEST, null) }
    }

    // ---- what is not offered ---------------------------------------------------------------------------------------------

    @Test
    fun aSectionWithoutChipsOffersOnlyYouTubesOrder_andRefusesTheOthers() {
        val live = loader.load(channel, ChannelTab.LIVE, ChannelSort.LATEST, null)

        assertThat(videos(live.items).map { it.videoId }).containsExactly("livevideo01")
        assertThat(live.sorts).containsExactly(ChannelSort.LATEST)
        assertThat(live.next).isNull()

        val refused = assertThrows(SourceException::class.java) { loader.load(channel, ChannelTab.LIVE, ChannelSort.POPULAR, null) }
        assertThat(refused.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(refused.technicalName).isEqualTo("SortUnavailable")
    }

    @Test
    fun playlistsAreNotSorted_andNothingIsAskedOfYouTubeForIt() {
        val refused = assertThrows(SourceException::class.java) { loader.load(channel, ChannelTab.PLAYLISTS, ChannelSort.OLDEST, null) }

        assertThat(refused.technicalName).isEqualTo("SortUnavailable")
        assertThat(youtube.browsePosts).isEmpty()
    }

    // ---- the other sections ----------------------------------------------------------------------------------------------

    @Test
    fun theShortsAreMarkedAsShorts() {
        val page = loader.load(channel, ChannelTab.SHORTS, ChannelSort.LATEST, null)

        val shorts = videos(page.items)
        assertThat(shorts.map { it.videoId }).containsExactly("shortsvid01", "shortsvid02").inOrder()
        assertThat(shorts.all { it.isShortForm }).isTrue()
        assertThat(shorts.first().title).isEqualTo("A short")
        assertThat(shorts.first().viewCount).isEqualTo(602_000L)
        assertThat(shorts.first().durationSeconds).isNull()
        assertThat(shorts.first().channelId).isEqualTo(channel)
        assertThat(page.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR, ChannelSort.OLDEST).inOrder()
    }

    @Test
    fun theShortsCanBeSortedToo() {
        loader.load(channel, ChannelTab.SHORTS, ChannelSort.LATEST, null)
        val popular = loader.load(channel, ChannelTab.SHORTS, ChannelSort.POPULAR, null)

        assertThat(videos(popular.items).map { it.videoId }).containsExactly("shortpop001").inOrder()
        assertThat(videos(popular.items).all { it.isShortForm }).isTrue()
    }

    @Test
    fun thePlaylistsAreListedAsPlaylists() {
        val page = loader.load(channel, ChannelTab.PLAYLISTS, ChannelSort.LATEST, null)

        val playlist = page.items.single() as PlaylistItem
        assertThat(playlist.playlistId).isEqualTo("PLabcdefghijklmnopqrstuvwxyz0123456")
        assertThat(playlist.title).isEqualTo("Hits")
        assertThat(playlist.videoCount).isEqualTo(25L)
        assertThat(page.sorts).containsExactly(ChannelSort.LATEST)
    }

    // ---- keys ------------------------------------------------------------------------------------------------------------

    @Test
    fun aKeyBelongsToTheSectionAndOrderThatReturnedIt() {
        val latest = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, null)
        loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, null)
        youtube.continuationsPosted.clear()

        // Replayed against another order, another section, another channel, or made up: the list just ends.
        val otherOrder = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.POPULAR, latest.next)
        val otherSection = loader.load(channel, ChannelTab.SHORTS, ChannelSort.LATEST, latest.next)
        val otherChannel = loader.load("UCscripted0000000000000a", ChannelTab.VIDEOS, ChannelSort.LATEST, latest.next)
        val madeUp = loader.load(channel, ChannelTab.VIDEOS, ChannelSort.LATEST, PageKey("nope"))

        listOf(otherOrder, otherSection, otherChannel, madeUp).forEach {
            assertThat(it.items).isEmpty()
            assertThat(it.next).isNull()
        }
        assertThat(youtube.continuationsPosted).isEmpty()
    }

    // ---- the channel's sections ------------------------------------------------------------------------------------------

    @Test
    fun theChannelsSectionsAreThoseTheExtractorListed_inTheFixedOrder() {
        fun handler(vararg filters: String) = ListLinkHandler("u", "u", "id", filters.toList(), "")
        val listed = listOf(handler("playlists"), handler("videos"), handler("releases"), handler("livestreams"), handler("shorts"))

        assertThat(ChannelTabLoader.tabsOf(listed)).containsExactly(
            ChannelTab.VIDEOS,
            ChannelTab.SHORTS,
            ChannelTab.LIVE,
            ChannelTab.PLAYLISTS,
        ).inOrder()
        assertThat(ChannelTabLoader.tabsOf(listOf(handler("videos"), handler("playlists")))).containsExactly(ChannelTab.VIDEOS, ChannelTab.PLAYLISTS).inOrder()
        // A channel without a Shorts tab has no Shorts section; one the extractor could not list shows the videos.
        assertThat(ChannelTabLoader.tabsOf(listOf(handler("videos")))).containsExactly(ChannelTab.VIDEOS)
        assertThat(ChannelTabLoader.tabsOf(emptyList())).containsExactly(ChannelTab.VIDEOS)
        assertThat(ChannelTabLoader.tabsOf(null)).containsExactly(ChannelTab.VIDEOS)
    }

    // ---- the fake YouTube ------------------------------------------------------------------------------------------------

    /** YouTube as far as these sections need it, in the shapes the real one answers. Requests are recorded. */
    private inner class FakeYouTube(private val taps: BrowseTaps) : Downloader() {
        /** Every body posted to browse. */
        val browsePosts = ArrayList<String>()

        /** The tokens posted to browse, in order. */
        val continuationsPosted = ArrayList<String>()

        var rejectTokensOnce: Set<String> = emptySet()
        var rejectAlways: Set<String> = emptySet()
        var nextChipTokens: Map<String, String> = emptyMap()
        var selectedChipInSortedAnswers: String? = null
        var sortedAnswersWithoutBody = false

        override fun execute(request: Request): Response {
            val url = request.url()
            val answer = when {
                url.endsWith("/sw.js") -> """"INNERTUBE_CONTEXT_CLIENT_VERSION":"2.20261002.01.00""""
                "/youtubei/v1/browse" in url -> browse(String(request.dataToSend() ?: ByteArray(0), Charsets.UTF_8))
                else -> error("This fake YouTube does not know $url")
            }
            return Response(200, "OK", emptyMap(), taps.answered(url, answer), url)
        }

        private fun browse(body: String): String {
            browsePosts += body
            val request = Json.parseToJsonElement(body).jsonObject
            request["continuation"]?.jsonPrimitive?.content?.let { return continuation(it) }
            val params = request.getValue("params").jsonPrimitive.content
            return when (params) {
                "EgZ2aWRlb3PyBgQKAjoA" -> firstAnswer("videos", gridOf(listOf(video("latestvid01", "Newest video", "3.2M views", "2 days ago", "3:34"), video("latestvid02", "Another video", "1.1M views", "1 week ago", "4:01")), "next-latest-1"), chips("Latest", youtubeTokens()))
                "EgZzaG9ydHPyBgUKA5oBAA%3D%3D" -> firstAnswer("shorts", gridOf(listOf(short("shortsvid01", "A short", "602K views"), short("shortsvid02", "Another short", "50K views")), "next-shorts-1"), chips("Latest", youtubeTokens("-short")))
                // Very few streams: YouTube shows no chips.
                "EgdzdHJlYW1z8gYECgJ6AA%3D%3D" -> firstAnswer("streams", gridOf(listOf(video("livevideo01", "Streamed once", "5K views", "Streamed 2 weeks ago", "1:00:00")), null), null)
                "EglwbGF5bGlzdHPyBgQKAkIA" -> playlistsAnswer()
                else -> error("This fake YouTube does not know the tab $params")
            }
        }

        private fun youtubeTokens(suffix: String = "") = mapOf("Latest" to "tok-latest$suffix", "Popular" to "tok-popular$suffix", "Oldest" to "tok-oldest$suffix")

        private fun continuation(token: String): String {
            continuationsPosted += token
            if (token in rejectAlways) error("INVALID_ARGUMENT")
            if (token in rejectTokensOnce) {
                rejectTokensOnce = rejectTokensOnce - token
                error("INVALID_ARGUMENT")
            }
            return when (token) {
                "tok-popular", "tok-popular-2" -> sorted("Popular", listOf(video("popularv001", "Most viewed", "1.8B views", "16 years ago", "3:34"), video("popularv002", "Second most", "207M views", "16 years ago", "3:24")), "next-popular-1")
                "tok-oldest" -> sorted("Oldest", listOf(video("oldestvid01", "First ever", "1.8B views", "16 years ago", "3:34"), video("oldestvid02", "Second ever", "20M views", "16 years ago", "3:28")), "next-oldest-1")
                "tok-popular-short" -> sorted("Popular", listOf(short("shortpop001", "Popular short", "7.2M views")), null)
                "next-latest-1" -> append(listOf(video("latestvid03", "Third video", "900K views", "2 weeks ago", "2:00")), null)
                "next-popular-1" -> append(listOf(video("popularv003", "Third most", "49M views", "14 years ago", "3:32")), null)
                "next-oldest-1" -> append(listOf(video("oldestvid03", "Third ever", "46M views", "16 years ago", "4:52")), null)
                else -> error("This fake YouTube does not know the token $token")
            }
        }

        // ---- answers

        private fun chips(selected: String, tokens: Map<String, String>): String {
            // After a token went stale YouTube gives new ones: [nextChipTokens].
            val shown = tokens.mapValues { (text, token) -> nextChipTokens[text] ?: token }
            return """{"chipBarViewModel":{"chips":[${shown.entries.joinToString(",") { (text, token) -> chip(text, text == selected, token) }}]}}"""
        }

        private fun chip(text: String, selected: Boolean, token: String) =
            """{"chipViewModel":{"text":"$text","selected":$selected,"tapCommand":{"innertubeCommand":{"continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}}"""

        private fun firstAnswer(suffix: String, grid: String, header: String?) = """
            {"metadata":{"channelMetadataRenderer":{"title":"Rick Astley","externalChannelId":"$channel"}},
             "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Videos","selected":true,
                "endpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/@RickAstleyYT/$suffix"}}},
                "content":{"richGridRenderer":{${if (header != null) "\"header\":$header," else ""}"contents":$grid}}}}
             ]}}}
        """.trimIndent()

        private fun gridOf(items: List<String>, nextToken: String?): String =
            "[" + (items + listOfNotNull(nextToken?.let(::continuationItem))).joinToString(",") + "]"

        private fun continuationItem(token: String) =
            """{"continuationItemRenderer":{"trigger":"CONTINUATION_TRIGGER_ON_ITEM_SHOWN","continuationEndpoint":{"continuationCommand":{"token":"$token","request":"CONTINUATION_REQUEST_TYPE_BROWSE"}}}}"""

        private fun sorted(selected: String, items: List<String>, nextToken: String?): String {
            val shownAs = selectedChipInSortedAnswers ?: selected
            val header = """{"reloadContinuationItemsCommand":{"targetId":"t","slot":"RELOAD_CONTINUATION_SLOT_HEADER","continuationItems":[${
            chips(shownAs, youtubeTokens())}]}}"""
            val body = """{"reloadContinuationItemsCommand":{"targetId":"t","slot":"RELOAD_CONTINUATION_SLOT_BODY","continuationItems":${gridOf(items, nextToken)}}}"""
            return """{"responseContext":{},"onResponseReceivedActions":[$header${if (sortedAnswersWithoutBody) "" else ",$body"}]}"""
        }

        private fun append(items: List<String>, nextToken: String?) =
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":${gridOf(items, nextToken)}}}]}"""

        private fun playlistsAnswer(): String = """
            {"metadata":{"channelMetadataRenderer":{"title":"Rick Astley","externalChannelId":"$channel"}},
             "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Playlists","selected":true,
                "endpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/@RickAstleyYT/playlists"}}},
                "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"gridRenderer":{"items":[${playlist()}]}}]}}]}}}}
             ]}}}
        """.trimIndent()

        // ---- items

        private fun video(id: String, title: String, views: String, ago: String, duration: String) = """
            {"richItemRenderer":{"content":{"lockupViewModel":{"contentId":"$id","contentType":"LOCKUP_CONTENT_TYPE_VIDEO",
              "contentImage":{"thumbnailViewModel":{"image":{"sources":[{"url":"https://i.ytimg.com/vi/$id/hq720.jpg","width":360,"height":202}]},
                "overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"$duration"}}]}}]}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"$title"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"$views"}},{"text":{"content":"$ago"}}]}]}}}}}}}}
        """.trimIndent()

        private fun short(id: String, title: String, views: String) = """
            {"richItemRenderer":{"content":{"shortsLockupViewModel":{
              "onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"$id"}}},
              "overlayMetadata":{"primaryText":{"content":"$title"},"secondaryText":{"content":"$views"}},
              "thumbnail":{"sources":[{"url":"https://i.ytimg.com/vi/$id/oardefault.jpg","width":405,"height":720}]}}}}}
        """.trimIndent()

        private fun playlist() = """
            {"lockupViewModel":{"contentId":"PLabcdefghijklmnopqrstuvwxyz0123456","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
              "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                "image":{"sources":[{"url":"https://i.ytimg.com/vi/abc/hqdefault.jpg","width":480,"height":270}]},
                "overlays":[{"thumbnailOverlayBadgeViewModel":{"thumbnailBadges":[{"thumbnailBadgeViewModel":{"text":"25 videos"}}]}}]}}}},
              "metadata":{"lockupMetadataViewModel":{"title":{"content":"Hits"},
                "metadata":{"contentMetadataViewModel":{"metadataRows":[{"metadataParts":[{"text":{"content":"Rick Astley"}}]}]}}}}}}
        """.trimIndent()
    }
}
