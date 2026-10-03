package com.ahmadnayfeh.shasha.mobile.ui.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The download sheet: the qualities the video can be downloaded in (MP4, video and sound merged into one file), and audio
 * only (M4A). The one that matches the download quality in Settings is marked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(container: AppContainer, video: VideoItem, onDismiss: () -> Unit, onStarted: () -> Unit) {
    val vm: DownloadSheetViewModel = viewModel(key = "download-sheet:${video.videoId}", factory = DownloadSheetViewModel.factory(container, video))
    val state by vm.state.collectAsStateWithLifecycle()
    val preferred by vm.preferredHeight.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<DownloadKind, Int>?>(null) }

    fun begin(kind: DownloadKind, height: Int) {
        vm.start(kind, height)
        onStarted()
        onDismiss()
    }

    // Before Android 10, a download is a file in Movies or Music: that needs the storage permission, asked on the first one.
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pending?.let { (kind, height) -> if (granted) begin(kind, height) }
        pending = null
    }
    // From Android 13, the progress notification needs its own permission; a download starts without it.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun choose(kind: DownloadKind, height: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pending = kind to height
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        begin(kind, height)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.download_title), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            Text(
                video.title,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            when (val loaded = state) {
                Loadable.Loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is Loadable.Failed -> ErrorState(loaded.error, onRetry = vm::load, modifier = Modifier.padding(vertical = 8.dp))
                is Loadable.Loaded -> {
                    val options = loaded.value
                    if (options.isEmpty) {
                        Text(stringResource(R.string.download_unavailable), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (options.videos.isNotEmpty()) {
                        Text(stringResource(R.string.download_video_section), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                        val marked = options.videoFor(preferred)?.height
                        for (option in options.videos) {
                            OptionRow(
                                icon = { Icon(Icons.Filled.Movie, contentDescription = null) },
                                title = Formatters.quality(option.height, option.fps),
                                detail = option.sizeBytes?.let(Formatters::fileSize),
                                marked = option.height == marked,
                                onClick = { choose(DownloadKind.VIDEO, option.height) },
                            )
                        }
                    }
                    options.audio?.let { audio ->
                        Text(stringResource(R.string.download_audio_section), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
                        OptionRow(
                            icon = { Icon(Icons.Filled.AudioFile, contentDescription = null) },
                            title = stringResource(R.string.download_audio_only),
                            detail = audio.sizeBytes?.let(Formatters::fileSize),
                            marked = false,
                            onClick = { choose(DownloadKind.AUDIO, 0) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(icon: @Composable () -> Unit, title: String, detail: String?, marked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (marked) Text(stringResource(R.string.download_preferred), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
