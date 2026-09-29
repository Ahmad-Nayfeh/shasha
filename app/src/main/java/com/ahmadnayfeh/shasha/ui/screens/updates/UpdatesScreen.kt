package com.ahmadnayfeh.shasha.ui.screens.updates

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.update.InstalledVersion
import com.ahmadnayfeh.shasha.update.ReleaseInfo
import com.ahmadnayfeh.shasha.update.UpdateErrorKind
import java.util.Locale

/**
 * «فحص التحديثات» (SPEC §6), from Settings and from the unified error screen. Only the adult profile reaches the
 * updater; a child sees who can update the app instead ([UpdatesChildNotice]).
 */
@Composable
fun UpdatesRoute(adult: Boolean, onBack: () -> Unit) {
    val container = LocalContext.current.appContainer
    if (!adult) {
        UpdatesChildNotice(BuildConfig.VERSION_NAME, container.videoSource.extractorVersion, onBack)
        return
    }
    val vm: UpdatesViewModel = viewModel(factory = viewModelFactory { initializer { UpdatesViewModel(container.updater) } })
    val state by vm.state.collectAsStateWithLifecycle()
    // Through the activity's result launcher: coming back from the system's dialog never relocks the profile.
    val confirmation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onConfirmationClosed() }
    val confirm = (state as? UpdateUiState.Installing)?.confirm
    LaunchedEffect(confirm) {
        if (confirm == null) return@LaunchedEffect
        vm.onConfirmationShown()
        try {
            confirmation.launch(confirm)
        } catch (e: ActivityNotFoundException) {
            vm.onConfirmationUnavailable()
        }
    }
    UpdatesScreen(
        state = state,
        installed = container.updater.installed,
        actions = UpdatesActions(onBack = onBack, onCheck = vm::check, onInstall = vm::install, onCancel = vm::cancelDownload),
    )
}

/** What the updater screen's buttons do. */
class UpdatesActions(
    val onBack: () -> Unit,
    val onCheck: () -> Unit = {},
    val onInstall: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/**
 * The updater in one [state]: what it found or does, the two versions and its buttons; an update on offer shows
 * its release notes beside them (the TV is wide, not tall).
 */
@Composable
fun UpdatesScreen(state: UpdateUiState, installed: InstalledVersion, actions: UpdatesActions, modifier: Modifier = Modifier) {
    val (title, body) = texts(state)
    val release = state.release()
    val notes = (state as? UpdateUiState.Available)?.release?.notes?.takeIf { it.isNotBlank() }
    UpdatesLayout(
        title = title,
        body = body,
        modifier = modifier.testTag("updates"),
        focusKey = state::class,
        side = notes?.let { { ReleaseNotesCard(it) } },
    ) { first ->
        when (state) {
            UpdateUiState.Checking -> ShashaLoader(size = 48.dp)
            UpdateUiState.TokenMissing -> {
                Text(stringResource(R.string.updates_token_adb), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, textAlign = TextAlign.Center)
                CommandBox(ADB_INSTALL_COMMAND)
            }
            is UpdateUiState.InstallNotAllowed -> CommandBox(state.command)
            is UpdateUiState.Downloading -> DownloadProgress(state.bytes, state.total)
            is UpdateUiState.Failed -> state.detail?.let { TechnicalDetail(it) }
            else -> Unit
        }
        if (state != UpdateUiState.Checking) {
            VersionFacts(installed, release ?: (state as? UpdateUiState.UpToDate)?.latest, highlightLatest = release != null)
        }
        if (state is UpdateUiState.Available && !state.installAllowed) {
            Text(stringResource(R.string.updates_available_not_allowed), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Secondary, textAlign = TextAlign.Center)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) { Buttons(state, actions, first) }
    }
}

/** A child profile never reaches the updater: who can update the app, and the version it runs. */
@Composable
fun UpdatesChildNotice(appVersion: String, extractorVersion: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    UpdatesLayout(
        title = stringResource(R.string.updates_child_title),
        body = stringResource(R.string.updates_child_body),
        modifier = modifier.testTag("updates_child"),
        focusKey = Unit,
    ) { first ->
        Text(
            stringResource(R.string.updates_current_version, appVersion, extractorVersion),
            style = MaterialTheme.typography.labelLarge,
            color = ShashaColors.Secondary,
        )
        ShashaButton(stringResource(R.string.action_back), onBack, icon = ShashaIcons.Back, modifier = Modifier.focusRequester(first).testTag("updates_back"))
    }
}

/**
 * The brand, [title] and [body], then [content]; with [side] (the release notes) the two sit side by side: the
 * information at the start (right), the notes at the end.
 */
@Composable
private fun UpdatesLayout(
    title: String,
    body: String,
    modifier: Modifier,
    focusKey: Any,
    side: (@Composable () -> Unit)? = null,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    // Each state puts the focus on its own first button (the previous one may have left the screen).
    LaunchedEffect(focusKey) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val main = @Composable { columnModifier: Modifier ->
        Column(columnModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.splash_lockup), contentDescription = null, modifier = Modifier.height(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("updates_title"))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, textAlign = TextAlign.Center)
            }
            content(first)
        }
    }
    Box(
        modifier.fillMaxSize().background(ShashaColors.Background).padding(ShashaDimens.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (side == null) {
            main(Modifier.widthIn(max = 760.dp))
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
                main(Modifier.weight(1f))
                Box(Modifier.weight(1f)) { side() }
            }
        }
    }
}

