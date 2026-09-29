package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ChannelAvatar
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.PagingFooter
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.SubscribeButton
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.rememberNow
import com.ahmadnayfeh.shasha.ui.components.videoCards
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.paging.LoadMoreWhenNearEnd
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.error.ErrorScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import com.ahmadnayfeh.shasha.youtube.VideoItem

@Composable
fun ChannelRoute(channelId: String, nameHint: String?, onPlay: (VideoItem) -> Unit, onBack: () -> Unit, onCheckUpdates: () -> Unit) {
    val vm = containerViewModel("channel:$channelId") { ChannelViewModel(channelId, it.videoSource, it.subscriptions, it.watch, it.appScope) }
    val header by vm.header.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val subscribed by vm.isSubscribed.collectAsStateWithLifecycle()
    ChannelScreen(
        channelId = channelId,
        nameHint = nameHint,
        header = header,
        videos = videos,
        subscribed = subscribed,
        watched = vm.watched.collectAsStateWithLifecycle(),
        nowMs = rememberNow(),
        focus = remember { ContentFocus() },
        extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
        onToggleSubscription = vm::toggleSubscription,
        onUnsubscribe = {
            vm.unsubscribe()
            onBack()
        },
        onPlay = onPlay,
        onLoadMore = vm::loadMore,
        onRetryMore = vm::retryMore,
        onBack = onBack,
        onCheckUpdates = onCheckUpdates,
    )
}

/** A channel: banner, picture, name and the subscribe toggle, then its videos (newest first). */
@Composable
fun ChannelScreen(
    channelId: String,
    nameHint: String?,
    header: ChannelHeader,
    videos: PagedState<VideoUi>,
    subscribed: Boolean,
    watched: State<Map<String, Float>>,
    nowMs: Long,
    focus: ContentFocus,
    extractorVersion: String,
    onToggleSubscription: () -> Unit,
    onUnsubscribe: () -> Unit,
    onPlay: (VideoItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMore: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = (header as? ChannelHeader.Failed)?.error ?: videos.error
    if (error != null) {
        ErrorScreen(error, extractorVersion, onCheckUpdates, onBack, modifier) {
            // A subscribed channel that is gone (deleted, terminated) can only be removed from here.
            if (subscribed) {
                ShashaButton(
                    stringResource(R.string.channel_unsubscribe),
                    onUnsubscribe,
                    icon = ShashaIcons.Delete,
                    modifier = Modifier.testTag("channel_unsubscribe"),
                )
            }
        }
        return
    }
    val formats = rememberFormats()
    val gridState = rememberLazyGridState()
    val firstCard = remember { FocusRequester() }
    TakeFocusWhenReady(focus, ready = header is ChannelHeader.Ready && !videos.loadingFirst)
    LoadMoreWhenNearEnd(gridState, onLoadMore = onLoadMore)
    ShashaGrid(
        gridState,
        focus,
        firstCard,
        modifier.fillMaxSize().testTag("channel_screen"),
        contentPadding = PaddingValues(horizontal = ShashaDimens.ScreenHorizontal, vertical = 40.dp),
    ) {
        fullWidthItem("header") { Header(channelId, nameHint, header, subscribed, onToggleSubscription, formats) }
        when {
            videos.loadingFirst -> fullWidthItem("loading") {
                Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                    ShashaLoaderWithText(stringResource(R.string.channel_loading))
                }
            }
            videos.items.isEmpty() -> fullWidthItem("empty") {
                Text(
                    stringResource(R.string.channel_no_videos),
                    style = MaterialTheme.typography.titleMedium,
                    color = ShashaColors.Muted,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            // Every video here is the channel's own: the cards show only when each was published.
            else -> videoCards(videos.items, watched, formats, nowMs, firstCard, showChannel = false) { onPlay(it.video) }
        }
        if (videos.loadingMore || videos.moreError != null) {
            fullWidthItem("footer") { PagingFooter(videos.loadingMore, videos.moreError, onRetryMore, extractorVersion, onCheckUpdates) }
        }
    }
}

private val BannerHeight = 132.dp
private val AvatarSize = 92.dp

@Composable
private fun Header(
    channelId: String,
    nameHint: String?,
    header: ChannelHeader,
    subscribed: Boolean,
    onToggleSubscription: () -> Unit,
    formats: Formats,
) {
    val details = (header as? ChannelHeader.Ready)?.details
    val name = details?.channel?.name ?: nameHint.orEmpty()
    // The picture straddles the banner's lower edge; name, subscribers and the toggle sit under it.
    Box(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Banner(details?.bannerUrl)
        Row(
            Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = BannerHeight - AvatarSize / 2),
            verticalAlignment = Alignment.Top,
        ) {
            ChannelAvatar(
                details?.channel?.avatarUrl,
                name,
                channelId,
                AvatarSize,
                Modifier.border(4.dp, ShashaColors.Background, CircleShape),
            )
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f).padding(top = AvatarSize / 2 + 6.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.ContentOrRtl),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                details?.channel?.subscriberCount?.let {
                    Text(formats.subscribers(it), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
                }
            }
            if (details != null) {
                SubscribeButton(subscribed, onToggleSubscription, Modifier.padding(top = AvatarSize / 2 + 14.dp))
            }
        }
    }
}

/** The channel's banner, or a quiet brand gradient when it has none; fades into the page at the bottom. */
@Composable
private fun Banner(url: String?) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0E3B37), ShashaColors.SurfaceVariant, ShashaColors.Surface))),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ShashaColors.Background.copy(alpha = 0.55f)))))
    }
}
