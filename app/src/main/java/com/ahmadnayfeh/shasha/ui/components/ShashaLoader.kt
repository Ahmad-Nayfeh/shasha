package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * The brand mark as a loading indicator: the three dots of «ش» above the screen light up one after
 * the other (in reading order, right to left), like a signal arriving.
 *
 * Geometry follows branding/README.md (units where the screen is 720 wide).
 */
@Composable
fun ShashaLoader(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val transition = rememberInfiniteTransition(label = "loader")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1300, easing = LinearEasing), RepeatMode.Restart),
        label = "loaderPhase",
    )
    Canvas(modifier.height(size).width(size * (MARK_WIDTH / MARK_HEIGHT))) { drawMark(t) }
}

/** [ShashaLoader] with a caption under it, centred. */
@Composable
fun ShashaLoaderWithText(text: String, modifier: Modifier = Modifier, size: Dp = 72.dp) {
    Column(
        modifier.semantics { contentDescription = text },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ShashaLoader(size = size)
        Text(text, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted)
    }
}

private const val SCREEN_W = 720f
private const val SCREEN_H = 500f
private const val SCREEN_R = 150f
private const val DOT_D = 108f
private const val DOT_GAP = 20f
private const val DOTS_TO_SCREEN = 48f
private val DOT_STEP = DOT_D + DOT_GAP
private val DOT_RISE = DOT_STEP * 0.8660254f // equilateral triangle
private val DOTS_H = DOT_RISE + DOT_D
private const val MARK_WIDTH = SCREEN_W
private val MARK_HEIGHT = DOTS_H + DOTS_TO_SCREEN + SCREEN_H

private val ScreenBrush = Brush.verticalGradient(listOf(Color(0xFF48DCCD), Color(0xFF1FAE9F)))
private val DotColor = ShashaColors.Secondary

private fun DrawScope.drawMark(t: Float) {
    val unit = minOf(size.width / MARK_WIDTH, size.height / MARK_HEIGHT)
    val left = (size.width - MARK_WIDTH * unit) / 2f
    val top = (size.height - MARK_HEIGHT * unit) / 2f
    fun x(v: Float) = left + v * unit
    fun y(v: Float) = top + v * unit

    // Dots: top one first, then bottom-right, then bottom-left (Arabic reading order).
    val cx = SCREEN_W / 2f
    val bottomY = DOT_RISE + DOT_D / 2f
    val dots = listOf(
        Offset(cx, DOT_D / 2f),
        Offset(cx + DOT_STEP / 2f, bottomY),
        Offset(cx - DOT_STEP / 2f, bottomY),
    )
    dots.forEachIndexed { i, c ->
        val phase = ((t - i * 0.16f) % 1f + 1f) % 1f
        // A short pulse per cycle, then rest: reads as "one, two, three".
        val pulse = if (phase < 0.45f) sin(phase / 0.45f * PI).toFloat() else 0f
        val scale = 0.62f + 0.38f * pulse
        val alpha = 0.35f + 0.65f * pulse
        drawCircle(DotColor.copy(alpha = alpha), radius = DOT_D / 2f * scale * unit, center = Offset(x(c.x), y(c.y)))
    }

    // Screen with the play triangle cut out (even-odd fill keeps whatever is behind visible).
    val screenTop = DOTS_H + DOTS_TO_SCREEN
    val path = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(
            RoundRect(
                left = x(0f), top = y(screenTop), right = x(SCREEN_W), bottom = y(screenTop + SCREEN_H),
                cornerRadius = CornerRadius(SCREEN_R * unit),
            ),
        )
        // Play triangle: 240 tall, 221 wide, nudged right so it looks optically centred.
        val triH = 240f
        val triW = 221f
        val tLeft = cx - triW / 2f + 14f
        val tTop = screenTop + (SCREEN_H - triH) / 2f
        moveTo(x(tLeft), y(tTop))
        lineTo(x(tLeft + triW), y(tTop + triH / 2f))
        lineTo(x(tLeft), y(tTop + triH))
        close()
    }
    drawPath(path, ScreenBrush)
}
