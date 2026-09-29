package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.PlexArabic
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A profile's picture ([com.ahmadnayfeh.shasha.policy.Profile.avatar] is one of [Avatars.keys]): a white
 * figure on a coloured disc, drawn in code so it stays crisp at every size and needs no image files. An
 * unknown key (a newer backup) shows the name's first letter instead.
 */
@Composable
fun ProfileAvatar(avatar: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    val art = Avatars.of(avatar)
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(art?.top ?: FallbackTop, art?.bottom ?: FallbackBottom))),
        contentAlignment = Alignment.Center,
    ) {
        if (art != null) {
            Canvas(Modifier.fillMaxSize()) {
                val side = this.size.minDimension * GLYPH
                val box = Rect(Offset((this.size.width - side) / 2f, (this.size.height - side) / 2f), Size(side, side))
                with(art) { draw(Glyph(box)) }
            }
        } else {
            val initial = remember(name) { name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "" }
            Text(
                initial,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = PlexArabic,
                    fontSize = (size.value * 0.42f).sp,
                    lineHeight = (size.value * 0.5f).sp,
                ),
                color = Color.White,
            )
        }
    }
}

/** The avatar choices, in the order the pickers show them. */
object Avatars {
    /** Every avatar; "person" suits the adult profile best. */
    val keys: List<String> get() = arts.map { it.key }

    /** What a child picks from (everything but the grown-up silhouette). */
    val childKeys: List<String> get() = keys - "person"

    fun of(key: String): AvatarArt? = arts.firstOrNull { it.key == key }

    private val arts = listOf(
        AvatarArt("person", Color(0xFF34D1C2), Color(0xFF0E6F66)) { g, _ -> person(g) },
        AvatarArt("star", Color(0xFFFFCB5C), Color(0xFFE0831A)) { g, _ -> star(g) },
        AvatarArt("rocket", Color(0xFF7E8BFF), Color(0xFF3A3FB0)) { g, art -> rocket(g, art.bottom) },
        AvatarArt("cat", Color(0xFFFF9180), Color(0xFFD2435A)) { g, art -> cat(g, art.bottom) },
        AvatarArt("bear", Color(0xFFDDA56C), Color(0xFF8A5628)) { g, art -> bear(g, art.bottom) },
        AvatarArt("fish", Color(0xFF58C8F8), Color(0xFF1B72CC)) { g, art -> fish(g, art.bottom) },
        AvatarArt("sun", Color(0xFFFFD86E), Color(0xFFF08C00)) { g, _ -> sun(g) },
        AvatarArt("moon", Color(0xFF9A8BFF), Color(0xFF45369E)) { g, _ -> moon(g) },
        AvatarArt("flower", Color(0xFFFFA3CB), Color(0xFFCF437C)) { g, _ -> flower(g) },
        AvatarArt("heart", Color(0xFFFF7B95), Color(0xFFC22D4C)) { g, _ -> heart(g) },
        AvatarArt("crown", Color(0xFFBE8CFF), Color(0xFF6630C2)) { g, _ -> crown(g) },
        AvatarArt("planet", Color(0xFF63D9A0), Color(0xFF1C8454)) { g, _ -> planet(g) },
        AvatarArt("balloon", Color(0xFFFFAA66), Color(0xFFDF522B)) { g, art -> balloon(g, art.bottom) },
    )
}

/** One avatar: its disc colours and how its figure is drawn inside [Glyph.box]. */
@Immutable
class AvatarArt(val key: String, val top: Color, val bottom: Color, private val figure: DrawScope.(Glyph, AvatarArt) -> Unit) {
    fun DrawScope.draw(glyph: Glyph) = figure(glyph, this@AvatarArt)
}

/** Coordinates inside the figure's square box, from 0 to 1 on both axes. */
class Glyph(val box: Rect) {
    fun x(v: Float) = box.left + v * box.width

    fun y(v: Float) = box.top + v * box.height

    fun p(px: Float, py: Float) = Offset(x(px), y(py))

    fun len(v: Float) = v * box.width
}

