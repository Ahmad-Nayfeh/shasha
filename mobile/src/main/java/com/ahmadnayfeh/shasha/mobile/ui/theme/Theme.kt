package com.ahmadnayfeh.shasha.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.AppTheme

/** The progress red (seek bar, watched bars), the one loud colour of the phone UI. */
val ProgressRed = Color(0xFFFF0033)

/** The brand teal, kept for small accents (links, section headings); the page itself is neutral. */
private val BrandTealDark = Color(0xFF2EC4B6)
private val BrandTealLight = Color(0xFF0A7A70)

/** Neutral near-black and white pages, grey surfaces; primary actions invert (white on black, black on white). */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFF1F1F1),
    onPrimary = Color(0xFF0F0F0F),
    primaryContainer = Color(0xFF272727),
    onPrimaryContainer = Color(0xFFF1F1F1),
    secondary = BrandTealDark,
    onSecondary = Color(0xFF04201D),
    secondaryContainer = Color(0xFF272727),
    onSecondaryContainer = Color(0xFFF1F1F1),
    tertiary = Color(0xFFFFB547),
    onTertiary = Color(0xFF2B1A00),
    background = Color(0xFF0F0F0F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF272727),
    onSurfaceVariant = Color(0xFFAAAAAA),
    surfaceTint = Color(0xFFF1F1F1),
    inverseSurface = Color(0xFFF1F1F1),
    inverseOnSurface = Color(0xFF0F0F0F),
    inversePrimary = Color(0xFF0F0F0F),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0707),
    errorContainer = Color(0xFF5A1717),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF717171),
    outlineVariant = Color(0xFF303030),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF303030),
    surfaceDim = Color(0xFF0F0F0F),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF212121),
    surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF303030),
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF0F0F0F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2F2F2),
    onPrimaryContainer = Color(0xFF0F0F0F),
    secondary = BrandTealLight,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2F2F2),
    onSecondaryContainer = Color(0xFF0F0F0F),
    tertiary = Color(0xFF9A5800),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F0F0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F0F0F),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF606060),
    surfaceTint = Color(0xFF0F0F0F),
    inverseSurface = Color(0xFF0F0F0F),
    inverseOnSurface = Color(0xFFFFFFFF),
    inversePrimary = Color(0xFFF1F1F1),
    error = Color(0xFFC22F33),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF909090),
    outlineVariant = Color(0xFFE5E5E5),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE5E5E5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEBEBEB),
    surfaceContainerHighest = Color(0xFFE5E5E5),
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
    titleMedium = BaseTypography.titleMedium.plex().copy(fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    titleSmall = BaseTypography.titleSmall.plex().copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    bodyLarge = BaseTypography.bodyLarge.plex(),
    bodyMedium = BaseTypography.bodyMedium.plex(),
    bodySmall = BaseTypography.bodySmall.plex().copy(fontSize = 12.sp, lineHeight = 16.sp),
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

/**
 * The app's theme. Text and icons take the theme's colour for the page ([ColorScheme.onBackground]) also where no [Surface] is
 * around them: without it, what is drawn outside the root Scaffold (the watch page is over it, not in it) took Compose's default
 * content colour, black, and the watch page's title, channel and playlist were black on the dark theme's background.
 */
@Composable
fun ShashaTheme(theme: AppTheme, content: @Composable () -> Unit) {
    val scheme = if (theme.isDark()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = ShashaTypography) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}

/**
 * [content] over a picture or a black backdrop (the player's error, the Shorts viewer): the dark scheme whatever the app's theme,
 * so that its text, icons and buttons stay readable on black in the light theme too.
 */
@Composable
fun OnDarkBackdrop(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, typography = MaterialTheme.typography) {
        CompositionLocalProvider(LocalContentColor provides DarkScheme.onSurface, content = content)
    }
}
