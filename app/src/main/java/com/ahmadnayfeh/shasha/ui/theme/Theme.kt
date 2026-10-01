package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import com.ahmadnayfeh.shasha.data.AppLanguage
import com.ahmadnayfeh.shasha.data.AppTheme

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
 * The brand theme: [theme] dark (the default) or light, and the layout in the direction of [language] (right to
 * left in Arabic, left to right in English), whatever the TV's system language. The strings themselves come in that
 * language from the activity's resources (MainActivity applies it before anything is read).
 */
@Composable
fun ShashaTheme(theme: AppTheme = AppTheme.DEFAULT, language: AppLanguage = AppLanguage.DEFAULT, content: @Composable () -> Unit) {
    val palette = Palettes.of(theme)
    // Before anything below reads a colour.
    ShashaColors.use(palette)
    val direction = if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    val scheme = remember(palette) { palette.colorScheme() }
    CompositionLocalProvider(
        LocalLayoutDirection provides direction,
        LocalAppLanguage provides language,
        LocalAccent provides Accents.Teal,
    ) {
        MaterialTheme(colorScheme = scheme, typography = ShashaTypography) {
            CompositionLocalProvider(LocalContentColor provides palette.text) {
                Box(Modifier.fillMaxSize().background(palette.background)) { content() }
            }
        }
    }
}
