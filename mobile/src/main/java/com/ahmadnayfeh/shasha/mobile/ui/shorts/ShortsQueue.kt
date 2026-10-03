package com.ahmadnayfeh.shasha.mobile.ui.shorts

import com.ahmadnayfeh.shasha.mobile.data.watchUrl
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The Shorts the viewer pages through: the list it was opened from (a channel's Shorts tab, the Home's row, the search results'
 * row), in that order, and where in it the viewer starts. Swiping up is [next], swiping down the one before; neither goes past an
 * end of the list.
 */
class ShortsQueue private constructor(val items: List<VideoItem>, val startIndex: Int) {
    val size: Int get() = items.size

    /** The Short at [index]; null outside the queue. */
    fun at(index: Int): VideoItem? = items.getOrNull(index)

    /** The Short after the one at [index]: the one whose streams are looked up ahead. Null at the last one (and outside the queue). */
    fun next(index: Int): VideoItem? = if (index < 0) null else items.getOrNull(index + 1)

    fun hasNext(index: Int): Boolean = next(index) != null

    fun hasPrevious(index: Int): Boolean = index in 1 until size

    /** [index] held inside the queue: the first Short for a number before it, the last for one past the end. */
    fun clamp(index: Int): Int = index.coerceIn(0, items.lastIndex)

    companion object {
        /**
         * The queue of [videos] opened at the Short [startId]. A Short listed twice stays where it first is. A list that does not hold
         * [startId] (the list is gone: a route restored after the process died has only the id) becomes the one Short, which plays
         * and tells its title once its streams are found.
         */
        fun of(videos: List<VideoItem>, startId: String): ShortsQueue {
            val seen = HashSet<String>()
            val distinct = videos.filter { seen.add(it.videoId) }
            val index = distinct.indexOfFirst { it.videoId == startId }
            return if (index >= 0) ShortsQueue(distinct, index) else ShortsQueue(listOf(placeholder(startId)), 0)
        }

        private fun placeholder(videoId: String) = VideoItem(
            videoId = videoId, title = "", channelId = null, channelName = null, durationSeconds = null, publishedAtEpochMs = null,
            thumbnailUrl = null, viewCount = null, isLive = false, isShortForm = true, url = watchUrl(videoId),
        )
    }
}
