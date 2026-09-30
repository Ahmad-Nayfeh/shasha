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
    val loadingMore: Boolean = false,
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
 */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val keyOf: (T) -> String,
    private val fetch: suspend (PageKey?) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>(loadingFirst = false))
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private var next: PageKey? = null
    private var job: Job? = null

    /** Where the list continues (null at the end), e.g. for handing a playlist to the player. */
    val nextPage: PageKey? get() = next

    /** (Re)loads from the first page, dropping what was there. */
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
            }
        }
    }

    /** Drops everything (no request in flight afterwards). */
    fun reset() {
        job?.cancel()
        next = null
        _state.value = PagedState(loadingFirst = false)
    }

    /** Asks for the next page; ignored while a page loads, at the end, or after a failure. */
    fun loadMore() {
        val s = _state.value
        if (job?.isActive == true || s.loadingFirst || s.endReached || s.error != null || s.moreError != null) return
        val from = next ?: return
        _state.update { it.copy(loadingMore = true) }
        job = scope.launch {
            try {
                var key: PageKey? = from
                var added = 0
                var attempts = 0
                // A page made only of repeats adds nothing: look one page further, a few times at most.
                while (key != null && added == 0 && attempts < MAX_EMPTY_PAGES) {
                    val page = fetch(key)
                    attempts++
                    key = page.next
                    next = key
                    _state.update { current ->
                        val seen = current.items.mapTo(HashSet(), keyOf)
                        val fresh = page.items.filter { seen.add(keyOf(it)) }
                        added = fresh.size
                        current.copy(items = current.items + fresh, endReached = key == null)
                    }
                }
                _state.update { it.copy(loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, moreError = e.asSourceException()) }
            }
        }
    }

    /** After a later page failed: try it again. */
    fun retryMore() {
        if (_state.value.moreError == null) return
        _state.update { it.copy(moreError = null) }
        loadMore()
    }

    private companion object {
        const val MAX_EMPTY_PAGES = 3
    }
}
