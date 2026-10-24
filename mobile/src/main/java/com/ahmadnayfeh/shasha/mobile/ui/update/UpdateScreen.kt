package com.ahmadnayfeh.shasha.mobile.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.ui.components.SubScreen
import com.ahmadnayfeh.shasha.mobile.update.UpdateErrorKind

/** The text for a failed update (never names where the updates come from). */
internal fun updateErrorText(kind: UpdateErrorKind): Int = when (kind) {
    UpdateErrorKind.NETWORK -> R.string.update_err_network
    UpdateErrorKind.FORBIDDEN, UpdateErrorKind.NOT_FOUND -> R.string.update_err_unavailable
    UpdateErrorKind.NO_APK, UpdateErrorKind.BAD_ANSWER -> R.string.update_err_server
    UpdateErrorKind.DOWNLOAD_FAILED -> R.string.update_err_download
    UpdateErrorKind.BAD_APK, UpdateErrorKind.WRONG_PACKAGE, UpdateErrorKind.NOT_NEWER -> R.string.update_err_file
    UpdateErrorKind.SIGNATURE_MISMATCH -> R.string.update_err_signature
    UpdateErrorKind.INSTALL_FAILED -> R.string.update_err_install
}

/** «Check for updates»: says whether the app is up to date, or offers the newer one with a single button. */
@Composable
fun UpdateScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: UpdateViewModel = viewModel(factory = UpdateViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The system installer's confirmation dialog, when it asks.
    LaunchedEffect(vm) {
        vm.confirmations.collect { intent -> runCatching { context.startActivity(intent) } }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResumed() }

    SubScreen(title = stringResource(R.string.update_title), onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (val s = state) {
                UpdateUi.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_checking))
                }
                is UpdateUi.UpToDate -> {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_up_to_date), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_installed_version, s.installed.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = vm::check) { Text(stringResource(R.string.update_check_again)) }
                }
                is UpdateUi.Available -> {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_available, s.release.versionName), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_installed_version, s.installed.name), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.release.apk.size > 0) Text(Formatters.fileSize(s.release.apk.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { vm.install(s.release) }) { Text(stringResource(R.string.update_download_install)) }
                }
                is UpdateUi.Downloading -> {
                    Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val total = s.total
                    if (total != null && total > 0) {
                        LinearProgressIndicator(progress = { (s.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${Formatters.fileSize(s.done)} / ${Formatters.fileSize(total)}", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                is UpdateUi.NeedsPermission -> {
                    Text(stringResource(R.string.update_needs_permission), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { runCatching { context.startActivity(vm.permissionSettings()) } }) {
                        Text(stringResource(R.string.update_allow_install))
                    }
                }
                is UpdateUi.Installing -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.update_installing), textAlign = TextAlign.Center)
                }
                is UpdateUi.Failed -> {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(updateErrorText(s.kind)), textAlign = TextAlign.Center)
                    s.detail?.takeIf { it.isNotBlank() }?.let {
                        Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { s.release?.let(vm::install) ?: vm.check() }) { Text(stringResource(R.string.action_retry)) }
                }
            }
        }
    }
}
