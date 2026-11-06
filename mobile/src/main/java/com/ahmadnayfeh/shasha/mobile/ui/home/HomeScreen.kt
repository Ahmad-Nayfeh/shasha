package com.ahmadnayfeh.shasha.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.toChannelItem
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.BrandMark
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.FeedChip
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnTabReselected
import com.ahmadnayfeh.shasha.mobile.ui.components.PrefetchVisibleStreams
import com.ahmadnayfeh.shasha.mobile.ui.components.SectionTitle
import com.ahmadnayfeh.shasha.mobile.ui.components.ShortsShelf
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoCard
import com.ahmadnayfeh.shasha.mobile.ui.theme.ProgressRed
import com.ahmadnayfeh.shasha.mobile.ui.nav.HomeRoute
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

private enum class HomeTab { HOME, SUBSCRIPTIONS }

private enum class HomeFilter { ALL, LATEST, SHORTS }

/**
 * The Home as the YouTube app has it: the brand and the search on the first row, the two text tabs «الرئيسية» | «الاشتراكات»
 * (red line under the chosen one) on the second, the filter chips (the Home tab) on the third, then big video cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    /** The Shorts viewer, on the Shorts shelf (the list) at the tapped one (its place in it). */
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
    onSearch: () -> Unit,
    /** The list of the followed channels (to unfollow them). */
    onManageSubscriptions: () -> Unit = {},
    /** The watch page is over the Home: the viewer does not see it. */
    covered: Boolean = false,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(HomeTab.HOME) }
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    // In front of the viewer (its page on top, nothing over it): after a video played, the suggestions may change now.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val shown = !covered && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(shown) { if (shown) vm.onShown() }

    val hasUploads = state.videos.isNotEmpty() || state.subscriptions.isNotEmpty()
    val kinds = (if (hasUploads) 1 else 0) + (if (state.shorts.isNotEmpty()) 1 else 0) + (if (state.suggestions.isNotEmpty()) 1 else 0)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
                    // The brand at the start; the search at the end. Refreshing is the pull down.
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.nav_search))
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TopTab(stringResource(R.string.nav_home), tab == HomeTab.HOME) { tab = HomeTab.HOME }
                    TopTab(stringResource(R.string.nav_subscriptions), tab == HomeTab.SUBSCRIPTIONS) { tab = HomeTab.SUBSCRIPTIONS }
                }
                if (tab == HomeTab.HOME && kinds > 1 && state.loaded) {
                    // Only filters that can really be applied to what the feed holds: everything, the newest uploads of the channels, the Shorts.
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FeedChip(stringResource(R.string.home_chip_all), filter == HomeFilter.ALL, { filter = HomeFilter.ALL }) }
                        if (state.shorts.isNotEmpty()) {
                            item { FeedChip(stringResource(R.string.home_shorts), filter == HomeFilter.SHORTS, { filter = HomeFilter.SHORTS }) }
                        }
                        if (hasUploads) {
                            item { FeedChip(stringResource(R.string.home_chip_channels), filter == HomeFilter.LATEST, { filter = HomeFilter.LATEST }) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (tab == HomeTab.SUBSCRIPTIONS) {
                SubscriptionsFeed(state, vm, onOpenVideo, onOpenChannel, onManageSubscriptions, onSearch)
                return@Column
            }
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
                        else -> FeedGrid(state, filter, onOpenVideo, onOpenShorts, onSearch)
                    }
                }
            }
        }
    }
}

/** One of the two text tabs under the top bar: the chosen one is bold with a red 2dp line under it. */
@Composable
private fun TopTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            Modifier.padding(vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.height(2.dp).width(40.dp).background(if (selected) ProgressRed else androidx.compose.ui.graphics.Color.Transparent))
    }
}

/** The Subscriptions tab: the followed channels as a row of 48dp circles, then their newest uploads as video cards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionsFeed(
    state: HomeState,
    vm: HomeViewModel,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    onManage: () -> Unit,
    onSearch: () -> Unit,
) {
    val list = rememberLazyListState()
    list.PrefetchVisibleStreams(state.videos)
    when {
        !state.loaded -> LoadingBox()
        state.subscriptions.isEmpty() -> EmptyState(
            icon = Icons.Filled.Subscriptions,
            title = stringResource(R.string.home_empty_title),
            body = stringResource(R.string.home_empty_body),
            action = { Button(onClick = onSearch) { Text(stringResource(R.string.home_empty_action)) } },
        )
        else -> PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { vm.refresh(force = true) }, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "channels") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.subscriptions, key = { it.channelId }) { channel ->
                            Column(Modifier.width(64.dp).clickable { onOpenChannel(channel.toChannelItem()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Avatar(channel.avatarUrl, size = 48, name = channel.name)
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
                        item(key = "all") { TextButton(onClick = onManage) { Text(stringResource(R.string.subscriptions_all)) } }
                    }
                }
                if (state.videos.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(R.string.home_no_videos_body),
                            Modifier.padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.videos, key = { it.videoId }, contentType = { "video" }) { video ->
                    VideoCard(video, onClick = { onOpenVideo(video) })
                }
            }
        }
    }
}

@Composable
private fun FeedGrid(
    state: HomeState,
    filter: HomeFilter,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenShorts: (List<VideoItem>, Int) -> Unit,
    onSearch: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    OnTabReselected(HomeRoute::class) { gridState.animateScrollToItem(0) }
    gridState.PrefetchVisibleStreams(state.suggestions + state.videos)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The first suggestion, then the Shorts shelf, then the rest: as the YouTube app places its shelf, a video or two down the page.
        val suggestedHere = if (filter == HomeFilter.ALL) state.suggestions else emptyList()
        if (suggestedHere.isNotEmpty()) {
            item(key = "suggested-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.home_suggested)) }
            gridItems(suggestedHere.take(1), key = { "suggested:" + it.videoId }, contentType = { "video" }) { video ->
                VideoCard(video, onClick = { onOpenVideo(video) })
            }
        }
        if (state.shorts.isNotEmpty() && filter != HomeFilter.LATEST) {
            item(key = "shorts", span = { GridItemSpan(maxLineSpan) }) {
                ShortsShelf(state.shorts, onOpen = onOpenShorts, count = if (filter == HomeFilter.SHORTS) SHELF_ALL else SHELF_COUNT)
            }
        }
        if (suggestedHere.size > 1) {
            gridItems(suggestedHere.drop(1), key = { "suggested:" + it.videoId }, contentType = { "video" }) { video ->
                VideoCard(video, onClick = { onOpenVideo(video) })
            }
        }
        if (state.videos.isNotEmpty() && filter != HomeFilter.SHORTS) {
            // The channels' uploads need a title only when something else is above them.
            if (state.hasMore) item(key = "uploads-title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(R.string.home_from_channels)) }
            gridItems(state.videos, key = { it.videoId }, contentType = { "video" }) { video ->
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

/** Shorts on the shelf of the Home (one row of two), and on the whole page when the Shorts chip is chosen. */
private const val SHELF_COUNT = 2
private const val SHELF_ALL = 20
