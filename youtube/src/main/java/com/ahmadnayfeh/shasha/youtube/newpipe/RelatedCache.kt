package com.ahmadnayfeh.shasha.youtube.newpipe

import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The related videos of the last [maxEntries] videos that were extracted (or asked for), each for [maxAgeMs]: what lets
 * [NewPipeVideoSource.related] answer for a video the player just extracted without reading its watch page again. Only
 * the small mapped lists are kept, never the StreamInfo (its stream URLs and storyboards are large). Thread-safe.
 */
internal class RelatedCache(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val entries = object : LinkedHashMap<String, Pair<Long, List<VideoItem>>>(maxEntries + 1, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<VideoItem>>>): Boolean = size > maxEntries
    }

    /** What is kept for [videoId], or null when nothing is, or it is older than [maxAgeMs]. */
    @Synchronized
    fun get(videoId: String): List<VideoItem>? {
        val (storedAt, videos) = entries[videoId] ?: return null
        if (clock() - storedAt !in 0..maxAgeMs) {
            entries.remove(videoId)
            return null
        }
        return videos
    }

    @Synchronized
    fun put(videoId: String, videos: List<VideoItem>) {
        entries[videoId] = clock() to videos
    }

    companion object {
        const val MAX_ENTRIES = 48

        /** A few hours, like the home's «مقترحة لك»: suggestions do not go stale faster than that. */
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
