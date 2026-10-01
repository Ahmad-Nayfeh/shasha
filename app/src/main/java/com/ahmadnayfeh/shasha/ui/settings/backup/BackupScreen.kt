package com.ahmadnayfeh.shasha.ui.settings.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.backup.BackupFile
import com.ahmadnayfeh.shasha.backup.BackupProblem
import com.ahmadnayfeh.shasha.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.settings.remote.Badge
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens

@Composable
fun BackupRoute() {
    val vm = containerViewModel { BackupViewModel(it.backup, it.appScope) }
    val files by vm.files.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchFiles() } }
    var confirm by remember { mutableStateOf<BackupFile?>(null) }
    val messages = LocalAppMessages.current
    val result = statusText(status)
    LaunchedEffect(status) {
        if (result != null && status !is BackupStatus.Working) {
            messages?.show(result, if (status is BackupStatus.Failed) ShashaIcons.Info else ShashaIcons.CheckCircle)
        }
    }
    BackupScreen(
        files = files,
        working = status == BackupStatus.Working,
        pullCommand = vm.pullCommand(),
        pushCommand = vm.pushCommand(),
        onExport = vm::export,
        onRestore = { confirm = it },
    )
    confirm?.let { file ->
        ConfirmDialog(
            title = stringResource(R.string.backup_import_confirm_title, file.name),
            body = stringResource(R.string.backup_import_confirm_body),
            confirmLabel = stringResource(R.string.backup_import_action),
            confirmIcon = RemoteIcons.Backup,
            onConfirm = {
                confirm = null
                vm.restore(file)
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * «الاستيراد والتصدير»: the manual JSON backup on the TV. «تصدير الآن» writes it to the app's folder and
 * shows the `adb pull` command for it; the files found in that folder (an export, or one put there with
 * `adb push`) are listed newest first, and OK on one restores it after a confirmation. The phone's parent
 * section downloads and restores the same file. [working]: an export or a restore is on its way (the result
 * comes as the app's short message).
 */
@Composable
fun BackupScreen(
    files: List<BackupFile>,
    working: Boolean,
    pullCommand: String?,
    pushCommand: String?,
    onExport: () -> Unit,
    onRestore: (BackupFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val exportButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { exportButton.requestFocus() }
    }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(ShashaDimens.ScreenPadding)
            .testTag("backup_screen"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(RemoteIcons.Backup, ShashaColors.Primary, 56)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.backup_subtitle), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        Card(
            RemoteIcons.Upload,
            stringResource(R.string.backup_export_title),
            stringResource(R.string.backup_export_body),
            action = {
                if (working) {
                    val label = stringResource(R.string.backup_working)
                    ShashaLoader(size = 32.dp, modifier = Modifier.testTag("backup_working").semantics { contentDescription = label })
                    Spacer(Modifier.width(16.dp))
                }
                ShashaButton(
                    stringResource(R.string.backup_export_action),
                    onExport,
                    icon = ShashaIcons.Download,
                    primary = true,
                    modifier = Modifier.focusRequester(exportButton).testTag("backup_export"),
                )
            },
        ) {
            if (pullCommand != null) {
                Spacer(Modifier.height(8.dp))
                CodeLine(pullCommand)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(ShashaIcons.Folder, stringResource(R.string.backup_import_title), stringResource(R.string.backup_import_body)) {
            if (pushCommand != null) {
                Spacer(Modifier.height(8.dp))
                CodeLine(pushCommand)
            }
            Spacer(Modifier.height(10.dp))
            if (files.isEmpty()) {
                Text(stringResource(R.string.backup_import_none), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { file -> FileRow(file) { onRestore(file) } }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.backup_phone_note), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun Card(
    icon: ImageVector,
    title: String,
    body: String,
    action: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(icon, ShashaColors.Secondary, 40)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            }
            action()
        }
        content()
    }
}

/** A command, left to right in a monospace box: it is typed exactly as shown. */
@Composable
private fun CodeLine(command: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Code, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            command,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                textDirection = TextDirection.Ltr,
            ),
            color = ShashaColors.PrimaryBright,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** One backup file: its name (as typed in adb, left to right) and size; OK restores it (after a question). */
@Composable
private fun FileRow(file: BackupFile, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("backup_file_${file.name}"),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.SurfaceVariant,
        scale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(ShashaIcons.File, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            // The name reads left to right, as adb types it; it sits beside its icon (the start, on the right).
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.backup_file_size, ((file.sizeBytes + 1023) / 1024).toString()),
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
            )
            Spacer(Modifier.width(12.dp))
            Icon(RemoteIcons.Backup, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.size(22.dp))
        }
    }
}

/** What an export or a restore ended with, for the app's short message; null while nothing happened. */
@Composable
private fun statusText(status: BackupStatus): String? {
    val formats = rememberFormats()
    return when (status) {
        BackupStatus.Idle, BackupStatus.Working -> null
        is BackupStatus.Exported -> stringResource(R.string.backup_exported, status.fileName)
        is BackupStatus.Restored -> {
            val restored = stringResource(
                R.string.backup_restored,
                formats.count(R.array.count_profiles, status.report.profiles.toLong()),
                formats.count(R.array.count_channels, status.report.subscriptions.toLong()),
            )
            if (status.report.skipped > 0) stringResource(R.string.backup_restored_skipped, restored, status.report.skipped.toString()) else restored
        }
        is BackupStatus.Failed -> stringResource(
            when (status.problem) {
                null -> if (status.export) R.string.backup_export_failed else R.string.backup_error_read
                BackupProblem.NOT_A_BACKUP -> R.string.backup_error_not_backup
                BackupProblem.NEWER_VERSION -> R.string.backup_error_newer
                BackupProblem.NO_ADULT -> R.string.backup_error_no_adult
                BackupProblem.TOO_LARGE -> R.string.backup_error_too_large
                BackupProblem.FAILED -> R.string.backup_error_failed
            },
        )
    }
}
