package com.ahmadnayfeh.shasha.ui.screens.channel

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.ChannelTabPage
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
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A [VideoSource] whose channel sections are scripted page by page ([sections]: page `n` of a section and order answers the
 * key `p<n>`; the last page has no next key). Every request is recorded as `"VIDEOS:POPULAR:1"` in [requests]; the ones
 * named in [failing] throw a network error, those in [hold] never answer. [offered] says which orders each section's first
 * page lists (all three where it says nothing), [tabs] which sections the channel has.
 */
class FakeChannelSource : VideoSource {
    override val extractorVersion = "test"

    var tabs: List<ChannelTab> = ChannelTab.entries
    val sections = HashMap<Pair<ChannelTab, ChannelSort>, List<List<SourceItem>>>()
    val offered = HashMap<ChannelTab, List<ChannelSort>>()
    var failing: Set<String> = emptySet()
    var hold: Set<String> = emptySet()
    var headerFails = false
    var headerRequests = 0
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun channel(channelId: String): ChannelDetails {
        headerRequests++
        if (headerFails) throw SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null)
        return ChannelDetails(ChannelItem(channelId, "Channel", null, null), null, null, tabs)
    }

    override suspend fun channelTab(channelId: String, tab: ChannelTab, sort: ChannelSort, page: PageKey?): ChannelTabPage {
        val n = page?.id?.removePrefix("p")?.toInt() ?: 0
        val name = "${tab.name}:${sort.name}:$n"
        requests += name
        if (name in hold) awaitCancellation()
        if (name in failing) throw SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null)
        val pages = sections[tab to sort].orEmpty()
        val sorts = offered[tab] ?: ChannelSort.entries
        return ChannelTabPage(pages.getOrElse(n) { emptyList() }, if (n + 1 < pages.size) PageKey("p${n + 1}") else null, sorts)
    }

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = error("the page reads channelTab")

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage = error("unused")

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage = error("unused")

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails = error("unused")

    override suspend fun recentVideos(channelId: String): List<VideoItem> = emptyList()

    override fun parseLink(text: String): YouTubeLink? = null

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = link.raw

    companion object {
        /** A playlist of the scripted channel, [n] making its id unique. */
        fun playlist(n: Int, title: String = "Playlist $n") =
            PlaylistItem("PL" + n.toString().padStart(32, '0'), title, "Channel", null, 10L)
    }
}
