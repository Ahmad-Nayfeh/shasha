package com.ahmadnayfeh.shasha.mobile.ui.nav

import com.ahmadnayfeh.shasha.youtube.ChannelItem
import kotlinx.serialization.Serializable

/** The app's screens, as type-safe navigation routes. */
@Serializable
data object HomeRoute

/** [query]: a search to run at once (a shared text). */
@Serializable
data class SearchRoute(val query: String? = null)

/** The Shorts tab: a grid of Shorts that opens the Shorts viewer. */
@Serializable
data object ShortsTabRoute

@Serializable
data object LibraryRoute

@Serializable
data object SettingsRoute

/**
 * [channelId]: "UC…", or a handle from a link ("@name").
 *
 * [name] and [avatarUrl] are what the screen that opened the page already knew of the channel (a search result, the player
 * screen, the list of subscriptions): the page shows them when YouTube's own page for the channel cannot be read, instead of an
 * error. Both are null when the opener knew nothing (a link; a video's menu knows the name only), and the page then falls back
 * on the subscription or on the channel's videos.
 */
@Serializable
data class ChannelRoute(val channelId: String, val name: String? = null, val avatarUrl: String? = null) {
    companion object {
        /** The route to [channel]'s page with what is known of it (a blank name or a missing picture is left out). */
        fun of(channel: ChannelItem) = ChannelRoute(channel.channelId, channel.name.trim().takeIf { it.isNotEmpty() }, channel.avatarUrl)
    }
}

@Serializable
data class PlaylistRoute(val playlistId: String)

/**
 * The Shorts viewer, opened at [videoId] in the list that the screen it was opened from put in
 * [com.ahmadnayfeh.shasha.mobile.common.ShortsQueues] under [queueId] (-1 or a number nobody holds: the viewer shows the one Short).
 */
@Serializable
data class ShortsRoute(val videoId: String, val queueId: Long = -1)

/** One of the viewer's own playlists ([com.ahmadnayfeh.shasha.mobile.data.UserPlaylistRepository]). */
@Serializable
data class UserPlaylistRoute(val playlistId: Long)

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
