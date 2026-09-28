package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.tv.material3.darkColorScheme

/** Brand palette (dark). Same values as branding/README.md. */
object ShashaColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF151B24)
    val SurfaceVariant = Color(0xFF1E2632)
    val SurfaceHigh = Color(0xFF28323F)
    val Primary = Color(0xFF2EC4B6)
    val PrimaryBright = Color(0xFF48DCCD)
    val OnPrimary = Color(0xFF04201D)
    val Secondary = Color(0xFFFFB547)
    val OnSecondary = Color(0xFF2B1A00)
    val Error = Color(0xFFFF6B6B)
    val OnError = Color(0xFF3A0707)
    val KidsAccent = Color(0xFFFFD166)
    val Text = Color(0xFFEAF0F6)
    val Muted = Color(0xFF8A97A8)
    val Outline = Color(0xFF2E3947)
    val Scrim = Color(0xFF000000)
}

internal val ShashaColorScheme = darkColorScheme(
    primary = ShashaColors.Primary,
    onPrimary = ShashaColors.OnPrimary,
    primaryContainer = Color(0xFF0E3B37),
    onPrimaryContainer = ShashaColors.PrimaryBright,
    secondary = ShashaColors.Secondary,
    onSecondary = ShashaColors.OnSecondary,
    secondaryContainer = Color(0xFF3D2B0A),
    onSecondaryContainer = ShashaColors.Secondary,
    tertiary = ShashaColors.KidsAccent,
    onTertiary = ShashaColors.OnSecondary,
    background = ShashaColors.Background,
    onBackground = ShashaColors.Text,
    surface = ShashaColors.Surface,
    onSurface = ShashaColors.Text,
    surfaceVariant = ShashaColors.SurfaceVariant,
    onSurfaceVariant = ShashaColors.Muted,
    inverseSurface = ShashaColors.Text,
    inverseOnSurface = ShashaColors.Background,
    error = ShashaColors.Error,
    onError = ShashaColors.OnError,
    border = ShashaColors.Outline,
    borderVariant = ShashaColors.SurfaceHigh,
    scrim = ShashaColors.Scrim,
)
