package com.ahmadnayfeh.shasha.mobile.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.data.db.UserPlaylistSummary
import com.ahmadnayfeh.shasha.mobile.data.toChannelItem
import com.ahmadnayfeh.shasha.mobile.ui.components.ChannelListItem
import com.ahmadnayfeh.shasha.mobile.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnTabReselected
import com.ahmadnayfeh.shasha.mobile.ui.components.PlaylistListItem
import com.ahmadnayfeh.shasha.mobile.ui.nav.LibraryRoute
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistNameDialog
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistsViewModel
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** The Library tab: downloads, history, watch later, the channels followed and the viewer's own playlists. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    container: AppContainer,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenWatchLater: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    /** One of the viewer's own playlists, by its id. */
    onOpenPlaylist: (Long) -> Unit,
    /** A YouTube playlist saved to the Library: its page, by its id. */
    onOpenSavedPlaylist: (String) -> Unit = {},
) {
    val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val playlistsVm: PlaylistsViewModel = viewModel(factory = PlaylistsViewModel.factory(container))
    val history by vm.history.collectAsStateWithLifecycle()
    val watchLater by vm.watchLater.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val playlists by playlistsVm.lists.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val downloads by container.downloads.downloads.collectAsStateWithLifecycle(initialValue = emptyList())
    val finished = downloads.count { it.status == DownloadStatus.DONE }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<UserPlaylistSummary?>(null) }
    var deleting by remember { mutableStateOf<UserPlaylistSummary?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_library)) }) },
    ) { padding ->
        val listState = rememberLazyListState()
        OnTabReselected(LibraryRoute::class) { listState.animateScrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            item { Entry(Icons.Filled.Download, stringResource(R.string.library_downloads), finished, onOpenDownloads) }
            item { Entry(Icons.Filled.WatchLater, stringResource(R.string.library_watch_later), watchLater?.size, onOpenWatchLater) }
            item { Entry(Icons.Filled.History, stringResource(R.string.library_history), history?.size, onOpenHistory) }
            item { Entry(Icons.Filled.Subscriptions, stringResource(R.string.library_subscriptions), channels?.size, onOpenSubscriptions) }
            item(key = "playlists-divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item(key = "playlists-title") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.library_my_playlists), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { creating = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.playlist_new)) }
                }
            }
            val lists = playlists
            if (lists != null && lists.isEmpty()) {
                item(key = "playlists-empty") {
                    Text(
                        stringResource(R.string.playlists_empty_body),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(lists.orEmpty(), key = { "playlist:" + it.id }) { playlist ->
                PlaylistEntry(playlist, onClick = { onOpenPlaylist(playlist.id) }, onRename = { renaming = playlist }, onDelete = { deleting = playlist })
            }
            // The YouTube playlists saved from their page or a list: shown once there is one (each row's button takes it out again).
            if (saved.isNotEmpty()) {
                item(key = "saved-divider") { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item(key = "saved-title") {
                    Text(
                        stringResource(R.string.library_saved_playlists),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(saved, key = { "saved:" + it.playlistId }) { playlist ->
                    PlaylistListItem(playlist, onClick = { onOpenSavedPlaylist(playlist.playlistId) })
                }
            }
        }
    }
    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            initial = "",
            onConfirm = playlistsVm::create,
            onDismiss = { creating = false },
        )
    }
    renaming?.let { target ->
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            confirmLabel = stringResource(R.string.playlist_rename),
            initial = target.name,
            onConfirm = { playlistsVm.rename(target.id, it) },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { target ->
        ConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            body = stringResource(R.string.playlist_delete_body, target.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { playlistsVm.delete(target.id) },
            onDismiss = { deleting = null },
        )
    }
}

/** One of the viewer's playlists: its first video's picture, its name and size, and a menu to rename or delete it. */
@Composable
private fun PlaylistEntry(playlist: UserPlaylistSummary, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(Modifier.width(80.dp).height(45.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (playlist.coverUrl != null) {
                    Thumbnail(playlist.coverUrl, Modifier.fillMaxSize())
                } else {
                    Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(Formatters.videos(LocalContext.current, playlist.itemCount.toLong())) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_rename)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.playlist_delete)) }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
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
fun SubscriptionsScreen(container: AppContainer, onBack: () -> Unit, onOpenChannel: (ChannelItem) -> Unit, onSearch: () -> Unit) {
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
                            channel = channel.toChannelItem(),
                            onClick = { onOpenChannel(channel.toChannelItem()) },
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
