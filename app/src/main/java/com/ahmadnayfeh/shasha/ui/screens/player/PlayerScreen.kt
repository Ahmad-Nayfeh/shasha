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
import androidx.compose.ui.platform.testTag
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
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.player.AttachToPlayerHub
import com.ahmadnayfeh.shasha.player.NextInPlaylist
import com.ahmadnayfeh.shasha.player.PlaybackController
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.policy.PlayOrigin
import com.ahmadnayfeh.shasha.policy.PlaybackMeter
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.UnlockMethod
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.PolicyNotice
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.lock.LockedPlayback
import com.ahmadnayfeh.shasha.ui.lock.LockedPlaybackState
import com.ahmadnayfeh.shasha.ui.lock.NoLockActions
import com.ahmadnayfeh.shasha.ui.lock.PlayerLockActions
import com.ahmadnayfeh.shasha.ui.lock.lockMethodFor
import com.ahmadnayfeh.shasha.ui.lock.playerActions
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.delay

/**
 * Loads a video and plays it. Any failure, while extracting or while playing, shows the unified
 * [ErrorScreen] in place of the video.
 *
 * The policy decides first ([PolicyGate.checkPlay], again before every restart): a video the profile may
 * not watch shows why ([PolicyNotice]) and never plays. [fromLink]: the video came as a link (the phone),
 * so only its own channel counts, never a playlist the link names.
 *
 * It resumes from the saved position (or starts at [startSeconds], a shared link's `t=`), saves progress,
 * adds the video to the history and offers watch later ([WatchSession]). Inside a playlist ([playlistId], [playlistIndex]) the next video the profile
 * may watch follows when this one ends ([onPlayNext]); otherwise a calm end card waits for «رجوع».
 * The time really played goes to the stats ([com.ahmadnayfeh.shasha.policy.PlaybackMeter]).
 *
 * SPEC §4: every video that starts is locked for [profile] ([lock]); leaving it early takes the parent PIN
 * (a child) or the exit challenge (the adult), see [LockedPlayback]. A child whose video or playlist ended
 * goes back to the child home ([onKidsHome]). A playlist video restored by the lock after a restart comes
 * without its position ([playlistIndex] -1): it is looked up by its id.
 */
