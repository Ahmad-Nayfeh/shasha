package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.policy.PlayedPart

/**
 * What the player tells the in-app lock (SPEC §4) as a video goes: it starts, it reaches its end, or it
 * cannot play. The lock itself decides what each means ([com.ahmadnayfeh.shasha.lock.LockController],
 * which asks the policy engine). Calls return at once; the lock is written in the background, in order.
 */
interface PlaybackLocking {
    /** A player started [videoId] (also again after Home, standby, "time up" or a replay). */
    fun onStarted(videoId: String, playlistId: String?)

    /**
     * [videoId] reached its end; [nextVideoId] is the playlist video that follows, null when nothing does.
     * [played]: how much of it was really played ([PlayedPart.endedByItself]: skipped to its end, it stays
     * locked); null when unknown.
     */
    fun onEnded(videoId: String, nextVideoId: String?, played: PlayedPart?)

    /** [videoId] cannot play here any more (YouTube no longer serves it, or the rules now refuse it). */
    fun onUnavailable(videoId: String)
}
