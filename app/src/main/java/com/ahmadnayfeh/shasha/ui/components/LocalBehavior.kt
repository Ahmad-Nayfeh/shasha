package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.runtime.compositionLocalOf
import com.ahmadnayfeh.shasha.data.Behavior

/**
 * The parent's adjustable behaviour ([com.ahmadnayfeh.shasha.data.BehaviorSettings]) for the screens: the player's
 * timings, the warning's length, the "time up" choices. ShashaRoot provides the live values; screens shown alone
 * (tests, screenshots) get the defaults.
 */
val LocalBehavior = compositionLocalOf { Behavior.DEFAULT }
