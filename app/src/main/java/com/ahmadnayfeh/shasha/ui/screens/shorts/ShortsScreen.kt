package com.ahmadnayfeh.shasha.ui.screens.shorts

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_DOWN
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.KeyEvent.KEYCODE_DPAD_RIGHT
import android.view.KeyEvent.KEYCODE_DPAD_UP
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_ESCAPE
import android.view.KeyEvent.KEYCODE_HEADSETHOOK
import android.view.KeyEvent.KEYCODE_MEDIA_NEXT
import android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY
import android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
import android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.policy.PlaybackMeter
import com.ahmadnayfeh.shasha.player.PlayerAction
import com.ahmadnayfeh.shasha.player.WatchSession
import com.ahmadnayfeh.shasha.policy.PlayOrigin
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.remote.ConfirmAnswer
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.format.Thumbnails
import com.ahmadnayfeh.shasha.ui.lock.LockedPlaybackState
import com.ahmadnayfeh.shasha.ui.lock.lockMethodFor
import com.ahmadnayfeh.shasha.ui.screens.player.PhoneStartConfirmation
import com.ahmadnayfeh.shasha.ui.screens.player.PlayGate
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerHost
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerViewModel
import com.ahmadnayfeh.shasha.ui.screens.player.PlaybackEffects
import com.ahmadnayfeh.shasha.ui.screens.player.StartConfirmation
import com.ahmadnayfeh.shasha.ui.screens.player.playOrigin
import com.ahmadnayfeh.shasha.ui.screens.player.rememberActivePlayback
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import kotlinx.coroutines.delay

/**
 * The Shorts player: one Short at a time on a vertical stage in the middle of the screen (9:16, dark sides), and a list to flip
 * through. DOWN plays the next Short, UP the one before, OK pauses and plays, BACK leaves; the title and the channel are shown
 * small, beside the stage. The list is the one the first Short was chosen from ([queueKey]: a search's Shorts, a channel's Shorts
 * tab, the home's rows, a video's suggested Shorts); [videoId] plays first.
 *
 * Everything the player enforces applies to every Short, because every Short is played by the player's own machinery: a
 * [PlayerViewModel] per Short (the policy's check before anything is extracted, the schedule and the daily time, the lock of
 * Serious Mode, the history and the progress, the retry of a failing decoder with the safe plan, the phone's «تأكيد»), inside
 * [PlayerHost] ([PlayerContent]'s loader, error, refusal and lock screens), and the player's [rememberActivePlayback] and
 * [PlaybackEffects] around its one ExoPlayer, so the phone sees what plays and drives it. Only the look and the keys are the Shorts
 * player's. SponsorBlock is not here: a Short has nothing to skip.
 *
 * One Short plays at a time, with one ExoPlayer: the one of the Short on screen is released when the viewer moves on, before the
 * next one's is made, and a Short that is left is finished with (its [PlayerViewModel] is dropped; going back to it starts it again,
 * from its beginning). Its stream is extracted again only after minutes ([com.ahmadnayfeh.shasha.data.StreamRepository]).
 * The Short after the one on screen has its stream extracted while this one plays ([ShortsViewModel.prefetchAhead]).
 *
 * While a Short is locked (Serious Mode: one that was started is watched to its end) UP and DOWN say so and do nothing; once it
 * ended by itself the lock is gone, and they flip. A Short is not looped: its end waits for OK («إعادة») or DOWN.
 */
