package com.ahmadnayfeh.shasha.mobile.ui.watch

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.common.Formatters
import com.ahmadnayfeh.shasha.mobile.common.Loadable
import com.ahmadnayfeh.shasha.mobile.player.PlaybackSpeeds
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.LocalActivityBridge
import com.ahmadnayfeh.shasha.mobile.ui.OrientationMode
import com.ahmadnayfeh.shasha.mobile.ui.components.Avatar
import com.ahmadnayfeh.shasha.mobile.ui.components.ErrorState
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.PagedFooter
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoListItem
import com.ahmadnayfeh.shasha.mobile.ui.nav.WatchRoute
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlin.math.roundToInt

/**
 * The player screen. Portrait: the video on top, then its title, actions, channel, description and the related videos (or
 * the playlist). Landscape: the video fills the screen with the system bars hidden. In picture-in-picture only the video
 * shows. The player (one ExoPlayer for the whole app) is attached to the same place in the tree in every layout, so a
 * rotation does not restart the video.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun WatchScreen(
    container: AppContainer,
    route: WatchRoute,
    onBack: () -> Unit,
    /** Opens another video in place of this one (a related video, the next in the playlist). */
    onOpenVideo: (VideoItem, String?) -> Unit,
    onOpenChannel: (String) -> Unit,
) {
    val vm: WatchViewModel = viewModel(
        key = "watch:${route.videoId}:${route.downloadId}:${route.playlistId}",
        factory = WatchViewModel.factory(
            container, route.videoId, route.playlistId, route.downloadId.takeIf { it >= 0 }, route.startSeconds.takeIf { it > 0 }?.times(1000),
        ),
    )
    val controller = container.player
    val state by vm.player.collectAsStateWithLifecycle()
    val settings by container.settingsFlow.collectAsStateWithLifecycle()
    val bridge = LocalActivityBridge.current
    val inPip by bridge.inPictureInPicture.collectAsStateWithLifecycle()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isFullscreen = isLandscape && !inPip
    var showSettings by remember { mutableStateOf(false) }
    // Where the video is in the window: picture-in-picture grows its floating window out of it.
    var videoBounds by remember { mutableStateOf<Rect?>(null) }
    val player = controller.player
    val view = LocalView.current

    // Back from a detour (this video's channel, then another video): the player may have been let go meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onShown() }
    // Playlists go on by themselves.
    LaunchedEffect(vm) {
        vm.eventFlow.collect { event ->
            if (event is WatchEvent.PlayNext) onOpenVideo(event.video, vm.playlistId)
        }
    }
    // Full screen hides the system bars; leaving the screen puts everything back.
    DisposableEffect(isFullscreen) {
        bridge.setSystemBarsHidden(isFullscreen)
        onDispose { bridge.setSystemBarsHidden(false) }
    }
    DisposableEffect(Unit) {
        onDispose {
            bridge.requestOrientation(OrientationMode.AUTO)
            bridge.setPictureInPicture(autoEnter = false, aspect = 16f / 9f, sourceRect = null)
        }
    }
    // Leaving the app while a video plays turns it into a floating window.
    LaunchedEffect(state.isPlaying, state.aspectRatio, state.isAudioOnly, settings.pictureInPicture, videoBounds) {
        bridge.setPictureInPicture(
            autoEnter = settings.pictureInPicture && state.isPlaying && !state.isAudioOnly && state.isVideoShown,
            aspect = state.aspectRatio,
            sourceRect = videoBounds,
        )
    }
    // The screen stays on while the video plays.
    DisposableEffect(state.isPlaying) {
        view.keepScreenOn = state.isPlaying
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = isFullscreen) { bridge.requestOrientation(OrientationMode.PORTRAIT) }

    val playerModifier = when {
        inPip || isFullscreen -> Modifier.fillMaxSize()
        else -> Modifier.fillMaxWidth().statusBarsPadding().aspectRatio(state.aspectRatio.coerceIn(0.8f, 16f / 9f))
    }

    Column(Modifier.fillMaxSize().background(if (isFullscreen || inPip) Color.Black else MaterialTheme.colorScheme.surface)) {
        Box(Modifier.then(if (isFullscreen || inPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().background(Color.Black))) {
            Box(
                playerModifier.background(Color.Black).onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInWindow()
                    videoBounds = Rect(bounds.left.roundToInt(), bounds.top.roundToInt(), bounds.right.roundToInt(), bounds.bottom.roundToInt())
                },
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
                        modifier = Modifier.fillMaxSize(),
                        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                        contentScale = ContentScale.Fit,
                    )
                }
                if (state.phase == PlayerPhase.LOADING && !state.isOffline) {
                    AsyncImage(
                        model = state.video?.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
                if (state.phase == PlayerPhase.ERROR && state.error != null) {
                    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
                        ErrorState(state.error!!, onRetry = vm::retry)
                    }
                } else if (!inPip) {
                    PlayerOverlay(
                        state = state,
                        player = player,
                        controller = controller,
                        title = state.video?.title.orEmpty(),
                        isFullscreen = isFullscreen,
                        onBack = {
                            if (isFullscreen) bridge.requestOrientation(OrientationMode.PORTRAIT) else onBack()
                        },
                        onToggleFullscreen = {
                            bridge.requestOrientation(if (isFullscreen) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE)
                        },
                        onPictureInPicture = if (bridge.supportsPictureInPicture && !state.isAudioOnly) {
                            { bridge.enterPictureInPicture(state.aspectRatio, videoBounds) }
                        } else {
                            null
                        },
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
        if (!isFullscreen && !inPip) {
            Details(vm, state, onOpenVideo = { onOpenVideo(it, null) }, onOpenChannel = onOpenChannel, modifier = Modifier.weight(1f))
        }
    }

    if (showSettings) {
        PlayerSettingsSheet(
            state = state,
            onSelectQuality = { controller.selectQuality(it) },
            onSelectSpeed = { controller.setSpeed(it) },
            onDismiss = { showSettings = false },
        )
    }
}

/** Title, actions, channel, description and the related videos (or the playlist) under the player. */
@Composable
private fun Details(
    vm: WatchViewModel,
    state: PlayerUiState,
    onOpenVideo: (VideoItem) -> Unit,
    onOpenChannel: (String) -> Unit,
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(
                        icon = if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                        label = stringResource(if (saved) R.string.action_saved else R.string.action_watch_later),
                        onClick = { actions.toggleWatchLater(video) },
                    )
                    if (!state.isOffline && !video.isLive) {
                        ActionButton(Icons.Filled.Download, stringResource(R.string.menu_download)) { actions.download(video) }
                    }
                    ActionButton(Icons.Filled.Share, stringResource(R.string.menu_share)) { actions.share(video) }
                }
            }
            val channelId = video.channelId
            if (channelId != null && !state.isOffline) {
                item(key = "channel") {
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenChannel(channelId) }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(channel?.avatarUrl, size = 40)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(video.channelName ?: channel?.name.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Quality (automatic, or one of the video's) and speed (0.5× to 4×). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    state: PlayerUiState,
    onSelectQuality: (Int?) -> Unit,
    onSelectSpeed: (Float) -> Unit,
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