private const val GLYPH = 0.58f
private val FallbackTop = Color(0xFF5B6778)
private val FallbackBottom = Color(0xFF2E3947)
private val Ink = Color.White
private val Warm = Color(0xFFFFB547)

private fun DrawScope.person(g: Glyph) {
    drawCircle(Ink, g.len(0.2f), g.p(0.5f, 0.3f))
    val body = Path().apply {
        moveTo(g.x(0.1f), g.y(0.98f))
        cubicTo(g.x(0.1f), g.y(0.62f), g.x(0.3f), g.y(0.56f), g.x(0.5f), g.y(0.56f))
        cubicTo(g.x(0.7f), g.y(0.56f), g.x(0.9f), g.y(0.62f), g.x(0.9f), g.y(0.98f))
        close()
    }
    drawPath(body, Ink)
}

private fun DrawScope.star(g: Glyph) {
    val center = g.p(0.5f, 0.54f)
    val path = Path()
    for (i in 0 until 10) {
        val radius = g.len(if (i % 2 == 0) 0.5f else 0.21f)
        val angle = -PI / 2 + i * PI / 5
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, Ink)
    // A round join softens the points, as a child would draw them.
    drawPath(path, Ink, style = Stroke(width = g.len(0.07f), join = StrokeJoin.Round))
}

private fun DrawScope.rocket(g: Glyph, shade: Color) {
    val flame = Path().apply {
        moveTo(g.x(0.4f), g.y(0.74f))
        lineTo(g.x(0.6f), g.y(0.74f))
        lineTo(g.x(0.5f), g.y(1.0f))
        close()
    }
    drawPath(flame, Warm)
    drawPath(flame, Warm, style = Stroke(width = g.len(0.06f), join = StrokeJoin.Round))
    listOf(-1f, 1f).forEach { side ->
        val fin = Path().apply {
            moveTo(g.x(0.5f + side * 0.14f), g.y(0.5f))
            lineTo(g.x(0.5f + side * 0.34f), g.y(0.8f))
            lineTo(g.x(0.5f + side * 0.14f), g.y(0.76f))
            close()
        }
        drawPath(fin, Ink)
        drawPath(fin, Ink, style = Stroke(width = g.len(0.05f), join = StrokeJoin.Round))
    }
    val body = Path().apply {
        moveTo(g.x(0.5f), g.y(0.0f))
        cubicTo(g.x(0.74f), g.y(0.16f), g.x(0.72f), g.y(0.5f), g.x(0.66f), g.y(0.76f))
        lineTo(g.x(0.34f), g.y(0.76f))
        cubicTo(g.x(0.28f), g.y(0.5f), g.x(0.26f), g.y(0.16f), g.x(0.5f), g.y(0.0f))
        close()
    }
    drawPath(body, Ink)
    drawCircle(shade, g.len(0.1f), g.p(0.5f, 0.36f))
    drawCircle(Ink.copy(alpha = 0.45f), g.len(0.035f), g.p(0.47f, 0.33f))
}

private fun DrawScope.cat(g: Glyph, shade: Color) {
    listOf(-1f, 1f).forEach { side ->
        val ear = Path().apply {
            moveTo(g.x(0.5f + side * 0.36f), g.y(0.46f))
            lineTo(g.x(0.5f + side * 0.33f), g.y(0.06f))
            lineTo(g.x(0.5f + side * 0.04f), g.y(0.26f))
            close()
        }
        drawPath(ear, Ink)
        drawPath(ear, Ink, style = Stroke(width = g.len(0.07f), join = StrokeJoin.Round))
    }
    drawOval(Ink, g.p(0.1f, 0.2f), Size(g.len(0.8f), g.len(0.74f)))
    drawCircle(shade, g.len(0.06f), g.p(0.34f, 0.54f))
    drawCircle(shade, g.len(0.06f), g.p(0.66f, 0.54f))
    val nose = Path().apply {
        moveTo(g.x(0.45f), g.y(0.66f))
        lineTo(g.x(0.55f), g.y(0.66f))
        lineTo(g.x(0.5f), g.y(0.72f))
        close()
    }
    drawPath(nose, Color(0xFFFF7B95))
    val whisker = Stroke(width = g.len(0.025f), cap = StrokeCap.Round)
    listOf(-1f, 1f).forEach { side ->
        drawLine(shade.copy(alpha = 0.5f), g.p(0.5f + side * 0.12f, 0.7f), g.p(0.5f + side * 0.32f, 0.66f), whisker.width, StrokeCap.Round)
        drawLine(shade.copy(alpha = 0.5f), g.p(0.5f + side * 0.12f, 0.74f), g.p(0.5f + side * 0.32f, 0.76f), whisker.width, StrokeCap.Round)
    }
}

