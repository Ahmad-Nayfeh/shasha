package com.ahmadnayfeh.shasha.ui.navigation

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes. */
sealed interface Screen {
    /** The rail and its tabs (home, search, subscriptions, watch later, history, settings). */
    @Serializable
    data object Main : Screen

    /** [name] shows while the channel loads (it is known from the card that was opened). */
    @Serializable
    data class Channel(val channelId: String, val name: String? = null) : Screen

    @Serializable
    data class Playlist(val playlistId: String) : Screen

    /** Inside a playlist, [playlistIndex] is the video's position: the next one plays when it ends. */
    @Serializable
    data class Player(val videoId: String, val playlistId: String? = null, val playlistIndex: Int = -1) : Screen

    /** Fixed test videos ("فيديوهات تجريبية"), under Settings → تشخيص. */
    @Serializable
    data object Diagnostics : Screen

    /** Settings → استيراد الاشتراكات. */
    @Serializable
    data object ImportSubscriptions : Screen

    /** Placeholder until the GitHub Releases updater arrives (SPEC phase 7). */
    @Serializable
    data object Updates : Screen
}
