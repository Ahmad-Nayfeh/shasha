package com.ahmadnayfeh.shasha.mobile.player

import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.StateFlow

/** What the one player is playing for ([PlayerController.play]). */
enum class PlaybackMode {
    /** A video on the player screen: resumes where the viewer stopped, SponsorBlock skips, and the sound goes on in the background. */
    VIDEO,

    /**
     * A Short in the Shorts viewer: from its start, in a loop, upright (9:16) until its picture says otherwise, with no resume point
     * and no SponsorBlock, entered into the history only once it was watched a little. Like a video it plays on in the background
     * and in picture-in-picture (the same media session), and has quality, speed and captions.
     */
    SHORT,
}

/**
 * What the Shorts viewer needs of the player: the app's [PlayerController] (the same ExoPlayer, extraction, decoder-failure retry
 * and history as the player screen, not a copy of them) in the app, a recording fake in the tests.
 */
interface ShortsPlayer {
    val state: StateFlow<PlayerUiState>

    /** Plays [video] as a Short from its start (asking for what is playing already does nothing). */
    fun playShort(video: VideoItem)

    /** Looks up [videoId]'s streams now, so that [playShort] of it, a moment later, starts at once. */
    fun prefetch(videoId: String)

    fun togglePlay()

    /** Plays on, from where the Short is (a Short that is over starts again). */
    fun resumePlayback()

    fun pause()

    /** Tries the current Short again (the error's button). */
    fun retry()

    /** Stops and forgets everything (the viewer was left). */
    fun stop()
}
