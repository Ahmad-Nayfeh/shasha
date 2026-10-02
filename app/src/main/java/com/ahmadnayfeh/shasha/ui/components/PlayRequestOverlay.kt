package com.ahmadnayfeh.shasha.ui.components

import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.remote.PhonePlayRequests
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay

/**
 * v1.2 (owner decision): a link the phone sent never plays by itself. Once the rules allowed it, it waits here
 * ([PhonePlayRequests.pending]) for «قبول» or «رفض»: a calm card at the bottom of the screen, over whatever is on it
 * (a video goes on playing, nothing is dimmed or left). Focus starts on «قبول»; BACK is «رفض»; after 30 s it is rejected
 * by itself. Only the screens of the profile the rules allowed it for ([profileId]) ask.
 */
@Composable
fun PhonePlayRequestOverlay(requests: PhonePlayRequests, profileId: String) {
    val pending by requests.pending.collectAsStateWithLifecycle()
    val request = pending?.takeIf { it.profileId == profileId } ?: return
    key(request.id) {
        Dialog(
            onDismissRequest = { requests.reject(request.id) },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            // Over the whole screen, transparent: what plays stays in sight.
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            SideEffect {
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window?.setDimAmount(0f)
            }
            val seconds by produceState(requests.secondsLeft(request), request) {
                while (true) {
                    value = requests.secondsLeft(request)
                    delay(TICK_MS)
                }
            }
            // Inside the TV's safe area, low on the screen: what plays stays in sight above it.
            Box(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), contentAlignment = Alignment.BottomCenter) {
                PlayRequestPanel(
                    title = request.title,
                    secondsLeft = seconds,
                    onAccept = { requests.accept(request.id) },
                    onReject = { requests.reject(request.id) },
                )
            }
        }
    }
}

/** The card of [PhonePlayRequestOverlay]: the request, its countdown, «قبول» (focused) and «رفض». */
@Composable
fun PlayRequestPanel(title: String, secondsLeft: Int, onAccept: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    val accept = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { accept.requestFocus() }
    }
    val accent = LocalAccent.current
    val formats = rememberFormats()
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(ShashaColors.Surface.copy(alpha = 0.97f), shape)
            .border(1.dp, accent.tint(0.5f), shape)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("play_request"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).background(accent.tint(0.16f), CircleShape).border(1.5.dp, accent.tint(0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(RemoteIcons.Phone, contentDescription = null, tint = accent.bright, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            // «طلب تشغيل من الجوال: «…»»: the question on its line, the video's title (any language) on the next.
            Text(
                stringResource(R.string.cast_request_label),
                style = MaterialTheme.typography.labelLarge,
                color = accent.bright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.quoted_name, title),
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("play_request_title"),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(R.string.cast_request_body) + "  ·  " +
                    stringResource(R.string.cast_auto_reject, formats.count(R.array.count_seconds_after, secondsLeft.toLong())),
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("play_request_countdown"),
            )
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ShashaButton(
                stringResource(R.string.cast_accept),
                onAccept,
                icon = ShashaIcons.Play,
                primary = true,
                modifier = Modifier.focusRequester(accept).testTag("play_request_accept"),
            )
            ShashaButton(stringResource(R.string.cast_reject), onReject, icon = ShashaIcons.Close, modifier = Modifier.testTag("play_request_reject"))
        }
    }
}

private const val TICK_MS = 500L
