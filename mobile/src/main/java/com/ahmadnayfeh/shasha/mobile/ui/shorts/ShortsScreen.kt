package com.ahmadnayfeh.shasha.mobile.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.LocalActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.OrientationMode
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.nav.ShortsRoute
import com.ahmadnayfeh.shasha.mobile.ui.watch.rememberProgress
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem

/**
 * The Shorts viewer: full screen and upright, one Short at a time, swipe up for the next and down for the one before (the list
 * the viewer was opened from, [ShortsQueue]), tap to pause or play, back to leave. The window things (the screen staying upright
 * and on, the bars away) happen here around [ShortsContent], which draws it; the player is the app's, as on the player screen.
 */
@Composable
fun ShortsScreen(
    container: AppContainer,
    route: ShortsRoute,
    onBack: () -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
) {
    val vm: ShortsViewModel = viewModel(
        key = "shorts:${route.queueId}:${route.videoId}",
        factory = ShortsViewModel.factory(container, ShortsQueue.of(container.shortsQueues.get(route.queueId).orEmpty(), route.videoId)),
    )
    val queue = vm.queue
    val state by vm.state.collectAsStateWithLifecycle()
    val bridge = LocalActivityBridge.current
    val view = LocalView.current
    val pagerState = rememberPagerState(initialPage = queue.startIndex) { queue.size }

    // Back from a detour (the channel of a Short): the Short on screen plays again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onShown() }
    // Only a settled page plays: a drag across two Shorts changes nothing until the finger has chosen one.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { vm.onPageSettled(it) }
    }
    // Shorts are vertical: the screen stays upright, with the system bars away; leaving puts everything back.
    DisposableEffect(Unit) {
        bridge.requestOrientation(OrientationMode.PORTRAIT)
        bridge.setSystemBarsHidden(true)
        onDispose {
            bridge.requestOrientation(OrientationMode.AUTO)
            bridge.setSystemBarsHidden(false)
        }
    }
    // The screen stays on while a Short plays.
    DisposableEffect(state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }

    ShortsContent(
        state = state,
        player = container.player.player,
        queue = queue,
        pagerState = pagerState,
        onTogglePlay = vm::togglePlay,
        onRetry = vm::retry,
        onBack = onBack,
        onOpenChannel = { channel ->
            vm.onLeaveForChannel()
            onOpenChannel(channel)
        },
    )
}

/** What the viewer looks like for a given player [state] (the screenshot tests draw it over a fake one). */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ShortsContent(
    state: PlayerUiState,
    player: Player,
    queue: ShortsQueue,
    pagerState: PagerState,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Only the Short on screen (and the one being dragged in) is composed: one player, one surface.
                beyondViewportPageCount = 0,
                key = { queue.items[it].videoId },
            ) { page ->
                ShortPage(
                    short = queue.items[page],
                    current = page == pagerState.settledPage,
                    state = state,
                    player = player,
                    onTogglePlay = onTogglePlay,
                    onRetry = onRetry,
                    onOpenChannel = onOpenChannel,
                )
            }
            Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
            IconButton(onClick = onBack, Modifier.statusBarsPadding().padding(4.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ShortPage(
    short: VideoItem,
    current: Boolean,
    state: PlayerUiState,
    player: Player,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
) {
    // The player's state is this Short's only once the player holds it: a swipe settles a moment before the player has switched,
    // and the Short before must not show through (its last picture, its pause button).
    val own = current && state.video?.videoId == short.videoId
    val shown = state.video.takeIf { own } ?: short
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = own,
            onClickLabel = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
            onClick = onTogglePlay,
        ),
    ) {
        if (own && state.phase != PlayerPhase.ERROR) {
            // Until the first picture is drawn the Short's own picture stands in for it, not a black one.
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = { Picture(short) },
            )
        } else {
            Picture(short)
        }

        if (current && (!own || state.phase == PlayerPhase.LOADING || (state.isBuffering && state.phase == PlayerPhase.READY))) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }
        if (own && state.phase == PlayerPhase.READY && !state.isPlaying && !state.isBuffering) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.player_play),
                modifier = Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color(0x66000000)).padding(12.dp),
            )
        }
        if (own && state.phase == PlayerPhase.ERROR && state.error != null) {
            Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
                ErrorState(state.error, onRetry = onRetry)
            }
        }

        // The title and the channel, over a soft shade so that they read on any picture.
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 20.dp),
        ) {
            val channelId = shown.channelId
            val channelName = shown.channelName.orEmpty()
            if (channelId != null || channelName.isNotEmpty()) {
                val avatar = if (own) state.details?.uploaderAvatarUrl else null
                Row(
                    Modifier.clip(CircleShape)
                        .then(
                            if (channelId != null) Modifier.clickable { onOpenChannel(ChannelItem(channelId, channelName, avatar, subscriberCount = null)) } else Modifier,
                        )
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(avatar, size = 32, name = channelName)
                    Text(
                        channelName,
                        Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (shown.title.isNotBlank()) {
                Text(shown.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (own && state.phase != PlayerPhase.ERROR) Hairline(player, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun Picture(short: VideoItem) {
    AsyncImage(model = short.thumbnailUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}

/** A hairline along the bottom edge: how far the Short has played. It runs left to right in every language, like the video itself. */
@Composable
private fun Hairline(player: Player, modifier: Modifier = Modifier) {
    val progress by rememberProgress(player)
    val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxWidth().height(2.dp).background(Color(0x4DFFFFFF))) {
            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}
