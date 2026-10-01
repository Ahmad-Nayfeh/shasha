package com.ahmadnayfeh.shasha.ui.lock

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_ESCAPE
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ParentPinPad
import com.ahmadnayfeh.shasha.ui.components.PinCheck
import com.ahmadnayfeh.shasha.ui.components.PinPanel
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.OsdColors

/**
 * A child pressed BACK in a locked video (SPEC §4): «الخروج يحتاج رمز الوالدين», over the paused video.
 * The right parent PIN ([check]: it also ends the lock) leaves the player ([onUnlocked]); a wrong one
 * stays here with the attempts left; BACK returns to the video ([onDismiss]).
 */
@Composable
fun ChildExitPrompt(
    check: suspend (String) -> PinCheck,
    onUnlocked: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialLockMillis: suspend () -> Long = { 0L },
) {
    val accent = LocalAccent.current
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(accent.tint(0.16f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.42f),
                        radius = size.width * 0.42f,
                    ),
                )
            }
            // BACK goes back to the video, on its release (the press may belong to the key that opened this).
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (code != KEYCODE_BACK && code != KEYCODE_ESCAPE) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onDismiss()
                true
            }
            .testTag("lock_pin_prompt"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PinPanel(compact = true) {
                ParentPinPad(
                    title = stringResource(R.string.lock_child_title),
                    subtitle = stringResource(R.string.lock_child_body),
                    check = check,
                    onAccepted = { onUnlocked() },
                    initialLockMillis = initialLockMillis,
                    header = { LockBadge() },
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(ShashaIcons.Back, contentDescription = null, tint = OsdColors.Muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.lock_back_to_video), style = MaterialTheme.typography.bodyMedium, color = OsdColors.Muted)
            }
        }
    }
}

/** The padlock over the prompt, in the profile's accent (the kids yellow). */
@Composable
private fun LockBadge() {
    val accent = LocalAccent.current
    Box(
        Modifier
            .size(64.dp)
            .background(accent.tint(0.16f), CircleShape)
            .border(1.5.dp, accent.tint(0.55f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ShashaIcons.Lock, contentDescription = null, tint = accent.bright, modifier = Modifier.size(30.dp))
    }
}
