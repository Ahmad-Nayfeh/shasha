package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme

/** Spacing shared by every screen. */
object ShashaDimens {
    /**
     * Overscan-safe margins: TVs may crop up to 5% of each edge (48dp x 27dp of the 960x540dp TV
     * canvas). Content keeps at least 48dp from every edge.
     */
    val ScreenHorizontal = 56.dp
    val ScreenVertical = 48.dp
    val ScreenPadding = PaddingValues(horizontal = ScreenHorizontal, vertical = ScreenVertical)
    val Gap = 16.dp
    val GapSmall = 8.dp
    val GapLarge = 24.dp
}

/**
 * Dark brand theme. The whole UI is Arabic, so the layout direction is forced to RTL regardless of the
 * TV's system language.
 */
@Composable
fun ShashaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = ShashaColorScheme, typography = ShashaTypography) {
            CompositionLocalProvider(LocalContentColor provides ShashaColors.Text) {
                Box(Modifier.fillMaxSize().background(ShashaColors.Background)) { content() }
            }
        }
    }
}
