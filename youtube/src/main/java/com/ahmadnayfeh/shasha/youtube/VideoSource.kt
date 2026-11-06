package com.ahmadnayfeh.shasha.youtube

/**
 * Everything the app needs from YouTube. The app never touches NewPipeExtractor directly:
 * swapping the extraction library means changing this module only.
 *
 * Every suspend function runs on Dispatchers.IO and throws only [SourceException].
 */
interface VideoSource {
    /** Pinned extractor version, shown on the error screen (e.g. "v0.26.5"). */
    val extractorVersion: String

    /**
     * Pays the one-time cost of the first extraction of the process (the service's first request, the player script and its
     * signature code) in the background, so the first video the viewer opens is as fast as the second. Never throws; the
     * default does nothing (tests, fakes).
     */
    suspend fun warmUp() {}

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL, page: PageKey? = null): ItemsPage

    /**
     * The Shorts YouTube finds for [query], page by page (null [page]: the first), best match first: the «Shorts» row beside a
     * search's results, in both apps. Every item is a [VideoItem] with [VideoItem.isShortForm] true.
     *
     * Why a method of its own: [search] answers videos, channels and playlists. YouTube puts the Shorts a search finds in
     * shelves of their own (two a page, which the extractor does not read) and answers a search for Shorts alone ("Type:
     * Shorts", about 25 a page), which the extractor cannot ask for. This asks for that and reads the Shorts itself, from
     * wherever in the answer YouTube lists them, so a change of its layout costs the Shorts, never a screen's results.
     *
     * What YouTube says of a Short in a search is its id, title, view count and (vertical) picture; for some queries also its
     * channel and length, for most not ([VideoItem.channelId], [VideoItem.channelName] and [VideoItem.durationSeconds] are then
     * null), and never its date; [stream] has them all. A rule that needs the channel (a block of the household, a child's
     * allowlist) therefore judges such a Short when it is opened, as the player's policy check does. The pages of one search
     * overlap (a Short comes again a page later): a list drops what it already shows.
     *
     * Unfiltered YouTube content: the app decides who may see it, the profile's Shorts rule first of all; the built-in explicit
     * filter ([com.ahmadnayfeh.shasha.youtube.safety.SafeVideoSource]) holds back an explicit [query] and explicit titles. A failure
     * is a [SourceException] like any other's, but Shorts are an extra beside the results: a screen shows no row for it, not its error
     * screen. A [PageKey] belongs to the [query] that returned it.
     *
     * The default answers nothing, so other implementations need not know it.
     */
    suspend fun searchShorts(query: String, page: PageKey? = null): ShortsPage = ShortsPage(emptyList(), null)

    /**
     * YouTube's search suggestions for what was typed so far, best first, at most [MAX_SUGGESTIONS], without
     * duplicates or blanks ([language]: "ar" or "en", YouTube's interface language for them). An empty query
     * gives none. Unfiltered YouTube text: the app decides who may see it.
     */
    suspend fun suggestions(query: String, language: String = "ar"): List<String> = emptyList()

    suspend fun channel(channelId: String): ChannelDetails

    suspend fun channelVideos(channelId: String, page: PageKey? = null): ItemsPage

    suspend fun playlist(playlistId: String, page: PageKey? = null): PlaylistPage

    /**
     * [allowLowQuality]: a video that only offers a muxed file of 360p or less (e.g. "made for kids" videos
     * with this extractor) comes back playable, marked with [StreamDetails.lowQualityHeight], instead of
     * failing with [SourceErrorKind.LOW_QUALITY_ONLY]. The caller must then say so on screen: it never plays
     * silently at 360p.
     */
    suspend fun stream(videoId: String, allowLowQuality: Boolean = false): StreamDetails

    /**
     * The videos YouTube suggests beside [videoId] (the related items of its watch page), best first, at most
     * [MAX_RELATED]: videos only (no mixes, channels or playlists), never [videoId] itself, no repeats.
     *
     * A video [stream] extracted lately is answered from that same extraction, without any request: playing a video and
     * then showing its suggestions never fetches it twice. Otherwise its watch page is read once (no stream URLs) and
     * the answer is kept a few hours. Unfiltered YouTube content: the app decides who may see it.
     */
    suspend fun related(videoId: String): List<VideoItem> = emptyList()

    /**
     * One page of [videoId]'s comments (null [page]: the first) in the order [sort]: YouTube's «Top comments» or «Newest first»
     * (it has no other). A video whose uploader turned comments off answers an empty page marked [CommentsPage.disabled]. A
     * [PageKey] belongs to the [videoId] and [sort] that returned it; an unknown or expired one ends the list (an empty page).
     * A Short's comments are asked for the same way. Unfiltered YouTube content, as YouTube shows it.
     *
     * The default answers nothing, so other implementations need not know it.
     */
    suspend fun comments(videoId: String, sort: CommentSort = CommentSort.TOP, page: PageKey? = null): CommentsPage = CommentsPage(emptyList(), null)

    /**
     * A page of the replies to one of [videoId]'s comments: [page] is the comment's [Comment.replies] (its first page), then the
     * [CommentsPage.next] of the page before. Oldest first, as YouTube lists replies.
     */
    suspend fun commentReplies(videoId: String, page: PageKey): CommentsPage = CommentsPage(emptyList(), null)

    /** The first page of a channel's Shorts tab, newest first, every item marked short-form; empty when it has none. */
    suspend fun channelShorts(channelId: String): List<VideoItem> = emptyList()

    /**
     * One page of a section of a channel's page ([tab]), in the order [sort] (null [page]: the first page). The videos,
     * Shorts and live streams are [VideoItem]s (every one of the Shorts marked short-form), the playlists [PlaylistItem]s;
     * a section the channel does not have is empty. Every section pages on its own: a [PageKey] belongs to the
     * [channelId], [tab] and [sort] that returned it.
     *
     * Only the sorts in [ChannelTabPage.sorts] of the section's first page may be asked for ([ChannelSort.LATEST] always
     * may), and only for the sections that list videos ([ChannelTab.listsVideos]); anything else fails with
     * [SourceErrorKind.CONTENT_UNAVAILABLE]. Unfiltered YouTube content: the app decides who may see it.
     *
     * The default serves what [channelVideos] and [channelShorts] know: the newest videos (paged) and the first page of
     * the Shorts, nothing else.
     */
    suspend fun channelTab(channelId: String, tab: ChannelTab, sort: ChannelSort = ChannelSort.LATEST, page: PageKey? = null): ChannelTabPage {
        if (sort != ChannelSort.LATEST) return ChannelTabPage(emptyList(), null)
        return when (tab) {
            ChannelTab.VIDEOS -> channelVideos(channelId, page).let { ChannelTabPage(it.items, it.next) }
            ChannelTab.SHORTS -> ChannelTabPage(if (page == null) channelShorts(channelId) else emptyList(), null)
            ChannelTab.LIVE, ChannelTab.PLAYLISTS -> ChannelTabPage(emptyList(), null)
        }
    }

    /**
     * A channel's Playlists tab, page by page ([PlaylistItem]s; a channel without playlists answers an empty page). Used by
     * the phone app's channel page; the default answers nothing, so other implementations need not know it.
     */
    suspend fun channelPlaylists(channelId: String, page: PageKey? = null): ItemsPage = ItemsPage(emptyList(), null)

    /** Latest uploads for the home feed: RSS first, channel Videos tab as fallback. */
    suspend fun recentVideos(channelId: String): List<VideoItem>

    /** Parses watch?v=, youtu.be/, /shorts/, /live/, /embed/, playlist?list=, /channel/UC…, /@handle, /c/, /user/ and bare ids. */
    fun parseLink(text: String): YouTubeLink?

    /** Returns the "UC…" id for any channel link (handles, /c/ and /user/ need one network call). */
    suspend fun resolveChannelId(link: YouTubeLink.Channel): String
}

/** At most this many from [VideoSource.suggestions]. */
const val MAX_SUGGESTIONS = 8

/** At most this many from [VideoSource.related]. */
const val MAX_RELATED = 24

enum class SearchFilter { ALL, VIDEOS, CHANNELS, PLAYLISTS }

/** Opaque continuation token. Only valid for the [VideoSource] instance (and process) that returned it. */
@JvmInline
value class PageKey(val id: String)