@Composable
fun PlayerScreen(
    videoId: String,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    playlistId: String? = null,
    playlistIndex: Int = -1,
    fromLink: Boolean = false,
    startSeconds: Long? = null,
    onPlayNext: (videoId: String, playlistIndex: Int) -> Unit = { _, _ -> },
    profile: Profile? = null,
    lock: LockController? = null,
    onKidsHome: (() -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer
    val locking = if (lock != null && profile != null) lock else null
    val vm: PlayerViewModel = viewModel(
        key = "player:$videoId",
        factory = viewModelFactory {
            initializer {
                val policy = container.policy
                val origin = playOrigin(fromLink, playlistId)
                val next = if (playlistId != null) NextInPlaylist(container.playQueues, container.videoSource) else null
                PlayerViewModel(
                    videoId = videoId,
                    streams = container.streams,
                    session = WatchSession(videoId, container.watch, container.appScope),
                    nextInPlaylist = next?.let { finder ->
                        {
                            // The same rule as each video's own check: a link's videos by their own channel.
                            nextVisible(finder, playlistId!!, playlistIndex, videoId) {
                                if (fromLink) policy.visibleVideos(listOf(it)).isNotEmpty() else policy.visiblePlaylistVideos(playlistId, listOf(it)).isNotEmpty()
                            }
                        }
                    },
                    gate = PlayGate { id -> policy.checkPlay(id, origin) },
                    lock = locking?.playerHooks(profile!!.id),
                    playlistId = playlistId,
                    startAtMs = startSeconds?.let { it * 1000 },
                )
            }
        },
    )
    val method = if (locking != null && profile != null) {
        // The lock as it is now (a StateFlow: its current value from the first frame), and how it holds this screen.
        val current by locking.lock.collectAsStateWithLifecycle()
        lockMethodFor(current, profile.id, videoId, playlistId)
    } else {
        null
    }
    val actions = remember(locking) { locking?.playerActions() ?: NoLockActions }
    // A video that failed for the lack of a network (its lock stays) goes on by itself once the TV is online.
    val online by container.network.online.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(vm, online) { if (online) vm.retryAfterNetwork() }
    val messages = LocalAppMessages.current
    val surrendered = stringResource(R.string.lock_surrendered)
    PlayerContent(
        vm = vm,
        extractorVersion = container.videoSource.extractorVersion,
        factory = { container.mediaSourceFactory },
        meterFor = { video -> container.usageTracker.playbackMeter(video.channelId, video.channelName) },
        onBack = onBack,
        onCheckUpdates = onCheckUpdates,
        onPlayNext = { next, index -> onPlayNext(next.videoId, if (index >= 0) index else playlistIndex + 1) },
        lockMethod = method,
        lockActions = actions,
        onSurrendered = { messages?.show(surrendered, ShashaIcons.Lock) },
        onKidsHome = onKidsHome,
    )
}

/**
 * How the video came to be played, for [com.ahmadnayfeh.shasha.policy.PolicyGate.checkPlay]: a link counts
 * only its own channel; a playlist's own page vouches for its videos.
 */
internal fun playOrigin(fromLink: Boolean, playlistId: String?): PlayOrigin = when {
    fromLink -> PlayOrigin.Link
    playlistId != null -> PlayOrigin.Playlist(playlistId)
    else -> PlayOrigin.Navigation
}

/**
 * What [vm] says: the loader, the error screen, the policy's refusal ([PolicyNotice], no player is ever
 * created for it: [factory] is not even asked), or the video; all inside the lock ([LockedPlayback]: with
 * [lockMethod] set, every way out goes through it).
 */
@Composable
internal fun PlayerContent(
    vm: PlayerViewModel,
    extractorVersion: String,
    factory: () -> YouTubeMediaSourceFactory,
    meterFor: (VideoItem) -> PlaybackMeter,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    onPlayNext: (VideoItem, index: Int) -> Unit,
    lockMethod: UnlockMethod? = null,
    lockActions: PlayerLockActions = NoLockActions,
    onSurrendered: () -> Unit = {},
    onKidsHome: (() -> Unit)? = null,
) {
    val load by vm.load.collectAsStateWithLifecycle()
    LockedPlayback(lockMethod, lockActions, onLeave = onBack, onSurrendered = onSurrendered) { lock ->
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
                    extractorVersion = extractorVersion,
                    onCheckUpdates = onCheckUpdates,
                    onBack = lock::requestExit,
                ) {
                    // Failures that pass (the network, YouTube's rate limit) are worth another try.
                    if (state.error.kind in RETRYABLE) {
                        ShashaButton(
                            stringResource(R.string.action_retry),
                            vm::retry,
                            icon = ShashaIcons.Refresh,
                            modifier = Modifier.testTag("error_retry"),
                        )
                    }
                }
                is PlayerViewModel.Load.Denied -> PolicyNotice(state.reason, lock::requestExit)
                is PlayerViewModel.Load.Ready -> VideoPlayer(
                    state,
                    vm,
                    factory,
                    meterFor,
                    lock,
                    onPlayNext = onPlayNext,
                    onKidsHome = onKidsHome,
                    locked = lockMethod != null,
                )
            }
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
    factory: () -> YouTubeMediaSourceFactory,
    meterFor: (VideoItem) -> PlaybackMeter,
    lock: LockedPlaybackState,
    onPlayNext: (VideoItem, index: Int) -> Unit,
    onKidsHome: (() -> Unit)?,
    locked: Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var controller by remember { mutableStateOf<PlaybackController?>(null) }
    // The time this player really plays, for the stats (most watched channels).
    var meter by remember { mutableStateOf<PlaybackMeter?>(null) }

    DisposableEffect(lifecycleOwner, ready) {
        fun start() {
            if (controller != null) return
            if (!vm.canStart(ready)) return // a fresh extraction replaces this one; its player starts then
            lateinit var created: PlaybackController
            created = PlaybackController(context, factory(), ready.stream, onJump = vm::onJumped) { error ->
                vm.onPlaybackError(ready, error, created.resumeState())
            }
            try {
                created.start(vm.resume)
                controller = created
                meter = meterFor(ready.stream.video)
                vm.onPlaybackStarted(ready.stream.video)
            } catch (e: Exception) {
                created.release()
                vm.fail(e.asSourceException())
            }
        }

        fun stop() {
            meter?.let {
                it.setPlaying(false)
                it.flush()
            }
            meter = null
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
    // SPEC §5: the phone sees this video and plays, pauses and seeks it; its «إيقاف» leaves like «رجوع» (the lock decides).
    AttachToPlayerHub(current, ready.stream.video, leave = lock::requestExit, covered = { lock.covered })
    val ui by current.state.collectAsStateWithLifecycle()
    val end by vm.end.collectAsStateWithLifecycle()
    val skipped by vm.skippedToEnd.collectAsStateWithLifecycle()
    val savedForLater by vm.savedForLater.collectAsStateWithLifecycle()
    val resumedFrom by vm.resumedFromMs.collectAsStateWithLifecycle()
    KeepScreenOn(ui.playWhenReady && !ui.isEnded)
    // Progress: every 10 s while playing, and at every pause (the release saves the last position).
    LaunchedEffect(current) {
        while (true) {
            delay(WatchSession.SAVE_EVERY_MS)
            if (current.state.value.playWhenReady) vm.saveProgress(current.progress())
            meter?.flush()
        }
    }
    LaunchedEffect(current, ui.isPlaying) { meter?.setPlaying(ui.isPlaying) }
    LaunchedEffect(current, ui.playWhenReady) {
        if (!ui.playWhenReady) vm.saveProgress(current.progress())
    }
    LaunchedEffect(current, ui.isEnded) {
        if (ui.isEnded) vm.onEnded(current.progress())
    }
    // A lock screen in front (the parent PIN, the exit phrase): the video waits, and goes on if it closes.
    var pausedForLock by remember { mutableStateOf(false) }
    LaunchedEffect(current, lock.covered) {
        val playing = current.state.value.let { it.playWhenReady && !it.isEnded }
        if (lock.covered && playing) {
            pausedForLock = true
            current.onAction(PlayerAction.Pause)
        } else if (!lock.covered && pausedForLock) {
            pausedForLock = false
            current.onAction(PlayerAction.Play)
        }
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
                onBack = lock::requestExit,
                onReplay = {
                    vm.onReplay()
                    current.onAction(PlayerAction.TogglePlayPause)
                },
                onPlayNext = { next -> onPlayNext(next, (ending as? PlaybackEnd.UpNext)?.index ?: -1) },
                paused = lock.covered,
                // Skipped to its end, the video is still locked (SPEC §4): no way home but the PIN.
                kidsHome = onKidsHome.takeUnless { skipped },
                stillLocked = skipped && locked,
            )
        } else {
            PlayerOsd(
                state = ui,
                progress = current::progress,
                onAction = current::onAction,
                onExit = lock::requestExit,
                savedForLater = savedForLater,
                onToggleWatchLater = { vm.toggleWatchLater(ready.stream.video) },
                resumedFromMs = resumedFrom,
                suspended = lock.suspended,
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

/**
 * The playlist's next video after [index] that the profile may watch ([visible]), skipping the others;
 * null at the end. A playlist long past the ones hidden is not searched forever. Without an [index] (-1:
 * the lock restored [videoId] after a restart) its position is looked up first.
 */
internal suspend fun nextVisible(
    finder: NextInPlaylist,
    playlistId: String,
    index: Int,
    videoId: String,
    visible: (VideoItem) -> Boolean,
): NextVideo? {
    var at = if (index >= 0) index else finder.indexOf(playlistId, videoId) ?: return null
    repeat(MAX_HIDDEN_SKIPPED) {
        val next = finder.after(playlistId, at) ?: return null
        at++
        if (visible(next)) return NextVideo(next, at)
    }
    return null
}

private const val MAX_HIDDEN_SKIPPED = 200

/** Failures that may pass by themselves: the error screen offers «إعادة المحاولة». */
private val RETRYABLE = setOf(SourceErrorKind.NETWORK, SourceErrorKind.RATE_LIMITED)
