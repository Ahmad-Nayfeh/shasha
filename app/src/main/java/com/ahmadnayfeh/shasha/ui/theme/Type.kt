package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography
import com.ahmadnayfeh.shasha.R

/** IBM Plex Sans Arabic (SIL OFL 1.1), bundled by the branding work in res/font. */
val PlexArabic = FontFamily(
    Font(R.font.plex_arabic_regular, FontWeight.Normal),
    Font(R.font.plex_arabic_medium, FontWeight.Medium),
    Font(R.font.plex_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.plex_arabic_bold, FontWeight.Bold),
)

// Arabic needs more vertical room than Latin (dots and marks above and below), and TV text is read from
// three metres away: sizes are a step larger than Material's defaults and line heights are generous.
private val CenteredLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: TextUnit, lineHeight: TextUnit, weight: FontWeight) = TextStyle(
    fontFamily = PlexArabic,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    lineHeightStyle = CenteredLines,
)

internal val ShashaTypography = Typography(
    displayLarge = style(48.sp, 68.sp, FontWeight.Bold),
    displayMedium = style(40.sp, 58.sp, FontWeight.Bold),
    displaySmall = style(34.sp, 50.sp, FontWeight.Bold),
    headlineLarge = style(30.sp, 44.sp, FontWeight.Bold),
    headlineMedium = style(26.sp, 40.sp, FontWeight.Bold),
    headlineSmall = style(22.sp, 34.sp, FontWeight.SemiBold),
    titleLarge = style(20.sp, 32.sp, FontWeight.SemiBold),
    titleMedium = style(17.sp, 27.sp, FontWeight.SemiBold),
    titleSmall = style(15.sp, 24.sp, FontWeight.Medium),
    bodyLarge = style(17.sp, 28.sp, FontWeight.Normal),
    bodyMedium = style(15.sp, 25.sp, FontWeight.Normal),
    bodySmall = style(13.sp, 21.sp, FontWeight.Normal),
    labelLarge = style(15.sp, 22.sp, FontWeight.SemiBold),
    labelMedium = style(13.sp, 20.sp, FontWeight.Medium),
    labelSmall = style(12.sp, 18.sp, FontWeight.Medium),
)
