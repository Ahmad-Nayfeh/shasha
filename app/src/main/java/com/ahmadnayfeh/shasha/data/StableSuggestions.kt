package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.HomeSuggestions.Suggested
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/**
 * «مقترحة لك» that stay put. The ranking under it ([HomeSuggestions.suggested]) changes whenever a list comes in, and lists come
 * in while the viewer moves along the rows (one by one, a few hundred milliseconds apart, and again each time the home is shown):
 * the cards used to change under the remote. What the home shows is this instead, kept for the app's whole run (it is the
 * container's), for one profile at a time:
 *
 * - **The first list** of a profile session (a cold start, another profile, the app opened again: [reset]) is the ranking as it
 *   is then.
 * - **Meanwhile** a new ranking only adds at the end: what is shown keeps its place, new suggestions come after it.
 * - **After a video was played** (the history's newest viewing changed: [Suggested.head]) one more round is asked for, and the
 *   ranking replaces the list once that round has the played video's suggestions ([Suggested.settled]): back from the player,
 *   the row is about what was just watched.
 * - **«تحديث»** ([refresh]) shows at once what the row had not shown yet (the ranking with the cards on screen moved to its
 *   end), and asks for the lists older than half an hour again (what they bring is added at the end).
 *
 * A list that becomes empty upstream (the history cleared, suggestions or Shorts turned off) is empty here too. Thread-safe.
 */
class StableSuggestions {
    /** [HomeSuggestions.requestRound], once the suggestions exist (the container makes this first, cheaply). */
    @Volatile
    internal var requestRound: (maxAgeMs: Long) -> Long = { 0L }

    private var key: String? = null
    private var shown: Suggested? = null
    private var shownHead: String? = null

    /** The round a replacement waits for (after a video was played), or null. */
    private var waitFor: Long? = null
    private var rotate = false

    /** Bumped by [refresh] and [reset], so a home that is on screen shows the new list without waiting for the ranking. */
    private val changes = MutableStateFlow(0L)

    /** What [upstream] (the ranking for the profile [key]) shows on a home. */
    fun stable(key: String, upstream: Flow<Suggested>): Flow<Suggested> =
        combine(upstream, changes) { suggested, _ -> next(key, suggested) }.distinctUntilChanged()

    /** «تحديث» on the home. */
    fun refresh() {
        synchronized(this) { rotate = true }
        requestRound(HomeSuggestions.REFRESH_MAX_AGE_MS)
        changes.update { it + 1 }
    }

    /** A new session (the app opened again): the next home shows the ranking as it is then. */
    fun reset() {
        synchronized(this) {
            shown = null
            key = null
            waitFor = null
            rotate = false
        }
        changes.update { it + 1 }
    }

    /** The list to show for [upstream], the ranking now; public for the tests. */
    @Synchronized
    fun next(key: String, upstream: Suggested): Suggested {
        val old = shown?.takeIf { this.key == key }
        if (old != null && upstream.head != shownHead) {
            // A video was played (or the history changed): wait for the round that brings its suggestions.
            shownHead = upstream.head
            waitFor = requestRound(HomeSuggestions.FRESH_MS)
        }
        val wanted = waitFor
        val result = when {
            old == null -> {
                shownHead = upstream.head
                rotate = false
                waitFor = null
                upstream
            }
            wanted != null && upstream.settled >= wanted -> {
                waitFor = null
                rotate = false
                upstream
            }
            rotate -> {
                rotate = false
                upstream.copy(videos = rotated(old.videos, upstream.videos, HomeSuggestions.ROW_LIMIT), shorts = rotated(old.shorts, upstream.shorts, HomeSuggestions.SHORTS_ROW_LIMIT))
            }
            else -> upstream.copy(videos = appended(old.videos, upstream.videos), shorts = appended(old.shorts, upstream.shorts))
        }
        this.key = key
        shown = result
        return result
    }

    private companion object {
        /** [old] in its order, then what [new] has that it does not; nothing when [new] is empty. */
        fun appended(old: List<VideoItem>, new: List<VideoItem>): List<VideoItem> {
            if (new.isEmpty()) return emptyList()
            val have = old.mapTo(HashSet()) { it.videoId }
            val added = new.filter { it.videoId !in have }
            if (added.isEmpty()) return old
            return (old + added).take(LIMIT)
        }

        /**
         * [new] with the cards [old] had on screen (its first ones, up to [row] and at most half of it) moved to its end: a new
         * row whatever the lists bring.
         */
        fun rotated(old: List<VideoItem>, new: List<VideoItem>, row: Int): List<VideoItem> {
            if (new.isEmpty()) return emptyList()
            val seen = old.take(minOf(row, maxOf(1, old.size / 2))).mapTo(HashSet()) { it.videoId }
            val (again, fresh) = new.partition { it.videoId in seen }
            return fresh + again
        }

        /** Room for what is added: more than a row, as the profile's rules may hold some of it back. */
        const val LIMIT = HomeSuggestions.RANK_LIMIT * 2
    }
}
