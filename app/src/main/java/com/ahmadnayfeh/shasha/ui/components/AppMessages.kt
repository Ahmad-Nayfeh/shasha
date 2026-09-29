package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay

@Immutable
data class AppMessage(val text: String, val icon: ImageVector?, val id: Long)

/**
 * Short confirmations and refusals at the bottom of the screen ("أُضيفت إلى قائمة سماح سارة"). Never takes
 * focus: the remote keeps doing what it was doing.
 */
@Stable
class AppMessages {
    var current by mutableStateOf<AppMessage?>(null)
        private set
    private var next = 0L

    fun show(text: String, icon: ImageVector? = ShashaIcons.CheckCircle) {
        current = AppMessage(text, icon, next++)
    }

    internal fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

/** Where screens post their [AppMessages]; null outside a host (previews, tests): nothing is shown. */
val LocalAppMessages = staticCompositionLocalOf<AppMessages?> { null }

/** Draws [messages] over the bottom of whatever it is placed over; each shows for a few seconds. */
@Composable
fun AppMessageHost(messages: AppMessages, modifier: Modifier = Modifier, showMs: Long = 3_500) {
    val message = messages.current
    var shown by remember { mutableStateOf<AppMessage?>(null) }
    LaunchedEffect(message) {
        if (message != null) {
            shown = message
            delay(showMs)
            messages.dismiss(message.id)
        }
    }
    Box(modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(220)) { it / 2 },
        ) {
            shown?.let { MessagePill(it) }
        }
    }
}

@Composable
fun MessagePill(message: AppMessage, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    val accent = LocalAccent.current
    Row(
        modifier
            .widthIn(max = 720.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(ShashaColors.SurfaceHigh, shape)
            .border(1.dp, accent.tint(0.45f), shape)
            .padding(horizontal = 26.dp, vertical = 12.dp)
            .testTag("app_message"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        message.icon?.let {
            Icon(it, contentDescription = null, tint = accent.bright, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(message.text, style = MaterialTheme.typography.titleMedium, maxLines = 2)
    }
}
