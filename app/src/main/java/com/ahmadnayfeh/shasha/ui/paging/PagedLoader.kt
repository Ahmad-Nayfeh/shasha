package com.ahmadnayfeh.shasha.ui.paging

import androidx.compose.runtime.Immutable
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One page from the source: its items and the key of the next page (null at the end). */
data class Page<T>(val items: List<T>, val next: PageKey?)

@Immutable
data class PagedState<T>(
    val items: List<T> = emptyList(),
    /** The first page is on its way. */
    val loadingFirst: Boolean = true,
    /** A later page is on its way (also while the first screen is still being filled): the list ends in a loader. */
    val loadingMore: Boolean = false,
    /** The source has no more pages: nothing more is asked for. */
    val endReached: Boolean = false,
    /** The first page failed: the screen shows the unified error screen. */
    val error: SourceException? = null,
    /** A later page failed: the items stay, and a retry row ends the list. */
    val moreError: SourceException? = null,
)

/**
 * A list loaded page by page (search results, a channel's videos, a playlist).
 *
 * Gentle on a weak TV and on YouTube: one request at a time, a page is never asked for twice, items
 * already shown are dropped from later pages (continuations repeat items, and a repeated key would
 * crash a lazy grid), and a failed page is retried only when the user asks ([retryMore]), never just
 * because focus reached the end of the list again.
 *
 * A page is not a screenful. YouTube's «all» search comes through the extractor in pages of two to eleven items
 * (twenty videos per page for the videos filter), and the profile's rules ([shown]: blocked words and channels, Shorts)
 * hide more of each. So the loader asks for pages until the screen is well filled: the first screen holds at least
 * [firstScreen] shown items, and each [loadMore] brings at least [nextBatch] new shown ones, as long as the source has
 * pages and a few requests (a run) allow: [PAGES_PER_RUN] pages, or [PAGES_WITHOUT_ITEMS] while the pages bring nothing
 * the screen shows. The first page appears at once; the rest is added while [PagedState.loadingMore] shows the loader.
 *
 * @param shown whether the screen shows an item (the profile's rules, as they are when a page arrives); a page whose
 *   items are all hidden counts for nothing. The items in [PagedState.items] are all those the source gave: the
 *   screen filters them again, so a rule that changes meanwhile takes effect at once.
 */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val keyOf: (T) -> String,
    private val shown: (T) -> Boolean = { true },
    private val firstScreen: Int = FIRST_SCREEN,
    private val nextBatch: Int = NEXT_BATCH,
    private val fetch: suspend (PageKey?) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>(loadingFirst = false))
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private var next: PageKey? = null
    private var job: Job? = null

    /** Where the list continues (null at the end), e.g. for handing a playlist to the player. */
    val nextPage: PageKey? get() = next

    /** (Re)loads from the first page, dropping what was there; then fills the first screen. */
    fun start() {
        job?.cancel()
        next = null
        _state.value = PagedState(loadingFirst = true)
        job = scope.launch {
            try {
                val page = fetch(null)
                next = page.next
                _state.value = PagedState(items = page.items.distinctBy(keyOf), loadingFirst = false, endReached = page.next == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = PagedState(loadingFirst = false, error = e.asSourceException())
                return@launch
            }
            fill(untilFirstScreenIsFull = true)
        }
    }

    /** Drops everything (no request in flight afterwards). */
    fun reset() {
        job?.cancel()
        next = null
        _state.value = PagedState(loadingFirst = false)
    }

    /** Asks for more; ignored while a page loads, at the end, or after a failure. */
    fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || s.loadingFirst || s.endReached || s.error != null || s.moreError != null) return
        if (next == null) return
        job = scope.launch { fill(untilFirstScreenIsFull = false) }
    }

    /** After a later page failed: try it again. */
    fun retryMore() {
        if (_state.value.moreError == null) return
        _state.update { it.copy(moreError = null) }
        loadMore()
    }

    /**
     * One run: pages from [next] on, one request at a time, until the first screen is full
     * ([untilFirstScreenIsFull]) or this run brought [nextBatch] new shown items, the source has no more, or the run's
     * page allowance is spent. A failed page ends the run with [PagedState.moreError]; the items stay.
     */
    private suspend fun fill(untilFirstScreenIsFull: Boolean) {
        var key = next ?: return
        if (untilFirstScreenIsFull && shownCount() >= firstScreen) return
        _state.update { it.copy(loadingMore = true) }
        try {
            var pages = 0
            var gained = 0
            while (true) {
                val page = fetch(key)
                pages++
                next = page.next
                val current = _state.value
                val seen = current.items.mapTo(HashSet(), keyOf)
                val fresh = page.items.filter { seen.add(keyOf(it)) }
                gained += fresh.count(shown)
                _state.value = current.copy(items = current.items + fresh, endReached = page.next == null)
                key = page.next ?: break
                val enough = if (untilFirstScreenIsFull) shownCount() >= firstScreen else gained >= nextBatch
                val allowance = if (gained == 0) PAGES_WITHOUT_ITEMS else PAGES_PER_RUN
                if (enough || pages >= allowance) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadingMore = false, moreError = e.asSourceException()) }
            return
        }
        _state.update { it.copy(loadingMore = false) }
    }

    private fun shownCount(): Int = _state.value.items.count(shown)

    companion object {
        /** The first screen of a 1080p TV is about 12 cards: twice that, so there is room to scroll before the next request. */
        const val FIRST_SCREEN = 24

        /** What one [loadMore] aims at: new items the screen shows (a page of YouTube's «all» search brings 2 to 11). */
        const val NEXT_BATCH = 12

        /** Pages one run may fetch while it keeps finding items the screen shows. */
        const val PAGES_PER_RUN = 6

        /** Pages one run may fetch while every page it gets is hidden by the rules (a blocked topic): then it looks further, but not forever. */
        const val PAGES_WITHOUT_ITEMS = 12
    }
}