@Composable
private fun Buttons(state: UpdateUiState, actions: UpdatesActions, first: FocusRequester) {
    when (state) {
        UpdateUiState.Checking, UpdateUiState.TokenMissing, is UpdateUiState.Installed -> BackButton(actions, first)
        is UpdateUiState.UpToDate -> {
            BackButton(actions, first)
            ShashaButton(stringResource(R.string.updates_action_check_again), actions.onCheck, icon = ShashaIcons.Refresh, modifier = Modifier.testTag("updates_check"))
        }
        is UpdateUiState.Available -> {
            PrimaryButton(R.string.updates_action_install, ShashaIcons.SystemUpdate, actions.onInstall, "updates_install", first)
            BackButton(actions)
        }
        is UpdateUiState.Downloading -> ShashaButton(
            stringResource(R.string.updates_action_cancel),
            actions.onCancel,
            icon = ShashaIcons.Close,
            modifier = Modifier.focusRequester(first).testTag("updates_cancel"),
        )
        is UpdateUiState.Installing -> if (state.confirmationClosed) {
            PrimaryButton(R.string.action_retry, ShashaIcons.Refresh, actions.onInstall, "updates_retry", first)
            BackButton(actions)
        } else {
            BackButton(actions, first)
        }
        is UpdateUiState.InstallNotAllowed -> {
            PrimaryButton(R.string.action_retry, ShashaIcons.Refresh, actions.onInstall, "updates_retry", first)
            BackButton(actions)
        }
        is UpdateUiState.Failed -> {
            // A failed check is checked again; a failed download or install is tried again.
            PrimaryButton(R.string.action_retry, ShashaIcons.Refresh, if (state.release != null) actions.onInstall else actions.onCheck, "updates_retry", first)
            BackButton(actions)
        }
    }
}

/** «رجوع»; [focus] when it is the state's first button. */
@Composable
private fun BackButton(actions: UpdatesActions, focus: FocusRequester? = null) {
    ShashaButton(
        stringResource(R.string.action_back),
        actions.onBack,
        icon = ShashaIcons.Back,
        modifier = (if (focus != null) Modifier.focusRequester(focus) else Modifier).testTag("updates_back"),
    )
}

@Composable
private fun PrimaryButton(@StringRes text: Int, icon: ImageVector, onClick: () -> Unit, tag: String, focus: FocusRequester) {
    ShashaButton(stringResource(text), onClick, icon = icon, primary = true, modifier = Modifier.focusRequester(focus).testTag(tag))
}