@Composable
fun ShortsScreen(
    videoId: String,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    queueKey: String? = null,
    fromLink: Boolean = false,
    phoneApproved: Boolean = false,
    profile: Profile? = null,
    lock: LockController? = null,
    onRestart: () -> Unit = {},
    onPairPhone: (() -> Unit)? = null,
) {
    val container = LocalContext.current.appContainer
    val locking = if (lock != null && profile != null) lock else null
    val list: ShortsViewModel = viewModel(
        key = "shorts:$videoId:$queueKey",
        factory = viewModelFactory {
            initializer {
                ShortsViewModel(
                    queue = queueKey?.let(container.shortsQueues::get).orEmpty(),
                    startVideoId = videoId,
                    // The policy's own look-up asks for the same extraction (low quality allowed), and the player takes it from here.
                    prefetch = { short -> container.streams.stream(short.videoId, allowLowQuality = true) },
                )
            }
        },
    )
    val index by list.index.collectAsStateWithLifecycle()
    val short = list.shorts[index]
    // v1.1.1: what asks the phone before a start only it could end early: once for the whole visit, not for every Short flipped to.
    val confirmation = if (locking != null && profile != null && !phoneApproved) {
        remember(locking, profile) {
            ConfirmedOnce(PhoneStartConfirmation(container.policyEngine, locking.lock, profile, null, container.phoneConfirmations))
        }
    } else {
        null
    }
    // A player of its own for the Short on screen, kept in a store of its own: dropped when the viewer moves on (or leaves).
    val owner = remember(index) { ShortOwner() }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val player: PlayerViewModel = viewModel(
        viewModelStoreOwner = owner,
        key = "short",
        factory = viewModelFactory {
            initializer {
                val policy = container.policy
                val origin = playOrigin(fromLink, playlistId = null)
                PlayerViewModel(
                    videoId = short.videoId,
                    streams = container.streams,
                    session = WatchSession(short.videoId, container.watch, container.appScope),
                    // The list item says the Short's channel when it knows it (the channel's own Shorts, the home): no look-up then.
                    gate = PlayGate { id -> policy.checkPlay(id, origin, known = short) },
                    startConfirmation = confirmation,
                    lock = locking?.playerHooks(profile!!.id),
                    allowLowQuality = profile?.isChild == true,
                    defaultSpeed = container.behavior.values.value.defaultSpeed,
                    resumesFromSaved = false,
                )
            }
        },
    )
    // Whether Serious Mode holds the Short on screen: the lock as it is now, followed (the end of the Short releases it).
    val locked = if (locking != null && profile != null) {
        val current by locking.lock.collectAsStateWithLifecycle()
        lockMethodFor(current, profile.id, short.videoId, playlistId = null) != null
    } else {
        false
    }
    // The viewer may skip a Short that is loading, refused or failed (nothing of it started, so nothing holds it): UP and DOWN
    // work over the loader and the refusal and error screens; a playing Short has them in [ShortsPlayer].
    val state by player.load.collectAsStateWithLifecycle()
    val flipsOverIt = state is PlayerViewModel.Load.Loading || state is PlayerViewModel.Load.Failed || state is PlayerViewModel.Load.Denied
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (!flipsOverIt || event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) return@onPreviewKeyEvent false
            when (event.nativeKeyEvent.keyCode) {
                KEYCODE_DPAD_DOWN, KEYCODE_MEDIA_NEXT -> list.next()
                KEYCODE_DPAD_UP, KEYCODE_MEDIA_PREVIOUS -> list.previous()
                else -> false
            }
        },
    ) {
        PlayerHost(
            vm = player,
            videoId = short.videoId,
            playlistId = null,
            profile = profile,
            lock = locking,
            onBack = onBack,
            onCheckUpdates = onCheckUpdates,
            onPlayNext = { _, _ -> },
            onKidsHome = null,
            skipper = null,
            onRestart = onRestart,
            onPairPhone = onPairPhone,
            loading = { ShortsLoading(short) },
            ready = { ready, lockState ->
                val factory = { container.mediaSourceFactory }
                ShortsPlayer(
                    ready = ready,
                    vm = player,
                    short = short,
                    position = index + 1,
                    total = list.shorts.size,
                    lock = lockState,
                    locked = locked,
                    factory = factory,
                    meterFor = { video -> container.usageTracker.playbackMeter(video.channelId, video.channelName) },
                    maxQuality = { container.playbackSettings.maxQuality.value },
                    onNext = list::next,
                    onPrevious = list::previous,
                    onPlaying = list::prefetchAhead,
                    onRestart = onRestart,
                )
            },
        )
    }
}

