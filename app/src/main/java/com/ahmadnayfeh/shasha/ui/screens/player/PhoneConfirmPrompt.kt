package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.formatCountdown
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay

/** What the «أكّد من الجوال» prompt reads while it waits (v1.1.1): it changes by itself, so it is read again and again. */
interface PhoneWait {
    /** Seconds before the TV stops waiting for the phone (0 when nothing waits). */
    fun secondsLeft(): Int

    /** A paired phone is there now: its page polled in the last minute ([com.ahmadnayfeh.shasha.remote.PhoneReach]). */
    fun phoneConnected(): Boolean
}

/** The prompt of [PhoneWait], read about twice a second. */
@Composable
internal fun PhoneConfirmPrompt(title: String, wait: PhoneWait, onCancel: () -> Unit, onPairPhone: (() -> Unit)?, modifier: Modifier = Modifier) {
    val seen by produceState(wait.secondsLeft() to wait.phoneConnected(), wait) {
        while (true) {
            value = wait.secondsLeft() to wait.phoneConnected()
            delay(READ_EVERY_MS)
        }
    }
    PhoneConfirmPanel(title, seen.first, seen.second, onCancel, onPairPhone, modifier)
}

/**
 * v1.1.1: the video would start in Serious Mode while only the phone could end it early (the adult's way out on the TV is
 * off), so it waits for the parent's «تأكيد» on the phone. A calm card over the dark player, nothing plays: the
 * question, the video's title, a waiting indicator with the time left, and «إلغاء» (it has the focus). With no phone
 * there ([phoneConnected] false: none paired, or none polled in the last minute) it says how to get one, with
 * «ربط الجوال» ([onPairPhone], the pairing screen).
 */
@Composable
internal fun PhoneConfirmPanel(
    title: String,
    secondsLeft: Int,
    phoneConnected: Boolean,
    onCancel: () -> Unit,
    onPairPhone: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { cancel.requestFocus() }
    }
    val accent = LocalAccent.current
    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.tint(0.12f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.4f),
                        radius = size.width * 0.42f,
                    ),
                )
            }
            .padding(horizontal = 48.dp, vertical = 27.dp)
            .testTag(if (phoneConnected) "phone_confirm" else "phone_confirm_no_phone"),
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .background(ShashaColors.Surface, shape)
                .border(1.dp, ShashaColors.Outline, shape)
                .padding(horizontal = 36.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(60.dp)
                    .background(if (phoneConnected) accent.tint(0.16f) else ShashaColors.Secondary.copy(alpha = 0.16f), CircleShape)
                    .border(1.5.dp, if (phoneConnected) accent.tint(0.55f) else ShashaColors.Secondary.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(RemoteIcons.Phone, contentDescription = null, tint = if (phoneConnected) accent.bright else ShashaColors.Secondary, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(if (phoneConnected) R.string.confirm_phone_title else R.string.confirm_no_phone),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().testTag("phone_confirm_title"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.quoted_name, title),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = ShashaColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("phone_confirm_video"),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(if (phoneConnected) R.string.confirm_phone_body else R.string.confirm_no_phone_body),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (phoneConnected) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShashaLoader(size = 30.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        stringResource(R.string.confirm_phone_waiting, formatCountdown(secondsLeft * 1000L)),
                        style = MaterialTheme.typography.titleSmall,
                        color = ShashaColors.Muted,
                        modifier = Modifier.testTag("phone_confirm_waiting"),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ShashaButton(
                    stringResource(R.string.action_cancel),
                    onCancel,
                    icon = ShashaIcons.Close,
                    modifier = Modifier.focusRequester(cancel).testTag("phone_confirm_cancel"),
                )
                if (!phoneConnected && onPairPhone != null) {
                    ShashaButton(
                        stringResource(R.string.confirm_pair_phone),
                        onPairPhone,
                        icon = RemoteIcons.QrCode,
                        modifier = Modifier.testTag("phone_confirm_pair"),
                    )
                }
            }
        }
    }
}

private const val READ_EVERY_MS = 500L
