package com.ahmadnayfeh.shasha.sponsorblock

import androidx.media3.common.C
import androidx.media3.common.Player
import com.ahmadnayfeh.shasha.player.PlaybackController
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The screen's [PlaybackController] as [SkipController] sees it: a few reads of ExoPlayer's state per look, and
 * the controller's exact seek. The length falls back to the extraction's while ExoPlayer does not know it.
 */
class PlaybackSkipTarget(private val controller: PlaybackController, private val video: VideoItem) : SkipTarget {
    private val player: Player get() = controller.player

    override val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)

    override val durationMs: Long?
        get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: video.durationSeconds?.takeIf { it > 0 }?.let { it * 1000 }

    override val isPlaying: Boolean get() = player.isPlaying

    override val isLive: Boolean get() = video.isLive || player.isCurrentMediaItemLive

    override val isEnded: Boolean get() = player.playbackState == Player.STATE_ENDED

    override fun seekExactly(positionMs: Long, automatic: Boolean) = controller.seekExactly(positionMs, automatic)
}
