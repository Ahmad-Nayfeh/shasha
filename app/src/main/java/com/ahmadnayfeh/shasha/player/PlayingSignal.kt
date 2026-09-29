package com.ahmadnayfeh.shasha.player

/**
 * Feeds [PlaybackActivity] from every player at once: true while any of them wants to play. Going from
 * one playlist video to the next briefly has two players (the old one is released after the new one
 * started), and the old one's "stopped" must not hide the new one's "playing".
 *
 * Main thread only (players are created and released there).
 */
object PlayingSignal {
    private val playing = HashSet<Any>()

    fun update(player: Any, wantsToPlay: Boolean) {
        if (wantsToPlay) playing.add(player) else playing.remove(player)
        PlaybackActivity.setPlaying(playing.isNotEmpty())
    }
}
