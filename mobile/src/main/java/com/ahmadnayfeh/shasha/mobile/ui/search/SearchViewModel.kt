package com.ahmadnayfeh.shasha.mobile.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.common.PagedLoader
import com.ahmadnayfeh.shasha.mobile.common.PagedState
import com.ahmadnayfeh.shasha.mobile.data.SearchHistoryRepository
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A search result's identity: the same video, channel or playlist is shown once, however many pages repeat it. */
internal fun SourceItem.key(): String = when (this) {
    is VideoItem -> "v:$videoId"
    is ChannelItem -> "c:$channelId"
    is PlaylistItem -> "p:$playlistId"
}

/**
 * What the results list shows: [items] (videos, channels, playlists) without the Shorts, and the Shorts that were among them as
 * their own row ([shorts], in the order they came). The general search ([SearchFilter.ALL]) has the row; the other filters ask for
 * videos, channels or playlists and show no Shorts. Shorts turned off: none anywhere.
 */
data class SearchRows(val items: List<SourceItem> = emptyList(), val shorts: List<VideoItem> = emptyList()) {
    companion object {
        fun of(all: List<SourceItem>, filter: SearchFilter, showShorts: Boolean): SearchRows {
            val shorts = all.filter { it is VideoItem && it.isShortForm }
            val rest = all.filterNot { it is VideoItem && it.isShortForm }
            return SearchRows(rest, if (showShorts && filter == SearchFilter.ALL) shorts.filterIsInstance<VideoItem>() else emptyList())
        }
    }
}

/**
 * Search: suggestions while typing (YouTube's, after a short pause), the four filters, and endless paging of the
 * results ([PagedLoader]). Typing shows suggestions and recent searches; submitting runs the search. The Shorts among
 * the results of the general search are a row of their own ([rows]), which [showShorts] can take away.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val source: VideoSource,
    private val history: SearchHistoryRepository,
    /** "ar" or "en": the language of YouTube's suggestions. */
    private val language: () -> String,
    initialQuery: String? = null,
    /** The «Show Shorts» setting. */
    showShorts: Flow<Boolean> = flowOf(true),
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter

    /** The query the shown results belong to; null while the viewer is still typing the first one. */
    private val _submitted = MutableStateFlow<String?>(null)
    val submitted: StateFlow<String?> = _submitted

    /** The text field is being edited: suggestions show instead of results. */
    private val _editing = MutableStateFlow(true)
    val editing: StateFlow<Boolean> = _editing

    private val results = PagedLoader<SourceItem, String>(viewModelScope, key = { it.key() }) { _ -> emptyList<SourceItem>() to null }
    val resultState: StateFlow<PagedState<SourceItem>> = results.state

    /** [resultState]'s items told apart into the list and the Shorts row. */
    val rows: StateFlow<SearchRows> = combine(results.state, _filter, showShorts) { state, filter, shortsOn ->
        SearchRows.of(state.items, filter, shortsOn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchRows())

    val recent: StateFlow<List<String>> = history.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** YouTube's suggestions for what is typed, after 250 ms without typing; none while not editing. */
    val suggestions: StateFlow<List<String>> = combine(_query, _editing) { q, editing -> if (editing) q.trim() else "" }
        .debounce(250)
        .distinctUntilChanged()
        .flatMapLatest { text ->
            if (text.isEmpty()) {
                flowOf(emptyList())
            } else {
                kotlinx.coroutines.flow.flow { emit(runCatching { source.suggestions(text, language()) }.getOrDefault(emptyList())) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (!initialQuery.isNullOrBlank()) submit(initialQuery)
    }

    fun onQueryChange(text: String) {
        _query.value = text
        _editing.value = true
    }

    /** The field gets focus again: back to editing (suggestions), keeping the results behind. */
    fun startEditing() {
        _editing.value = true
    }

    fun submit(text: String = _query.value) {
        val q = text.trim()
        if (q.isEmpty()) return
        _query.value = q
        _editing.value = false
        _submitted.value = q
        viewModelScope.launch { history.add(q) }
        run(q, _filter.value)
    }

    fun selectFilter(filter: SearchFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        _submitted.value?.let { run(it, filter) }
    }

    fun loadMore() = results.loadMore()

    fun retry() = results.loadMore()

    fun clearQuery() {
        _query.value = ""
        _editing.value = true
    }

    fun removeRecent(query: String) {
        viewModelScope.launch { history.remove(query) }
    }

    fun clearRecent() {
        viewModelScope.launch { history.clear() }
    }

    private fun run(query: String, filter: SearchFilter) {
        results.restart { page ->
            val answer = source.search(query, filter, page)
            answer.items to answer.next
        }
    }

    companion object {
        /** YouTube's suggestions come in Arabic or English: the app's language when it has one, else the phone's. */
        fun suggestionLanguage(appTag: String?, systemLanguage: String): String = if ((appTag ?: systemLanguage) == "ar") "ar" else "en"

        fun factory(container: AppContainer, initialQuery: String?) = viewModelFactory {
            initializer {
                SearchViewModel(
                    source = container.videoSource,
                    history = container.searchHistory,
                    language = { suggestionLanguage(container.latestSettings().language.tag, java.util.Locale.getDefault().language) },
                    initialQuery = initialQuery,
                    showShorts = container.showShorts,
                )
            }
        }
    }
}
