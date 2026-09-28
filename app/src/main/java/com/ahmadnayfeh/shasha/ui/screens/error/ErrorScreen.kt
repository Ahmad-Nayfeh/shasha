package com.ahmadnayfeh.shasha.ui.screens.error

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/**
 * The ONE error screen of the app (SPEC: "شاشة الخطأ الموحدة"). Every [SourceException], from browsing
 * or from playback, ends here: nothing fails silently.
 *
 * Initial focus is on "رجوع" so an accidental OK press does not start anything.
 */
@Composable
fun ErrorScreen(
    error: SourceException,
    extractorVersion: String,
    onCheckUpdates: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val kind = error.kind
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { backFocus.requestFocus() } }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing)) }

    Box(
        modifier
            .fillMaxSize()
            .background(ShashaColors.Background)
            .drawBehind {
                // A faint coral halo behind the illustration.
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(ShashaColors.Error.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.30f),
                        radius = size.width * 0.42f,
                    ),
                )
            }
            .padding(ShashaDimens.ScreenPadding)
            .testTag("error_screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 24.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ErrorIllustration(kind, Modifier.size(width = 132.dp, height = 140.dp))
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(ErrorTexts.title(kind)),
                style = MaterialTheme.typography.headlineLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("error_title"),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(ErrorTexts.explanation(kind)),
                style = MaterialTheme.typography.bodyLarge,
                color = ShashaColors.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("error_explanation"),
            )
            Spacer(Modifier.height(24.dp))
            ErrorDetails(
                technical = ErrorTexts.technical(kind, error.technicalName),
                extractorVersion = extractorVersion,
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                ShashaButton(
                    text = stringResource(R.string.action_back),
                    onClick = onBack,
                    icon = ShashaIcons.Back,
                    modifier = Modifier.focusRequester(backFocus).testTag("error_back"),
                )
                ShashaButton(
                    text = stringResource(R.string.action_check_updates),
                    onClick = onCheckUpdates,
                    icon = ShashaIcons.SystemUpdate,
                    primary = true,
                    modifier = Modifier.testTag("error_check_updates"),
                )
            }
        }
    }
}

/** Two facts side by side: what failed (technical) and which extractor version is installed. */
@Composable
private fun ErrorDetails(technical: String, extractorVersion: String) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .height(IntrinsicSize.Min)
            .background(ShashaColors.Surface.copy(alpha = 0.85f), shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Fact(stringResource(R.string.error_detail_name), technical, Modifier.testTag("error_technical"))
        Box(
            Modifier
                .padding(horizontal = 24.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(ShashaColors.Outline),
        )
        Fact(stringResource(R.string.error_detail_extractor), extractorVersion, Modifier.testTag("error_extractor_version"))
    }
}

@Composable
private fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Muted)
        // Technical values are Latin: lay them out left-to-right so "HTTP 403 · BLOCKED_403" reads correctly.
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 380.dp),
        )
    }
}

/**
 * The brand mark in trouble: the screen turns coral with an exclamation mark. For NETWORK the screen
 * is grey and the three dots of «ش» (the signal) are hollow: nothing is arriving.
 */
@Composable
private fun ErrorIllustration(kind: SourceErrorKind, modifier: Modifier = Modifier) {
    val offline = kind == SourceErrorKind.NETWORK
    Canvas(modifier) { drawTroubledMark(offline) }
}

private fun DrawScope.drawTroubledMark(offline: Boolean) {
    // Same proportions as the logo (screen 720x500, dots 108 with gaps of 20, 48 above the screen).
    val markW = 720f
    val dotD = 108f
    val step = dotD + 20f
    val rise = step * 0.8660254f
    val dotsH = rise + dotD
    val markH = dotsH + 48f + 500f
    val unit = minOf(size.width / markW, size.height / markH)
    val ox = (size.width - markW * unit) / 2f
    val oy = (size.height - markH * unit) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * unit, oy + y * unit)

    val dotColor = if (offline) ShashaColors.Muted else ShashaColors.Secondary
    listOf(Offset(360f, dotD / 2), Offset(360f + step / 2, rise + dotD / 2), Offset(360f - step / 2, rise + dotD / 2))
        .forEach { c ->
            if (offline) {
                drawCircle(dotColor, radius = (dotD / 2 - 9f) * unit, center = p(c.x, c.y), style = Stroke(width = 18f * unit))
            } else {
                drawCircle(dotColor, radius = dotD / 2 * unit, center = p(c.x, c.y))
            }
        }

    val top = dotsH + 48f
    val screen = Path().apply {
        fillType = PathFillType.EvenOdd
        addRoundRect(RoundRect(p(0f, top).x, p(0f, top).y, p(markW, top + 500f).x, p(markW, top + 500f).y, CornerRadius(150f * unit)))
        // Exclamation mark: a rounded bar and a dot, cut out of the screen.
        val barW = 64f
        val barTop = top + 105f
        addRoundRect(
            RoundRect(
                p(360f - barW / 2, barTop).x, p(0f, barTop).y, p(360f + barW / 2, 0f).x, p(0f, barTop + 190f).y,
                CornerRadius(barW / 2 * unit),
            ),
        )
        val dotTop = barTop + 190f + 40f
        addOval(androidx.compose.ui.geometry.Rect(p(360f - 38f, dotTop), p(360f + 38f, dotTop + 76f)))
    }
    val brush = if (offline) {
        Brush.verticalGradient(listOf(Color(0xFF5B6778), Color(0xFF3A4452)), startY = p(0f, top).y, endY = p(0f, top + 500f).y)
    } else {
        Brush.verticalGradient(listOf(Color(0xFFFF8A80), Color(0xFFE5484D)), startY = p(0f, top).y, endY = p(0f, top + 500f).y)
    }
    drawPath(screen, brush)
}
