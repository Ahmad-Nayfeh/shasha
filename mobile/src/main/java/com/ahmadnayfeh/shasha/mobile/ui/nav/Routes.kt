package com.ahmadnayfeh.shasha.mobile.ui.nav

import kotlinx.serialization.Serializable

/** The app's screens, as type-safe navigation routes. */
@Serializable
data object HomeRoute

/** [query]: a search to run at once (a shared text). */
@Serializable
data class SearchRoute(val query: String? = null)

@Serializable
data object LibraryRoute

@Serializable
data object SettingsRoute

/** [channelId]: "UC…", or a handle from a link ("@name"). */
@Serializable
data class ChannelRoute(val channelId: String)

@Serializable
data class PlaylistRoute(val playlistId: String)

/**
 * The player. [playlistId]: the playlist it is played from (the next video follows). [startSeconds]: from a link's `t=`.
 * [downloadId] ≥ 0: a downloaded file plays instead of a stream (then [videoId] is the video it was).
 */
@Serializable
data class WatchRoute(
    val videoId: String,
    val playlistId: String? = null,
    val startSeconds: Long = 0,
    val downloadId: Long = -1,
)

@Serializable
data object HistoryRoute

@Serializable
data object WatchLaterRoute

@Serializable
data object SubscriptionsRoute

@Serializable
data object DownloadsRoute

@Serializable
data object AboutRoute

@Serializable
data object UpdateRoute
