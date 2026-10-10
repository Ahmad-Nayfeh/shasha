package com.ahmadnayfeh.shasha.youtube.safety

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.ChannelTabPage
import com.ahmadnayfeh.shasha.youtube.Comment
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaybackHandle
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.Storyboard
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.SubtitleTrack
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import com.ahmadnayfeh.shasha.youtube.playback.PlaybackFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [SafeVideoSource] over a source that has everything: a search that blocks is empty and never reaches the source, items are
 * dropped by title, channel name and description snippet, pages keep their keys, a refused video cannot be played, and no method
 * of the interface is left to its default.
 */
class SafeVideoSourceTest {

    private val clean = video("cleanVideo01", "Minecraft survival episode 1", "Gamer Channel")
    private val explicitTitle = video("badTitle0001", "Sexy twerking compilation", "Dance Channel")
    private val explicitChannel = video("badChannel01", "Weekend vlog", "Hot Girls TV")
    private val explicitSnippet = video("badSnippet01", "Dance cover 23", "Dance Channel", snippet = "...hot girls shaking their ass...")
    private val cleanSnippet = video("cleanSnippet1", "Dance cover 24", "Dance Channel", snippet = "...learn the steps of the Dabke...")

    private val cleanChannel = ChannelItem("UCclean000000000000000000", "Cartoons for kids", null, 1000)
    private val explicitChannelItem = ChannelItem("UCbad00000000000000000000", "Onlyfans leaks", null, 1000)
    private val explicitChannelSnippet = ChannelItem("UCsnippet0000000000000000", "Dance studio", null, 10, snippet = "Pole dancing and striptease classes")
    private val cleanPlaylist = PlaylistItem("PLclean", "Math lessons", "Teacher", null, 10)
    private val explicitPlaylist = PlaylistItem("PLbad", "Hot scenes compilation", "Teacher", null, 10)

    private val mixed: List<SourceItem> = listOf(
        clean, explicitTitle, explicitChannel, explicitSnippet, cleanSnippet, cleanChannel, explicitChannelItem, explicitChannelSnippet,
        cleanPlaylist, explicitPlaylist,
    )
    private val cleanOfMixed: List<SourceItem> = listOf(clean, cleanSnippet, cleanChannel, cleanPlaylist)

    private val fake = FakeSource()
    private val source = SafeVideoSource(fake)

    private fun video(id: String, title: String, channel: String?, snippet: String? = null) = VideoItem(
        videoId = id, title = title, channelId = "UCchannel0000000000000000", channelName = channel, durationSeconds = 300,
        publishedAtEpochMs = null, thumbnailUrl = null, viewCount = null, isLive = false, isShortForm = false,
        url = "https://www.youtube.com/watch?v=$id", snippet = snippet,
    )

    private fun details(title: String, channel: String? = "Some Channel", description: String? = null, id: String = "streamVideo1") = StreamDetails(
        video = video(id, title, channel),
        description = description,
        videoTracks = emptyList(),
        subtitles = emptyList(),
        playback = PlaybackHandle(PlaybackFixtures.info()),
    )

    private fun refusal(block: suspend () -> Any?): SourceException = assertThrows(SourceException::class.java) { runBlocking { block() } }

    // ---- search ----

