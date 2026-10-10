package com.ahmadnayfeh.shasha.mobile.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.ui.components.ChannelListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnNearEnd
import com.ahmadnayfeh.shasha.mobile.ui.components.OnTabReselected
import com.ahmadnayfeh.shasha.mobile.ui.nav.SearchRoute
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.PlaylistListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.SectionTitle
import com.ahmadnayfeh.shasha.mobile.ui.components.ShortsRow
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.VideoItem

@Composable
fun SearchScreen(
    container: AppContainer,
    initialQuery: String?,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    /** The Shorts viewer, on the Shorts row (the list) at the tapped one (its place in it). */
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
) {
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container, initialQuery))
    val query by vm.query.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val results by vm.resultState.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    // Opening the tab does not focus the field (and so does not pop the keyboard up over the recent searches): the keyboard
    // comes when the viewer taps the field. Only «Clear» puts the focus back in it, since the viewer is typing then.
    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .focusRequester(focus)
                .testTag(SEARCH_FIELD_TAG)
                .onFocusChanged { if (it.isFocused) vm.startEditing() },
            singleLine = true,
            shape = CircleShape,
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.clearQuery(); focus.requestFocus() }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.submit(); keyboard?.hide() }),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
        )

        if (submitted != null) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchFilter.entries.toList()) { option ->
                    FilterChip(
                        selected = option == filter,
                        onClick = { vm.selectFilter(option) },
                        label = { Text(stringResource(filterLabel(option))) },
                    )
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (editing) {
                SuggestionsList(
                    typed = query.isNotBlank(),
                    suggestions = suggestions,
                    recent = recent,
                    onPick = { vm.submit(it); keyboard?.hide() },
                    onRemoveRecent = vm::removeRecent,
                    onClearRecent = vm::clearRecent,
                )
            } else {
                Results(results, rows, vm, onOpenVideo, onOpenChannel, onOpenPlaylist, onOpenShorts)
            }
        }
    }
}

/** The search field, for the tests. */
internal const val SEARCH_FIELD_TAG = "search_field"

private fun filterLabel(filter: SearchFilter): Int = when (filter) {
    SearchFilter.ALL -> R.string.filter_all
    SearchFilter.VIDEOS -> R.string.filter_videos
    SearchFilter.CHANNELS -> R.string.filter_channels
    SearchFilter.PLAYLISTS -> R.string.filter_playlists
}

@Composable
private fun SuggestionsList(
    typed: Boolean,
    suggestions: List<String>,
    recent: List<String>,
    onPick: (String) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (typed) {
            items(suggestions, key = { "s:$it" }) { text ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(text) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (recent.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.search_recent),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onClearRecent) { Text(stringResource(R.string.action_clear_all)) }
                }
            }
            items(recent, key = { "r:$it" }) { text ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(text) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { onRemoveRecent(text) }) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_remove), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Results(
    results: com.ahmadnayfeh.shasha.mobile.common.PagedState<com.ahmadnayfeh.shasha.youtube.SourceItem>,
    rows: SearchRows,
    vm: SearchViewModel,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
) {
    val listState = rememberLazyListState()
    listState.OnNearEnd { vm.loadMore() }
    OnTabReselected(SearchRoute::class) { listState.animateScrollToItem(0) }
    when {
        !results.started && results.error == null -> LoadingBox()
        rows.items.isEmpty() && rows.shorts.isEmpty() && results.error != null -> ErrorState(results.error, onRetry = vm::retry)
        rows.items.isEmpty() && rows.shorts.isEmpty() -> EmptyState(
            icon = Icons.Filled.Search,
            title = stringResource(R.string.search_no_results),
            body = stringResource(R.string.search_no_results_body),
        )
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            // The Shorts come as a row after the first few results (at the end of a short list), where the results already shown stay put.
            val rowAt = minOf(SHORTS_ROW_AFTER, rows.items.size)
            itemsIndexed(rows.items, key = { _, item -> item.key() }) { index, item ->
                Column {
                    if (index == rowAt && rows.shorts.isNotEmpty()) ShortsSection(rows.shorts, onOpenShorts)
                    when (item) {
                        is VideoItem -> VideoListItem(item, onClick = { onOpenVideo(item) })
                        is ChannelItem -> ChannelListItem(item, onClick = { onOpenChannel(item) })
                        is PlaylistItem -> PlaylistListItem(item, onClick = { onOpenPlaylist(item.playlistId) })
                    }
                }
            }
            if (rowAt == rows.items.size && rows.shorts.isNotEmpty()) item(key = "shorts") { ShortsSection(rows.shorts, onOpenShorts) }
            item(key = "footer") { PagedFooter(loading = results.loading, error = results.error, onRetry = vm::retry) }
        }
    }
}

/** The Shorts after this many results of the general search. */
private const val SHORTS_ROW_AFTER = 4

@Composable
private fun ShortsSection(shorts: List<VideoItem>, onOpenShorts: (List<VideoItem>, Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.search_shorts))
        ShortsRow(shorts, onOpen = onOpenShorts)
    }
}
