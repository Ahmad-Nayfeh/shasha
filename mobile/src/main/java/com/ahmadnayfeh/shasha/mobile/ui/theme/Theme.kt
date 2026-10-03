package com.ahmadnayfeh.shasha.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.AppTheme

/** The brand palette (the same teal and amber as the TV app), in a dark and a light scheme. */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF2EC4B6),
    onPrimary = Color(0xFF04201D),
    primaryContainer = Color(0xFF0E3B37),
    onPrimaryContainer = Color(0xFF48DCCD),
    secondary = Color(0xFFFFB547),
    onSecondary = Color(0xFF2B1A00),
    secondaryContainer = Color(0xFF3D2B0A),
    onSecondaryContainer = Color(0xFFFFB547),
    tertiary = Color(0xFFFFD166),
    onTertiary = Color(0xFF332300),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFEAF0F6),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFEAF0F6),
    surfaceVariant = Color(0xFF1E2632),
    onSurfaceVariant = Color(0xFFA2AEBD),
    surfaceTint = Color(0xFF2EC4B6),
    inverseSurface = Color(0xFFEAF0F6),
    inverseOnSurface = Color(0xFF0B0F14),
    inversePrimary = Color(0xFF0A7A70),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0707),
    errorContainer = Color(0xFF5A1717),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF627286),
    outlineVariant = Color(0xFF2E3947),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF28323F),
    surfaceDim = Color(0xFF070A0E),
    surfaceContainerLowest = Color(0xFF070A0E),
    surfaceContainerLow = Color(0xFF10151C),
    surfaceContainer = Color(0xFF151B24),
    surfaceContainerHigh = Color(0xFF1E2632),
    surfaceContainerHighest = Color(0xFF28323F),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0A7A70),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3F3EF),
    onPrimaryContainer = Color(0xFF053F39),
    secondary = Color(0xFF9A5800),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE7C2),
    onSecondaryContainer = Color(0xFF5C3500),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F5F8),
    onBackground = Color(0xFF14202B),
    surface = Color(0xFFF2F5F8),
    onSurface = Color(0xFF14202B),
    surfaceVariant = Color(0xFFE6ECF1),
    onSurfaceVariant = Color(0xFF47535F),
    surfaceTint = Color(0xFF0A7A70),
    inverseSurface = Color(0xFF1E2632),
    inverseOnSurface = Color(0xFFEAF0F6),
    inversePrimary = Color(0xFF2EC4B6),
    error = Color(0xFFC22F33),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF7A8794),
    outlineVariant = Color(0xFFC5D0DB),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8E1E9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F9FB),
    surfaceContainer = Color(0xFFEEF2F6),
    surfaceContainerHigh = Color(0xFFE6ECF1),
    surfaceContainerHighest = Color(0xFFD8E1E9),
)

/** IBM Plex Sans Arabic (SIL OFL 1.1): Arabic and Latin text in one family, the same as the TV app. */
private val PlexArabic = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

private fun TextStyle.plex(): TextStyle = copy(fontFamily = PlexArabic)

private val BaseTypography = Typography()

private val ShashaTypography = Typography(
    displayLarge = BaseTypography.displayLarge.plex(),
    displayMedium = BaseTypography.displayMedium.plex(),
    displaySmall = BaseTypography.displaySmall.plex(),
    headlineLarge = BaseTypography.headlineLarge.plex(),
    headlineMedium = BaseTypography.headlineMedium.plex(),
    headlineSmall = BaseTypography.headlineSmall.plex(),
    titleLarge = BaseTypography.titleLarge.plex().copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.plex().copy(fontWeight = FontWeight.Medium),
    titleSmall = BaseTypography.titleSmall.plex().copy(fontWeight = FontWeight.Medium),
    bodyLarge = BaseTypography.bodyLarge.plex(),
    bodyMedium = BaseTypography.bodyMedium.plex(),
    bodySmall = BaseTypography.bodySmall.plex(),
    labelLarge = BaseTypography.labelLarge.plex().copy(fontWeight = FontWeight.Medium),
    labelMedium = BaseTypography.labelMedium.plex().copy(fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.plex().copy(fontWeight = FontWeight.Medium),
)

/** Whether [theme] is dark, given the phone's own setting for [AppTheme.SYSTEM]. */
@Composable
fun AppTheme.isDark(): Boolean = when (this) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

@Composable
fun ShashaTheme(theme: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (theme.isDark()) DarkScheme else LightScheme,
        typography = ShashaTypography,
        content = content,
    )
}
