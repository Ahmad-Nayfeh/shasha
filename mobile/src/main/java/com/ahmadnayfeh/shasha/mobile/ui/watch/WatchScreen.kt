package com.ahmadnayfeh.shasha.mobile.ui.watch

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.player.PlaybackSpeeds
import com.ahmadnayfeh.shasha.mobile.player.PlayerController
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.player.VideoFit
import com.ahmadnayfeh.shasha.mobile.ui.LocalActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.OrientationMode
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsSheet
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsTeaser
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsViewModel
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SubtitleTrack
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The watch page: the expanded view of the app's one video ([NowPlaying]), drawn over the pages of the app. Everything that
 * belongs to the window and to this page happens here (rotation, the system bars in full screen) around [WatchContent], which
 * draws it; picture-in-picture and the screen staying on follow the video wherever it shows (`ShashaRoot`), the mini player too.
 * Back, and a drag down on the picture, collapse it into the mini player ([onCollapse]) instead of stopping it; in full screen
 * they come back upright first. The player (one ExoPlayer for the whole app) is attached to the same place in the tree in every
 * layout, so a rotation does not restart the video.
 */
@Composable
internal fun WatchScreen(
    container: AppContainer,
    vm: WatchViewModel,
    onCollapse: () -> Unit,
    /** Opens another video in place of this one (a related video, the next in the playlist). */
    onOpenVideo: (VideoItem, String?) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    /** Where the video is in the window: picture-in-picture grows its floating window out of it. */
    onVideoBounds: (Rect) -> Unit = {},
    /** The video's comments; null: none shown (the setting is off, or a downloaded file plays). */
    comments: CommentsViewModel? = null,
) {
    val controller = container.player
    val state by vm.player.collectAsStateWithLifecycle()
    // How the picture sits in its frame (pinch to zoom): kept for the run of the app, so the next video is as the viewer left it.
    val fit by container.videoFit.collectAsStateWithLifecycle()
    val bridge = LocalActivityBridge.current
    val inPip by bridge.inPictureInPicture.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFullscreen = isLandscape && !inPip
    var videoBounds by remember { mutableStateOf<Rect?>(null) }

    // Full screen hides the system bars; leaving the page puts everything back.
    DisposableEffect(isFullscreen) {
        bridge.setSystemBarsHidden(isFullscreen)
        onDispose { bridge.setSystemBarsHidden(false) }
    }
    DisposableEffect(Unit) {
        onDispose { bridge.requestOrientation(OrientationMode.AUTO) }
    }
    BackHandler(enabled = isFullscreen) { bridge.requestOrientation(OrientationMode.PORTRAIT) }
    // Back does not stop the video: it goes on in the mini player while the viewer goes on through the app.
    BackHandler(enabled = !isFullscreen && !inPip) { onCollapse() }

    // The page covers the app's pages under it: they get no touches while it is up (they stay composed, as the viewer left them).
    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
        WatchContent(
            state = state,
            player = controller.player,
            controller = controller,
            details = vm,
            isFullscreen = isFullscreen,
            inPip = inPip,
            onRetry = vm::retry,
            onBack = { if (isFullscreen) bridge.requestOrientation(OrientationMode.PORTRAIT) else onCollapse() },
            onToggleFullscreen = { bridge.requestOrientation(if (isFullscreen) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE) },
            onEnterPictureInPicture = if (bridge.supportsPictureInPicture && !state.isAudioOnly) {
                { bridge.enterPictureInPicture(state.aspectRatio, videoBounds) }
            } else {
                null
            },
            onVideoBounds = {
                videoBounds = it
                onVideoBounds(it)
            },
            // What the details list under the player holds is the playlist being played, or (without one) the related videos: a video taken
            // from the playlist stays in it, the next one still follows.
            onOpenVideo = { onOpenVideo(it, vm.playlistId) },
            onOpenChannel = onOpenChannel,
            fit = fit,
            onFitChange = { container.videoFit.value = it },
            onCollapse = onCollapse,
            comments = comments,
        )
    }
}

