package com.ahmadnayfeh.shasha.mobile.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** What the menu of a video offers; the root screen provides the implementation ([LocalVideoActions]). */
interface VideoActions {
    fun toggleWatchLater(video: VideoItem)

    /** Opens the «Save to» sheet for [video]: «Watch later» and the viewer's own playlists, each with a checkmark. */
    fun addToPlaylist(video: VideoItem)

    /** Opens the download sheet for [video]. */
    fun download(video: VideoItem)

    fun share(video: VideoItem)

    /** Opens the page of [channel]: the video's own channel, with the name the video gave (its page shows it until it has more). */
    fun openChannel(channel: ChannelItem)
}

private object NoVideoActions : VideoActions {
    override fun toggleWatchLater(video: VideoItem) = Unit
    override fun addToPlaylist(video: VideoItem) = Unit
    override fun download(video: VideoItem) = Unit
    override fun share(video: VideoItem) = Unit
    override fun openChannel(channel: ChannelItem) = Unit
}

val LocalVideoActions = staticCompositionLocalOf<VideoActions> { NoVideoActions }

/** Video id → how much of it was watched (0..1): the bar under the thumbnails. */
val LocalWatchProgress = compositionLocalOf<Map<String, Float>> { emptyMap() }

/** The ids of the videos saved for later: the menu says «Remove from Watch later» for those. */
val LocalWatchLaterIds = compositionLocalOf<Set<String>> { emptySet() }
