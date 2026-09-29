package com.ahmadnayfeh.shasha.ui.screens.player

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.data.asSourceException
import com.ahmadnayfeh.shasha.player.NextInPlaylist
import com.ahmadnayfeh.shasha.player.PlaybackController
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.delay

/**
 * Loads a video and plays it. Any failure, while extracting or while playing, shows the unified
 * [ErrorScreen] in place of the video.
 *
 * It resumes from the saved position, saves progress, adds the video to the history and offers watch
 * later ([WatchSession]). Inside a playlist ([playlistId], [playlistIndex]) the next video follows when
 * this one ends ([onPlayNext]); otherwise a calm end card waits for «رجوع».
 */
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    playlistId: String? = null,
    playlistIndex: Int = -1,
    onPlayNext: (videoId: String, playlistIndex: Int) -> Unit = { _, _ -> },
) {
    val container = LocalContext.current.appContainer
    val vm: PlayerViewModel = viewModel(
        key = "player:$videoId",
        factory = viewModelFactory {
            initializer {
                val next = if (playlistId != null && playlistIndex >= 0) NextInPlaylist(container.playQueues, container.videoSource) else null
                PlayerViewModel(
                    videoId = videoId,
                    streams = container.streams,
                    session = WatchSession(videoId, container.watch, container.appScope),
                    nextInPlaylist = next?.let { finder -> { finder.after(playlistId!!, playlistIndex) } },
                )
            }
        },
    )
    val load by vm.load.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = load,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
        modifier = Modifier.fillMaxSize().background(Color.Black),
        label = "player",
    ) { state ->
        when (state) {
            PlayerViewModel.Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ShashaLoaderWithText(stringResource(R.string.player_loading))
            }
            is PlayerViewModel.Load.Failed -> ErrorScreen(
                error = state.error,
                extractorVersion = container.videoSource.extractorVersion,
                onCheckUpdates = onCheckUpdates,
                onBack = onBack,
            )
            is PlayerViewModel.Load.Ready -> VideoPlayer(
                state,
                vm,
                container.mediaSourceFactory,
                onExit = onBack,
                onPlayNext = { next -> onPlayNext(next.videoId, playlistIndex + 1) },
            )
        }
    }
}

/**
 * Owns the ExoPlayer for as long as this screen is started: created on ON_START, released on ON_STOP
 * (Home pressed, screen covered) and when leaving the screen. Stale URLs (hours later) are replaced by
 * a fresh extraction first, and playback continues where it was.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    ready: PlayerViewModel.Load.Ready,
    vm: PlayerViewModel,
    factory: YouTubeMediaSourceFactory,
    onExit: () -> Unit,
    onPlayNext: (VideoItem) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var controller by remember { mutableStateOf<PlaybackController?>(null) }

    DisposableEffect(lifecycleOwner, ready) {
        fun start() {
            if (controller != null) return
            if (!vm.canStart(ready)) return // a fresh extraction replaces this one; its player starts then
            lateinit var created: PlaybackController
            created = PlaybackController(context, factory, ready.stream) { error ->
                vm.onPlaybackError(ready, error, created.resumeState())
            }
            try {
                created.start(vm.resume)
                controller = created
                vm.onPlaybackStarted(ready.stream.video)
            } catch (e: Exception) {
                created.release()
                vm.fail(e.asSourceException())
            }
        }

        fun stop() {
            controller?.let {
                // Leaving (BACK, Home, standby): remember where this profile stopped.
                vm.saveProgress(it.progress())
                vm.onReleased(it.release())
            }
            controller = null
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }

    val current = controller ?: return
    val ui by current.state.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val savedForLater by vm.savedForLater.collectAsStateWithLifecycle()
    val resumedFrom by vm.resumedFromMs.collectAsStateWithLifecycle()
    KeepScreenOn(ui.playWhenReady && !ui.isEnded)
    // Progress: every 10 s while playing, and at every pause (the release saves the last position).
    LaunchedEffect(current) {
        while (true) {
            delay(WatchSession.SAVE_EVERY_MS)
            if (current.state.value.playWhenReady) vm.saveProgress(current.progress())
        }
    }
    LaunchedEffect(current, ui.playWhenReady) {
        if (!ui.playWhenReady) vm.saveProgress(current.progress())
    }
    LaunchedEffect(current, ui.isEnded) {
        if (ui.isEnded) vm.onEnded(current.progress())
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // SurfaceView: the video goes straight to the hardware composer, the cheapest path on a weak TV.
        ContentFrame(
            player = current.player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = ContentScale.Fit,
        )
        val ending = end
        if (ui.isEnded && ending != null) {
            EndCard(
                end = ending,
                title = ui.title,
                onBack = onExit,
                onReplay = {
                    vm.onReplay()
                    current.onAction(PlayerAction.TogglePlayPause)
                },
                onPlayNext = onPlayNext,
            )
        } else {
            PlayerOsd(
                state = ui,
                progress = current::progress,
                onAction = current::onAction,
                onExit = onExit,
                savedForLater = savedForLater,
                onToggleWatchLater = { vm.toggleWatchLater(ready.stream.video) },
                resumedFromMs = resumedFrom,
            )
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
