package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.BuildConfig
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.MAX_SUGGESTIONS
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
import com.ahmadnayfeh.shasha.youtube.YouTubeLinkParser
import com.ahmadnayfeh.shasha.youtube.newpipe.ItemMapper.nonNegativeOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.feed.FeedInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeChannelHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * [VideoSource] backed by NewPipeExtractor. Constructible without an Android Context (JVM tests).
 * NewPipe's global state is initialised once per process; the first client wins.
 */
internal class NewPipeVideoSource(okHttpClient: OkHttpClient) : VideoSource {

    private val downloader: NewPipeDownloader = NewPipeInit.ensure(okHttpClient)
    private val service: StreamingService = ServiceList.YouTube
    private val pages = PageStore()

    override val extractorVersion: String = BuildConfig.EXTRACTOR_VERSION

    override suspend fun search(query: String, filter: SearchFilter, page: PageKey?): ItemsPage {
        val q = query.trim()
        if (q.isEmpty()) return ItemsPage(emptyList(), null)
        return io {
            val handler: SearchQueryHandler = service.searchQHFactory.fromQuery(q, listOf(filter.contentFilter()), "")
            val owner = "search:${filter.name}:$q"
            val result = if (page == null) {
                // The results only: SearchInfo would also parse (and report failures of) the spelling
                // suggestion and info boxes, which this app never shows.
                val extractor = service.getSearchExtractor(handler)
                extractor.fetchPage()
                noResultsIsEmpty { extractor.initialPage }
            } else {
                val (next, _) = pages.get(owner, page) ?: return@io ItemsPage(emptyList(), null)
                noResultsIsEmpty { SearchInfo.getMoreItems(service, handler, next) }
            }
            failIfBroken(result.items, result.errors)
            ItemsPage(result.items.mapNotNull(ItemMapper::toSourceItem), pages.put(owner, result.nextPage))
        }
    }

    override suspend fun suggestions(query: String, language: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return io {
            // A new extractor per call (thread-safe); its localization only sets Accept-Language on this request.
            val extractor = service.suggestionExtractor
            extractor.forceLocalization(Localization(if (language.startsWith("en")) "en" else "ar"))
            extractor.forceContentCountry(ContentCountry("SA"))
            cleanSuggestions(extractor.suggestionList(q).orEmpty())
        }
    }

