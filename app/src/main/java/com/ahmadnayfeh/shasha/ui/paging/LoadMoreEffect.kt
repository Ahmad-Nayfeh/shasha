package com.ahmadnayfeh.shasha.ui.paging

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Asks for the next page when the last [threshold] items come into view, which on a TV is where focus
 * is heading. Fires once per list size, so a slow page is never requested again while it loads, and a
 * list that is still short after a page arrives asks again by itself.
 */
@Composable
fun LoadMoreWhenNearEnd(state: LazyGridState, threshold: Int = 8, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, threshold) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { load() }
    }
}

@Composable
fun LoadMoreWhenNearEnd(state: LazyListState, threshold: Int = 5, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, threshold) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { load() }
    }
}