/** The installed version beside the newest published one (in the accent when it is the update on offer). */
@Composable
private fun VersionFacts(installed: InstalledVersion, latest: ReleaseInfo?, highlightLatest: Boolean) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .background(ShashaColors.Surface.copy(alpha = 0.85f), shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("updates_versions"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Fact(stringResource(R.string.updates_installed_version), stringResource(R.string.updates_version_value, installed.name, installed.code.toString()))
        if (latest != null) {
            Box(Modifier.padding(horizontal = 24.dp).width(1.dp).fillMaxHeight().background(ShashaColors.Outline))
            Fact(
                stringResource(R.string.updates_latest_version),
                stringResource(R.string.updates_version_value, latest.versionName, latest.versionCode.toString()),
                highlight = highlightLatest,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String, highlight: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
            color = if (highlight) LocalAccent.current.bright else ShashaColors.Text,
            maxLines = 1,
        )
    }
}

/** The release's own text (CI writes the extractor version, the commit and the signing key's fingerprint there). */
@Composable
private fun ReleaseNotesCard(notes: String) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 22.dp, vertical = 14.dp)
            .testTag("updates_notes"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.updates_notes), style = MaterialTheme.typography.labelLarge, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
        // Lines in Latin keep their own order but, like everything else, hang from the right edge.
        Text(
            notes,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            textAlign = TextAlign.Right,
            maxLines = NOTES_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A bar that fills from the right (Arabic reading order) and "12.3 من 25.0 ميغابايت" under it. */
@Composable
private fun DownloadProgress(bytes: Long, total: Long?) {
    val fraction = if (total != null && total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(ShashaColors.SurfaceVariant, RoundedCornerShape(percent = 50))
                .testTag("updates_progress"),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction ?: 0f)
                    .fillMaxHeight()
                    .background(LocalAccent.current.main, RoundedCornerShape(percent = 50)),
            )
        }
        val done = megabytes(bytes)
        Text(
            if (total != null && total > 0) {
                stringResource(R.string.updates_progress, done, megabytes(total))
            } else {
                stringResource(R.string.updates_progress_unknown, done)
            },
            style = MaterialTheme.typography.titleMedium,
            color = ShashaColors.Text,
        )
    }
}

/** An adb command as it is typed: left to right, fixed width. */
@Composable
private fun CommandBox(command: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            command,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, textDirection = TextDirection.Ltr),
            color = ShashaColors.Text,
            modifier = Modifier
                .background(ShashaColors.Surface, RoundedCornerShape(10.dp))
                .border(1.dp, ShashaColors.Outline, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("updates_command"),
        )
    }
}

/** What failed, as GitHub or the system said it (Latin, left to right). */
@Composable
private fun TechnicalDetail(detail: String) {
    Text(
        detail,
        style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr),
        color = ShashaColors.Secondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.testTag("updates_detail"),
    )
}

/** Title and explanation of each state. */
@Composable
private fun texts(state: UpdateUiState): Pair<String, String> = when (state) {
    UpdateUiState.Checking -> stringResource(R.string.updates_checking_title) to stringResource(R.string.updates_checking_body)
    UpdateUiState.TokenMissing -> stringResource(R.string.updates_token_title) to stringResource(R.string.updates_token_body)
    is UpdateUiState.UpToDate -> stringResource(R.string.updates_up_to_date_title) to
        stringResource(if (state.latest != null) R.string.updates_up_to_date_body else R.string.updates_none_published_body)
    is UpdateUiState.Available -> stringResource(R.string.updates_available_title) to stringResource(R.string.updates_available_body)
    is UpdateUiState.Downloading -> stringResource(R.string.updates_downloading_title) to stringResource(R.string.updates_downloading_body)
    is UpdateUiState.Installing -> stringResource(R.string.updates_installing_title) to
        stringResource(if (state.confirmationClosed) R.string.updates_installing_closed else R.string.updates_installing_body)
    is UpdateUiState.Installed -> stringResource(R.string.updates_installed_title) to stringResource(R.string.updates_installed_body)
    is UpdateUiState.InstallNotAllowed -> stringResource(R.string.updates_not_allowed_title) to stringResource(R.string.updates_not_allowed_body)
    is UpdateUiState.Failed -> stringResource(errorTitle(state.kind)) to stringResource(errorBody(state.kind))
}

