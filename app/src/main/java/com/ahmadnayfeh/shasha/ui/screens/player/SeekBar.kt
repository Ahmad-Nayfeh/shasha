package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.player.formatDuration
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * The progress bar. Always drawn left-to-right, even in the Arabic UI: media timelines are not
 * mirrored (they follow the direction of the tape, not of reading), so RIGHT on the remote always
 * means "later".
 *
 * Focus and keys are handled by the caller (see PlayerOsd); this only draws. While [scrubbing], a
 * bubble above the thumb shows the target time.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    scrubbing: Boolean,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
) {
    val focused by interactionSource.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val trackHeight by animateDpAsState(if (focused) 8.dp else 5.dp, label = "trackHeight")
    val thumbRadius by animateDpAsState(if (focused) THUMB_RADIUS else 0.dp, label = "thumb")
    val glow by animateFloatAsState(if (focused) 1f else 0f, label = "glow")
    // A live stream whose window is not known yet sits at the live edge.
    val unknownLive = isLive && durationMs <= 0
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else if (unknownLive) 1f else 0f
    val bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else fraction

    Box(modifier.fillMaxWidth().height(64.dp)) {
        if (scrubbing) {
            Text(
                text = formatDuration(positionMs),
                style = MaterialTheme.typography.titleMedium,
                color = accent.onMain,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .centeredAt(fraction, inset = THUMB_RADIUS)
                    .background(accent.bright, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        Canvas(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(28.dp),
        ) {
            val h = trackHeight.toPx()
            val y = size.height / 2f
            val r = CornerRadius(h / 2f)
            // The track is inset by the thumb's radius so the thumb stays inside the bar at both ends
            // (and inside the overscan-safe margins).
            val start = THUMB_RADIUS.toPx()
            val w = size.width - 2 * start
            drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(start, y - h / 2), Size(w, h), r)
            drawRoundRect(Color.White.copy(alpha = 0.40f), Offset(start, y - h / 2), Size(w * bufferedFraction, h), r)
            drawRoundRect(
                if (focused) accent.bright else accent.main,
                Offset(start, y - h / 2),
                Size(w * fraction, h),
                r,
            )
            val thumb = thumbRadius.toPx()
            if (thumb > 0f) {
                val c = Offset(start + w * fraction, y)
                drawCircle(accent.tint(0.35f * glow), radius = thumb * 2.1f, center = c)
                drawCircle(Color.White, radius = thumb, center = c)
            }
        }
    }
}

/**
 * Places the element so its centre sits above the thumb, at [fraction] of the track (the parent's width
 * minus [inset] on both sides), clamped so the bubble stays inside the bar near either end.
 */
private fun Modifier.centeredAt(fraction: Float, inset: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    val parentWidth = constraints.maxWidth
    val start = inset.toPx()
    val center = start + (parentWidth - 2 * start) * fraction
    val x = (center - placeable.width / 2f).toInt().coerceIn(0, (parentWidth - placeable.width).coerceAtLeast(0))
    layout(parentWidth, placeable.height) { placeable.place(x, 0) }
}

private val THUMB_RADIUS = 10.dp
