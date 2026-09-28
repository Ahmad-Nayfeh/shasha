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

    suspend fun channel(channelId: String): ChannelDetails

    suspend fun channelVideos(channelId: String, page: PageKey? = null): ItemsPage

    suspend fun playlist(playlistId: String, page: PageKey? = null): PlaylistPage

    suspend fun stream(videoId: String): StreamDetails

    /** Latest uploads for the home feed: RSS first, channel Videos tab as fallback. */
    suspend fun recentVideos(channelId: String): List<VideoItem>

    /** Parses watch?v=, youtu.be/, /shorts/, /live/, /embed/, playlist?list=, /channel/UC…, /@handle, /c/, /user/ and bare ids. */
    fun parseLink(text: String): YouTubeLink?

    /** Returns the "UC…" id for any channel link (handles, /c/ and /user/ need one network call). */
    suspend fun resolveChannelId(link: YouTubeLink.Channel): String
}

enum class SearchFilter { ALL, VIDEOS, CHANNELS, PLAYLISTS }

/** Opaque continuation token. Only valid for the [VideoSource] instance (and process) that returned it. */
@JvmInline
value class PageKey(val id: String)
