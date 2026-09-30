package com.ahmadnayfeh.shasha.ui.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.SessionState
import com.ahmadnayfeh.shasha.ui.components.LocalTopClearance
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * SPEC §3's quiet notice, 5 minutes before watching stops: a small card at the top that never takes the
 * focus and goes away by itself. Shown once each time the session enters its last minutes
 * ([com.ahmadnayfeh.shasha.policy.SessionController.warningEvents]), and when a profile opens already in
 * them; while it shows, the minutes follow [state]. It keeps below what a screen shows at the top
 * ([LocalTopClearance]: the player's title while its controls are up).
 */
@Composable
fun WarningBanner(warnings: Flow<SessionState.Warning>, state: SessionState, modifier: Modifier = Modifier, showMs: Long = SHOW_MS) {
    var visible by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf(0L) }
    var shownAt by remember { mutableLongStateOf(0L) }
    val current by rememberUpdatedState(state)

    LaunchedEffect(warnings) {
        (current as? SessionState.Warning)?.let {
            remaining = it.remainingSeconds
            visible = true
            shownAt++
        }
        warnings.collect {
            remaining = it.remainingSeconds
            visible = true
            shownAt++
        }
    }
    LaunchedEffect(shownAt) {
        if (visible) {
            delay(showMs)
            visible = false
        }
    }
    val live = (state as? SessionState.Warning)?.remainingSeconds ?: remaining
    val taken = LocalTopClearance.current?.bottom ?: 0.dp
    val top by animateDpAsState(maxOf(TOP, taken + GAP_BELOW), tween(300), label = "warningTop")
    Box(modifier.fillMaxWidth().padding(top = top), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible && state.canWatch,
            enter = fadeIn(tween(400)) + slideInVertically(tween(450)) { -it },
            exit = fadeOut(tween(400)) + slideOutVertically(tween(450)) { -it },
        ) {
            WarningCard(live)
        }
    }
}

/** The card itself: an hourglass and "بقيت 5 دقائق". */
@Composable
fun WarningCard(remainingSeconds: Long, modifier: Modifier = Modifier) {
    val formats = rememberFormats()
    val minutes = ((remainingSeconds + 59) / 60).coerceAtLeast(1)
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Secondary.copy(alpha = 0.45f), shape)
            .padding(start = 14.dp, end = 26.dp, top = 12.dp, bottom = 12.dp)
            .testTag("time_warning"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).background(ShashaColors.Secondary.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(ShashaIcons.Hourglass, contentDescription = null, tint = ShashaColors.Secondary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                stringResource(R.string.time_warning_title, formats.count(R.array.count_minutes, minutes)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.time_warning_body), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
        }
    }
}

private const val SHOW_MS = 12_000L

/** From the top of the screen, when nothing is there. */
private val TOP = 28.dp

/** Below a screen's own text at the top. */
private val GAP_BELOW = 14.dp
