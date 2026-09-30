package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How far down from the top of the window a screen's own text reaches right now (the player's title while
 * its controls show). Whatever floats over every screen, the quiet 5-minute notice, sits below it.
 */
@Stable
class TopClearance {
    var bottom: Dp by mutableStateOf(0.dp)
}

/** Provided around a profile's screens; null elsewhere (screenshots, tests), where nothing reports. */
val LocalTopClearance = staticCompositionLocalOf<TopClearance?> { null }
