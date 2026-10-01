package com.ahmadnayfeh.shasha.ui

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import java.util.concurrent.CopyOnWriteArrayList

/** A [VideoSource] for the browsing screens' tests: search and playlist pages come from maps. */
class FakeUiSource : VideoSource {
    override val extractorVersion = "test"

    data class SearchCall(val query: String, val filter: SearchFilter, val page: PageKey?)

    val searches = CopyOnWriteArrayList<SearchCall>()
    var searchResult: (SearchCall) -> ItemsPage = { ItemsPage(emptyList(), null) }

    val playlistCalls = CopyOnWriteArrayList<PageKey?>()
    val playlistPages = HashMap<PageKey?, List<VideoItem>>()
    val playlistNext = HashMap<PageKey?, PageKey?>()

    /** Suggestion requests (text, language) as they reach YouTube; [suggest] answers them. */
    val suggestionCalls = CopyOnWriteArrayList<Pair<String, String>>()
    var suggest: suspend (String, String) -> List<String> = { q, _ -> listOf(q, "$q 1", "$q 2") }

    override suspend fun suggestions(query: String, language: String): List<String> {
        suggestionCalls += query to language
        return suggest(query, language)
    }

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        val call = SearchCall(query, filter, page)
        searches += call
        return searchResult(call)
    }

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        playlistCalls += page
        val videos = playlistPages[page] ?: throw SourceException(SourceErrorKind.NETWORK, "UnknownHostException", null, null)
        return PlaylistPage(PlaylistItem(playlistId, "Playlist", null, null, null), videos, playlistNext[page])
    }

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails =
        throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NotInTests", "StreamDetails cannot be built outside :youtube", null)

    override suspend fun channel(channelId: String): ChannelDetails = error("unused")

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = error("unused")

    override suspend fun recentVideos(channelId: String): List<VideoItem> = emptyList()

    override fun parseLink(text: String): YouTubeLink? = null

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = link.raw
}