private fun DrawScope.bear(g: Glyph, shade: Color) {
    drawCircle(Ink, g.len(0.15f), g.p(0.2f, 0.22f))
    drawCircle(Ink, g.len(0.15f), g.p(0.8f, 0.22f))
    drawCircle(shade.copy(alpha = 0.35f), g.len(0.07f), g.p(0.2f, 0.22f))
    drawCircle(shade.copy(alpha = 0.35f), g.len(0.07f), g.p(0.8f, 0.22f))
    drawCircle(Ink, g.len(0.38f), g.p(0.5f, 0.56f))
    drawOval(shade.copy(alpha = 0.22f), g.p(0.32f, 0.58f), Size(g.len(0.36f), g.len(0.26f)))
    drawCircle(shade, g.len(0.05f), g.p(0.36f, 0.48f))
    drawCircle(shade, g.len(0.05f), g.p(0.64f, 0.48f))
    drawOval(shade, g.p(0.43f, 0.62f), Size(g.len(0.14f), g.len(0.09f)))
}

private fun DrawScope.fish(g: Glyph, shade: Color) {
    val tail = Path().apply {
        moveTo(g.x(0.66f), g.y(0.5f))
        lineTo(g.x(0.96f), g.y(0.28f))
        lineTo(g.x(0.96f), g.y(0.72f))
        close()
    }
    drawPath(tail, Ink)
    drawPath(tail, Ink, style = Stroke(width = g.len(0.07f), join = StrokeJoin.Round))
    drawOval(Ink, g.p(0.04f, 0.27f), Size(g.len(0.72f), g.len(0.46f)))
    drawCircle(shade, g.len(0.055f), g.p(0.24f, 0.45f))
    drawArc(
        shade.copy(alpha = 0.45f),
        startAngle = -60f,
        sweepAngle = 120f,
        useCenter = false,
        topLeft = g.p(0.3f, 0.34f),
        size = Size(g.len(0.18f), g.len(0.32f)),
        style = Stroke(width = g.len(0.03f), cap = StrokeCap.Round),
    )
}

private fun DrawScope.sun(g: Glyph) {
    val center = g.p(0.5f, 0.5f)
    for (i in 0 until 8) {
        val angle = i * PI / 4
        val from = Offset(center.x + (g.len(0.34f) * cos(angle)).toFloat(), center.y + (g.len(0.34f) * sin(angle)).toFloat())
        val to = Offset(center.x + (g.len(0.49f) * cos(angle)).toFloat(), center.y + (g.len(0.49f) * sin(angle)).toFloat())
        drawLine(Ink, from, to, strokeWidth = g.len(0.09f), cap = StrokeCap.Round)
    }
    drawCircle(Ink, g.len(0.25f), center)
}

private fun DrawScope.moon(g: Glyph) {
    val disc = Path().apply { addOval(Rect(g.p(0.5f, 0.52f), g.len(0.44f))) }
    val bite = Path().apply { addOval(Rect(g.p(0.7f, 0.36f), g.len(0.36f))) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), Ink)
    drawCircle(Ink, g.len(0.045f), g.p(0.8f, 0.7f))
    drawCircle(Ink.copy(alpha = 0.8f), g.len(0.03f), g.p(0.9f, 0.52f))
    drawCircle(Ink.copy(alpha = 0.7f), g.len(0.025f), g.p(0.66f, 0.84f))
}