/**
 * What the player screen looks like for a given [state] (the screenshot tests draw it over fake ones). Portrait: the video
 * on top, then its title, actions, channel, description and the related videos (or the playlist). Landscape ([isFullscreen]):
 * the video fills the screen. In picture-in-picture ([inPip]) only the video shows.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun WatchContent(
    state: PlayerUiState,
    player: Player,
    controller: PlayerController,
    details: WatchDetailsSource,
    isFullscreen: Boolean,
    inPip: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onEnterPictureInPicture: (() -> Unit)?,
    onVideoBounds: (Rect) -> Unit,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    /** How the picture sits in its frame; pinching it asks for the other ([onFitChange]). */
    fit: VideoFit = VideoFit.FIT,
    onFitChange: (VideoFit) -> Unit = {},
    /**
     * Collapses the page into the mini player: a drag down on the picture (the page follows the finger) asks for it. Null: no
     * dragging. In full screen the same drag comes back upright ([onToggleFullscreen]).
     */
    onCollapse: (() -> Unit)? = null,
    /** The video's comments (a section under its details, all of them in a sheet); null: none. */
    comments: CommentsViewModel? = null,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    // How far the page has been dragged down by its picture (px), while the finger is on it.
    var dragged by remember { mutableFloatStateOf(0f) }
    val collapse by rememberUpdatedState(onCollapse)
    val exitFullscreen by rememberUpdatedState(onToggleFullscreen)
    val collapseDistance = with(LocalDensity.current) { COLLAPSE_DRAG.toPx() }
    val dragToCollapse = when {
        inPip || onCollapse == null -> Modifier
        isFullscreen -> Modifier.pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > collapseDistance) exitFullscreen() }) { change, dy ->
                total += dy
                change.consume()
            }
        }
        else -> Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragEnd = {
                    if (dragged > collapseDistance) collapse?.invoke()
                    dragged = 0f
                },
                onDragCancel = { dragged = 0f },
            ) { change, dy ->
                dragged = (dragged + dy).coerceAtLeast(0f)
                change.consume()
            }
        }
    }
    // «Zoomed to fill» / «Original», for a moment after a pinch changed the fit.
    var zoomHint by remember { mutableStateOf<VideoFit?>(null) }
    LaunchedEffect(zoomHint) {
        if (zoomHint != null) {
            delay(ZOOM_HINT_MS)
            zoomHint = null
        }
    }
    val playerModifier = when {
        inPip || isFullscreen -> Modifier.fillMaxSize()
        else -> Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(state.aspectRatio.coerceIn(0.8f, 16f / 9f))
    }

    Column(
        Modifier.fillMaxSize().offset { IntOffset(0, dragged.roundToInt()) }
            .background(if (isFullscreen || inPip) Color.Black else MaterialTheme.colorScheme.surface),
    ) {
        Box(Modifier.then(if (isFullscreen || inPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().background(Color.Black))) {
            Box(
                playerModifier.background(Color.Black)
                    .testTag(WATCH_VIDEO_TAG)
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        onVideoBounds(Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt()))
                    }
                    .then(dragToCollapse)
                    // A picture of a file that has no video (audio only) has nothing to zoom; in the floating window the system owns the touches.
                    .then(if (state.isAudioOnly || inPip) Modifier else Modifier.pinchToFit(fit) { next -> onFitChange(next); zoomHint = next }),
            ) {
                if (state.isAudioOnly) {
                    AsyncImage(
                        model = state.video?.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    ContentFrame(
                        player = player,
                        // Filling the frame overflows it: what is cropped must not be drawn outside.
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                        contentScale = fit.contentScale,
                        // Until the first frame is drawn the video's own picture stands in for it, not a black one.
                        shutter = {
                            AsyncImage(
                                model = state.video?.thumbnailUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = fit.contentScale,
                            )
                        },
                    )
                }
                if (state.phase == PlayerPhase.LOADING && !state.isOffline) {
                    AsyncImage(
                        model = state.video?.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = fit.contentScale,
                    )
                }
                if (state.phase == PlayerPhase.ERROR && state.error != null) {
                    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
                        ErrorState(state.error!!, onRetry = onRetry)
                    }
                    // The way out stays where the controls have it: collapse into the mini player (upright), or upright (full screen).
                    if (!inPip) {
                        IconButton(onClick = onBack, Modifier.align(Alignment.TopStart).padding(horizontal = 4.dp)) {
                            if (isFullscreen) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
                            } else {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.player_minimize), tint = Color.White)
                            }
                        }
                    }
                } else if (inPip) {
                    // The floating window has no controls, only the picture and what is said.
                    state.cueText?.let { CaptionText(it, small = true, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) }
                } else {
                    PlayerOverlay(
                        state = state,
                        player = player,
                        controller = controller,
                        title = state.video?.title.orEmpty(),
                        isFullscreen = isFullscreen,
                        onBack = onBack,
                        onToggleFullscreen = onToggleFullscreen,
                        onPictureInPicture = onEnterPictureInPicture,
                        onOpenSettings = { showSettings = true },
                    )
                }
                zoomHint?.let { shown ->
                    Text(
                        stringResource(if (shown == VideoFit.FILL) R.string.player_zoom_fill else R.string.player_zoom_fit),
                        // Between the row of buttons at the top and the play button in the middle.
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 50.dp)
                            .clip(RoundedCornerShape(16.dp)).background(Color(0xB3000000)).padding(horizontal = 14.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
        if (!isFullscreen && !inPip) {
            Details(
                details,
                state,
                onOpenVideo = onOpenVideo,
                onOpenChannel = onOpenChannel,
                comments = comments,
                onOpenComments = { showComments = true },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showComments && comments != null && !inPip) {
        CommentsSheet(comments, onDismiss = { showComments = false })
    }
    if (showSettings) {
        PlayerSettingsSheet(
            state = state,
            onSelectQuality = { controller.selectQuality(it) },
            onSelectSpeed = { controller.setSpeed(it) },
            onSelectSubtitles = { controller.selectSubtitles(it) },
            onDismiss = { showSettings = false },
        )
    }
}

/** Title, actions, channel, description and the related videos (or the playlist) under the player. */
@Composable
private fun Details(
    vm: WatchDetailsSource,
    state: PlayerUiState,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (ChannelItem) -> Unit,
    comments: CommentsViewModel?,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val actions = LocalVideoActions.current
    val related by vm.related.collectAsStateWithLifecycle()
    val queue by vm.queueState.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val saved by vm.savedForLater.collectAsStateWithLifecycle()
    val channel by vm.channel.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    val video = state.video
    val listState: LazyListState = rememberLazyListState()

    LazyColumn(modifier.navigationBarsPadding(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "title") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clickable { expanded = !expanded }.animateContentSize()) {
                Text(
                    video?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (video != null) {
                    val meta = listOfNotNull(
                        video.viewCount?.let { Formatters.views(context, it) },
                        video.publishedAtEpochMs?.let { Formatters.relativeTime(context, it, System.currentTimeMillis()) },
                    ).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.lowQualityHeight?.let {
                    Text(
                        stringResource(R.string.player_low_quality, it.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (expanded) {
                    state.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
        if (video != null) {
            item(key = "actions") {
                // Scrolls sideways where the buttons do not fit (a narrow phone, a long Arabic label).
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        icon = if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                        label = stringResource(if (saved) R.string.action_saved else R.string.action_watch_later),
                        onClick = { actions.toggleWatchLater(video) },
                    )
                    ActionButton(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.action_save_to_playlist)) { actions.addToPlaylist(video) }
                    if (!state.isOffline && !video.isLive) {
                        ActionButton(Icons.Filled.Download, stringResource(R.string.menu_download)) { actions.download(video) }
                    }
                    ActionButton(Icons.Filled.Share, stringResource(R.string.menu_share)) { actions.share(video) }
                }
            }
            val channelId = video.channelId
            if (channelId != null && !state.isOffline) {
                item(key = "channel") {
                    val channelName = video.channelName ?: channel?.name.orEmpty()
                    Row(
                        // The channel's page opens with what is known of it here: it shows that if YouTube's own page for the channel cannot be read.
                        Modifier.fillMaxWidth().clickable { onOpenChannel(ChannelItem(channelId, channelName, channel?.avatarUrl, channel?.subscriberCount)) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(channel?.avatarUrl, size = 40, name = channelName)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(channelName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            channel?.subscriberCount?.let {
                                Text(Formatters.subscribers(context, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (subscribed) {
                            OutlinedButton(onClick = vm::toggleSubscribe) { Text(stringResource(R.string.action_unsubscribe)) }
                        } else {
                            Button(onClick = vm::toggleSubscribe) { Text(stringResource(R.string.action_subscribe)) }
                        }
                    }
                }
            }
        }
        if (comments != null && !state.isOffline) {
            item(key = "comments") { CommentsTeaser(comments, onOpen = onOpenComments, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        }
        item(key = "divider") { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        if (vm.playlistId != null) {
            item(key = "queue-title") {
                Text(stringResource(R.string.watch_playlist), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(queue.items, key = { "q:${it.videoId}" }) { item ->
                val current = item.videoId == vm.videoId
                VideoListItem(
                    item,
                    onClick = { if (!current) onOpenVideo(item) },
                    modifier = if (current) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier,
                )
            }
            item(key = "queue-footer") { PagedFooter(queue.loading, queue.error, onRetry = vm::loadMoreQueue) }
        } else if (!state.isOffline) {
            item(key = "related-title") {
                Text(stringResource(R.string.watch_related), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            when (val loaded = related) {
                Loadable.Loading -> item(key = "related-loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(28.dp)) }
                }
                is Loadable.Failed -> item(key = "related-error") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = vm::retryRelated) { Text(stringResource(R.string.action_retry)) }
                    }
                }
                is Loadable.Loaded -> items(loaded.value, key = { "r:${it.videoId}" }) { item -> VideoListItem(item, onClick = { onOpenVideo(item) }) }
            }
        }
    }
}

/** One of the buttons under the player. Snug, so that the four of them (Watch later, Save, Download, Share) fit a phone's width in Arabic too. */
@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Speed (0.5× to 4×), captions (off, or one of the video's languages) and quality (automatic, or one of the video's): the player
 * screen's and the Shorts viewer's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsSheet(
    state: PlayerUiState,
    onSelectQuality: (Int?) -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectSubtitles: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.player_speed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PlaybackSpeeds.ALL) { speed ->
                    FilterChip(selected = speed == state.speed, onClick = { onSelectSpeed(speed) }, label = { Text(PlaybackSpeeds.label(speed)) })
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.player_captions), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            if (state.subtitles.isEmpty()) {
                Text(stringResource(R.string.player_captions_none), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val locale = LocalConfiguration.current.locales[0]
                QualityRow(stringResource(R.string.player_captions_off), selected = state.subtitleLanguage == null) {
                    onSelectSubtitles(null)
                    onDismiss()
                }
                for (track in state.subtitles) {
                    val name = captionName(track, locale)
                    QualityRow(
                        label = if (track.isAutoGenerated) stringResource(R.string.player_captions_auto, name) else name,
                        selected = state.subtitleLanguage == track.languageCode,
                        onClick = { onSelectSubtitles(track.languageCode); onDismiss() },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.player_quality), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            if (state.qualities.isEmpty()) {
                Text(stringResource(R.string.player_quality_none), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                QualityRow(
                    label = if (state.pinnedQuality == null && state.playingHeight != null) {
                        stringResource(R.string.quality_auto_playing, state.playingHeight.toString())
                    } else {
                        stringResource(R.string.quality_auto)
                    },
                    selected = state.pinnedQuality == null,
                    onClick = { onSelectQuality(null); onDismiss() },
                )
                for (option in state.qualities) {
                    QualityRow(
                        label = Formatters.quality(option.height, option.fps),
                        selected = state.pinnedQuality == option.height,
                        onClick = { onSelectQuality(option.height); onDismiss() },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun QualityRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Whether leaving the app now (Home, the gesture, the recent apps) turns the player into a floating window: when the setting is
 * on and a video with a picture is meant to play. Meant to, not playing this very moment: a video that waits for data
 * ([PlayerUiState.isBuffering]) is still the one the viewer is watching, and leaving it then used to keep only its sound.
 */
internal fun pictureInPictureOnLeave(state: PlayerUiState, setting: Boolean): Boolean =
    setting && !state.isAudioOnly && state.playWhenReady && state.phase == PlayerPhase.READY

/**
 * A captions language as the viewer reads it: its name in the app's own language (YouTube's label is in Arabic for every viewer),
 * else YouTube's label.
 */
internal fun captionName(track: SubtitleTrack, locale: Locale): String {
    val name = Locale.forLanguageTag(track.languageCode).getDisplayName(locale)
    return if (name.isNotBlank() && !name.equals(track.languageCode, ignoreCase = true)) {
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    } else {
        track.label.ifBlank { track.languageCode }
    }
}

/** What is said in the video, over its picture: white on a dark band, readable over any picture. */
@Composable
internal fun CaptionText(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 12.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xB3000000))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
    )
}

/** The watch page's picture, for the tests. */
internal const val WATCH_VIDEO_TAG = "watch_video"

/** How far the picture is dragged down to collapse the page into the mini player (or to leave full screen). */
private val COLLAPSE_DRAG = 96.dp

/** How long «Zoomed to fill» / «Original» stays on the picture after a pinch. */
private const val ZOOM_HINT_MS = 1_400L
