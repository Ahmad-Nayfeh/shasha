package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay

/** The wall clock for "منذ 3 ساعات" texts, refreshed once a minute (not on every recomposition). */
@Composable
fun rememberNow(periodMs: Long = 60_000L): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}
