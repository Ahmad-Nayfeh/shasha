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

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL, page: PageKey? = null): ItemsPage

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

    /** The first page of a channel's Shorts tab, newest first, every item marked short-form; empty when it has none. */
    suspend fun channelShorts(channelId: String): List<VideoItem> = emptyList()

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
