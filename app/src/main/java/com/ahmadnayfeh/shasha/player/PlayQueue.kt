package com.ahmadnayfeh.shasha.player

import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource

/**
 * A playlist being played: the videos known so far, in order, and where the rest continues. Playing
 * the next item when one ends is the only autoplay the app has (SPEC §2).
 */
data class PlayQueue(val playlistId: String, val videos: List<VideoItem>, val next: PageKey?)

/**
 * Hands a playlist from its page to the player without loading it twice. In memory only, and only the
 * last [max] playlists (a queue can hold thousands of videos): the player reloads a playlist it does not
 * find here itself ([NextInPlaylist]), e.g. after the process was killed.
 */
class PlayQueues(private val max: Int = 2) {
    private val queues = object : LinkedHashMap<String, PlayQueue>(max + 1, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PlayQueue>): Boolean = size > max
    }

    @Synchronized
    fun put(queue: PlayQueue) {
        queues[queue.playlistId] = queue
    }

    @Synchronized
    fun get(playlistId: String): PlayQueue? = queues[playlistId]
}

/** Finds the video that follows position [index] of a playlist, loading more of it when needed. */
class NextInPlaylist(private val queues: PlayQueues, private val source: VideoSource) {

    /** @return null at the end of the playlist. @throws com.ahmadnayfeh.shasha.youtube.SourceException */
    suspend fun after(playlistId: String, index: Int): VideoItem? =
        load(playlistId) { index + 1 <= it.videos.lastIndex }.videos.getOrNull(index + 1)

    /**
     * Where [videoId] is in the playlist, loading more of it when needed; null when it is not there. For a
     * video that comes back without its position (the playback lock restores it after a restart).
     * @throws com.ahmadnayfeh.shasha.youtube.SourceException
     */
    suspend fun indexOf(playlistId: String, videoId: String): Int? =
        load(playlistId) { queue -> queue.videos.any { it.videoId == videoId } }.videos.indexOfFirst { it.videoId == videoId }.takeIf { it >= 0 }

    /** The playlist as known so far, with pages added until [enough] (or its end). */
    private suspend fun load(playlistId: String, enough: (PlayQueue) -> Boolean): PlayQueue {
        var queue = queues.get(playlistId)
            ?: source.playlist(playlistId).let { PlayQueue(playlistId, it.videos.distinctBy { v -> v.videoId }, it.next) }
        var pages = 0
        while (!enough(queue) && queue.next != null && pages < MAX_PAGES) {
            val more = source.playlist(playlistId, queue.next)
            val known = queue.videos.mapTo(HashSet()) { it.videoId }
            // Same rule as the playlist page: a video already listed is not listed twice.
            queue = PlayQueue(playlistId, queue.videos + more.videos.filter { known.add(it.videoId) }, more.next)
            pages++
        }
        queues.put(queue)
        return queue
    }

    private companion object {
        /** A broken continuation must not loop: a playlist page holds about 100 videos. */
        const val MAX_PAGES = 20
    }
}
