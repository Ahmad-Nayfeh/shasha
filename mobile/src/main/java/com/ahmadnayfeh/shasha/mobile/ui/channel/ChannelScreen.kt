package com.ahmadnayfeh.shasha.mobile.ui.channel

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
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
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnNearEnd
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.PlaylistListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.mobile.ui.nav.ChannelRoute
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * A channel's page. It is not replaced by an error screen where YouTube refuses a part: that shows quietly where it would have
 * been (the header made of what is known with a small «Try again», the videos' «Try again» in the list, the Playlists tab's
 * note), and the rest of the page, the subscribe button included, keeps working ([ChannelViewModel]). Only a channel the content
 * filter refuses ([ChannelHeader.Refused]) is "not available" for the whole page.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(
    container: AppContainer,
    route: ChannelRoute,
    onBack: () -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    val vm: ChannelViewModel = viewModel(key = "channel:${route.channelId}", factory = ChannelViewModel.factory(container, route.channelId, route.known()))
    val header by vm.header.collectAsStateWithLifecycle()
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
                title = { Text((header as? ChannelHeader.Ready)?.channel?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val top = header) {
                ChannelHeader.Loading -> LoadingBox()
                // Held back by the content filter: "not available", with nothing to try again.
                is ChannelHeader.Refused -> ErrorState(top.error, onRetry = null)
                is ChannelHeader.Ready -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") { Header(top, subscribed, onToggle = vm::toggleSubscribe, onRetry = vm::retryHeader) }
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
                                    PagedFooter(videos.loading, videos.error, onRetry = vm::retry)
                                }
                            }
                        }
                        1 -> shortsTab(shorts, onOpenVideo, retry = { vm.loadShorts(force = true) })
                        else -> {
                            items(playlists.items, key = { "p:${it.playlistId}" }) { playlist -> PlaylistListItem(playlist, onClick = { onOpenPlaylist(playlist.playlistId) }) }
                            item(key = "playlists-footer") {
                                when {
                                    playlists.started && playlists.items.isEmpty() && playlists.error == null ->
                                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(stringResource(R.string.channel_no_playlists)) }
                                    // No other way to the playlists exists: the tab says so without claiming the channel has none.
                                    playlists.started && playlists.items.isEmpty() -> UnavailableNote(stringResource(R.string.channel_playlists_unavailable), vm::loadMorePlaylists)
                                    else -> PagedFooter(playlists.loading, playlists.error, onRetry = vm::loadMorePlaylists)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What the screen that opened the page passed about the channel, if anything. */
private fun ChannelRoute.known(): ChannelItem? =
    if (name == null && avatarUrl == null) null else ChannelItem(channelId, name.orEmpty(), avatarUrl, subscriberCount = null)

@Composable
private fun Header(header: ChannelHeader.Ready, subscribed: Boolean, onToggle: () -> Unit, onRetry: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val channel = header.channel
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(channel.avatarUrl, size = 72, name = channel.name)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                channel.subscriberCount?.let {
                    Text(Formatters.subscribers(context, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Made of what is known, not of YouTube's page for the channel: a small way to ask for the page again, and no more.
            if (!header.complete) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (header.refreshing) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (subscribed) {
            OutlinedButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.action_unsubscribe)) }
        } else {
            Button(onClick = onToggle, enabled = header.canSubscribe, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(stringResource(R.string.action_subscribe)) }
        }
        header.description?.let { description ->
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
        Loadable.Loading -> item(key = "shorts-loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        is Loadable.Failed -> item(key = "shorts-error") { PagedFooter(loading = false, error = shorts.error, onRetry = retry) }
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

/** A quiet note in a list that could not be read, with «Try again»: for a part that has no other way to be read. */
@Composable
private fun UnavailableNote(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}
