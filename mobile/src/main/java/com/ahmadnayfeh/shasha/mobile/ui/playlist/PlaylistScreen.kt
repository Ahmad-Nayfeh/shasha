package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.LibraryAddCheck
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalSavedPlaylistIds
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.OnNearEnd
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    container: AppContainer,
    playlistId: String,
    onBack: () -> Unit,
    /** The video, and the playlist it is played from (what plays next). */
    onOpenVideo: (VideoItem, String) -> Unit,
) {
    val vm: PlaylistViewModel = viewModel(key = "playlist:$playlistId", factory = PlaylistViewModel.factory(container, playlistId))
    val details by vm.details.collectAsStateWithLifecycle()
    val videos by vm.videoState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    listState.OnNearEnd { vm.loadMore() }
    val context = LocalContext.current
    val actions = LocalVideoActions.current
    val savedIds = LocalSavedPlaylistIds.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(details?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val failure = videos.error
            when {
                !videos.started && failure == null -> LoadingBox()
                videos.items.isEmpty() && failure != null -> ErrorState(failure, onRetry = vm::retry)
                videos.items.isEmpty() -> EmptyState(Icons.Filled.PlayArrow, stringResource(R.string.playlist_empty), null)
                else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "header") {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(details?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
                            val owner = listOfNotNull(
                                details?.channelName,
                                details?.videoCount?.let { Formatters.videos(context, it) },
                            ).joinToString(" · ")
                            if (owner.isNotEmpty()) {
                                Text(owner, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onOpenVideo(videos.items.first(), playlistId) }) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                    Text(stringResource(R.string.playlist_play_all), Modifier.padding(start = 8.dp))
                                }
                                // «Save to library» / «Saved»: the Library lists it and opens this page again.
                                details?.let { playlist ->
                                    val saved = playlistId in savedIds
                                    OutlinedButton(onClick = { actions.toggleSavedPlaylist(playlist.copy(playlistId = playlistId)) }) {
                                        Icon(if (saved) Icons.Filled.LibraryAddCheck else Icons.Filled.LibraryAdd, contentDescription = null)
                                        Text(
                                            stringResource(if (saved) R.string.playlist_saved else R.string.playlist_save),
                                            Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    items(videos.items, key = { it.videoId }, contentType = { "video" }) { video -> VideoListItem(video, onClick = { onOpenVideo(video, playlistId) }) }
                    item(key = "footer") { PagedFooter(videos.loading, videos.error, onRetry = vm::retry) }
                }
            }
        }
    }
}