/** What keeps the [PlayerViewModel] of one Short: dropped with the Short. */
private class ShortOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/** The phone's «تأكيد» ([StartConfirmation]) asked once for the whole visit: once given, a Short flipped to starts without a new one. */
internal class ConfirmedOnce(private val inner: StartConfirmation) : StartConfirmation {
    @Volatile
    private var confirmed = false

    override fun needed(videoId: String): Boolean = !confirmed && inner.needed(videoId)

    override suspend fun ask(videoId: String, title: String): ConfirmAnswer =
        inner.ask(videoId, title).also { if (it == ConfirmAnswer.CONFIRMED) confirmed = true }
}

/** While a Short is made ready: its picture, soft, over the screen, and a quiet «جارٍ تجهيز المقطع». */
@Composable
internal fun ShortsLoading(short: VideoItem) {
    Box(Modifier.fillMaxSize().testTag("shorts_loading")) {
        ShortsBackdrop(backdropOf(short))
        ShashaLoaderWithText(stringResource(R.string.shorts_loading), Modifier.align(Alignment.Center))
    }
}

/** The picture behind the stage: the Short's own vertical one when the list had it, else the one every list shows for it. */
private fun backdropOf(short: VideoItem): String = short.thumbnailUrl ?: Thumbnails.video(short.videoId, Thumbnails.Size.SMALL)

/**
 * The Short playing: the stage with the video, the keys (UP and DOWN flip, OK pauses and plays or replays at the end, BACK leaves
 * through the lock) and the notices. [position] of [total] is where it is in its list. [onNext] and [onPrevious] move in the list
 * (false at its ends); [onPlaying] tells the list its next Short may be got ready (once this one plays); [locked]: Serious Mode
 * holds this Short, so UP and DOWN only say so.
 */
