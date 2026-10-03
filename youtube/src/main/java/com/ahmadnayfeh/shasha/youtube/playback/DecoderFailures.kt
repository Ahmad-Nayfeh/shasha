package com.ahmadnayfeh.shasha.youtube.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * What a decoder of this device failed on in this app run: per codec family and orientation, the smallest picture (short
 * side) it failed to decode. [PlaybackPlanner] leaves those pictures out for every video that follows, so the next Short
 * does not walk into the failure the last one did. Kept in memory only: a decoder in a bad state is cured by restarting the
 * app (or the TV), and a new run asks the device afresh.
 *
 * "From this size up", not "this size only": a decoder that failed on a 720x1280 H.264 picture will not decode a 1080x1920
 * one either, while the smaller pictures of the same codec are still worth playing. The orientation is part of the key
 * because the failures behind this are about vertical pictures: they must not hold back the same codec's landscape videos.
 *
 * Thread-safe: the planner runs on any thread, failures arrive on the main one.
 */
internal class DecoderFailures {
    private data class Key(val family: String, val portrait: Boolean)

    private val smallestFailing = ConcurrentHashMap<Key, Int>()

    /** A decoder failed on a [width]x[height] picture of [codecs] ("avc1.640028"). Without a size there is nothing to leave out. */
    fun record(codecs: String, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        smallestFailing.merge(key(codecs, width, height), minOf(width, height)) { old, new -> minOf(old, new) }
    }

    /** Whether this picture is as big as one that failed, in the same codec family and orientation: to be left out. */
    fun rulesOut(codecs: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val failed = smallestFailing[key(codecs, width, height)] ?: return false
        return minOf(width, height) >= failed
    }

    private fun key(codecs: String, width: Int, height: Int) = Key(VideoCodecs.family(codecs), portrait = height > width)
}
