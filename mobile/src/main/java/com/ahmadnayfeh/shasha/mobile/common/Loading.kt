package com.ahmadnayfeh.shasha.mobile.common

import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * What went wrong, as the screens show it: YouTube's [kind] (null for anything else) and the technical name of the
 * failure, which is what to tell the developer when YouTube changes something.
 */
data class UiError(val kind: SourceErrorKind?, val technicalName: String) {
    companion object {
        fun of(error: Throwable): UiError = when (error) {
            is SourceException -> UiError(error.kind, error.technicalName)
            is IOException -> UiError(SourceErrorKind.NETWORK, error.javaClass.simpleName)
            else -> UiError(null, error.javaClass.simpleName)
        }
    }
}

/** A value that is loading, loaded or failed. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
    data class Failed(val error: UiError) : Loadable<Nothing>
}

/** A list that arrives page by page. */
data class PagedState<T>(
    val items: List<T> = emptyList(),
    /** A page is being fetched. */
    val loading: Boolean = false,
    /** The first page has answered (with items, or an error, or nothing). */
    val started: Boolean = false,
    /** The source has no more pages. */
    val endReached: Boolean = false,
    val error: UiError? = null,
)

/**
 * Loads a list page by page for infinite scrolling ([loadMore] when the end of the list comes near).
 *
 * A page is not a screenful (YouTube's «all» search comes through the extractor in pages of 2 to 11 items), so one
 * [loadMore] asks for pages until at least [minPerLoad] new items arrived, up to [maxRounds] pages, or until the source
 * ends. Items already shown (by [key]) are dropped. [restart] begins again, with another source of pages.
 */
class PagedLoader<T, K>(
    private val scope: CoroutineScope,
    private val key: (T) -> K,
    private val minPerLoad: Int = 12,
    private val maxRounds: Int = 6,
    private var fetch: suspend (PageKey?) -> Pair<List<T>, PageKey?>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private var next: PageKey? = null
    private var begun = false
    private var job: Job? = null

    /** Asks for the next page(s); does nothing while loading or at the end. After an error it is "try again". */
    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached) return
        if (begun && next == null) return
        job = scope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                var added = 0
                var rounds = 0
                do {
                    val (items, nextKey) = fetch(next)
                    begun = true
                    next = nextKey
                    val seen = _state.value.items.mapTo(HashSet(), key)
                    val fresh = items.filter { seen.add(key(it)) }
                    _state.update { it.copy(items = it.items + fresh, started = true) }
                    added += fresh.size
                    rounds++
                } while (next != null && added < minPerLoad && rounds < maxRounds)
                _state.update { it.copy(loading = false, endReached = next == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, started = true, error = UiError.of(e)) }
            }
        }
    }

    /** Forgets everything and loads the first page of [newFetch] (or of the same source). */
    fun restart(newFetch: (suspend (PageKey?) -> Pair<List<T>, PageKey?>)? = null) {
        job?.cancel()
        if (newFetch != null) fetch = newFetch
        next = null
        begun = false
        _state.value = PagedState()
        loadMore()
    }

    /** Stops what is loading and empties the list, without loading again. */
    fun clear() {
        job?.cancel()
        next = null
        begun = false
        _state.value = PagedState()
    }
}
