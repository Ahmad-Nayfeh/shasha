package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Glow

/**
 * The colour of focus and selection. The brand's teal everywhere, except in a child profile, which gets
 * the warm kids yellow (brand palette "kids accent") so the TV itself shows whose profile is on.
 */
@Immutable
class Accent(
    /** Focus fill, focus ring, selected tint. */
    val main: Color,
    /** Selected content at rest, pressed fill. */
    val bright: Color,
    /** Content drawn on [main]. */
    val onMain: Color,
    glowColor: Color,
) {
    val cardGlow = Glow(elevationColor = glowColor, elevation = 18.dp)
    val buttonGlow = Glow(elevationColor = glowColor, elevation = 12.dp)

    /** A quiet tint of [main] for selected-but-not-focused backgrounds. */
    fun tint(alpha: Float): Color = main.copy(alpha = alpha)
}

object Accents {
    val Teal = Accent(
        main = ShashaColors.Primary,
        bright = ShashaColors.PrimaryBright,
        onMain = ShashaColors.OnPrimary,
        glowColor = ShashaColors.Primary.copy(alpha = 0.55f),
    )

    val Kids = Accent(
        main = ShashaColors.KidsAccent,
        bright = Color(0xFFFFE08F),
        onMain = Color(0xFF332300),
        glowColor = ShashaColors.KidsAccent.copy(alpha = 0.5f),
    )
}

val LocalAccent = staticCompositionLocalOf { Accents.Teal }

/** The child profile's warm accent for everything inside [content]. */
@Composable
fun KidsAccent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides Accents.Kids, content = content)
}
