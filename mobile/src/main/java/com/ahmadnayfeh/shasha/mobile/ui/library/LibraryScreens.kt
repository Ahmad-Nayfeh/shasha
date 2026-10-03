package com.ahmadnayfeh.shasha.mobile.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.ui.components.ChannelListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** The Library tab: downloads, history, watch later and the channels followed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val history by vm.history.collectAsStateWithLifecycle()
    val watchLater by vm.watchLater.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val downloads by container.downloads.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val finished = downloads.count { it.status == DownloadStatus.DONE }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_library)) }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Entry(Icons.Filled.Download, stringResource(R.string.library_downloads), finished, onOpenDownloads) }
            item { Entry(Icons.Filled.WatchLater, stringResource(R.string.library_watch_later), watchLater?.size, onOpenWatchLater) }
            item { Entry(Icons.Filled.History, stringResource(R.string.library_history), history?.size, onOpenHistory) }
            item { Entry(Icons.Filled.Subscriptions, stringResource(R.string.library_subscriptions), channels?.size, onOpenSubscriptions) }
        }
    }
}

@Composable
private fun Entry(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, count: Int?, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        trailingContent = {
            if (count != null && count > 0) Text(count.toString())
            else Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        },
    )
}

@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit, onOpenVideo: (VideoItem) -> Unit) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val items by vm.history.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    SubScreen(
        title = stringResource(R.string.library_history),
        onBack = onBack,
        actions = {
            if (!items.isNullOrEmpty()) {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear_all)) }
            }
        },
    ) { padding ->
        VideoList(items, padding, emptyIcon = Icons.Filled.History, emptyTitle = stringResource(R.string.history_empty), emptyBody = stringResource(R.string.history_empty_body), onOpenVideo = onOpenVideo, onRemove = vm::removeFromHistory)
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.history_clear_title),
            body = stringResource(R.string.history_clear_body),
            confirmLabel = stringResource(R.string.action_clear_all),
            onConfirm = vm::clearHistory,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
fun WatchLaterScreen(container: AppContainer, onBack: () -> Unit, onOpenVideo: (VideoItem) -> Unit) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val items by vm.watchLater.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    SubScreen(
        title = stringResource(R.string.library_watch_later),
        onBack = onBack,
        actions = {
            if (!items.isNullOrEmpty()) {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.action_clear_all)) }
            }
        },
    ) { padding ->
        VideoList(items, padding, emptyIcon = Icons.Filled.WatchLater, emptyTitle = stringResource(R.string.watch_later_empty), emptyBody = stringResource(R.string.watch_later_empty_body), onOpenVideo = onOpenVideo, onRemove = vm::removeFromWatchLater)
    }
    if (confirm) {
        ConfirmDialog(
            title = stringResource(R.string.watch_later_clear_title),
            body = null,
            confirmLabel = stringResource(R.string.action_clear_all),
            onConfirm = vm::clearWatchLater,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun VideoList(
    items: List<VideoItem>?,
    padding: PaddingValues,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptyBody: String,
    onOpenVideo: (VideoItem) -> Unit,
    onRemove: (String) -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding)) {
        when {
            items == null -> LoadingBox()
            items.isEmpty() -> EmptyState(emptyIcon, emptyTitle, emptyBody)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(items, key = { it.videoId }) { video ->
                    VideoListItem(
                        video,
                        onClick = { onOpenVideo(video) },
                        trailing = {
                            IconButton(onClick = { onRemove(video.videoId) }) {
                                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_remove))
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SubscriptionsScreen(container: AppContainer, onBack: () -> Unit, onOpenChannel: (String) -> Unit, onSearch: () -> Unit) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val channels by vm.channels.collectAsStateWithLifecycle()
    SubScreen(title = stringResource(R.string.library_subscriptions), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = channels
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(
                    Icons.Filled.Subscriptions,
                    stringResource(R.string.subscriptions_empty),
                    stringResource(R.string.home_empty_body),
                    action = { OutlinedButton(onClick = onSearch) { Text(stringResource(R.string.home_empty_action)) } },
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.channelId }) { channel ->
                        ChannelListItem(
                            channel = com.ahmadnayfeh.shasha.youtube.ChannelItem(channel.channelId, channel.name, channel.avatarUrl, null),
                            onClick = { onOpenChannel(channel.channelId) },
                            trailing = {
                                OutlinedButton(onClick = { vm.unsubscribe(channel.channelId) }) { Text(stringResource(R.string.action_unsubscribe)) }
                            },
                        )
                    }
                }
            }
        }
    }
}
