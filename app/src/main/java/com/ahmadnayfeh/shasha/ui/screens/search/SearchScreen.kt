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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.ahmadnayfeh.shasha.data.BehaviorKeys
import kotlinx.coroutines.launch
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
    val container = LocalContext.current.appContainer
    val vm = containerViewModel { SearchViewModel(it.videoSource, it.subscriptions, it.watch, it.policy.filter, it.searchHistory) }
    val extractorVersion = container.videoSource.extractorVersion
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val alternatives by vm.voiceAlternatives.collectAsStateWithLifecycle()
    val behavior by container.behavior.values.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf(query) }
    var searches by remember { mutableIntStateOf(0) }
    val voice = rememberVoiceSearch(
        languageTag = SearchViewModel.voiceTag(behavior.voiceLanguage),
        onHeard = { words ->
            vm.onVoiceResults(words)?.let {
                text = it
                searches++
            }
        },
        requests = container.voiceSearchRequests,
    )
    val voiceState by voice.state.collectAsStateWithLifecycle()
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
        text = text,
        onTextChange = {
            text = it
            vm.onTextChanged(it)
        },
        assist = SearchAssist(
            suggestions = suggestions,
            recent = recent,
            alternatives = alternatives,
            voice = voiceState,
            voiceLanguage = behavior.voiceLanguage,
        ),
        voice = VoiceCallbacks(
            onMic = voice::listen,
            onToggleLanguage = {
                val next = BehaviorKeys.VOICE_LANGUAGES.first { it != SearchViewModel.voiceTag(behavior.voiceLanguage) }
                container.appScope.launch { container.behavior.setVoiceLanguage(next) }
            },
            onCancel = voice::cancel,
            onRetry = voice::listen,
            onDismiss = voice::dismiss,
            onPick = { picked, alternative ->
                text = picked
                vm.pick(picked, alternative)
            },
            onClearRecent = vm::clearRecent,
        ),
        searches = searches,
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
    text: String = query,
    onTextChange: (String) -> Unit = {},
    assist: SearchAssist = SearchAssist(),
    voice: VoiceCallbacks = VoiceCallbacks(),
    /** Grows with each search started from outside the box (the microphone): focus then goes to the results. */
    searches: Int = 0,
) {
    val error = results.error
    if (error != null && query.isNotEmpty()) {
        ErrorScreen(error, extractorVersion, callbacks.onCheckUpdates, onBack = {
            callbacks.onDismissError()
            focus.request()
        })
        return
    }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    var focusResults by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    TakeFocusWhenReady(focus, ready = true)
    LaunchedEffect(searches) { if (searches > 0) focusResults = true }
    // The listening panel went away without a search (cancelled, closed): the focus goes back to the microphone.
    val mic = remember { FocusRequester() }
    val voiceShown = assist.voice.isShown
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(voiceShown) {
        if (wasShown && !voiceShown && !focusResults) {
            withFrameNanos { }
            runCatching { mic.requestFocus() }
        }
        wasShown = voiceShown
    }
    LaunchedEffect(focusResults, results.loadingFirst, results.loadingMore, results.items.isEmpty()) {
        if (focusResults && !results.loadingFirst) {
            // A first page the profile's rules hid entirely: the next pages are on their way, the focus waits for them.
            if (results.items.isEmpty() && results.loadingMore) return@LaunchedEffect
            focusResults = false
            if (results.items.isNotEmpty()) {
                withFrameNanos { }
                firstResult.requestFocus()
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().contentFocus(focus).focusRestorer(field).focusGroup()) {
            Column(Modifier.padding(start = 24.dp, end = 48.dp, top = 44.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SearchField(
                        value = text,
                        onValueChange = onTextChange,
                        onSearch = {
                            keyboard?.hide()
                            focusResults = true
                            callbacks.onSubmit(text)
                        },
                        hint = stringResource(R.string.search_hint),
                        modifier = Modifier.weight(1f).focusRequester(field),
                    )
                    if (assist.micAvailable) {
                        Spacer(Modifier.width(16.dp))
                        MicButton(voice.onMic, Modifier.focusRequester(mic).testTag("search_mic"))
                        Spacer(Modifier.width(12.dp))
                        LanguageToggle(assist.voiceLanguage, voice.onToggleLanguage, Modifier.testTag("search_voice_language"))
                    }
                }
                AssistRow(text, query, assist, voice, onPicked = {
                    keyboard?.hide()
                    focusResults = true
                })
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
                    // Nothing of the pages so far is shown, but more are on their way: still searching, not "no results".
                    results.items.isEmpty() && results.loadingMore -> Centered { ShashaLoaderWithText(stringResource(R.string.search_loading)) }
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
        if (assist.voice.isShown) VoiceOverlay(assist.voice, assist.voiceLanguage, voice)
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
