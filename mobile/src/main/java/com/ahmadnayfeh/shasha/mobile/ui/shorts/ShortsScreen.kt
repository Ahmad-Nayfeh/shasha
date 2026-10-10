package com.ahmadnayfeh.shasha.mobile.ui.shorts

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.player.PlaybackMode
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.LocalActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.OrientationMode
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsSheet
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsViewModel
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchLaterIds
import com.ahmadnayfeh.shasha.mobile.ui.nav.ShortsRoute
import com.ahmadnayfeh.shasha.mobile.ui.watch.CaptionText
import com.ahmadnayfeh.shasha.mobile.ui.watch.PREVIEW_MAX_FRAME_WIDTH
import com.ahmadnayfeh.shasha.mobile.ui.watch.PlayerSettingsSheet
import com.ahmadnayfeh.shasha.mobile.ui.watch.SeekBar
import com.ahmadnayfeh.shasha.mobile.ui.watch.SeekPreview
import com.ahmadnayfeh.shasha.mobile.ui.watch.aboveThumb
import com.ahmadnayfeh.shasha.mobile.ui.watch.pictureInPictureOnLeave
import com.ahmadnayfeh.shasha.mobile.ui.watch.rememberProgress
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import com.ahmadnayfeh.shasha.youtube.forPreview
import kotlin.math.roundToInt

/** The Shorts viewer's parts, for the tests. */
internal const val SHORTS_FRAME_TAG = "shorts_frame"
internal const val SHORTS_SEEK_TAG = "shorts_seek"

/**
 * The Shorts viewer: full screen and upright, one Short at a time, swipe up for the next and down for the one before (the list
 * the viewer was opened from, [ShortsQueue]), tap to pause or play, a thin seek bar at the bottom, the actions on the side
 * (watch later, save, download, share, the player's settings), back to leave. The window things (the screen staying upright and
 * on, the bars away, picture-in-picture when the viewer leaves the app) happen here around [ShortsContent], which draws it; the
 * player is the app's, as on the player screen, so the sound goes on in the background through the same media session.
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
    val settings by container.settingsFlow.collectAsStateWithLifecycle()
    val bridge = LocalActivityBridge.current
    val inPip by bridge.inPictureInPicture.collectAsStateWithLifecycle()
    val view = LocalView.current
    val pagerState = rememberPagerState(initialPage = queue.startIndex) { queue.size }
    var showSettings by remember { mutableStateOf(false) }
    // The Short whose comments are open (a sheet over the viewer).
    var commentsFor by remember { mutableStateOf<String?>(null) }
    // Where the Short's frame is in the window: picture-in-picture grows its floating window out of it.
    var frameBounds by remember { mutableStateOf<Rect?>(null) }

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
            // No floating window for what comes after the viewer, unless a video took the player over meanwhile (a link opened
            // while a Short played): the app's player owns the window's parameters then.
            val now = container.player.state.value
            if (container.player.mode == PlaybackMode.SHORT || now.phase == PlayerPhase.IDLE) {
                bridge.setPictureInPicture(autoEnter = false, aspect = PlayerUiState.DEFAULT_ASPECT, sourceRect = null)
            }
        }
    }
    // Leaving the app while a Short plays turns it into a floating window (the same setting as for videos), upright from the start.
    val autoEnterPip = pictureInPictureOnLeave(state, settings.pictureInPicture)
    LaunchedEffect(autoEnterPip, state.aspectRatio, frameBounds) {
        bridge.setPictureInPicture(autoEnter = autoEnterPip, aspect = state.aspectRatio, sourceRect = frameBounds)
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
        onSeek = { container.player.seekTo(it) },
        onOpenSettings = { showSettings = true },
        inPip = inPip,
        onFrameBounds = { frameBounds = it },
        onOpenComments = if (settings.showComments) ({ short -> commentsFor = short.videoId }) else null,
    )

    val commentsId = commentsFor
    if (commentsId != null && settings.showComments && !inPip) {
        val comments: CommentsViewModel = viewModel(key = "comments:$commentsId", factory = CommentsViewModel.factory(container, commentsId))
        CommentsSheet(comments, onDismiss = { commentsFor = null })
    }

    if (showSettings && !inPip) {
        PlayerSettingsSheet(
            state = state,
            onSelectQuality = { container.player.selectQuality(it) },
            onSelectSpeed = { container.player.setSpeed(it) },
            onSelectSubtitles = { container.player.selectSubtitles(it) },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * What the viewer looks like for a given player [state] (the screenshot tests draw it over a fake one). In picture-in-picture
 * ([inPip]) only the Short on screen shows, its picture and what is said.
 */
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
    onSeek: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    inPip: Boolean = false,
    onFrameBounds: (Rect) -> Unit = {},
    /** Opens a Short's comments; null: no comments button (the setting is off). */
    onOpenComments: ((VideoItem) -> Unit)? = null,
) {
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        if (inPip) {
            PipFrame(state, player, fallback = queue.items.getOrNull(pagerState.settledPage), onFrameBounds = onFrameBounds)
        } else {
            Pages(state, player, queue, pagerState, onTogglePlay, onRetry, onBack, onOpenChannel, onSeek, onOpenSettings, onFrameBounds, onOpenComments)
        }
    }
}

