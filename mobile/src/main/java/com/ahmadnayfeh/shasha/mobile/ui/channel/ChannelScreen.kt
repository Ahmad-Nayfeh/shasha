package com.ahmadnayfeh.shasha.mobile.ui.channel

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.DurationBadge
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnNearEnd
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.PlaylistListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.ChannelDetails
import com.ahmadnayfeh.shasha.youtube.VideoItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    container: AppContainer,
    channelId: String,
    onBack: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val vm: ChannelViewModel = viewModel(key = "channel:$channelId", factory = ChannelViewModel.factory(container, channelId))
    val details by vm.details.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val videos by vm.videoState.collectAsStateWithLifecycle()
    val playlists by vm.playlistState.collectAsStateWithLifecycle()
    val shorts by vm.shorts.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(tab) {
        when (tab) {
            1 -> vm.loadShorts()
            2 -> vm.loadPlaylists()
        }
    }
    if (tab == 0) listState.OnNearEnd { vm.loadMoreVideos() }
    if (tab == 2) listState.OnNearEnd { vm.loadMorePlaylists() }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text((details as? Loadable.Loaded)?.value?.channel?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val loaded = details) {
                Loadable.Loading -> LoadingBox()
                is Loadable.Failed -> ErrorState(loaded.error, onRetry = vm::loadDetails)
                is Loadable.Loaded -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") { Header(loaded.value, subscribed, onToggle = vm::toggleSubscribe) }
                    stickyHeader(key = "tabs") {
                        PrimaryTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.surface) {
                            listOf(R.string.tab_videos, R.string.tab_shorts, R.string.tab_playlists).forEachIndexed { index, label ->
                                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(label)) })
                            }
                        }
                    }
                    when (tab) {
                        0 -> {
                            items(videos.items, key = { "v:${it.videoId}" }) { video -> VideoListItem(video, onClick = { onOpenVideo(video) }, showChannel = false) }
                            item(key = "videos-footer") {
                                if (videos.started && videos.items.isEmpty() && videos.error == null) {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.channel_no_videos)) }
                                } else {
                                    PagedFooter(videos.loading, videos.error, onRetry = vm::loadMoreVideos)
                                }
                            }
                        }
                        1 -> shortsTab(shorts, onOpenVideo, vm::loadShorts)
                        else -> {
                            items(playlists.items, key = { "p:${it.playlistId}" }) { playlist -> PlaylistListItem(playlist, onClick = { onOpenPlaylist(playlist.playlistId) }) }
                            item(key = "playlists-footer") {
                                if (playlists.started && playlists.items.isEmpty() && playlists.error == null) {
                                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.channel_no_playlists)) }
                                } else {
                                    PagedFooter(playlists.loading, playlists.error, onRetry = vm::loadMorePlaylists)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(details: ChannelDetails, subscribed: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(details.channel.avatarUrl, size = 72)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(details.channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details.channel.subscriberCount?.let {
                    Text(Formatters.subscribers(context, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (subscribed) {
            OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.action_unsubscribe)) }
        } else {
            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.action_subscribe)) }
        }
        details.description?.let { description ->
            Text(
                description,
                modifier = Modifier.padding(top = 12.dp).animateContentSize().clickable { expanded = !expanded },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shorts as a grid of three tall pictures per row. */
private fun androidx.compose.foundation.lazy.LazyListScope.shortsTab(
    shorts: Loadable<List<VideoItem>>,
    onOpenVideo: (VideoItem) -> Unit,
    retry: () -> Unit,
) {
    when (shorts) {
        Loadable.Loading -> item(key = "shorts-loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() } }
        is Loadable.Failed -> item(key = "shorts-error") { ErrorStateInline(shorts, retry) }
        is Loadable.Loaded -> if (shorts.value.isEmpty()) {
            item(key = "shorts-empty") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.channel_no_shorts)) } }
        } else {
            items(shorts.value.chunked(3), key = { row -> "s:" + row.first().videoId }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (video in row) {
                        Column(Modifier.weight(1f).clickable { onOpenVideo(video) }) {
                            Box(Modifier.fillMaxWidth().aspectRatio(9f / 14f).clip(RoundedCornerShape(10.dp))) {
                                Thumbnail(video.thumbnailUrl, Modifier.fillMaxSize())
                                DurationBadge(video, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                            }
                            Text(video.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ErrorStateInline(failed: Loadable.Failed, retry: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { ErrorState(failed.error, onRetry = retry, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) }
}
