package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.LocalPlaylistIds
import com.ahmadnayfeh.shasha.mobile.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * One of the viewer's own playlists: its videos in the order they were added, «Play all», and renaming, deleting the list and
 * taking a video out of it. A video, or «Play all», plays the list in order: the player screen goes on to the next video by itself.
 */
@Composable
fun UserPlaylistScreen(
    container: AppContainer,
    playlistId: Long,
    onBack: () -> Unit,
    /** The video, and the id of this list as a playlist ([LocalPlaylistIds]): what plays next. */
    onPlay: (VideoItem, String) -> Unit,
) {
    val vm: UserPlaylistViewModel = viewModel(key = "user-playlist:$playlistId", factory = UserPlaylistViewModel.factory(container, playlistId))
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val name = (playlist as? Loadable.Loaded)?.value?.name.orEmpty()
    // Deleted from here, or from anywhere else while this page is open: there is nothing left to show.
    val gone = deleted || (playlist is Loadable.Loaded && (playlist as Loadable.Loaded).value == null)
    LaunchedEffect(gone) { if (gone) onBack() }

    SubScreen(
        title = name,
        onBack = onBack,
        actions = {
            if (playlist is Loadable.Loaded && !gone) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.playlist_rename)) }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.playlist_delete)) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = videos
            when {
                list == null || playlist is Loadable.Loading -> LoadingBox()
                gone -> Unit
                list.isEmpty() -> EmptyState(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    stringResource(R.string.playlist_user_empty),
                    stringResource(R.string.playlist_user_empty_body),
                )
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                Formatters.videos(LocalContext.current, list.size.toLong()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { onPlay(list.first(), LocalPlaylistIds.of(playlistId)) }, modifier = Modifier.padding(top = 12.dp)) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(stringResource(R.string.playlist_play_all), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    items(list, key = { it.videoId }) { video ->
                        VideoListItem(
                            video,
                            onClick = { onPlay(video, LocalPlaylistIds.of(playlistId)) },
                            trailing = {
                                IconButton(onClick = { vm.remove(video.videoId) }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_remove))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename_title),
            confirmLabel = stringResource(R.string.playlist_rename),
            initial = name,
            onConfirm = vm::rename,
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.playlist_delete_title),
            body = stringResource(R.string.playlist_delete_body, name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }
}
