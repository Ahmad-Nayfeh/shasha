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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
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
import com.ahmadnayfeh.shasha.ui.components.brandLockup
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
        actions = UpdatesActions(onBack = onBack, onCheck = vm::check, onRetry = vm::retry, onCancel = vm::cancel),
    )
}

/** What the updater screen's buttons do. */
class UpdatesActions(
    val onBack: () -> Unit,
    val onCheck: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onCancel: () -> Unit = {},
)

/** The updater in one [state]: what it found or does, the version(s), and the few buttons that state has. */
@Composable
fun UpdatesScreen(state: UpdateUiState, installed: InstalledVersion, actions: UpdatesActions, modifier: Modifier = Modifier) {
    val (title, body) = texts(state)
    UpdatesLayout(title = title, body = body, modifier = modifier.testTag("updates"), focusKey = state.focusKey()) { first ->
        when {
            state == UpdateUiState.Checking -> ShashaLoader(size = 48.dp)
            state is UpdateUiState.Downloading -> DownloadProgress(state.bytes, state.total)
            state is UpdateUiState.Installing && !state.confirmationClosed -> ShashaLoader(size = 48.dp)
        }
        if (state != UpdateUiState.Checking) VersionFacts(installed, state.release())
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

/** The brand, [title] and [body], then [content], all centred. */
@Composable
private fun UpdatesLayout(
    title: String,
    body: String,
    modifier: Modifier,
    focusKey: Any,
    content: @Composable (first: FocusRequester) -> Unit,
) {
    val first = remember { FocusRequester() }
    // Each state puts the focus on its own first button (the previous one may have left the screen).
    LaunchedEffect(focusKey) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Box(
        modifier.fillMaxSize().background(ShashaColors.Background).padding(ShashaDimens.ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Image(painterResource(brandLockup()), contentDescription = null, modifier = Modifier.height(40.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.testTag("updates_title"))
                Text(body, style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, textAlign = TextAlign.Center, modifier = Modifier.testTag("updates_body"))
            }
            content(first)
        }
    }
}

@Composable
private fun Buttons(state: UpdateUiState, actions: UpdatesActions, first: FocusRequester) {
    when (state) {
        UpdateUiState.Checking, UpdateUiState.TokenMissing, is UpdateUiState.Installed -> BackButton(actions, first)
        UpdateUiState.UpToDate -> {
            BackButton(actions, first)
            ShashaButton(stringResource(R.string.updates_action_check_again), actions.onCheck, icon = ShashaIcons.Refresh, modifier = Modifier.testTag("updates_check"))
        }
        is UpdateUiState.Downloading -> ShashaButton(
            stringResource(R.string.action_cancel),
            actions.onCancel,
            icon = ShashaIcons.Close,
            modifier = Modifier.focusRequester(first).testTag("updates_cancel"),
        )
        is UpdateUiState.Installing -> if (state.confirmationClosed) {
            RetryAndBack(actions, first)
        } else {
            BackButton(actions, first)
        }
        // A failed check is checked again; a stopped download or install is tried again (the ViewModel knows which).
        is UpdateUiState.InstallNotAllowed, is UpdateUiState.Cancelled, is UpdateUiState.Failed -> RetryAndBack(actions, first)
    }
}

/** «إعادة المحاولة» first (it has the focus), then «رجوع». */
@Composable
private fun RetryAndBack(actions: UpdatesActions, first: FocusRequester) {
    PrimaryButton(R.string.action_retry, ShashaIcons.Refresh, actions.onRetry, "updates_retry", first)
    BackButton(actions)
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

/** The installed version, beside the one being installed (in the accent) when there is one. */
@Composable
private fun VersionFacts(installed: InstalledVersion, latest: ReleaseInfo?) {
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
                highlight = true,
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

/** Title and explanation of each state. */
@Composable
private fun texts(state: UpdateUiState): Pair<String, String> = when (state) {
    UpdateUiState.Checking -> stringResource(R.string.updates_checking_title) to stringResource(R.string.updates_checking_body)
    UpdateUiState.TokenMissing -> stringResource(R.string.updates_token_title) to stringResource(R.string.updates_token_body)
    UpdateUiState.UpToDate -> stringResource(R.string.updates_up_to_date_title) to stringResource(R.string.updates_up_to_date_body)
    is UpdateUiState.Downloading -> stringResource(R.string.updates_downloading_title) to stringResource(R.string.updates_downloading_body)
    is UpdateUiState.Installing -> when {
        state.confirmationClosed -> stringResource(R.string.updates_closed_title) to stringResource(R.string.updates_closed_body)
        state.asked -> stringResource(R.string.updates_asked_title) to stringResource(R.string.updates_asked_body)
        else -> stringResource(R.string.updates_installing_title) to stringResource(R.string.updates_installing_body)
    }
    is UpdateUiState.Installed -> stringResource(R.string.updates_installed_title) to stringResource(R.string.updates_installed_body)
    is UpdateUiState.InstallNotAllowed -> stringResource(R.string.updates_not_allowed_title) to stringResource(R.string.updates_not_allowed_body)
    is UpdateUiState.Cancelled -> stringResource(R.string.updates_cancelled_title) to stringResource(R.string.updates_cancelled_body)
    is UpdateUiState.Failed -> stringResource(errorTitle(state.kind)) to stringResource(errorBody(state.kind))
}

/** The release a state is about (null: none yet, or the installed one is the latest). */
private fun UpdateUiState.release(): ReleaseInfo? = when (this) {
    is UpdateUiState.Downloading -> release
    is UpdateUiState.Installing -> release
    is UpdateUiState.Installed -> release
    is UpdateUiState.InstallNotAllowed -> release
    is UpdateUiState.Cancelled -> release
    is UpdateUiState.Failed -> release
    else -> null
}

/** What moves the focus onto the state's first button: another kind of state, or the confirmation having closed. */
private fun UpdateUiState.focusKey(): Any = if (this is UpdateUiState.Installing) "installing-$confirmationClosed" else this::class

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