private fun DrawScope.flower(g: Glyph) {
    val center = g.p(0.5f, 0.5f)
    for (i in 0 until 6) {
        val angle = -PI / 2 + i * PI / 3
        val c = Offset(center.x + (g.len(0.25f) * cos(angle)).toFloat(), center.y + (g.len(0.25f) * sin(angle)).toFloat())
        drawCircle(Ink, g.len(0.18f), c)
    }
    drawCircle(Warm, g.len(0.15f), center)
}

private fun DrawScope.heart(g: Glyph) {
    val path = Path().apply {
        moveTo(g.x(0.5f), g.y(0.9f))
        cubicTo(g.x(0.1f), g.y(0.62f), g.x(0.0f), g.y(0.4f), g.x(0.08f), g.y(0.24f))
        cubicTo(g.x(0.18f), g.y(0.06f), g.x(0.42f), g.y(0.05f), g.x(0.5f), g.y(0.26f))
        cubicTo(g.x(0.58f), g.y(0.05f), g.x(0.82f), g.y(0.06f), g.x(0.92f), g.y(0.24f))
        cubicTo(g.x(1.0f), g.y(0.4f), g.x(0.9f), g.y(0.62f), g.x(0.5f), g.y(0.9f))
        close()
    }
    drawPath(path, Ink)
}

private fun DrawScope.crown(g: Glyph) {
    val path = Path().apply {
        moveTo(g.x(0.1f), g.y(0.76f))
        lineTo(g.x(0.08f), g.y(0.3f))
        lineTo(g.x(0.3f), g.y(0.5f))
        lineTo(g.x(0.5f), g.y(0.2f))
        lineTo(g.x(0.7f), g.y(0.5f))
        lineTo(g.x(0.92f), g.y(0.3f))
        lineTo(g.x(0.9f), g.y(0.76f))
        close()
    }
    drawPath(path, Ink)
    drawPath(path, Ink, style = Stroke(width = g.len(0.07f), join = StrokeJoin.Round))
    drawRoundRect(Ink, g.p(0.1f, 0.82f), Size(g.len(0.8f), g.len(0.1f)), androidx.compose.ui.geometry.CornerRadius(g.len(0.05f)))
    drawCircle(Warm, g.len(0.065f), g.p(0.5f, 0.56f))
}

private fun DrawScope.planet(g: Glyph) {
    val center = g.p(0.5f, 0.5f)
    val ring = Stroke(width = g.len(0.07f))
    val ringTopLeft = g.p(0.0f, 0.34f)
    val ringSize = Size(g.len(1.0f), g.len(0.32f))
    rotate(-18f, center) {
        // The ring's far half is hidden behind the planet, its near half passes in front.
        clipRect(bottom = center.y) { drawOval(Ink.copy(alpha = 0.75f), ringTopLeft, ringSize, style = ring) }
    }
    drawCircle(Ink, g.len(0.27f), center)
    rotate(-18f, center) {
        clipRect(top = center.y) { drawOval(Ink.copy(alpha = 0.95f), ringTopLeft, ringSize, style = ring) }
    }
}

private fun DrawScope.balloon(g: Glyph, shade: Color) {
    val string = Path().apply {
        moveTo(g.x(0.5f), g.y(0.76f))
        cubicTo(g.x(0.42f), g.y(0.84f), g.x(0.58f), g.y(0.9f), g.x(0.5f), g.y(1.0f))
    }
    drawPath(string, Ink, style = Stroke(width = g.len(0.03f), cap = StrokeCap.Round))
    val knot = Path().apply {
        moveTo(g.x(0.45f), g.y(0.78f))
        lineTo(g.x(0.55f), g.y(0.78f))
        lineTo(g.x(0.5f), g.y(0.7f))
        close()
    }
    drawPath(knot, Ink)
    drawOval(Ink, g.p(0.2f, 0.02f), Size(g.len(0.6f), g.len(0.7f)))
    drawOval(shade.copy(alpha = 0.22f), g.p(0.3f, 0.12f), Size(g.len(0.12f), g.len(0.2f)))
}
