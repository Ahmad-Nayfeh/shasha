package com.ahmadnayfeh.shasha.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ahmadnayfeh.shasha.data.AppLanguage

/** The app's language on screen ([ShashaTheme] provides it). */
val LocalAppLanguage = staticCompositionLocalOf { AppLanguage.DEFAULT }

/**
 * The app's reading direction: right to left in Arabic, left to right in English. Also inside the parts laid out
 * left to right in both (keypads, media controls), to give a label back its own direction.
 */
val appLayoutDirection: LayoutDirection
    @Composable @ReadOnlyComposable
    get() = if (LocalAppLanguage.current.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

/**
 * Text that hangs from the reading start of the layout, whatever the direction of its own words: a Latin video
 * title in the Arabic interface is right-aligned like the Arabic around it, an Arabic one in English left-aligned.
 */
val StartTextAlign: TextAlign
    @Composable @ReadOnlyComposable
    get() = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left

/** For a sentence of the app's own that may begin with a Latin word or a number: it reads in the app's direction. */
val AppTextDirection: TextDirection
    @Composable @ReadOnlyComposable
    get() = if (LocalAppLanguage.current.isRtl) TextDirection.Rtl else TextDirection.Ltr
