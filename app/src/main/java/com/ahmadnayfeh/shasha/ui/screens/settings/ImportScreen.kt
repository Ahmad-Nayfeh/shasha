package com.ahmadnayfeh.shasha.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
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
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import java.io.File

@Composable
fun ImportRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel {
        ImportViewModel(it.subscriptions, context.getExternalFilesDir(null)?.let { dir -> File(dir, "$DROP_DIR/$DROP_FILE") })
    }
    val status by vm.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.watchDropFile() } }

    val resolver = context.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onPicked { resolver.displayName(uri) to resolver.openInputStream(uri) }
    }
    ImportScreen(
        status = status,
        adbCommand = vm.adbCommand,
        onPick = {
            // Many TVs have no document picker at all: say so instead of doing nothing.
            try {
                picker.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                vm.pickerUnavailable()
            }
        },
        onDone = onDone,
    )
}

/**
 * «استيراد الاشتراكات»: bring the Takeout subscriptions list onto the TV, pushed with ADB (the exact
 * command is shown, and the file is picked up by itself) or picked from the TV's storage.
 */
@Composable
fun ImportScreen(status: ImportStatus, adbCommand: String?, onPick: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val formats = rememberFormats()
    val pickButton = remember { FocusRequester() }
    val doneButton = remember { FocusRequester() }
    LaunchedEffect(status is ImportStatus.Done) {
        withFrameNanos { }
        if (status is ImportStatus.Done) doneButton.requestFocus() else pickButton.requestFocus()
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(ShashaDimens.ScreenPadding)
            .testTag("import_screen"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ShashaIcons.Download, ShashaColors.Primary, size = 56.dp)
            Spacer(Modifier.width(18.dp))
            // Full width: Arabic text with Latin runs, laid out at its own measured width, can wrap its last word.
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.import_title), style = MaterialTheme.typography.headlineMedium)
                Text(
                    stringResource(R.string.import_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShashaColors.Muted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (adbCommand != null) {
            Card(
                ShashaIcons.Computer,
                stringResource(R.string.import_adb_title),
                stringResource(R.string.import_adb_body),
                action = { WaitingLine(status) },
            ) {
                Spacer(Modifier.height(10.dp))
                CodeBlock(adbCommand)
            }
            Spacer(Modifier.height(12.dp))
        }
        Card(
            ShashaIcons.Folder,
            stringResource(R.string.import_file_title),
            stringResource(R.string.import_file_body),
            action = {
                ShashaButton(
                    stringResource(R.string.import_file_pick),
                    onPick,
                    icon = ShashaIcons.File,
                    primary = true,
                    modifier = Modifier.focusRequester(pickButton).testTag("import_pick"),
                )
            },
        )
        if (status != ImportStatus.Waiting) {
            Spacer(Modifier.height(12.dp))
            Result(status, formats, onDone, doneButton)
        }
    }
}

/** A way to bring the file over: icon, title and explanation, then [action] beside them or [content] under them. */
@Composable
private fun Card(
    icon: ImageVector,
    title: String,
    body: String,
    action: @Composable () -> Unit = {},
    content: @Composable () -> Unit = {},
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, ShashaColors.Secondary, size = 40.dp)
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

/** The command, left to right in a monospace box: it is typed exactly as shown. */
@Composable
private fun CodeBlock(command: String) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF070A0E), shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(
            command,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                textDirection = TextDirection.Ltr,
            ),
            color = ShashaColors.PrimaryBright,
            modifier = Modifier.fillMaxWidth().testTag("import_adb_command"),
        )
    }
}

/** A breathing dot while the ADB file is awaited. */
@Composable
private fun WaitingLine(status: ImportStatus) {
    val waiting = status is ImportStatus.Waiting || status is ImportStatus.Failed
    Row(verticalAlignment = Alignment.CenterVertically) {
        val pulse = if (waiting) {
            rememberInfiniteTransition(label = "wait").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot").value
        } else {
            1f
        }
        Box(Modifier.size(10.dp).alpha(pulse).background(if (waiting) ShashaColors.Secondary else ShashaColors.Primary, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (status is ImportStatus.Importing) R.string.import_adb_found else R.string.import_adb_waiting),
            style = MaterialTheme.typography.labelLarge,
            color = ShashaColors.Muted,
        )
    }
}

@Composable
private fun Result(status: ImportStatus, formats: Formats, onDone: () -> Unit, doneButton: FocusRequester) {
    val shape = RoundedCornerShape(18.dp)
    val (accent, icon) = when (status) {
        is ImportStatus.Done -> ShashaColors.Primary to ShashaIcons.CheckCircle
        is ImportStatus.Failed -> ShashaColors.Error to ShashaIcons.Info
        else -> ShashaColors.Muted to ShashaIcons.Info
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f), shape)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag("import_result"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status is ImportStatus.Importing) ShashaLoader(size = 28.dp) else Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(resultTitle(status), style = MaterialTheme.typography.titleMedium)
            resultBody(status, formats)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            }
        }
        if (status is ImportStatus.Done) {
            ShashaButton(
                stringResource(R.string.import_go_home),
                onDone,
                icon = ShashaIcons.Home,
                primary = true,
                modifier = Modifier.focusRequester(doneButton).testTag("import_done"),
            )
        }
    }
}

@Composable
private fun resultTitle(status: ImportStatus): String = stringResource(
    when (status) {
        ImportStatus.Waiting, ImportStatus.Importing -> R.string.import_working
        is ImportStatus.Done -> R.string.import_done_title
        is ImportStatus.Failed -> R.string.import_error_title
    },
)

@Composable
private fun resultBody(status: ImportStatus, formats: Formats): String? = when (status) {
    ImportStatus.Waiting, ImportStatus.Importing -> null
    is ImportStatus.Done -> {
        val r = status.result
        buildList {
            if (r.added > 0) add(stringResource(R.string.import_done_added, formats.count(R.array.count_channels, r.added.toLong())))
            if (r.alreadyPresent > 0) add(stringResource(R.string.import_done_existing, r.alreadyPresent.toString()))
            if (r.invalid > 0) add(stringResource(R.string.import_done_invalid, r.invalid.toString()))
        }.joinToString("  ·  ")
    }
    is ImportStatus.Failed -> stringResource(
        when (status.reason) {
            ImportFailure.EMPTY -> R.string.import_error_empty
            ImportFailure.TOO_LARGE -> R.string.import_error_too_large
            ImportFailure.BROKEN_ZIP -> R.string.import_error_broken_zip
            ImportFailure.NO_CSV -> R.string.import_error_no_csv
            ImportFailure.NO_CHANNELS -> R.string.import_error_no_channels
            ImportFailure.UNREADABLE -> R.string.import_error_read
            ImportFailure.NO_PICKER -> R.string.import_file_unavailable
        },
    )
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color, size: Dp) {
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.14f), CircleShape).border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

private fun ContentResolver.displayName(uri: Uri): String =
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: DROP_FILE

/** `<external files dir>/import/subscriptions.csv`: where ADB drops the file. */
const val DROP_DIR = "import"
const val DROP_FILE = "subscriptions.csv"