@Composable
private fun Pages(
    state: PlayerUiState,
    player: Player,
    queue: ShortsQueue,
    pagerState: PagerState,
    onTogglePlay: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onFrameBounds: (Rect) -> Unit,
    onOpenComments: ((VideoItem) -> Unit)?,
) {
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
                onSeek = onSeek,
                onOpenSettings = onOpenSettings,
                onFrameBounds = onFrameBounds,
                onOpenComments = onOpenComments,
            )
        }
        Box(Modifier.fillMaxWidth().height(96.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        IconButton(onClick = onBack, Modifier.statusBarsPadding().padding(4.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onFrameBounds: (Rect) -> Unit,
    onOpenComments: ((VideoItem) -> Unit)?,
) {
    // The player's state is this Short's only once the player holds it: a swipe settles a moment before the player has switched,
    // and the Short before must not show through (its last picture, its pause button).
    val own = current && state.video?.videoId == short.videoId
    val shown = state.video.takeIf { own } ?: short
    // Where the finger is on the seek bar while it drags (0..1): the frame at that time shows, and the rest steps aside.
    var scrub by remember { mutableStateOf<Float?>(null) }
    Box(
        Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = own,
            onClickLabel = stringResource(if (state.isPlaying) R.string.player_pause else R.string.player_play),
            onClick = onTogglePlay,
        ),
    ) {
        // An upright 9:16 frame from the very first moment (the Short's own picture, cropped to it, while it loads), so a Short never
        // shows wide and then turns upright. A Short whose picture turns out wide fits inside the same frame.
        ShortFrame(
            short = short,
            own = own,
            state = state,
            player = player,
            modifier = Modifier.align(Alignment.Center).aspectRatio(PlayerUiState.SHORT_ASPECT).testTag(SHORTS_FRAME_TAG)
                .then(
                    if (own) {
                        Modifier.onGloballyPositioned { coordinates ->
                            val b = coordinates.boundsInWindow()
                            onFrameBounds(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
                        }
                    } else {
                        Modifier
                    },
                ),
        )

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

        // The bottom: what is said, the channel and the title with the actions beside them, over a soft shade so that they read
        // on any picture, and the seek bar along the bottom edge.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                .navigationBarsPadding()
                .padding(top = 48.dp),
        ) {
            if (scrub == null) {
                if (own) {
                    state.cueText?.let { CaptionText(it, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.Bottom) {
                    ShortInfo(shown, own, state, onOpenChannel, Modifier.weight(1f).padding(bottom = 4.dp))
                    ShortActions(
                        video = shown.copy(isShortForm = true),
                        isLive = shown.isLive,
                        onOpenSettings = onOpenSettings,
                        onOpenComments = onOpenComments?.let { open -> { open(short) } },
                    )
                }
            }
            ShortSeekBar(own = own && state.phase != PlayerPhase.ERROR, state = state, player = player, onSeek = onSeek, onScrub = { scrub = it })
        }
    }
}

/** The Short's channel (its page opens on a tap) and its title. */
@Composable
private fun ShortInfo(shown: VideoItem, own: Boolean, state: PlayerUiState, onOpenChannel: (ChannelItem) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
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
        // When it was published, as everywhere in the app («3 days ago»): from the list it was opened from, else from its own page
        // (the extraction that started it), which knows it for every Short.
        val published = shown.publishedAtEpochMs ?: state.details?.video?.publishedAtEpochMs?.takeIf { own }
        if (published != null) {
            val context = LocalContext.current
            Text(
                Formatters.relativeTime(context, published, System.currentTimeMillis()),
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

/**
 * The column of actions on the side, those the app has for every video (no likes: the app has none): the comments (when the
 * setting shows them), Watch later, Save to a playlist, Download, Share (the Short's own `/shorts/` link) and the player's
 * settings (quality, speed, captions).
 */
@Composable
private fun ShortActions(video: VideoItem, isLive: Boolean, onOpenSettings: () -> Unit, onOpenComments: (() -> Unit)?) {
    val actions = LocalVideoActions.current
    val saved = video.videoId in LocalWatchLaterIds.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (onOpenComments != null) ShortAction(Icons.AutoMirrored.Outlined.Comment, stringResource(R.string.comments_title), onOpenComments)
        ShortAction(
            icon = if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
            label = stringResource(if (saved) R.string.action_saved else R.string.action_watch_later),
            onClick = { actions.toggleWatchLater(video) },
        )
        ShortAction(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.action_save_to_playlist)) { actions.addToPlaylist(video) }
        if (!isLive) ShortAction(Icons.Filled.Download, stringResource(R.string.menu_download)) { actions.download(video) }
        ShortAction(Icons.Filled.Share, stringResource(R.string.menu_share)) { actions.share(video) }
        ShortAction(Icons.Filled.Settings, stringResource(R.string.shorts_settings), onOpenSettings)
    }
}

@Composable
private fun ShortAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0x40000000)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
        }
        Text(
            label,
            Modifier.padding(top = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The thin bar along the bottom: how far the Short has played. Tap or drag it to go elsewhere in the Short; while it is dragged
 * its thumb shows, with the frame at the finger (the storyboard, when YouTube has one for the Short) and its time above it.
 */
@Composable
private fun ShortSeekBar(own: Boolean, state: PlayerUiState, player: Player, onSeek: (Long) -> Unit, onScrub: (Float?) -> Unit) {
    if (!own) {
        SeekBar(positionMs = 0, bufferedMs = 0, durationMs = 0, segments = emptyList(), onSeek = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
        return
    }
    val progress by rememberProgress(player)
    var scrub by remember { mutableStateOf<Float?>(null) }
    val storyboard = remember(state.details) { state.details?.storyboards?.forPreview(PREVIEW_MAX_FRAME_WIDTH) }
    Column(Modifier.fillMaxWidth()) {
        scrub?.let { fraction ->
            SeekPreview(
                storyboard = storyboard,
                positionMs = (fraction * progress.durationMs).toLong(),
                durationMs = progress.durationMs,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).aboveThumb(fraction, barInset = 0.dp),
            )
        }
        SeekBar(
            positionMs = progress.positionMs,
            bufferedMs = progress.bufferedMs,
            durationMs = progress.durationMs,
            segments = emptyList(),
            onSeek = onSeek,
            modifier = Modifier.fillMaxWidth().testTag(SHORTS_SEEK_TAG),
            enabled = !state.isLive && progress.durationMs > 0,
            onScrub = {
                scrub = it
                onScrub(it)
            },
            thumbAtRest = false,
        )
    }
}

/** The Short's upright frame: the player's picture once it has one, the Short's own picture (cropped to the frame) until then. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ShortFrame(short: VideoItem, own: Boolean, state: PlayerUiState, player: Player, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black).clipToBounds()) {
        if (own && state.phase != PlayerPhase.ERROR) {
            // A fresh frame for every Short (the pager composes one per page), so nothing of the Short before it (its size, its last
            // picture) carries over; until its first picture is drawn, the Short's own picture stands in for it.
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = { Picture(short.thumbnailUrl) },
            )
        } else {
            Picture(short.thumbnailUrl)
        }
    }
}

/** Picture-in-picture: the Short alone, with what is said. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PipFrame(state: PlayerUiState, player: Player, fallback: VideoItem?, onFrameBounds: (Rect) -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { coordinates ->
            val b = coordinates.boundsInWindow()
            onFrameBounds(Rect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
        },
    ) {
        val picture = (state.video ?: fallback)?.thumbnailUrl
        if (state.phase == PlayerPhase.ERROR) {
            Picture(picture)
        } else {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = { Picture(picture) },
            )
        }
        state.cueText?.let { CaptionText(it, small = true, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) }
    }
}

/** A Short's picture, cropped to the upright frame: YouTube's pictures of a Short are often wide, with the Short in their middle. */
@Composable
private fun Picture(url: String?) {
    AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}
