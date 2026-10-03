package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.youtube.VideoItem
import java.util.UUID

/**
 * The Shorts the Shorts player goes through: the list the first one was chosen from (a search's Shorts row, a channel's Shorts,
 * the home's rows, the Shorts of a video's suggestions), in the order the viewer saw them.
 */
class ShortsQueues(private val max: Int = 4) {
    private val queues = object : LinkedHashMap<String, List<VideoItem>>(max + 1, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<VideoItem>>): Boolean = size > max
    }

    /** Keeps [shorts] (no repeats, in order) and gives the key the Shorts player's route carries. */
    @Synchronized
    fun put(shorts: List<VideoItem>): String {
        val key = UUID.randomUUID().toString()
        queues[key] = shorts.distinctBy { it.videoId }
        return key
    }

    /** The list behind [key], or null when it is gone (in memory only: after the process was killed, the player has its one Short). */
    @Synchronized
    fun get(key: String): List<VideoItem>? = queues[key]
}