    @Test
    fun anExplicitQueryGetsAnEmptyPageWithoutANextPage_andTheExtractorIsNotAsked() = runBlocking<Unit> {
        fake.searchPage = ItemsPage(mixed, PageKey("next"))
        for (query in listOf("Sexy twerking", "twerking", "sexy dance", "belly dance", "رقص شرقي", "s e x", "xxx videos")) {
            for (filter in SearchFilter.entries) {
                val page = source.search(query, filter, page = null)
                assertThat(page.items).isEmpty()
                assertThat(page.next).isNull()
            }
            // The next pages of an explicit query are empty as well.
            assertThat(source.search(query, SearchFilter.ALL, PageKey("next")).items).isEmpty()
        }
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun anOrdinaryQueryGetsItsResults_withoutWhatIsExplicit_andTheKeysOfItsPages() = runBlocking<Unit> {
        fake.searchPage = ItemsPage(mixed, PageKey("page-2"))
        val page = source.search("dance", SearchFilter.ALL, page = null)
        assertThat(page.items).containsExactlyElementsIn(cleanOfMixed).inOrder()
        assertThat(page.next).isEqualTo(PageKey("page-2"))
        assertThat(fake.calls).containsExactly("search:dance:ALL:null")
    }

    @Test
    fun aPageOfNothingButExplicitItemsStillGoesOn() = runBlocking<Unit> {
        fake.searchPage = ItemsPage(listOf(explicitTitle, explicitChannel, explicitChannelItem), PageKey("page-3"))
        val page = source.search("dance", SearchFilter.VIDEOS, PageKey("page-2"))
        assertThat(page.items).isEmpty()
        assertThat(page.next).isEqualTo(PageKey("page-3"))
        assertThat(fake.calls).containsExactly("search:dance:VIDEOS:page-2")
    }

    @Test
    fun theLastPageHasNoNextPage() = runBlocking<Unit> {
        fake.searchPage = ItemsPage(mixed, null)
        assertThat(source.search("dance").next).isNull()
    }

    // ---- the Shorts of a search ----

    private fun short(id: String, title: String) = video(id, title, null).copy(
        channelId = null, durationSeconds = null, isShortForm = true, url = "https://www.youtube.com/shorts/$id",
    )

    @Test
    fun theShortsOfAnExplicitQueryAreNone_andTheExtractorIsNotAsked() = runBlocking<Unit> {
        fake.shortsPage = ShortsPage(listOf(short("shortClean01", "Cute cat")), PageKey("next"))
        for (query in listOf("Sexy twerking", "twerking", "belly dance", "رقص شرقي", "xxx videos")) {
            assertThat(source.searchShorts(query, null).shorts).isEmpty()
            assertThat(source.searchShorts(query, null).next).isNull()
            assertThat(source.searchShorts(query, PageKey("next")).shorts).isEmpty()
        }
        assertThat(fake.calls).isEmpty()
    }

    @Test
    fun theShortsOfAnOrdinaryQueryLoseTheExplicitOnes_andKeepTheirKey() = runBlocking<Unit> {
        fake.shortsPage = ShortsPage(
            listOf(short("shortClean01", "Cute cat"), short("shortBad0001", "Sexy twerking challenge"), short("shortClean02", "Cooking in 30 seconds")),
            PageKey("page-2"),
        )

        val page = source.searchShorts("cats", null)

        assertThat(page.shorts.map { it.videoId }).containsExactly("shortClean01", "shortClean02").inOrder()
        assertThat(page.next).isEqualTo(PageKey("page-2"))
        assertThat(fake.calls).containsExactly("searchShorts:cats:null")
        // What was found explicit once stays out of every list, and cannot be played: the same video as a normal item.
        assertThat(refusal { source.stream("shortBad0001") }.technicalName).isEqualTo("ContentFiltered")
    }

    @Test
    fun aPageOfNothingButExplicitShortsStillGoesOn() = runBlocking<Unit> {
        fake.shortsPage = ShortsPage(listOf(short("shortBad0001", "Sexy twerking challenge")), PageKey("page-3"))

        val page = source.searchShorts("cats", PageKey("page-2"))

        assertThat(page.shorts).isEmpty()
        assertThat(page.next).isEqualTo(PageKey("page-3"))
        assertThat(fake.calls).containsExactly("searchShorts:cats:page-2")
    }

    // ---- suggestions ----

    @Test
    fun suggestionsOfAnExplicitPrefixAreNone_andOthersLoseTheExplicitOnes() = runBlocking<Unit> {
        fake.suggestionList = listOf("twerking", "tw", "twitter", "twenty", "tw sexy", "tweety", "twin")
        assertThat(source.suggestions("sexy tw", "en")).isEmpty()
        assertThat(source.suggestions("بنات عاريات", "ar")).isEmpty()
        assertThat(fake.calls).isEmpty()
        assertThat(source.suggestions("tw", "en")).containsExactly("tw", "twitter", "twenty", "tweety", "twin").inOrder()
        assertThat(fake.calls).containsExactly("suggestions:tw:en")
    }

    // ---- channel and playlist ----

    @Test
    fun anExplicitChannelIsRefused_byItsNameOrItsDescription() {
        fake.channelDetails = ChannelDetails(ChannelItem("UCx0000000000000000000x0", "Hot Girls Dance", null, 5), null, "Welcome")
        assertThat(refusal { source.channel("UCx0000000000000000000x0") }.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        fake.channelDetails = ChannelDetails(cleanChannel, null, "Sexy twerking and belly dance every day")
        assertThat(refusal { source.channel("UCclean000000000000000000") }.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
    }

    @Test
    fun anOrdinaryChannelPassesAsItIs() = runBlocking<Unit> {
        val ordinary = ChannelDetails(cleanChannel, "https://banner", "Songs and stories for kids", listOf(ChannelTab.VIDEOS, ChannelTab.SHORTS))
        fake.channelDetails = ordinary
        assertThat(source.channel("UCclean000000000000000000")).isEqualTo(ordinary)
    }

    @Test
    fun aLongDescriptionIsJudgedByItsStartOnly() = runBlocking<Unit> {
        val long = "Songs and stories for kids. ".repeat(40) + "sexy twerking"
        fake.channelDetails = ChannelDetails(cleanChannel, null, long)
        assertThat(source.channel("UCclean000000000000000000").description).isEqualTo(long)
    }

    @Test
    fun aPlaylistKeepsItsOrdinaryVideosAndItsNextPage_andAnExplicitOneIsRefused() = runBlocking<Unit> {
        fake.playlistPage = PlaylistPage(cleanPlaylist, listOf(clean, explicitTitle, explicitSnippet, cleanSnippet), PageKey("more"))
        val page = source.playlist("PLclean", null)
        assertThat(page.details).isEqualTo(cleanPlaylist)
        assertThat(page.videos).containsExactly(clean, cleanSnippet).inOrder()
        assertThat(page.next).isEqualTo(PageKey("more"))
        fake.playlistPage = PlaylistPage(explicitPlaylist, listOf(clean), null)
        assertThat(refusal { source.playlist("PLbad", null) }.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
    }

    // ---- every other list ----

    @Test
    fun everyOtherListLosesItsExplicitItems_andKeepsItsKeys() = runBlocking<Unit> {
        val videos = listOf(clean, explicitTitle, explicitChannel, explicitSnippet, cleanSnippet)
        val ordinary = listOf(clean, cleanSnippet)
        fake.channelVideosPage = ItemsPage(videos, PageKey("channel-2"))
        assertThat(source.channelVideos("UCx", null).items).containsExactlyElementsIn(ordinary).inOrder()
        assertThat(source.channelVideos("UCx", PageKey("channel-1")).next).isEqualTo(PageKey("channel-2"))

        fake.shorts = videos
        assertThat(source.channelShorts("UCx")).containsExactlyElementsIn(ordinary).inOrder()

        fake.tabPage = ChannelTabPage(mixed, PageKey("tab-2"), listOf(ChannelSort.LATEST, ChannelSort.POPULAR))
        val tab = source.channelTab("UCx", ChannelTab.VIDEOS, ChannelSort.POPULAR, PageKey("tab-1"))
        assertThat(tab.items).containsExactlyElementsIn(cleanOfMixed).inOrder()
        assertThat(tab.next).isEqualTo(PageKey("tab-2"))
        assertThat(tab.sorts).containsExactly(ChannelSort.LATEST, ChannelSort.POPULAR).inOrder()
        assertThat(fake.calls.last()).isEqualTo("channelTab:UCx:VIDEOS:POPULAR:tab-1")

        fake.playlistsPage = ItemsPage(listOf(cleanPlaylist, explicitPlaylist), PageKey("pl-2"))
        val playlists = source.channelPlaylists("UCx", null)
        assertThat(playlists.items).containsExactly(cleanPlaylist)
        assertThat(playlists.next).isEqualTo(PageKey("pl-2"))

        fake.recent = videos
        assertThat(source.recentVideos("UCx")).containsExactlyElementsIn(ordinary).inOrder()

        fake.relatedVideos = videos
        assertThat(source.related("cleanVideo01")).containsExactlyElementsIn(ordinary).inOrder()
    }

    // ---- stream ----

    @Test
    fun aVideoWhoseTitleOrChannelIsExplicitCannotBePlayed() {
        fake.streamDetails = details("Sexy twerking compilation")
        val byTitle = refusal { source.stream("streamVideo1") }
        assertThat(byTitle.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(byTitle.technicalName).isEqualTo("ContentFiltered")
        // The refusal never says what it was about.
        assertThat(byTitle.message).doesNotContain("twerk")
        assertThat(byTitle.message).doesNotContain("Sexy")
        fake.streamDetails = details("Weekend vlog", channel = "OnlyFans Official", id = "streamVideo2")
        assertThat(refusal { source.stream("streamVideo2", allowLowQuality = true) }.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
    }

    @Test
    fun anOrdinaryVideoPlaysAsItIs_withTheFlagItWasAskedWith() = runBlocking<Unit> {
        val ordinary = details("Football highlights", description = "All the goals of the week")
        fake.streamDetails = ordinary
        assertThat(source.stream("streamVideo1")).isSameInstanceAs(ordinary)
        source.stream("streamVideo1", allowLowQuality = true)
        assertThat(fake.calls).containsExactly("stream:streamVideo1:false", "stream:streamVideo1:true").inOrder()
    }

    @Test
    fun anExplicitDescriptionIsNotShown_theVideoStillPlays() = runBlocking<Unit> {
        fake.streamDetails = details("Football highlights", description = "Hot girls, naked, porn")
        val played = source.stream("streamVideo1")
        assertThat(played.description).isNull()
        assertThat(played.video.title).isEqualTo("Football highlights")
    }

    @Test
    fun theStoryboardsAndSubtitlesOfAPlayedVideoAreKept_alsoWhenItsDescriptionIsNotShown() = runBlocking<Unit> {
        val board = Storyboard(listOf("https://i.ytimg.com/sb/streamVideo1/storyboard3_L1/M0.jpg"), 80, 45, 10, 10, 50, 2_000)
        val subtitles = listOf(SubtitleTrack("en", "English", isAutoGenerated = false))
        fake.streamDetails = details("Football highlights", description = "Hot girls, naked, porn").copy(storyboards = listOf(board), subtitles = subtitles)

        val played = source.stream("streamVideo1")

        assertThat(played.storyboards).containsExactly(board)
        assertThat(played.subtitles).isEqualTo(subtitles)
    }

    @Test
    fun aVideoFoundExplicitOnceIsRefusedAndDroppedEverywhere_withoutAskingAgain() = runBlocking<Unit> {
        // Found by its snippet in a search: only that list carries one.
        fake.searchPage = ItemsPage(listOf(explicitSnippet), null)
        assertThat(source.search("dance").items).isEmpty()
        fake.calls.clear()
        fake.streamDetails = details("Dance cover 23", id = "badSnippet01")
        assertThat(refusal { source.stream("badSnippet01") }.kind).isEqualTo(SourceErrorKind.CONTENT_UNAVAILABLE)
        assertThat(source.related("badSnippet01")).isEmpty()
        assertThat(fake.calls).isEmpty()
        // ... and the same video, without its snippet, in another list.
        fake.relatedVideos = listOf(explicitSnippet.copy(snippet = null), clean)
        assertThat(source.related("cleanVideo01")).containsExactly(clean)
    }

    // ---- what is not content ----

    @Test
    fun linksAndVersionsAreTheSourcesOwn() = runBlocking<Unit> {
        assertThat(source.extractorVersion).isEqualTo("v-test")
        assertThat(source.parseLink("https://youtu.be/abcdefghijk")).isEqualTo(YouTubeLink.Video("abcdefghijk", null, null))
        assertThat(source.resolveChannelId(YouTubeLink.Channel("@somebody"))).isEqualTo("UCresolved0000000000000000")
    }

    @Test
    fun errorsOfTheSourceAreNotChanged() {
        fake.failure = SourceException(SourceErrorKind.NETWORK, "UnknownHostException", "offline", null)
        val thrown = refusal { source.search("dance") }
        assertThat(thrown).isSameInstanceAs(fake.failure)
    }

    @Test
    fun commentsPassAsYouTubeGivesThem_noneIsFilteredOrHeldBack() = runBlocking<Unit> {
        val comments = listOf(
            Comment("c1", "Pole dance fan", null, null, "striptease classes are the best", "1 day ago", null, 3, 0, null),
            Comment("c2", "Someone", null, null, "Nice video", "2 days ago", null, null, 1, PageKey("r")),
        )
        fake.commentsPage = CommentsPage(comments, PageKey("next"), totalCount = 2)

        assertThat(source.comments("abcdefghijk", CommentSort.NEWEST, PageKey("p"))).isEqualTo(fake.commentsPage)
        assertThat(source.commentReplies("abcdefghijk", PageKey("r"))).isEqualTo(fake.commentsPage)
        assertThat(fake.calls).containsExactly("comments:abcdefghijk:NEWEST:p", "replies:abcdefghijk:r").inOrder()
    }

    // ---- the guard ----

    @Test
    fun everyMethodOfTheInterfaceIsOverridden_noneIsLeftToItsDefault() {
        val own = SafeVideoSource::class.java.declaredMethods.filterNot { it.isSynthetic }.map { it.name }.toSet()
        val inherited = VideoSource::class.java.methods.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        // The interface's members are the suspend functions, parseLink and the extractor version (a property: getExtractorVersion).
        assertThat(inherited).isNotEmpty()
        assertThat(inherited - own).isEmpty()
    }

    // ---------------------------------------------------------------------------------------------------------------------------

    /** A source that has everything, and notes what it was asked (`calls`). */
    private class FakeSource : VideoSource {
        val calls = ArrayList<String>()
        var failure: SourceException? = null
        var searchPage = ItemsPage(emptyList(), null)
        var shortsPage = ShortsPage(emptyList(), null)
        var suggestionList = emptyList<String>()
        var channelDetails: ChannelDetails? = null
        var channelVideosPage = ItemsPage(emptyList(), null)
        var playlistPage: PlaylistPage? = null
        var streamDetails: StreamDetails? = null
        var relatedVideos = emptyList<VideoItem>()
        var shorts = emptyList<VideoItem>()
        var tabPage = ChannelTabPage(emptyList(), null)
        var playlistsPage = ItemsPage(emptyList(), null)
        var recent = emptyList<VideoItem>()
        var commentsPage = CommentsPage(emptyList(), null)

        override val extractorVersion = "v-test"

        override suspend fun comments(videoId: String, sort: CommentSort, page: PageKey?): CommentsPage {
            note("comments:$videoId:$sort:${page?.id}")
            return commentsPage
        }

        override suspend fun commentReplies(videoId: String, page: PageKey): CommentsPage {
            note("replies:$videoId:${page.id}")
            return commentsPage
        }

        private fun note(call: String) {
            calls += call
            failure?.let { throw it }
        }

        override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
            note("search:$query:$filter:${page?.id}")
            return searchPage
        }

        override suspend fun searchShorts(query: String, page: PageKey?): ShortsPage {
            note("searchShorts:$query:${page?.id}")
            return shortsPage
        }

        override suspend fun suggestions(query: String, language: String): List<String> {
            note("suggestions:$query:$language")
            return suggestionList
        }

        override suspend fun channel(channelId: String): ChannelDetails {
            note("channel:$channelId")
            return channelDetails!!
        }

        override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage {
            note("channelVideos:$channelId:${page?.id}")
            return channelVideosPage
        }

        override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
            note("playlist:$playlistId:${page?.id}")
            return playlistPage!!
        }

        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            note("stream:$videoId:$allowLowQuality")
            return streamDetails!!
        }

        override suspend fun related(videoId: String): List<VideoItem> {
            note("related:$videoId")
            return relatedVideos
        }

        override suspend fun channelShorts(channelId: String): List<VideoItem> {
            note("channelShorts:$channelId")
            return shorts
        }

        override suspend fun channelTab(channelId: String, tab: ChannelTab, sort: ChannelSort, page: PageKey?): ChannelTabPage {
            note("channelTab:$channelId:$tab:$sort:${page?.id}")
            return tabPage
        }

        override suspend fun channelPlaylists(channelId: String, page: PageKey?): ItemsPage {
            note("channelPlaylists:$channelId:${page?.id}")
            return playlistsPage
        }

        override suspend fun recentVideos(channelId: String): List<VideoItem> {
            note("recentVideos:$channelId")
            return recent
        }

        override fun parseLink(text: String): YouTubeLink? =
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})").find(text)?.let { YouTubeLink.Video(it.groupValues[1], null, null) }

        override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = "UCresolved0000000000000000"
    }
}
