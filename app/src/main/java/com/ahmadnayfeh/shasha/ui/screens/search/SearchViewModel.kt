package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.model.toUi
import com.ahmadnayfeh.shasha.ui.paging.Page
import com.ahmadnayfeh.shasha.ui.paging.PagedLoader
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.watchedFractions
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.VideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * البحث (adult profile; phase 3 removes it for children). A search runs when the user submits it or
 * changes the filter, never while typing; results come page by page as focus nears their end.
 */
class SearchViewModel(
    private val source: VideoSource,
    private val subscriptions: SubscriptionRepository,
    watch: WatchRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** The last submitted query ("" = nothing searched yet). */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val loader = PagedLoader(viewModelScope, ItemUi::key) { page ->
        val result = source.search(_query.value, _filter.value, page)
        Page(result.items.map { it.toUi() }, result.next)
    }

    val results: StateFlow<PagedState<ItemUi>> = loader.state

    /** Ids of subscribed channels, for the subscribe buttons on channel results. */
    val subscribed: StateFlow<Set<String>> = subscriptions.observe()
        .map { channels -> channels.mapTo(HashSet()) { it.channelId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val watched: StateFlow<Map<String, Float>> =
        watchedFractions(watch, results.map { s -> s.items.mapNotNull { (it as? VideoUi)?.video?.videoId } })
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun submit(text: String) {
        val q = text.trim()
        if (q.isEmpty()) return
        if (q == _query.value && results.value.error == null && !results.value.loadingFirst && results.value.items.isNotEmpty()) return
        _query.value = q
        loader.start()
    }

    fun setFilter(filter: SearchFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        if (_query.value.isNotEmpty()) loader.start()
    }

    fun loadMore() = loader.loadMore()

    fun retryMore() = loader.retryMore()

    /** The error screen's «رجوع»: back to an empty search, the typed text stays to be edited. */
    fun clearResults() {
        _query.value = ""
        loader.reset()
    }

    fun toggleSubscription(channel: ChannelItem) {
        val subscribedNow = channel.channelId in subscribed.value
        viewModelScope.launch {
            if (subscribedNow) subscriptions.remove(channel.channelId) else subscriptions.add(channel)
        }
    }
}
