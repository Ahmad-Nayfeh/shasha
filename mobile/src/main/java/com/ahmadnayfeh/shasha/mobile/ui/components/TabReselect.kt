package com.ahmadnayfeh.shasha.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlin.reflect.KClass

/**
 * The tab the viewer tapped again while already on its first page, by the class of its route: that page scrolls back to the top
 * ([OnTabReselected]). The root screen provides it; without one nothing is ever tapped again.
 */
val LocalTabReselections = staticCompositionLocalOf<SharedFlow<KClass<*>>> { MutableSharedFlow() }

/** Runs [action] (scroll to the top) each time the tab of [route] is tapped again while its first page is on screen. */
@Composable
fun OnTabReselected(route: KClass<*>, action: suspend () -> Unit) {
    val reselections = LocalTabReselections.current
    val current by rememberUpdatedState(action)
    LaunchedEffect(reselections, route) {
        reselections.collect { if (it == route) current() }
    }
}