    override suspend fun channel(channelId: String): ChannelDetails = io {
        val info = ChannelInfo.getInfo(service, channelUrl(channelId))
        ChannelDetails(
            channel = ChannelItem(
                channelId = info.id,
                name = info.name.orEmpty(),
                avatarUrl = ItemMapper.avatar(info.avatars),
                subscriberCount = info.subscriberCount.nonNegativeOrNull(),
            ),
            bannerUrl = ItemMapper.banner(info.banners),
            description = info.description?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun channelVideos(channelId: String, page: PageKey?): ItemsPage = io {
        val owner = "channel:$channelId"
        if (page == null) {
            val (videos, next) = firstChannelVideos(channelId)
            ItemsPage(videos, pages.put(owner, next))
        } else {
            val (next, _) = pages.get(owner, page) ?: return@io ItemsPage(emptyList(), null)
            val more = ChannelTabInfo.getMoreItems(service, videosTab(channelId), next)
            failIfBroken(more.items, more.errors)
            ItemsPage(more.items.toVideos(channelId.takeIf(YouTubeLinkParser::isChannelId)), pages.put(owner, more.nextPage))
        }
    }

    /** The first page of a channel's Videos tab and its continuation. Blocking: call inside [io]. */
    private fun firstChannelVideos(channelId: String): Pair<List<VideoItem>, Page?> {
        val info = ChannelTabInfo.getInfo(service, videosTab(channelId))
        failIfBroken(info.relatedItems, info.errors)
        return info.relatedItems.toVideos(channelId.takeIf(YouTubeLinkParser::isChannelId)) to info.nextPage
    }

    private fun videosTab(channelId: String) =
        service.channelTabLHFactory.fromQuery(channelPath(channelId), listOf(ChannelTabs.VIDEOS), "")

    override suspend fun playlist(playlistId: String, page: PageKey?): PlaylistPage {
        if (!YouTubeLinkParser.isPlaylistId(playlistId)) {
            throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "InvalidPlaylistId", "Not a playlist id: $playlistId", null)
        }
        return playlistPage(playlistId, page)
    }

    private suspend fun playlistPage(playlistId: String, page: PageKey?): PlaylistPage = io {
        val url = "https://www.youtube.com/playlist?list=$playlistId"
        val owner = "playlist:$playlistId"
        val stored = page?.let { pages.get(owner, it) }
        if (stored == null) {
            val info = PlaylistInfo.getInfo(service, url)
            val details = PlaylistItem(
                playlistId = playlistId,
                title = info.name.orEmpty(),
                channelName = info.uploaderName?.takeIf { it.isNotBlank() },
                thumbnailUrl = ItemMapper.thumbnail(info.thumbnails),
                videoCount = info.streamCount.nonNegativeOrNull(),
            )
            // An unknown/expired key ends the list instead of repeating the first page.
            if (page != null) return@io PlaylistPage(details, emptyList(), null)
            failIfBroken(info.relatedItems, info.errors)
            PlaylistPage(details, info.relatedItems.toVideos(null), pages.put(owner, info.nextPage, details))
        } else {
            val (next, details) = stored
            val more = PlaylistInfo.getMoreItems(service, url, next)
            failIfBroken(more.items, more.errors)
            PlaylistPage(details as PlaylistItem, more.items.toVideos(null), pages.put(owner, more.nextPage, details))
        }
    }

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
        if (!YouTubeLinkParser.isVideoId(videoId)) {
            throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "InvalidVideoId", "Not a video id: $videoId", null)
        }
        return io { StreamMapper.toDetails(StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId"), allowLowQuality) }
    }

    override suspend fun recentVideos(channelId: String): List<VideoItem> {
        val fromRss = withContext(Dispatchers.IO) {
            try {
                val info = FeedInfo.getInfo(service, channelUrl(channelId))
                val fallbackName = info.name?.takeIf { it.isNotBlank() }
                info.relatedItems.mapNotNull {
                    ItemMapper.toVideo(it, channelId.takeIf(YouTubeLinkParser::isChannelId), fallbackName, exactDates = true)
                }.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                ensureActive()
                null // RSS has intermittent 404 windows: fall back to the channel's Videos tab.
            }
        }
        // Only the first page, and no continuation key: a feed sync of hundreds of channels must not
        // push the keys of the list the user is scrolling out of the (bounded) page store.
        return fromRss ?: io { firstChannelVideos(channelId).first }
    }

    override fun parseLink(text: String): YouTubeLink? = YouTubeLinkParser.parse(text)

    override suspend fun resolveChannelId(link: YouTubeLink.Channel): String {
        val raw = link.raw.trim()
        if (YouTubeLinkParser.isChannelId(raw)) return raw
        return io {
            val handler = service.channelLHFactory.fromUrl(channelUrl(raw))
            // resolve_url is one small request; the full channel page is the fallback.
            val resolved = runCatching { YoutubeChannelHelper.resolveChannelId(handler.id) }.getOrNull()
            resolved?.takeIf(YouTubeLinkParser::isChannelId)
                ?: ChannelInfo.getInfo(service, handler.url).id.takeIf(YouTubeLinkParser::isChannelId)
                ?: throw SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "UnresolvedChannel", "No channel id for $raw", null)
        }
    }

