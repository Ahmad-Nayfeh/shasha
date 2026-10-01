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
    /** The dark theme's teal (also what code drawn outside [ShashaTheme] gets, e.g. previews). */
    val TealDark = Accent(
        main = Palettes.Dark.primary,
        bright = Palettes.Dark.primaryBright,
        onMain = Palettes.Dark.onPrimary,
        glowColor = Palettes.Dark.primary.copy(alpha = 0.55f),
    )

    // The same teal fill (dark text on it); a deeper teal where it is text or an icon on the light surfaces.
    private val TealLight = Accent(
        main = Palettes.Dark.primary,
        bright = Palettes.Light.primary,
        onMain = Palettes.Dark.onPrimary,
        glowColor = Palettes.Dark.primary.copy(alpha = 0.45f),
    )

    private val KidsDark = Accent(
        main = Palettes.Dark.kidsAccent,
        bright = Color(0xFFFFE08F),
        onMain = Color(0xFF332300),
        glowColor = Palettes.Dark.kidsAccent.copy(alpha = 0.5f),
    )

    private val KidsLight = Accent(
        main = Color(0xFFFFC94A),
        bright = Palettes.Light.kidsAccent,
        onMain = Color(0xFF332300),
        glowColor = Color(0xFFFFC94A).copy(alpha = 0.5f),
    )

    /** The brand's teal, for the theme on screen. */
    val Teal: Accent get() = if (ShashaColors.isLight) TealLight else TealDark

    /** A child profile's yellow, for the theme on screen. */
    val Kids: Accent get() = if (ShashaColors.isLight) KidsLight else KidsDark

    /** [accent] where the player stays dark in both themes (over its video, [OsdColors]): the dark theme's variant. */
    fun overDark(accent: Accent): Accent = when (accent) {
        TealLight -> TealDark
        KidsLight -> KidsDark
        else -> accent
    }
}

/** [ShashaTheme] provides the theme's teal; this default only serves code drawn outside it (previews). */
val LocalAccent = staticCompositionLocalOf { Accents.TealDark }

/** The child profile's warm accent for everything inside [content]. */
@Composable
fun KidsAccent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAccent provides Accents.Kids, content = content)
}
