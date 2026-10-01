package com.ahmadnayfeh.shasha.ui.settings.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
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
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.remote.NsdAdvertiser
import com.ahmadnayfeh.shasha.remote.PhonePin
import com.ahmadnayfeh.shasha.remote.PhonePinStatus
import com.ahmadnayfeh.shasha.remote.RemoteService
import com.ahmadnayfeh.shasha.ui.components.ConfirmDialog
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.formatCountdown
import com.ahmadnayfeh.shasha.ui.screens.settings.ToggleRow
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RemoteRoute() {
    val context = LocalContext.current
    val container = context.appContainer
    val runtime = container.remote
    val state by runtime.state.collectAsStateWithLifecycle()
    val token by runtime.pairingToken.collectAsStateWithLifecycle()
    val enabled by container.remoteSettings.enabled.collectAsStateWithLifecycle(initialValue = true)
    val phonePin by rememberPhonePinStatus(container.phonePin)
    val messages = LocalAppMessages.current
    val regenerated = stringResource(R.string.remote_regenerated)
    var confirming by remember { mutableStateOf(false) }
    // In front, the service may start even if a start from the background was refused earlier.
    LaunchedEffect(enabled) { if (enabled) RemoteService.startIfWanted(context) }
    RemoteScreen(
        ui = PairingUi.of(enabled, state, token),
        onToggle = {
            val on = !enabled
            container.appScope.launch {
                container.remoteSettings.setEnabled(on)
                if (on) RemoteService.startIfWanted(context)
            }
        },
        onRegenerate = { confirming = true },
        phonePin = phonePin,
    )
    if (confirming) {
        ConfirmDialog(
            title = stringResource(R.string.remote_regenerate_title),
            body = stringResource(R.string.remote_regenerate_body),
            confirmLabel = stringResource(R.string.remote_regenerate_confirm),
            confirmIcon = ShashaIcons.Refresh,
            onConfirm = {
                confirming = false
                container.appScope.launch {
                    runtime.regenerateToken()
                    messages?.show(regenerated, ShashaIcons.Refresh)
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The phone's PIN count ([PhonePin]), read again every second while the screen is in front (a lockout counts down). */
@Composable
private fun rememberPhonePinStatus(phonePin: PhonePin): State<PhonePinStatus?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<PhonePinStatus?>(null, phonePin, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = try {
                    phonePin.status()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                delay(PHONE_PIN_REFRESH_MS)
            }
        }
    }
}

/**
 * «التحكم من الجوال» (SPEC §5, §7): the pairing QR code, large, with the link as text under it (typed by hand
 * if the camera cannot read the screen) and the NSD name; beside it what to do, on/off, and a new token
 * (every paired phone must scan again). D-pad: the switch, then «إعادة توليد الرمز».
 *
 * [phonePin]: wrong PINs typed on a phone (every paired phone shares one count, and it may close the parent
 * section on all of them for a while) show above «إعادة توليد الرمز», which unpairs them and ends that.
 */
@Composable
fun RemoteScreen(
    ui: PairingUi,
    onToggle: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
    phonePin: PhonePinStatus? = null,
) {
    val toggle = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { toggle.requestFocus() }
    }
    Row(
        modifier
            .fillMaxSize()
            .padding(ShashaDimens.ScreenPadding)
            .testTag("remote_screen"),
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Centred beside the code while it fits, scrolling (as the focus moves) when it does not.
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            Details(ui, phonePin, toggle, onToggle, onRegenerate, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        }
        Column(Modifier.width(QR_SIZE)) {
            PairingQrCard(ui, size = QR_SIZE)
            if (ui.url != null) {
                Spacer(Modifier.height(12.dp))
                AddressBlock(ui.url, ui.serviceName ?: NsdAdvertiser.SERVICE_NAME)
            }
        }
    }
}

/** The title and state, the switch, what to do, the phone's wrong PINs (if any) and «إعادة توليد الرمز». */
@Composable
private fun Details(
    ui: PairingUi,
    phonePin: PhonePinStatus?,
    toggle: FocusRequester,
    onToggle: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(RemoteIcons.Phone, ShashaColors.Primary, 52)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.remote_title), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.width(14.dp))
                    StatusPill(ui.status)
                }
                Text(stringResource(R.string.remote_subtitle), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(16.dp))
        ToggleRow(
            icon = RemoteIcons.Wifi,
            label = stringResource(R.string.settings_remote),
            checked = ui.enabled,
            onToggle = onToggle,
            modifier = Modifier.focusRequester(toggle).testTag("remote_toggle"),
        )
        Spacer(Modifier.height(14.dp))
        Step(RemoteIcons.Camera, stringResource(R.string.remote_step_scan))
        Step(RemoteIcons.HomeScreen, stringResource(R.string.remote_step_home))
        Step(RemoteIcons.Share, stringResource(R.string.remote_step_share))
        if (phonePin != null && (phonePin.wrong > 0 || phonePin.lockedMillis > 0)) {
            Spacer(Modifier.height(8.dp))
            PhonePinNotice(phonePin)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ShashaButton(
                stringResource(R.string.remote_regenerate),
                onRegenerate,
                icon = ShashaIcons.Refresh,
                compact = true,
                modifier = Modifier.testTag("remote_regenerate"),
            )
            Spacer(Modifier.width(16.dp))
            // What a new token also does: the phones' shared count of wrong PINs (an accepted limit) starts again.
            Text(
                stringResource(R.string.remote_regenerate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = ShashaColors.Muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Wrong PINs from a phone, in amber: how many since the last right one, or that the phone's parent section is
 * closed and for how long (the TV's own PIN pad is not). Just above «إعادة توليد الرمز», the answer when it
 * was not the parent.
 */
@Composable
private fun PhonePinNotice(status: PhonePinStatus) {
    val text = if (status.lockedMillis > 0) {
        stringResource(R.string.remote_phone_pin_locked, formatCountdown(status.lockedMillis))
    } else {
        stringResource(R.string.remote_phone_pin_wrong, status.wrong.toString())
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(ShashaColors.Secondary.copy(alpha = 0.10f), shape)
            .border(1.dp, ShashaColors.Secondary.copy(alpha = 0.35f), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag("remote_phone_pin"),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(ShashaIcons.Lock, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** A small round icon in its colour's tint. */
@Composable
internal fun Badge(icon: ImageVector, tint: Color, sizeDp: Int) {
    val size = sizeDp.dp
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.14f), CircleShape).border(1.dp, tint.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun StatusPill(status: PairingStatus) {
    val (color, text) = when (status) {
        PairingStatus.RUNNING -> ShashaColors.Primary to R.string.remote_status_running
        PairingStatus.STARTING -> ShashaColors.Muted to R.string.remote_status_starting
        PairingStatus.NO_NETWORK -> ShashaColors.Secondary to R.string.remote_status_no_network
        PairingStatus.FAILED -> ShashaColors.Error to R.string.remote_status_failed
        PairingStatus.OFF -> ShashaColors.Muted to R.string.remote_status_off
    }
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.4f), shape)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("remote_status"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** One thing to do, with its icon. */
@Composable
private fun Step(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = ShashaColors.PrimaryBright, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Spacer(Modifier.width(12.dp))
        // Full width: Arabic text with Latin runs, laid out at its own measured width, can wrap its last word.
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** The pairing link, left to right in a monospace box (typed as shown), and the NSD name. */
@Composable
internal fun AddressBlock(url: String, serviceName: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(ShashaColors.Code, shape)
                .border(1.dp, ShashaColors.Outline, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(
                url,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textDirection = TextDirection.Ltr,
                ),
                color = ShashaColors.PrimaryBright,
                modifier = Modifier.fillMaxWidth().testTag("remote_url"),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.remote_nsd_label, serviceName),
            style = MaterialTheme.typography.labelMedium,
            color = ShashaColors.Muted,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val QR_SIZE = 300.dp

private const val PHONE_PIN_REFRESH_MS = 1_000L
