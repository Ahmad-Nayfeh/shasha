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
 *
 * [busy]: a page is being loaded. A loader ignores a request that comes while it is loading (one request at a time), and the
 * focus may well stay on the last row through the whole load: with nothing below to scroll to, nothing would ever ask again, and
 * the list would stand still short of its end, with pages left, until the viewer moved up and down. So nothing is asked while
 * [busy], and the end being still near when the load is over asks again, even for the same list size (a page that brought
 * nothing new). It cannot loop: every request moves the loader on to its next page, and the loader ends where the source does.
 */
@Composable
fun LoadMoreWhenNearEnd(state: LazyGridState, threshold: Int = 8, busy: Boolean = false, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    val loading by rememberUpdatedState(busy)
    LaunchedEffect(state, threshold) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (!loading && info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { load() }
    }
}

@Composable
fun LoadMoreWhenNearEnd(state: LazyListState, threshold: Int = 5, busy: Boolean = false, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    val loading by rememberUpdatedState(busy)
    LaunchedEffect(state, threshold) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (!loading && info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - threshold) info.totalItemsCount else -1
        }.distinctUntilChanged().filter { it >= 0 }.collect { load() }
    }
}