@OptIn(UnstableApi::class)
@Composable
private fun ShortsPlayer(
    ready: PlayerViewModel.Load.Ready,
    vm: PlayerViewModel,
    short: VideoItem,
    position: Int,
    total: Int,
    lock: LockedPlaybackState,
    locked: Boolean,
    factory: () -> YouTubeMediaSourceFactory,
    meterFor: (VideoItem) -> PlaybackMeter,
    maxQuality: () -> Int,
    onNext: () -> Boolean,
    onPrevious: () -> Boolean,
    onPlaying: () -> Unit,
    onRestart: () -> Unit,
) {
    val active = rememberActivePlayback(ready, vm, factory, meterFor, maxQuality)
    if (active == null) {
        // Between the extraction and the player: the same picture the loader had, so nothing flickers.
        ShortsBackdrop(backdropOf(short))
        return
    }
    val controller = active.controller
    PlaybackEffects(active, ready, vm, lock)
    val ui by controller.state.collectAsStateWithLifecycle()
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(controller) {
        while (true) {
            val now = controller.progress()
            progress = if (now.durationMs > 0) (now.positionMs.toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
            delay(PROGRESS_TICK_MS)
        }
    }
    // The next Short is made ready once this one plays (not before: its own start comes first).
    LaunchedEffect(controller, ui.isPlaying) { if (ui.isPlaying) onPlaying() }

    // The hints show at first, and for a moment after any key, and stay while the Short waits for the viewer.
    var hintsFor by remember { mutableIntStateOf(1) }
    var hintsShown by remember { mutableStateOf(true) }
    LaunchedEffect(hintsFor) {
        hintsShown = true
        delay(HINTS_MS)
        hintsShown = false
    }
    var notice by remember { mutableStateOf<Int?>(null) }
    var noticeFor by remember { mutableIntStateOf(0) }
    LaunchedEffect(noticeFor) {
        if (noticeFor == 0) return@LaunchedEffect
        delay(NOTICE_MS)
        notice = null
    }
    fun say(text: Int) {
        notice = text
        noticeFor++
    }

    fun flip(forward: Boolean) {
        hintsFor++
        // Serious Mode: nothing else starts while this Short is held.
        if (locked) return say(R.string.shorts_locked)
        // Silent at once: the release of this player comes with the next Short's.
        controller.onAction(PlayerAction.Pause)
        val moved = if (forward) onNext() else onPrevious()
        if (!moved) {
            controller.onAction(PlayerAction.Play)
            say(if (forward) R.string.shorts_last else R.string.shorts_first)
        }
    }

    fun toggle() {
        hintsFor++
        if (ui.isEnded) {
            // v1.1.1: a replay that only the phone could end early is a new start: it asks the phone first.
            if (vm.replayNeedsConfirmation()) {
                onRestart()
            } else {
                vm.onReplay()
                controller.onAction(PlayerAction.TogglePlayPause)
            }
        } else {
            controller.onAction(PlayerAction.TogglePlayPause)
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val down = event.type == KeyEventType.KeyDown
        val up = event.type == KeyEventType.KeyUp
        // The lock has something in front (the parent PIN pad, the phrase): the keys are its.
        if (lock.covered) return false
        return when (native.keyCode) {
            // Acting on key-up (and eating the key-down) keeps the system from also handling BACK.
            KEYCODE_BACK, KEYCODE_ESCAPE -> {
                if (up) lock.requestExit()
                true
            }
            KEYCODE_DPAD_DOWN, KEYCODE_MEDIA_NEXT -> {
                if (down && native.repeatCount == 0) flip(forward = true)
                true
            }
            KEYCODE_DPAD_UP, KEYCODE_MEDIA_PREVIOUS -> {
                if (down && native.repeatCount == 0) flip(forward = false)
                true
            }
            // A press of OK acts when it is let go: the lock's hold (the adult's way out) owns a long one.
            KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER -> {
                if (up) toggle()
                true
            }
            KEYCODE_MEDIA_PLAY_PAUSE, KEYCODE_HEADSETHOOK -> {
                if (down && native.repeatCount == 0) toggle()
                true
            }
            KEYCODE_MEDIA_PLAY, KEYCODE_MEDIA_PAUSE -> {
                if (down && native.repeatCount == 0) {
                    hintsFor++
                    controller.onAction(if (native.keyCode == KEYCODE_MEDIA_PLAY) PlayerAction.Play else PlayerAction.Pause)
                }
                true
            }
            // Nothing to move along here: the keys never leave for another control.
            KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT -> true
            else -> false
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(lock.covered) { if (!lock.covered) runCatching { rootFocus.requestFocus() } }
    // Safety net: if the focus was lost, BACK still behaves. Not while the lock has something in front: BACK is its own then.
    BackHandler(enabled = !lock.suspended) { lock.requestExit() }

    val stateOfScreen = ShortsUiState(
        title = ui.title.ifBlank { short.title },
        channelName = ui.channelName ?: short.channelName,
        position = position,
        total = total,
        playing = ui.playWhenReady,
        buffering = ui.isBuffering,
        ended = ui.isEnded,
        progress = progress,
        aspect = ui.videoAspect,
        hints = hintsShown || !ui.playWhenReady || ui.isEnded,
        notice = notice?.let { stringResource(it) },
    )
    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(::handleKey)
            .focusRequester(rootFocus)
            .focusable()
            .testTag("shorts_root"),
    ) {
        ShortsLayout(stateOfScreen, backdropOf(short)) {
            // SurfaceView: the video goes straight to the hardware composer, the cheapest path on a weak TV.
            ContentFrame(
                player = controller.player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** How often the progress line along the Short is moved. */
private const val PROGRESS_TICK_MS = 250L

/** How long UP, DOWN and OK stay shown after a key. */
private const val HINTS_MS = 3_500L

/** How long a notice stays. */
private const val NOTICE_MS = 2_500L
