package com.ahmadnayfeh.shasha.mobile.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * «Save to…»: where [video] can be kept, from a video's menu or the player screen. «Watch later» (the built-in list) and every
 * playlist of the viewer's own, each with a checkmark when the video is in it; a tap puts the video there or takes it out at once,
 * and «New playlist» makes a list that holds the video. A video can be in several lists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(container: AppContainer, video: VideoItem, onDismiss: () -> Unit) {
    val vm: PlaylistPickerViewModel = viewModel(key = "playlist-sheet:${video.videoId}", factory = PlaylistPickerViewModel.factory(container, video))
    val entries by vm.entries.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Text(stringResource(R.string.playlist_picker_title), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(entries.orEmpty(), key = { it.playlistId ?: -1L }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { vm.toggle(entry) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = entry.contains, onCheckedChange = null)
                        Icon(
                            if (entry.playlistId == null) Icons.Filled.WatchLater else Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
                            Text(
                                if (entry.playlistId == null) stringResource(R.string.library_watch_later) else entry.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            entry.count?.let {
                                Text(Formatters.videos(context, it.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.playlist_new), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.action_done)) }
        }
    }
    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            initial = "",
            onConfirm = vm::createAndAdd,
            onDismiss = { creating = false },
        )
    }
}
