package com.ahmadnayfeh.shasha.mobile.common

import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The lists the Shorts viewer was opened from. A route carries only a few small values, so the list waits here under a number
 * and the route names the number. Only the last few are kept (a viewer is opened from one list at a time); a number nobody
 * holds any more finds nothing, and [com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsQueue.of] copes with that.
 */
class ShortsQueues {
    private val queues = LinkedHashMap<Long, List<VideoItem>>()
    private var lastId = 0L

    /** Keeps [videos] and returns the number to open the viewer with. */
    fun put(videos: List<VideoItem>): Long {
        val id = ++lastId
        queues[id] = videos
        while (queues.size > KEPT) queues.remove(queues.keys.first())
        return id
    }

    fun get(id: Long): List<VideoItem>? = queues[id]

    companion object {
        private const val KEPT = 4
    }
}
