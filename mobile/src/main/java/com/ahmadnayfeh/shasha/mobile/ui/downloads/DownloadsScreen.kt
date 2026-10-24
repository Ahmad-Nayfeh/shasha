package com.ahmadnayfeh.shasha.mobile.ui.downloads

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.download.DeleteOutcome
import com.ahmadnayfeh.shasha.mobile.download.DownloadStage
import com.ahmadnayfeh.shasha.mobile.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.Thumbnail

/** The downloads: the queue with its progress, and the finished files to play offline or delete. */
@Composable
fun DownloadsScreen(container: AppContainer, onBack: () -> Unit, onPlay: (DownloadEntity) -> Unit) {
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.factory(container))
    val rows by vm.rows.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<DownloadEntity?>(null) }
    var failedDelete by remember { mutableStateOf<String?>(null) }
    var pendingForget by remember { mutableStateOf<Long?>(null) }

    // A file made by an earlier install of the app is deleted only with the viewer's consent, asked by the system.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingForget
        pendingForget = null
        if (result.resultCode == Activity.RESULT_OK && id != null) vm.forget(id)
    }

    SubScreen(title = stringResource(R.string.library_downloads), onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val list = rows
            when {
                list == null -> LoadingBox()
                list.isEmpty() -> EmptyState(Icons.Filled.Download, stringResource(R.string.downloads_empty), stringResource(R.string.downloads_empty_body))
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(list, key = { it.entity.id }) { row ->
                        DownloadItem(
                            row = row,
                            onPlay = { onPlay(row.entity) },
                            onCancel = { vm.cancel(row.entity.id) },
                            onRetry = { vm.retry(row.entity.id) },
                            onDelete = { toDelete = row.entity },
                        )
                    }
                }
            }
        }
    }

    toDelete?.let { entity ->
        ConfirmDialog(
            title = stringResource(R.string.downloads_delete_title),
            body = stringResource(R.string.downloads_delete_body, entity.title),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                vm.delete(entity.id) { outcome ->
                    when (outcome) {
                        is DeleteOutcome.NeedsConsent -> {
                            pendingForget = entity.id
                            consent.launch(IntentSenderRequest.Builder(outcome.sender).build())
                        }
                        is DeleteOutcome.Failed -> failedDelete = outcome.reason
                        else -> Unit
                    }
                }
            },
            onDismiss = { toDelete = null },
        )
    }
    failedDelete?.let {
        ConfirmDialog(
            title = stringResource(R.string.downloads_delete_failed),
            body = stringResource(R.string.downloads_delete_failed_body),
            confirmLabel = stringResource(R.string.action_ok),
            onConfirm = {},
            onDismiss = { failedDelete = null },
        )
    }
}

@Composable
private fun DownloadItem(row: DownloadRow, onPlay: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    val entity = row.entity
    val done = entity.status == DownloadStatus.DONE
    Row(
        Modifier.fillMaxWidth().clickable(enabled = done, onClick = onPlay).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            Thumbnail(entity.thumbnailUrl, Modifier.fillMaxSize())
            if (entity.kind == DownloadKind.AUDIO) {
                Box(Modifier.fillMaxSize().padding(0.dp), contentAlignment = Alignment.Center) {
                    // On a dark round backdrop: without a picture the placeholder is light in the light theme.
                    Icon(
                        Icons.Filled.AudioFile,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.clip(CircleShape).background(Color(0x66000000)).padding(6.dp).size(28.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entity.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when (entity.status) {
                DownloadStatus.QUEUED -> Detail(stringResource(R.string.download_queued))
                DownloadStatus.RUNNING -> {
                    val progress = row.progress
                    val fraction = progress?.fraction ?: -1f
                    Detail(
                        when {
                            progress == null || progress.stage == DownloadStage.PREPARING -> stringResource(R.string.download_preparing)
                            progress.stage == DownloadStage.MERGING -> stringResource(R.string.download_merging)
                            fraction >= 0 -> stringResource(R.string.download_downloading_percent, (fraction * 100).toInt().toString()) +
                                if (progress.bytesTotal > 0) " · \u200E${Formatters.fileSize(progress.bytesDone)} / ${Formatters.fileSize(progress.bytesTotal)}\u200E" else ""
                            else -> stringResource(R.string.download_downloading)
                        },
                    )
                    if (fraction >= 0) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                DownloadStatus.DONE -> Detail(
                    listOfNotNull(
                        if (entity.kind == DownloadKind.AUDIO) stringResource(R.string.download_audio_label) else Formatters.quality(entity.height),
                        entity.fileBytes.takeIf { it > 0 }?.let(Formatters::fileSize),
                    ).joinToString(" · "),
                )
                DownloadStatus.FAILED -> {
                    Detail(stringResource(R.string.download_failed), error = true)
                    entity.error?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        when (entity.status) {
            DownloadStatus.QUEUED, DownloadStatus.RUNNING ->
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
            DownloadStatus.FAILED -> {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry)) }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
            }
            DownloadStatus.DONE ->
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete)) }
        }
    }
}

@Composable
private fun Detail(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
