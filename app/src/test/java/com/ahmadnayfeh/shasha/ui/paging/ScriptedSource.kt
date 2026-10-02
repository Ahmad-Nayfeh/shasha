package com.ahmadnayfeh.shasha.ui.paging

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [VideoSource] whose search, channel and playlist pages are scripted page by page (page `n` answers the key `p<n>`;
 * the last scripted page has no next key, like the end of YouTube's results). Every request is recorded as
 * `"search:2"`, `"channel:0"`, `"playlist:1"` in [requests], and the pages named in [failing] throw a network error.
 */
class ScriptedSource : VideoSource {
    override val extractorVersion = "test"

    var searchPages: List<List<SourceItem>> = emptyList()
    var channelPages: List<List<VideoItem>> = emptyList()
    var playlistPages: List<List<VideoItem>> = emptyList()
    var failing: Set<String> = emptySet()

    val requests = CopyOnWriteArrayList<String>()

    private fun <T> answer(kind: String, key: PageKey?, pages: List<List<T>>): Pair<List<T>, PageKey?> {
        val n = key?.id?.removePrefix("p")?.toInt() ?: 0
        requests += "$kind:$n"
        if ("$kind:$n" in failing) throw SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null)
        return pages.getOrElse(n) { emptyList() } to if (n + 1 < pages.size) PageKey("p${n + 1}") else null
    }

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        val (items, next) = answer("search", page, searchPages)
        return ItemsPage(items, next)
    }

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage {
        val (items, next) = answer("channel", page, channelPages)
        return ItemsPage(items, next)
    }

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        val (items, next) = answer("playlist", page, playlistPages)
        return PlaylistPage(PlaylistItem(playlistId, "Playlist", null, null, null), items, next)
    }

    override suspend fun channel(channelId: String): ChannelDetails = ChannelDetails(ChannelItem(channelId, "Channel", null, null), null, null)

    override suspend fun suggestions(query: String, language: String): List<String> = emptyList()

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails =
        throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "NotInTests", "StreamDetails cannot be built outside :youtube", null)

    override suspend fun recentVideos(channelId: String): List<VideoItem> = emptyList()

    override fun parseLink(text: String): YouTubeLink? = null

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = link.raw

    companion object {
        /** A channel id of the right shape: "UC" and 22 characters. */
        const val CHANNEL = "UCscripted0000000000000a"

        /**
         * Videos [numbers] of one scripted list. [short]: which of them YouTube flags as Shorts (a profile that hides
         * Shorts will not show those). Ids are unique per number, so several pages never repeat a video by accident.
         */
        fun videos(numbers: IntRange, channelId: String = CHANNEL, short: (Int) -> Boolean = { false }): List<VideoItem> = numbers.map { n ->
            VideoItem(
                videoId = "vid" + n.toString().padStart(8, '0'),
                title = "Video $n",
                channelId = channelId,
                channelName = "Channel",
                durationSeconds = if (short(n)) 45L else 600L,
                publishedAtEpochMs = null,
                thumbnailUrl = null,
                viewCount = null,
                isLive = false,
                isShortForm = short(n),
                url = "https://www.youtube.com/watch?v=vid" + n.toString().padStart(8, '0'),
            )
        }

        /** [sizes]'s pages of consecutive videos: `pagesOf(8, 4, 6)` is videos 1..8, then 9..12, then 13..18. */
        fun pagesOf(vararg sizes: Int, short: (Int) -> Boolean = { false }): List<List<VideoItem>> {
            var next = 1
            return sizes.map { size -> videos(next until next + size, short = short).also { next += size } }
        }
    }
}
