package com.ahmadnayfeh.shasha.ui.screens.shorts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoader
import com.ahmadnayfeh.shasha.ui.components.Thumbnail
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.OsdColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign

/**
 * What the Shorts player shows now. Plain data: the screen is drawn from it without a player, so it can be screenshotted and
 * tested with fake data.
 */
@Immutable
data class ShortsUiState(
    val title: String,
    val channelName: String?,
    /** Where the viewer is in the list: Short [position] (1-based) of [total]. */
    val position: Int,
    val total: Int,
    /** The viewer wants it playing (it may still be buffering). */
    val playing: Boolean = true,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    /** 0..1 along the Short. */
    val progress: Float = 0f,
    /** Width over height of the picture, once known: the stage takes its shape (a Short's is about 9:16). */
    val aspect: Float? = null,
    /** UP, DOWN and OK are drawn (for a moment after a key, and while the Short waits for the viewer). */
    val hints: Boolean = true,
    /** Why a key did nothing, for a few seconds («هذا آخر مقطع», the lock). */
    val notice: String? = null,
)

/** A Short's picture is 9:16 until the player says otherwise. */
const val SHORTS_ASPECT = 9f / 16f

/**
 * The Shorts player's screen, over a black window: the Short on a [stage] of its own shape in the middle (9:16, the whole height of
 * the screen), the screen's sides left dark with a soft, blurred copy of its picture behind them ([ShortsBackdrop]), the title and
 * channel on one side and what UP, DOWN and OK do on the other, small. The video is [video], drawn in the stage; the progress, the
 * pause and the end sit over it.
 */
@Composable
fun ShortsLayout(
    state: ShortsUiState,
    backdropUrl: String?,
    modifier: Modifier = Modifier,
    video: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize().background(Color.Black).testTag("shorts_layout")) {
        ShortsBackdrop(backdropUrl)
        val ratio = (state.aspect ?: SHORTS_ASPECT).coerceIn(MIN_STAGE_ASPECT, MAX_STAGE_ASPECT)
        Box(Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(ratio).testTag("shorts_stage")) {
            video()
            if (state.buffering && !state.ended) ShashaLoader(Modifier.align(Alignment.Center), size = 56.dp)
            StageOverlay(state)
        }
        Info(state, Modifier.align(Alignment.BottomStart).padding(start = ShashaDimens.ScreenHorizontal, bottom = ShashaDimens.ScreenVertical))
        AnimatedVisibility(
            visible = state.hints,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = ShashaDimens.ScreenHorizontal),
        ) {
            Hints(state)
        }
        state.notice?.let { Notice(it, Modifier.align(Alignment.TopCenter).padding(top = ShashaDimens.ScreenVertical)) }
    }
}

/**
 * What stands behind the stage while the Short is loading, and around it: its picture, small, stretched over the whole screen
 * (the cheapest blur there is: nothing to compute, on any TV; a TV with Android 12 or later blurs it properly too), darkened.
 */
@Composable
fun ShortsBackdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().testTag("shorts_backdrop")) {
        Thumbnail(url, Modifier.fillMaxSize().blur(24.dp), shape = RectangleShape, showLogo = false)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.64f)))
    }
}

/** The progress along the bottom of the stage, the pause over its middle, and the end (replay) in its place. */
@Composable
private fun BoxScope.StageOverlay(state: ShortsUiState) {
    val accent = Accents.overDark(LocalAccent.current)
    if (state.ended) {
        Column(
            Modifier.align(Alignment.Center).testTag("shorts_ended"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Circle { Icon(ShashaIcons.Replay, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.shorts_ended), style = MaterialTheme.typography.titleMedium, color = OsdColors.Text)
        }
    } else if (!state.playing) {
        Circle(Modifier.align(Alignment.Center).testTag("shorts_paused")) {
            Icon(ShashaIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
    }
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.22f)).testTag("shorts_progress")) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(state.progress.coerceIn(0f, 1f)).background(accent.bright))
    }
}

@Composable
private fun Circle(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.size(84.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) { content() }
}

/** The title and the channel, small, on the screen's dark side (the reading start), with where the viewer is in the list. */
@Composable
private fun Info(state: ShortsUiState, modifier: Modifier) {
    Column(modifier.widthIn(max = 250.dp).testTag("shorts_info")) {
        state.channelName?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Content),
                color = OsdColors.Muted,
                textAlign = StartTextAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("shorts_channel"),
            )
            Spacer(Modifier.height(4.dp))
        }
        if (state.title.isNotBlank()) {
            Text(
                state.title,
                style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
                color = OsdColors.Text,
                textAlign = StartTextAlign,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().testTag("shorts_title"),
            )
            Spacer(Modifier.height(10.dp))
        }
        if (state.total > 1) {
            Text(
                stringResource(R.string.shorts_position, state.position.toString(), state.total.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = OsdColors.Muted,
                modifier = Modifier.testTag("shorts_position"),
            )
        }
    }
}

/** What the remote's keys do here, one small chip each, on the screen's other dark side. */
@Composable
private fun Hints(state: ShortsUiState) {
    Column(Modifier.testTag("shorts_hints"), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
        KeyHint(ShashaIcons.ArrowUp, null, stringResource(R.string.shorts_previous), Modifier.testTag("shorts_hint_up"))
        KeyHint(ShashaIcons.ArrowDown, null, stringResource(R.string.shorts_next), Modifier.testTag("shorts_hint_down"))
        val ok = when {
            state.ended -> R.string.shorts_replay
            state.playing -> R.string.shorts_pause
            else -> R.string.shorts_play
        }
        KeyHint(null, "OK", stringResource(ok), Modifier.testTag("shorts_hint_ok"))
    }
}

@Composable
private fun KeyHint(icon: ImageVector?, key: String?, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .background(Color.Black.copy(alpha = 0.45f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .padding(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        } else if (key != null) {
            Text(key, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.width(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = OsdColors.Text, maxLines = 1)
    }
}

/** A line that says why a key did nothing, over the top of the screen. */
@Composable
private fun Notice(text: String, modifier: Modifier) {
    val accent = Accents.overDark(LocalAccent.current)
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .background(OsdColors.SurfaceHigh.copy(alpha = 0.96f), shape)
            .border(1.dp, accent.tint(0.5f), shape)
            .padding(start = 18.dp, end = 24.dp, top = 10.dp, bottom = 10.dp)
            .testTag("shorts_notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ShashaIcons.Info, contentDescription = null, tint = accent.bright, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1)
    }
}

/** The stage keeps the shape of the picture, within what a screen can hold (a sliver and a strip are not stages). */
private const val MIN_STAGE_ASPECT = 0.4f
private const val MAX_STAGE_ASPECT = 2.4f
