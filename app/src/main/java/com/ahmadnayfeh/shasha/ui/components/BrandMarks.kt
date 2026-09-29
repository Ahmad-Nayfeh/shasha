package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope

/** What the logo's screen shows in [BrandMark]. */
enum class MarkGlyph {
    /** The day's watching time is used up. */
    HOURGLASS,

    /** Outside the allowed hours (bedtime): the three dots become stars. */
    MOON,

    /** Not for this profile (the allowlist, a block). */
    SHIELD,

    /** A locked video. */
    LOCK,
}

/**
 * The brand mark (a rounded screen under the three dots of «ش») telling something calm: the glyph is cut
 * out of the screen, like the play triangle of the logo. Same proportions as the logo and the error
 * screen's mark (screen 720x500, dots 108 with gaps of 20, 48 between them).
 */
@Composable
fun BrandMark(glyph: MarkGlyph, screen: List<Color>, modifier: Modifier = Modifier, dots: Color = DotAmber) {
    Canvas(modifier) { drawMark(glyph, screen, dots) }
}

private val DotAmber = Color(0xFFFFB547)

private const val MARK_W = 720f
private const val DOT_D = 108f
private const val DOT_STEP = DOT_D + 20f
private const val SCREEN_H = 500f
private val DOT_RISE = DOT_STEP * 0.8660254f
private val DOTS_H = DOT_RISE + DOT_D
private val MARK_H = DOTS_H + 48f + SCREEN_H

private fun DrawScope.drawMark(glyph: MarkGlyph, screenColors: List<Color>, dotColor: Color) {
    val unit = minOf(size.width / MARK_W, size.height / MARK_H)
    val ox = (size.width - MARK_W * unit) / 2f
    val oy = (size.height - MARK_H * unit) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * unit, oy + y * unit)

    val cx = MARK_W / 2f
    val dotCenters = listOf(
        Offset(cx, DOT_D / 2f),
        Offset(cx + DOT_STEP / 2f, DOT_RISE + DOT_D / 2f),
        Offset(cx - DOT_STEP / 2f, DOT_RISE + DOT_D / 2f),
    )
    dotCenters.forEach { c ->
        if (glyph == MarkGlyph.MOON) {
            drawPath(sparkle(p(c.x, c.y), DOT_D * 0.62f * unit), dotColor)
        } else {
            drawCircle(dotColor, DOT_D / 2f * unit, p(c.x, c.y))
        }
    }

    val top = DOTS_H + 48f
    val screen = Path().apply {
        addRoundRect(RoundRect(Rect(p(0f, top), p(MARK_W, top + SCREEN_H)), CornerRadius(150f * unit)))
    }
    val cut = Path()
    val extra = mutableListOf<DrawScope.() -> Unit>()
    when (glyph) {
        MarkGlyph.HOURGLASS -> {
            cut.addRoundRect(RoundRect(Rect(p(252f, top + 104f), p(468f, top + 136f)), CornerRadius(16f * unit)))
            cut.addRoundRect(RoundRect(Rect(p(252f, top + 364f), p(468f, top + 396f)), CornerRadius(16f * unit)))
            val glass = Path().apply {
                moveTo(p(274f, top + 136f).x, p(0f, top + 136f).y)
                lineTo(p(446f, 0f).x, p(0f, top + 136f).y)
                lineTo(p(374f, 0f).x, p(0f, top + 250f).y)
                lineTo(p(446f, 0f).x, p(0f, top + 364f).y)
                lineTo(p(274f, 0f).x, p(0f, top + 364f).y)
                lineTo(p(346f, 0f).x, p(0f, top + 250f).y)
                close()
            }
            cut.addPath(glass)
            // The sand that already ran through, and its last grains.
            extra += {
                val sand = Path().apply {
                    moveTo(p(300f, 0f).x, p(0f, top + 356f).y)
                    lineTo(p(420f, 0f).x, p(0f, top + 356f).y)
                    lineTo(p(360f, 0f).x, p(0f, top + 300f).y)
                    close()
                }
                drawPath(sand, dotColor)
                drawLine(dotColor, p(360f, top + 252f), p(360f, top + 300f), strokeWidth = 8f * unit, cap = StrokeCap.Round)
            }
        }
        MarkGlyph.MOON -> {
            val disc = Path().apply { addOval(Rect(p(360f, top + 252f), 128f * unit)) }
            val bite = Path().apply { addOval(Rect(p(418f, top + 204f), 112f * unit)) }
            cut.addPath(Path.combine(PathOperation.Difference, disc, bite))
        }
        MarkGlyph.SHIELD -> {
            cut.moveTo(p(360f, 0f).x, p(0f, top + 92f).y)
            cut.lineTo(p(476f, 0f).x, p(0f, top + 140f).y)
            cut.lineTo(p(476f, 0f).x, p(0f, top + 246f).y)
            cut.cubicTo(p(476f, 0f).x, p(0f, top + 330f).y, p(424f, 0f).x, p(0f, top + 388f).y, p(360f, 0f).x, p(0f, top + 412f).y)
            cut.cubicTo(p(296f, 0f).x, p(0f, top + 388f).y, p(244f, 0f).x, p(0f, top + 330f).y, p(244f, 0f).x, p(0f, top + 246f).y)
            cut.lineTo(p(244f, 0f).x, p(0f, top + 140f).y)
            cut.close()
        }
        MarkGlyph.LOCK -> {
            cut.addRoundRect(RoundRect(Rect(p(270f, top + 222f), p(450f, top + 404f)), CornerRadius(28f * unit)))
            val outer = Path().apply { addRoundRect(RoundRect(Rect(p(298f, top + 96f), p(422f, top + 262f)), CornerRadius(62f * unit))) }
            val inner = Path().apply { addRoundRect(RoundRect(Rect(p(330f, top + 128f), p(390f, top + 262f)), CornerRadius(30f * unit))) }
            cut.addPath(Path.combine(PathOperation.Difference, outer, inner))
        }
    }
    val shape = Path.combine(PathOperation.Difference, screen, cut)
    drawPath(shape, Brush.verticalGradient(screenColors, startY = p(0f, top).y, endY = p(0f, top + SCREEN_H).y))
    extra.forEach { it() }
}

/** A soft four-pointed star, [size] across. */
private fun sparkle(center: Offset, size: Float): Path {
    val r = size / 2f
    val w = r * 0.28f
    return Path().apply {
        moveTo(center.x, center.y - r)
        quadraticTo(center.x + w, center.y - w, center.x + r, center.y)
        quadraticTo(center.x + w, center.y + w, center.x, center.y + r)
        quadraticTo(center.x - w, center.y + w, center.x - r, center.y)
        quadraticTo(center.x - w, center.y - w, center.x, center.y - r)
        close()
    }
}