private fun UpdateUiState.release(): ReleaseInfo? = when (this) {
    is UpdateUiState.Available -> release
    is UpdateUiState.Downloading -> release
    is UpdateUiState.Installing -> release
    is UpdateUiState.Installed -> release
    is UpdateUiState.InstallNotAllowed -> release
    is UpdateUiState.Failed -> release
    else -> null
}

/** Every [UpdateErrorKind] has its words; `when` is exhaustive, so a new kind cannot be forgotten. */
@StringRes
internal fun errorTitle(kind: UpdateErrorKind): Int = when (kind) {
    UpdateErrorKind.NETWORK -> R.string.updates_error_network_title
    UpdateErrorKind.TOKEN_REJECTED -> R.string.updates_error_token_title
    UpdateErrorKind.FORBIDDEN -> R.string.updates_error_forbidden_title
    UpdateErrorKind.NOT_FOUND -> R.string.updates_error_not_found_title
    UpdateErrorKind.NO_APK -> R.string.updates_error_no_apk_title
    UpdateErrorKind.BAD_TAG -> R.string.updates_error_bad_tag_title
    UpdateErrorKind.BAD_ANSWER -> R.string.updates_error_bad_answer_title
    UpdateErrorKind.DOWNLOAD_FAILED -> R.string.updates_error_download_title
    UpdateErrorKind.BAD_APK -> R.string.updates_error_bad_apk_title
    UpdateErrorKind.WRONG_PACKAGE -> R.string.updates_error_wrong_package_title
    UpdateErrorKind.NOT_NEWER -> R.string.updates_error_not_newer_title
    UpdateErrorKind.SIGNATURE_MISMATCH -> R.string.updates_error_signature_title
    UpdateErrorKind.INSTALL_FAILED -> R.string.updates_error_install_title
}

@StringRes
internal fun errorBody(kind: UpdateErrorKind): Int = when (kind) {
    UpdateErrorKind.NETWORK -> R.string.updates_error_network_body
    UpdateErrorKind.TOKEN_REJECTED -> R.string.updates_error_token_body
    UpdateErrorKind.FORBIDDEN -> R.string.updates_error_forbidden_body
    UpdateErrorKind.NOT_FOUND -> R.string.updates_error_not_found_body
    UpdateErrorKind.NO_APK -> R.string.updates_error_no_apk_body
    UpdateErrorKind.BAD_TAG -> R.string.updates_error_bad_tag_body
    UpdateErrorKind.BAD_ANSWER -> R.string.updates_error_bad_answer_body
    UpdateErrorKind.DOWNLOAD_FAILED -> R.string.updates_error_download_body
    UpdateErrorKind.BAD_APK -> R.string.updates_error_bad_apk_body
    UpdateErrorKind.WRONG_PACKAGE -> R.string.updates_error_wrong_package_body
    UpdateErrorKind.NOT_NEWER -> R.string.updates_error_not_newer_body
    UpdateErrorKind.SIGNATURE_MISMATCH -> R.string.updates_error_signature_body
    UpdateErrorKind.INSTALL_FAILED -> R.string.updates_error_install_body
}

/** Megabytes with one decimal, Western digits whatever the TV's language. */
private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)

/** The adb alternative when the build has no token (the file name as downloaded from the Releases page). */
private const val ADB_INSTALL_COMMAND = "adb install -r shasha-<version>.apk"

private const val NOTES_MAX_LINES = 12
