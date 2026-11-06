package com.ahmadnayfeh.shasha.mobile.ui.shorts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.ui.components.EmptyState
import com.ahmadnayfeh.shasha.mobile.ui.components.LoadingBox
import com.ahmadnayfeh.shasha.mobile.ui.components.OnTabReselected
import com.ahmadnayfeh.shasha.mobile.ui.components.ShortTile
import com.ahmadnayfeh.shasha.mobile.ui.home.HomeViewModel
import com.ahmadnayfeh.shasha.mobile.ui.nav.ShortsTabRoute
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The Shorts tab: the Shorts the Home holds (the followed channels' and the suggested ones), as a grid of tall pictures. A tap opens the
 * Shorts viewer on this list at the tapped one, and swiping there goes through it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortsHubScreen(container: AppContainer, onOpenShorts: (List<VideoItem>, Int) -> Unit) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
    val state by vm.state.collectAsStateWithLifecycle()
    val grid = rememberLazyGridState()
    OnTabReselected(ShortsTabRoute::class) { grid.animateScrollToItem(0) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.nav_shorts), style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        when {
            !state.loaded -> LoadingBox(Modifier.padding(padding))
            state.shorts.isEmpty() -> EmptyState(
                icon = Icons.Filled.SmartDisplay,
                title = stringResource(R.string.shorts_tab_empty_title),
                body = stringResource(R.string.shorts_tab_empty_body),
                modifier = Modifier.padding(padding),
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                state = grid,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.shorts, key = { _, short -> "short:" + short.videoId }) { index, short ->
                    ShortTile(short, onClick = { onOpenShorts(state.shorts, index) })
                }
            }
        }
    }
}
