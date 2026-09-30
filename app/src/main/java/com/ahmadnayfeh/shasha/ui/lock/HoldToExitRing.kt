package com.ahmadnayfeh.shasha.ui.lock

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.theme.Accent
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import kotlin.math.cos
import kotlin.math.sin

/** Which remote button the adult holds to leave a locked video. */
enum class HeldKey { BACK, OK }

/**
 * The adult's long press out of a locked video (SPEC §4), drawn large over it: a ring that fills in ten
 * seconds with the seconds still to hold in its middle, the button being held, and what letting go does.
 * Pure drawing: [progress] (0..1) and [secondsLeft] come from the hold ([com.ahmadnayfeh.shasha.lock.ExitHold]).
 */
@Composable
fun HoldToExitRing(progress: Float, secondsLeft: Int, key: HeldKey, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val unit = rememberFormats().count(R.array.lock_hold_seconds, secondsLeft.toLong())
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0f to Color.Black.copy(alpha = 0.5f),
                    0.55f to Color.Black.copy(alpha = 0.64f),
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            )
            .semantics { stateDescription = secondsLeft.toString() }
            .testTag("lock_hold_ring"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            KeyChip(key)
            Spacer(Modifier.height(22.dp))
            Box(Modifier.size(RingSize), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) { drawRing(progress.coerceIn(0f, 1f), accent) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        secondsLeft.toString(),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 96.sp, textDirection = TextDirection.Ltr),
                        color = Color.White,
                        modifier = Modifier.testTag("lock_hold_seconds"),
                    )
                    Text(unit, style = MaterialTheme.typography.titleMedium, color = ShashaColors.Muted)
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(stringResource(R.string.lock_hold_title), style = MaterialTheme.typography.headlineMedium, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.lock_hold_body), style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, textAlign = TextAlign.Center)
        }
    }
}

/** "زر «رجوع»" with its glyph: the button whose press is being counted. */
@Composable
private fun KeyChip(key: HeldKey) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .background(ShashaColors.Surface.copy(alpha = 0.92f), shape)
            .border(1.dp, accent.tint(0.45f), shape)
            .padding(start = 8.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).background(accent.tint(0.18f), CircleShape).border(1.5.dp, accent.main, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (key) {
                HeldKey.BACK -> Icon(ShashaIcons.Back, contentDescription = null, tint = accent.bright, modifier = Modifier.size(18.dp))
                HeldKey.OK -> Text("OK", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp), color = accent.bright)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(if (key == HeldKey.BACK) R.string.lock_hold_key_back else R.string.lock_hold_key_ok),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
        )
    }
}

/** The track, the part already held (clockwise from the top, lighter towards its head) and a glowing head. */
private fun DrawScope.drawRing(progress: Float, accent: Accent) {
    val stroke = RingStroke.toPx()
    val pad = stroke * 1.4f // room for the head's glow
    val diameter = size.minDimension - 2 * pad
    val radius = diameter / 2
    val center = Offset(size.width / 2, size.height / 2)
    val topLeft = Offset(center.x - radius, center.y - radius)
    val arc = Size(diameter, diameter)

    drawCircle(Color.Black.copy(alpha = 0.32f), radius = radius - stroke / 2, center = center)
    drawCircle(Color.White.copy(alpha = 0.11f), radius = radius, center = center, style = Stroke(stroke))
    if (progress <= 0f) return
    // The sweep gradient starts at 3 o'clock: draw in a frame turned a quarter back, so it starts at the top.
    rotate(-90f, center) {
        drawArc(
            brush = Brush.sweepGradient(0f to accent.main.copy(alpha = 0.75f), progress to accent.bright, center = center),
            startAngle = 0f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = arc,
            // A flat start sits exactly at 12 o'clock; the glowing head covers the other end.
            style = Stroke(stroke, cap = StrokeCap.Butt),
        )
    }
    val angle = Math.toRadians(-90.0 + 360.0 * progress)
    val head = Offset(center.x + (radius * cos(angle)).toFloat(), center.y + (radius * sin(angle)).toFloat())
    drawCircle(accent.main.copy(alpha = 0.22f), radius = stroke * 1.35f, center = head)
    drawCircle(accent.bright.copy(alpha = 0.45f), radius = stroke * 0.85f, center = head)
    drawCircle(Color.White, radius = stroke * 0.3f, center = head)
}

private val RingSize = 236.dp
private val RingStroke = 14.dp
