package com.ahmadnayfeh.shasha.youtube.safety

import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.ahmadnayfeh.shasha.youtube.ChannelTabPage
import com.ahmadnayfeh.shasha.youtube.CommentSort
import com.ahmadnayfeh.shasha.youtube.CommentsPage
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.ahmadnayfeh.shasha.youtube.YouTubeLink
import java.util.Collections

/**
 * The built-in explicit-content filter, at the source: a [VideoSource] that answers like [delegate] except that nothing
 * sexual, indecent or suggestive ([ExplicitFilter]) gets through, for any profile of the TV app and in the phone app. Every
 * screen reads YouTube through a [VideoSource] (the phone page's search too: the TV runs it), so this is the one place that
 * covers them all; [com.ahmadnayfeh.shasha.youtube.YouTube.create] wraps the extractor in it, so neither app can forget it.
 *
 * What it does, method by method (every method of the interface is overridden here, on purpose: one left to the interface's
 * default would reach the extractor unfiltered, and a test fails when one is missing):
 * - [search]: an explicit query gets an empty page without a next page (the screens show their ordinary "no results"; the
 *   extractor is not even asked); other pages lose their explicit items, and keep their next-page keys, so paging goes on.
 * - [searchShorts]: like [search]: an explicit query gets an empty page, other pages lose their explicit Shorts (judged by their
 *   title: a Short in a search has no channel or snippet) and keep their next-page keys.
 * - [suggestions]: none for an explicit prefix; explicit suggestions are dropped.
 * - [channelVideos], [channelShorts], [channelTab], [channelPlaylists], [recentVideos] (the subscription feeds, RSS included),
 *   [related], [playlist]'s videos: items lose what is explicit, judged by title, channel name and, where the item has one,
 *   the description snippet ([VideoItem.snippet]).
 * - [channel], [playlist]: a channel or a playlist whose name (or the channel's description) is explicit is refused.
 * - [stream]: a video whose title or channel is explicit is refused, so it cannot be played or downloaded by any path (a link, a
 *   queue, a remembered id). Its description is left out when that is explicit.
 * - [comments], [commentReplies]: passed through as YouTube gives them (the owner's choice: comments are shown as they are, not
 *   filtered or held back).
 * - [parseLink], [resolveChannelId], [extractorVersion]: not content.
 *
 * A refusal is a [SourceException] of kind [SourceErrorKind.CONTENT_UNAVAILABLE], technical name "ContentFiltered": the screens'
 * ordinary "not available" (no lecture, and never the word or the title that caused it).
 *
 * A video found explicit once (by its snippet, say, which only some lists carry) is remembered for the life of the process,
 * so the same video is dropped from every other list and refused to [stream] without asking YouTube.
 */
class SafeVideoSource internal constructor(
    private val delegate: VideoSource,
    private val custom: ExplicitFilter?,
) : VideoSource {

    constructor(delegate: VideoSource) : this(delegate, null)

    // Resolved on use, not at construction: building the default filter takes a few tens of milliseconds, and this object is
    // made on whatever thread asks for the container's source.
    private val filter: ExplicitFilter get() = custom ?: ExplicitFilter.default

    private val held: MutableMap<String, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_HELD
        },
    )

    override val extractorVersion: String get() = delegate.extractorVersion

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        if (this.filter.isExplicit(query)) return ItemsPage(emptyList(), null)
        return delegate.search(query, filter, page).let { it.copy(items = it.items.safe()) }
    }

    override suspend fun searchShorts(query: String, page: PageKey?): ShortsPage {
        if (this.filter.isExplicit(query)) return ShortsPage(emptyList(), null)
        return delegate.searchShorts(query, page).let { it.copy(shorts = it.shorts.safe()) }
    }

    override suspend fun comments(videoId: String, sort: CommentSort, page: PageKey?): CommentsPage = delegate.comments(videoId, sort, page)

    override suspend fun commentReplies(videoId: String, page: PageKey): CommentsPage = delegate.commentReplies(videoId, page)

    override suspend fun suggestions(query: String, language: String): List<String> {
        if (filter.isExplicit(query)) return emptyList()
        return delegate.suggestions(query, language).filterNot { filter.isExplicit(it) }
    }

    override suspend fun channel(channelId: String): ChannelDetails {
        val details = delegate.channel(channelId)
        if (filter.isExplicit(details.channel.name, details.description?.take(SNIPPET_CHARS))) throw refused()
        return details
    }

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage =
        delegate.channelVideos(channelId, page).let { it.copy(items = it.items.safe()) }

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        val result = delegate.playlist(playlistId, page)
        if (filter.isExplicit(result.details.title, result.details.channelName)) throw refused()
        return result.copy(videos = result.videos.safe())
    }

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
        if (videoId in held) throw refused()
        val details = delegate.stream(videoId, allowLowQuality)
        if (filter.isExplicit(details.video.title, details.video.channelName)) {
            held[videoId] = true
            throw refused()
        }
        // A clean title over an explicit description: the video plays, the description does not show.
        val description = details.description
        return if (description != null && filter.isExplicit(description.take(SNIPPET_CHARS))) details.copy(description = null) else details
    }

    override suspend fun related(videoId: String): List<VideoItem> {
        if (videoId in held) return emptyList()
        return delegate.related(videoId).safe()
    }

    override suspend fun channelShorts(channelId: String): List<VideoItem> = delegate.channelShorts(channelId).safe()

    override suspend fun channelTab(channelId: String, tab: ChannelTab, sort: ChannelSort, page: PageKey?): ChannelTabPage =
        delegate.channelTab(channelId, tab, sort, page).let { it.copy(items = it.items.safe()) }

    override suspend fun channelPlaylists(channelId: String, page: PageKey?): ItemsPage =
        delegate.channelPlaylists(channelId, page).let { it.copy(items = it.items.safe()) }

    override suspend fun recentVideos(channelId: String): List<VideoItem> = delegate.recentVideos(channelId).safe()

    override fun parseLink(text: String): YouTubeLink? = delegate.parseLink(text)

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String = delegate.resolveChannelId(link)

    // ---- Judging items ------------------------------------------------------------------------------------------------

    private fun <T : SourceItem> List<T>.safe(): List<T> = filterNot { explicit(it) }

    private fun explicit(item: SourceItem): Boolean {
        val bad = when (item) {
            is VideoItem -> item.videoId in held || filter.isExplicit(item.title, item.channelName, item.snippet?.take(SNIPPET_CHARS))
            is ChannelItem -> filter.isExplicit(item.name, item.snippet?.take(SNIPPET_CHARS))
            is PlaylistItem -> filter.isExplicit(item.title, item.channelName)
        }
        if (bad && item is VideoItem) held[item.videoId] = true
        return bad
    }

    private fun refused() = SourceException(
        SourceErrorKind.CONTENT_UNAVAILABLE,
        "ContentFiltered",
        "Held back by the built-in content filter",
        null,
    )

    private companion object {
        /** Longer descriptions are judged by their start only: the end of one is links, credits and hashtags. */
        const val SNIPPET_CHARS = 300

        /** Videos remembered as explicit. */
        const val MAX_HELD = 2048
    }
}
