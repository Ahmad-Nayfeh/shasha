package com.ahmadnayfeh.shasha.ui.screens.search

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.EmptyState
import com.ahmadnayfeh.shasha.ui.components.PagingFooter
import com.ahmadnayfeh.shasha.ui.components.ShashaChip
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.contentFocus
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.resultCards
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.navigation.BrowseActions
import com.ahmadnayfeh.shasha.ui.paging.LoadMoreWhenNearEnd
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter

@Composable
fun SearchRoute(focus: ContentFocus, actions: BrowseActions) {
    val vm = containerViewModel { SearchViewModel(it.videoSource, it.subscriptions, it.watch) }
    val extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    SearchScreen(
        query = query,
        filter = filter,
        results = results,
        subscribed = vm.subscribed.collectAsStateWithLifecycle(),
        watched = vm.watched.collectAsStateWithLifecycle(),
        nowMs = rememberNow(),
        focus = focus,
        extractorVersion = extractorVersion,
        callbacks = SearchCallbacks(
            onSubmit = vm::submit,
            onFilter = vm::setFilter,
            onLoadMore = vm::loadMore,
            onRetryMore = vm::retryMore,
            onDismissError = vm::clearResults,
            onToggleSubscription = vm::toggleSubscription,
            onOpen = actions::open,
            onCheckUpdates = actions.checkUpdates,
        ),
    )
}

class SearchCallbacks(
    val onSubmit: (String) -> Unit,
    val onFilter: (SearchFilter) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetryMore: () -> Unit,
    val onDismissError: () -> Unit,
    val onToggleSubscription: (ChannelItem) -> Unit,
    val onOpen: (ItemUi) -> Unit,
    val onCheckUpdates: () -> Unit,
)

/**
 * The search box and filter chips on top, results below in the usual grid. Focus enters on the box the
 * first time; after a search it moves to the first result.
 */
@Composable
fun SearchScreen(
    query: String,
    filter: SearchFilter,
    results: PagedState<ItemUi>,
    subscribed: State<Set<String>>,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    extractorVersion: String,
    callbacks: SearchCallbacks,
    modifier: Modifier = Modifier,
    initialText: String = query,
) {
    val error = results.error
    if (error != null && query.isNotEmpty()) {
        ErrorScreen(error, extractorVersion, callbacks.onCheckUpdates, onBack = {
            callbacks.onDismissError()
            focus.request()
        })
        return
    }
    var text by rememberSaveable { mutableStateOf(initialText) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    var focusResults by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    TakeFocusWhenReady(focus, ready = true)
    LaunchedEffect(focusResults, results.loadingFirst, results.items.isEmpty()) {
        if (focusResults && !results.loadingFirst) {
            focusResults = false
            if (results.items.isNotEmpty()) {
                withFrameNanos { }
                firstResult.requestFocus()
            }
        }
    }

    Column(modifier.fillMaxSize().contentFocus(focus).focusRestorer(field).focusGroup()) {
        Column(Modifier.padding(start = 24.dp, end = 48.dp, top = 44.dp)) {
            SearchField(
                value = text,
                onValueChange = { text = it },
                onSearch = {
                    keyboard?.hide()
                    focusResults = true
                    callbacks.onSubmit(text)
                },
                hint = stringResource(R.string.search_hint),
                modifier = Modifier.fillMaxWidth().focusRequester(field),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchFilter.entries.forEach { option ->
                    ShashaChip(
                        stringResource(option.label()),
                        selected = option == filter,
                        onClick = { callbacks.onFilter(option) },
                        modifier = Modifier.testTag("filter_${option.name}"),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                query.isEmpty() -> Centered {
                    EmptyState(ShashaIcons.Search, stringResource(R.string.search_idle_title), stringResource(R.string.search_idle_body))
                }
                results.loadingFirst -> Centered { ShashaLoaderWithText(stringResource(R.string.search_loading)) }
                results.items.isEmpty() -> Centered {
                    EmptyState(
                        ShashaIcons.Search,
                        stringResource(R.string.search_no_results, query),
                        stringResource(R.string.search_no_results_body),
                    )
                }
                else -> Results(results, subscribed, watched, nowMs, firstResult, extractorVersion, callbacks)
            }
        }
    }
}

@Composable
private fun Results(
    results: PagedState<ItemUi>,
    subscribed: State<Set<String>>,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    firstResult: FocusRequester,
    extractorVersion: String,
    callbacks: SearchCallbacks,
) {
    val formats = rememberFormats()
    val gridState = rememberLazyGridState()
    LoadMoreWhenNearEnd(gridState, onLoadMore = callbacks.onLoadMore)
    ShashaGrid(
        gridState,
        focus = null,
        firstItem = firstResult,
        modifier = Modifier.fillMaxSize().testTag("search_results"),
        contentPadding = PaddingValues(start = 24.dp, end = 48.dp, top = 24.dp, bottom = 48.dp),
    ) {
        resultCards(results.items, watched, subscribed, formats, nowMs, firstResult, callbacks.onOpen, callbacks.onToggleSubscription)
        if (results.loadingMore || results.moreError != null) {
            fullWidthItem("footer") { PagingFooter(results.loadingMore, results.moreError, callbacks.onRetryMore, extractorVersion, callbacks.onCheckUpdates) }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 48.dp, bottom = 48.dp), contentAlignment = Alignment.Center) { content() }
}

private fun SearchFilter.label(): Int = when (this) {
    SearchFilter.ALL -> R.string.search_filter_all
    SearchFilter.VIDEOS -> R.string.search_filter_videos
    SearchFilter.CHANNELS -> R.string.search_filter_channels
    SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
}