    /** Runs blocking extractor code on IO and maps every failure to [SourceException]. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        downloader.beginTrace()
        try {
            block()
        } catch (e: SourceException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive()
            throw ErrorMapper.map(e, downloader.blockedStatusSinceTrace())
        } catch (e: LinkageError) {
            // e.g. a class missing after shrinking or desugaring: the extractor is unusable, say so.
            throw SourceException(SourceErrorKind.EXTRACTION_BROKEN, ErrorMapper.technicalName(e), e.message, e)
        } catch (e: StackOverflowError) {
            // Rhino (JS deobfuscation) can overflow small thread stacks on weak devices.
            throw SourceException(SourceErrorKind.EXTRACTION_BROKEN, ErrorMapper.technicalName(e), e.message, e)
        }
    }

    private fun List<InfoItem>.toVideos(channelId: String?): List<VideoItem> =
        filterIsInstance<StreamInfoItem>().mapNotNull { ItemMapper.toVideo(it, channelId) }

    private fun SearchFilter.contentFilter(): String = when (this) {
        SearchFilter.ALL -> YoutubeSearchQueryHandlerFactory.ALL
        SearchFilter.VIDEOS -> YoutubeSearchQueryHandlerFactory.VIDEOS
        SearchFilter.CHANNELS -> YoutubeSearchQueryHandlerFactory.CHANNELS
        SearchFilter.PLAYLISTS -> YoutubeSearchQueryHandlerFactory.PLAYLISTS
    }

    internal companion object {
        /**
         * A list that came back empty while the extractor logged errors (the page failed, or every item
         * failed to parse) is a broken extraction, not "no results". YouTube's own "no results" answer
         * is not an error.
         */
        fun failIfBroken(items: List<*>, errors: List<Throwable>?) {
            if (items.isNotEmpty()) return
            errors.orEmpty().firstOrNull { it !is SearchExtractor.NothingFoundException }?.let { throw it }
        }

        /** Trimmed, one line each, no blanks or repeats (ignoring case), at most [MAX_SUGGESTIONS]. */
        fun cleanSuggestions(raw: List<String?>): List<String> {
            val seen = HashSet<String>()
            return raw.asSequence()
                .mapNotNull { it?.replace(Regex("\\s+"), " ")?.trim()?.takeIf(String::isNotEmpty) }
                .filter { seen.add(it.lowercase()) }
                .take(MAX_SUGGESTIONS)
                .toList()
        }

        /** NPE reports a search without results (a typo, a narrow filter) as NothingFoundException. */
        fun <T : InfoItem> noResultsIsEmpty(load: () -> InfoItemsPage<T>): InfoItemsPage<T> = try {
            load()
        } catch (e: SearchExtractor.NothingFoundException) {
            InfoItemsPage.emptyPage()
        }

        /** "UC…" → channel/UC…; "@handle", "c/x", "user/x" are kept (percent-encoded). */
        fun channelPath(channelIdOrRaw: String): String {
            val raw = channelIdOrRaw.trim().removePrefix("/")
            return if (YouTubeLinkParser.isChannelId(raw)) "channel/$raw" else encodePath(raw)
        }

        fun channelUrl(channelIdOrRaw: String) = "https://www.youtube.com/" + channelPath(channelIdOrRaw)

        private fun encodePath(path: String): String = buildString {
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "/@.-_~")) append(c.toChar())
                else append('%').append("%02X".format(c))
            }
        }
    }
}

/**
 * NewPipe keeps its downloader and locale in static state: set them exactly once. Note: v0.26.5's YouTube
 * service supports only en-GB and falls back to it, so YouTube's texts ("2 weeks ago", view counts) come in
 * English whatever is preferred here, and NewPipe's English parsers read them.
 */
internal object NewPipeInit {
    private var downloader: NewPipeDownloader? = null

    @Synchronized
    fun ensure(okHttpClient: OkHttpClient): NewPipeDownloader =
        downloader ?: NewPipeDownloader(okHttpClient).also {
            NewPipe.init(it, Localization("ar", "SA"), ContentCountry("SA"))
            downloader = it
        }
}
