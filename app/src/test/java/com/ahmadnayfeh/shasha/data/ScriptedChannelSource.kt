package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [VideoSource] for the tests of a channel's uploads: each way of reading them is scripted on its own, so that any one can
 * fail while the others answer. Whatever is not scripted fails like YouTube does for a channel it cannot give
 * (`ContentNotAvailableException`, "Got error: ..."): the extractor's own plain exception, kind CONTENT_UNAVAILABLE.
 *
 * Every request is recorded in [calls]: `tab:<channel>` (a page after the first: `tab:<channel>:<key>`),
 * `playlist:<id>` (`playlist:<id>:<key>`), `rss:<channel>` and `channel:<channel>`.
 */
class ScriptedChannelSource : VideoSource {
    override val extractorVersion = "test"

    val calls = CopyOnWriteArrayList<String>()

    private val tabs = ConcurrentHashMap<String, Any>()
    private val playlists = ConcurrentHashMap<String, Any>()
    private val feeds = ConcurrentHashMap<String, Any>()
    private val headers = ConcurrentHashMap<String, Any>()

    /** Runs at the start of every Videos-tab request (a test holds it there to look at the state meanwhile). */
    @Volatile var beforeTab: suspend (channelId: String) -> Unit = {}

    // ---- scripting -----------------------------------------------------------------------------------------------------

    /** The Videos tab's first page ([key] null) or the page after [key]. [next]: the key of the page after it. */
    fun tab(channelId: String, videos: List<VideoItem>, next: String? = null, key: String? = null) {
        tabs[tabKey(channelId, key)] = ItemsPage(videos, next?.let(::PageKey))
    }

    fun tabFails(channelId: String, error: SourceException = contentUnavailable()) {
        tabs[tabKey(channelId, null)] = error
    }

    fun playlist(playlistId: String, videos: List<VideoItem>, next: String? = null, key: String? = null) {
        playlists[tabKey(playlistId, key)] = PlaylistPage(PlaylistItem(playlistId, "Videos", null, null, null), videos, next?.let(::PageKey))
    }

    fun playlistFails(playlistId: String, error: SourceException = contentUnavailable("Got error: \"The playlist does not exist.\"")) {
        playlists[tabKey(playlistId, null)] = error
    }

    fun feed(channelId: String, videos: List<VideoItem>) {
        feeds[channelId] = videos
    }

    fun feedFails(channelId: String, error: SourceException = contentUnavailable()) {
        feeds[channelId] = error
    }

    /** The channel's own page (name, picture): scripted to fail; it answers by default. */
    fun headerFails(channelId: String, error: SourceException = contentUnavailable()) {
        headers[channelId] = error
    }

    // ---- VideoSource ---------------------------------------------------------------------------------------------------

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage {
        calls += if (page == null) "tab:$channelId" else "tab:$channelId:${page.id}"
        beforeTab(channelId)
        return answer(tabs[tabKey(channelId, page?.id)], "the Videos tab of $channelId")
    }

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        calls += if (page == null) "playlist:$playlistId" else "playlist:$playlistId:${page.id}"
        return answer(playlists[tabKey(playlistId, page?.id)], "the playlist $playlistId")
    }

    override suspend fun recentVideos(channelId: String): List<VideoItem> {
        calls += "rss:$channelId"
        return answer(feeds[channelId], "the feed of $channelId")
    }

    override suspend fun channel(channelId: String): ChannelDetails {
        calls += "channel:$channelId"
        return answer(headers[channelId] ?: ChannelDetails(ChannelItem(channelId, "Channel $channelId", null, 1_000L), null, null), "the page of $channelId")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> answer(scripted: Any?, what: String): T = when (scripted) {
        null -> throw contentUnavailable("Got error: \"not scripted: $what\"")
        is SourceException -> throw scripted
        else -> scripted as T
    }

    private fun tabKey(id: String, key: String?) = if (key == null) id else "$id/$key"

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage = error("unused")

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails = error("unused")

    override fun parseLink(text: String): YouTubeLink? = null

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = link.raw

    companion object {
        /** What the extractor throws for an error answer of a channel request, as the app sees it. */
        fun contentUnavailable(message: String = "Got error: \"This channel does not exist.\"") =
            SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentNotAvailableException", message, null)

        fun offline() = SourceException(SourceErrorKind.NETWORK, "UnknownHostException", "Unable to resolve host", null)
    }
}
