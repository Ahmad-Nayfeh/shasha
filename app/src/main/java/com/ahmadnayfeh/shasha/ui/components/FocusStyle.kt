package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * One focus language for the whole app: the focused element grows a little, gets a teal outline and a
 * soft teal glow. Everything is driven by tv-material3 (animated scale, border and glow on focus).
 */
object ShashaFocus {
    const val CardScale = 1.05f
    const val ButtonScale = 1.08f
    val GlowColor = ShashaColors.Primary.copy(alpha = 0.55f)
    val CardGlow = Glow(elevationColor = GlowColor, elevation = 18.dp)
    val ButtonGlow = Glow(elevationColor = GlowColor, elevation = 12.dp)
    val BorderWidth = 3.dp

    fun border(shape: Shape, color: Color = ShashaColors.Primary, width: Dp = BorderWidth) =
        Border(BorderStroke(width, color), inset = 0.dp, shape = shape)
}

/**
 * A focusable card-like surface (list rows, tiles, menu options). [onLongClick] fires when OK is held
 * (the remote repeats the key), e.g. to open a card's context menu.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    containerColor: Color = ShashaColors.Surface,
    focusedContainerColor: Color = ShashaColors.SurfaceHigh,
    contentColor: Color = ShashaColors.Text,
    focusedContentColor: Color = contentColor,
    scale: Float = ShashaFocus.CardScale,
    interactionSource: MutableInteractionSource? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            contentColor = contentColor,
            focusedContainerColor = focusedContainerColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedContainerColor,
            pressedContentColor = focusedContentColor,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale, pressedScale = scale * 0.98f),
        border = ClickableSurfaceDefaults.border(focusedBorder = ShashaFocus.border(shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = ShashaFocus.CardGlow),
        interactionSource = interactionSource ?: remember { MutableInteractionSource() },
        content = content,
    )
}

/**
 * Pill button with an optional leading icon. [primary] buttons are teal at rest; the others turn teal
 * only when focused, so the focused one is always unmistakable from the sofa. [compact] is the smaller
 * size used inside cards.
 */
@Composable
fun ShashaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(percent = 50)
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape = shape),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) ShashaColors.Primary.copy(alpha = 0.18f) else ShashaColors.SurfaceVariant,
            contentColor = if (primary) ShashaColors.PrimaryBright else ShashaColors.Text,
            focusedContainerColor = ShashaColors.Primary,
            focusedContentColor = ShashaColors.OnPrimary,
            pressedContainerColor = ShashaColors.PrimaryBright,
            pressedContentColor = ShashaColors.OnPrimary,
        ),
        scale = ButtonDefaults.scale(focusedScale = ShashaFocus.ButtonScale),
        glow = ButtonDefaults.glow(focusedGlow = ShashaFocus.ButtonGlow),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.dp, ShashaColors.Outline), shape = shape),
            focusedBorder = Border.None,
        ),
        contentPadding = if (compact) {
            PaddingValues(start = if (icon != null) 14.dp else 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
        } else {
            PaddingValues(start = if (icon != null) 20.dp else 28.dp, end = 28.dp, top = 12.dp, bottom = 12.dp)
        },
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 18.dp else 22.dp))
            Spacer(Modifier.width(if (compact) 6.dp else 10.dp))
        }
        Text(text, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium)
    }
}
