package com.ahmadnayfeh.shasha.ui.settings.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.remote.NsdAdvertiser
import com.ahmadnayfeh.shasha.remote.RemoteService
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The first start's last step: pair the phone right away (SPEC §5). The parent PIN exists by now, so the
 * phone remote starts (on by default) and its QR code shows as soon as it listens.
 */
@Composable
fun SetupPairingStep(onDone: () -> Unit) {
    val context = LocalContext.current
    val container = context.appContainer
    val runtime = container.remote
    val state by runtime.state.collectAsStateWithLifecycle()
    val token by runtime.pairingToken.collectAsStateWithLifecycle()
    val enabled by container.remoteSettings.enabled.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(Unit) { RemoteService.startIfWanted(context) }
    SetupPairingPanel(PairingUi.of(enabled, state, token), onDone)
}

/** The step itself, without the app (screenshots). */
@Composable
fun SetupPairingPanel(ui: PairingUi, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val done = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { done.requestFocus() }
    }
    Row(modifier.testTag("setup_phone"), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(430.dp)) {
            Badge(RemoteIcons.Phone, ShashaColors.Primary, 52)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.setup_phone_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.setup_phone_body), style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            if (ui.url != null) {
                Spacer(Modifier.height(14.dp))
                AddressBlock(ui.url, ui.serviceName ?: NsdAdvertiser.SERVICE_NAME)
            }
            Spacer(Modifier.height(22.dp))
            ShashaButton(
                stringResource(R.string.setup_phone_done),
                onDone,
                icon = ShashaIcons.Check,
                primary = true,
                modifier = Modifier.focusRequester(done).testTag("setup_phone_done"),
            )
        }
        PairingQrCard(ui, size = QR_SIZE)
    }
}

/** Above the step dots there are 422dp. */
private val QR_SIZE = 300.dp
