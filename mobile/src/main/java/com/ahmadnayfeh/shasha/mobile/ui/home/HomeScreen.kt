package com.ahmadnayfeh.shasha.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.toChannelItem
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnTabReselected
import com.ahmadnayfeh.shasha.mobile.ui.components.SectionTitle
import com.ahmadnayfeh.shasha.mobile.ui.components.ShortsRow
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoCard
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoTile
import com.ahmadnayfeh.shasha.mobile.ui.nav.HomeRoute
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    /** The Shorts viewer, on the Shorts row (the list) at the tapped one (its place in it). */
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
    onSearch: () -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Nobody followed and nothing suggested (nothing watched yet, or its suggestions not known): a short friendly hint, not an empty page.
            val nothingToShow = state.subscriptions.isEmpty() && !state.hasMore
            when {
                !state.loaded -> LoadingBox()
                nothingToShow && state.suggesting -> LoadingBox()
                nothingToShow -> EmptyState(
                    icon = Icons.Filled.Subscriptions,
                    title = stringResource(R.string.home_empty_title),
                    body = stringResource(R.string.home_empty_body),
                    action = {
                        Button(onClick = onSearch) {
                            androidx.compose.material3.Icon(Icons.Filled.Search, contentDescription = null)
                            Text(stringResource(R.string.home_empty_action), Modifier.padding(start = 8.dp))
                        }
                    },
                )
                else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
                    when {
                        !state.hasMore && state.videos.isEmpty() && state.refreshing -> LoadingBox()
                        !state.hasMore && state.videos.isEmpty() && state.error != null -> ErrorState(state.error!!, onRetry = { vm.refresh(force = true) })
                        !state.hasMore && state.videos.isEmpty() -> EmptyState(
                            icon = Icons.Filled.Subscriptions,
                            title = stringResource(R.string.home_no_videos_title),
                            body = stringResource(R.string.home_no_videos_body),
                        )
                        else -> FeedGrid(state, onOpenVideo, onOpenChannel, onOpenShorts, onSearch)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeedGrid(
    state: HomeState,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
    onSearch: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    OnTabReselected(HomeRoute::class) { gridState.animateScrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.subscriptions.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(state.subscriptions, key = { it.channelId }) { channel ->
                        Column(
                            Modifier.width(68.dp).clickable { onOpenChannel(channel.toChannelItem()) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Avatar(channel.avatarUrl, size = 56, name = channel.name)
                            Text(
                                channel.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        if (state.suggestions.isNotEmpty()) {
            item(key = "suggested-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.home_suggested)) }
            item(key = "suggested", span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.suggestions, key = { "suggested:" + it.videoId }) { video ->
                        VideoTile(video, onClick = { onOpenVideo(video) }, modifier = Modifier.width(280.dp))
                    }
                }
            }
        }
        if (state.shorts.isNotEmpty()) {
            item(key = "shorts-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.home_shorts)) }
            item(key = "shorts", span = { GridItemSpan(maxLineSpan) }) { ShortsRow(state.shorts, onOpen = onOpenShorts) }
        }
        if (state.videos.isNotEmpty()) {
            // The channels' uploads need a title only when something else is above them.
            if (state.hasMore) item(key = "uploads-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.home_from_channels)) }
            items(state.videos, key = { it.videoId }) { video ->
                VideoCard(video, onClick = { onOpenVideo(video) })
            }
        }
        if (state.subscriptions.isEmpty()) {
            // Suggestions without a single channel followed: the way to a home of one's own stays in view.
            item(key = "follow-hint", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.home_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = onSearch, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.home_empty_action)) }
                }
            }
        }
    }
}
