package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.ahmadnayfeh.shasha.data.AppTheme

/** The brand palette of one theme («المظهر»). */
@Immutable
class Palette(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceHigh: Color,
    /** The teal as text or an icon on the surfaces (the focus fill is [Accent.main]). */
    val primary: Color,
    val primaryBright: Color,
    val onPrimary: Color,
    /** Amber: warnings, time. */
    val secondary: Color,
    val onSecondary: Color,
    val error: Color,
    val onError: Color,
    val kidsAccent: Color,
    val text: Color,
    val muted: Color,
    val outline: Color,
    val scrim: Color,
    /** Behind commands and addresses to copy (monospace blocks). */
    val code: Color,
) {
    fun colorScheme(): ColorScheme = if (isLight) {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = Color(0xFFD3F3EF),
            onPrimaryContainer = primaryBright,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = Color(0xFFFFE7C2),
            onSecondaryContainer = Color(0xFF5C3500),
            tertiary = kidsAccent,
            onTertiary = Color(0xFF332300),
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = muted,
            inverseSurface = Color(0xFF1E2632),
            inverseOnSurface = Color(0xFFEAF0F6),
            error = error,
            onError = onError,
            border = outline,
            borderVariant = surfaceHigh,
            scrim = scrim,
        )
    } else {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = Color(0xFF0E3B37),
            onPrimaryContainer = primaryBright,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = Color(0xFF3D2B0A),
            onSecondaryContainer = secondary,
            tertiary = kidsAccent,
            onTertiary = onSecondary,
            background = background,
            onBackground = text,
            surface = surface,
            onSurface = text,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = muted,
            inverseSurface = text,
            inverseOnSurface = background,
            error = error,
            onError = onError,
            border = outline,
            borderVariant = surfaceHigh,
            scrim = scrim,
        )
    }
}

object Palettes {
    /** The default: dark neutral greys (TV-style) with the brand's teal. */
    val Dark = Palette(
        isLight = false,
        background = Color(0xFF0F0F0F),
        surface = Color(0xFF212121),
        surfaceVariant = Color(0xFF272727),
        surfaceHigh = Color(0xFF303030),
        primary = Color(0xFF2EC4B6),
        primaryBright = Color(0xFF48DCCD),
        onPrimary = Color(0xFF04201D),
        secondary = Color(0xFFFFB547),
        onSecondary = Color(0xFF2B1A00),
        error = Color(0xFFFF6B6B),
        onError = Color(0xFF3A0707),
        kidsAccent = Color(0xFFFFD166),
        text = Color(0xFFF1F1F1),
        muted = Color(0xFFAAAAAA),
        outline = Color(0xFF3A3A3A),
        scrim = Color(0xFF000000),
        code = Color(0xFF0A0A0A),
    )

    /**
     * Light: the same teal and amber, deepened wherever they are text or an icon on a light surface (contrast ≥ 4.5:1
     * against the surfaces; the teal focus fill keeps the brand's own colour, with dark text on it).
     */
    val Light = Palette(
        isLight = true,
        background = Color(0xFFF2F5F8),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFE6ECF1),
        surfaceHigh = Color(0xFFD8E1E9),
        primary = Color(0xFF0A7A70),
        primaryBright = Color(0xFF086B62),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF9A5800),
        onSecondary = Color(0xFFFFFFFF),
        error = Color(0xFFC22F33),
        onError = Color(0xFFFFFFFF),
        kidsAccent = Color(0xFF8A5A00),
        text = Color(0xFF14202B),
        muted = Color(0xFF55616F),
        outline = Color(0xFFC5D0DB),
        scrim = Color(0xFF000000),
        code = Color(0xFFE9EEF3),
    )

    fun of(theme: AppTheme): Palette = if (theme == AppTheme.LIGHT) Light else Dark
}

/**
 * The palette of the theme on screen. [ShashaTheme] sets it; every colour is read from it when used (in
 * composition or while drawing), so a switch repaints whatever shows it, without rebuilding the screens.
 */
object ShashaColors {
    private val active = mutableStateOf(Palettes.Dark)

    /** The palette in use. */
    val current: Palette get() = active.value

    val isLight: Boolean get() = active.value.isLight

    /** Called by [ShashaTheme] (and the activity, as soon as the stored theme is known). */
    fun use(palette: Palette) {
        if (active.value !== palette) active.value = palette
    }

    val Background: Color get() = active.value.background
    val Surface: Color get() = active.value.surface
    val SurfaceVariant: Color get() = active.value.surfaceVariant
    val SurfaceHigh: Color get() = active.value.surfaceHigh
    val Primary: Color get() = active.value.primary
    val PrimaryBright: Color get() = active.value.primaryBright
    val OnPrimary: Color get() = active.value.onPrimary
    val Secondary: Color get() = active.value.secondary
    val OnSecondary: Color get() = active.value.onSecondary
    val Error: Color get() = active.value.error
    val OnError: Color get() = active.value.onError
    val KidsAccent: Color get() = active.value.kidsAccent
    val Text: Color get() = active.value.text
    val Muted: Color get() = active.value.muted
    val Outline: Color get() = active.value.outline
    val Scrim: Color get() = active.value.scrim
    val Code: Color get() = active.value.code
}

/**
 * Over the video the player's controls stay dark in both themes (white on a dark veil): their texts and
 * surfaces come from here, never from the theme's palette.
 */
object OsdColors {
    val Text: Color get() = Palettes.Dark.text
    val Muted: Color get() = Palettes.Dark.muted
    val Surface: Color get() = Palettes.Dark.surface
    val SurfaceVariant: Color get() = Palettes.Dark.surfaceVariant
    val SurfaceHigh: Color get() = Palettes.Dark.surfaceHigh
    val Outline: Color get() = Palettes.Dark.outline
    val Secondary: Color get() = Palettes.Dark.secondary
    val Error: Color get() = Palettes.Dark.error
    val Background: Color get() = Palettes.Dark.background
}
