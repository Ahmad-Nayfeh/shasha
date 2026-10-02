package com.ahmadnayfeh.shasha.ui.screens.channel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.ahmadnayfeh.shasha.policy.Decision
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.ui.components.ChannelAvatar
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.LocalCollectionMenu
import com.ahmadnayfeh.shasha.ui.components.MenuTarget
import com.ahmadnayfeh.shasha.ui.components.PagingFooter
import com.ahmadnayfeh.shasha.ui.components.PolicyNotice
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaLoaderWithText
import com.ahmadnayfeh.shasha.ui.components.ShelfTitle
import com.ahmadnayfeh.shasha.ui.components.ShortsShelf
import com.ahmadnayfeh.shasha.ui.components.SubscribeButton
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.components.gridColumns
import com.ahmadnayfeh.shasha.ui.components.shortCardWidth
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

/**
 * [adult]: the adult profile's page, with the subscribe toggle and the parental menu (allowlists, block).
 * A child's page has neither: nothing to manage.
 */
@Composable
fun ChannelRoute(channelId: String, nameHint: String?, adult: Boolean, onPlay: (VideoItem) -> Unit, onBack: () -> Unit, onCheckUpdates: () -> Unit) {
    val vm = containerViewModel("channel:$channelId") {
        ChannelViewModel(channelId, it.videoSource, it.subscriptions, it.watch, it.appScope, it.policy.filter)
    }
    val access by vm.access.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val videos by vm.videos.collectAsStateWithLifecycle()
    val shorts by vm.shorts.collectAsStateWithLifecycle()
    val subscribed by vm.isSubscribed.collectAsStateWithLifecycle()
    val openMenu = LocalCollectionMenu.current
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
        access = access,
        adult = adult,
        onParental = { (header as? ChannelHeader.Ready)?.let { openMenu(MenuTarget.Channel(it.details.channel)) } },
        shorts = shorts,
    )
}

/**
 * A channel: banner, picture, name and (adult profile) the subscribe toggle and «الرقابة», then its videos
 * (newest first). A channel the profile may not open shows why instead ([access]). Its [shorts], where the profile
 * shows them, are a row under the videos' first line.
 */
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
    access: Decision? = Decision.Allow,
    adult: Boolean = true,
    onParental: () -> Unit = {},
    shorts: List<VideoUi> = emptyList(),
) {
    when (access) {
        null -> return // the profile's rules are a moment away
        is Decision.Deny -> {
            PolicyNotice(access.reason, onBack, modifier) {
                // Blocked for the household while the adult still follows it: the page never opens, so leave here.
                if (subscribed && adult && access.reason == DenyReason.BLOCKED_CHANNEL) {
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
        Decision.Allow -> Unit
    }
    val error = (header as? ChannelHeader.Failed)?.error ?: videos.error
    if (error != null) {
        ErrorScreen(error, extractorVersion, onCheckUpdates, onBack, modifier) {
            // A subscribed channel that is gone (deleted, terminated) can only be removed from here.
            if (subscribed && adult) {
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
    val padding = PaddingValues(horizontal = ShashaDimens.ScreenHorizontal, vertical = 40.dp)
    BoxWithConstraints(modifier.fillMaxSize()) {
        // The Shorts row comes after the first line of videos: the newest upload stays on top.
        val columns = gridColumns(maxWidth, padding)
        ShashaGrid(gridState, focus, firstCard, Modifier.fillMaxSize().testTag("channel_screen"), contentPadding = padding) {
            fullWidthItem("header") { Header(channelId, nameHint, header, subscribed, onToggleSubscription, formats, adult, onParental) }
            when {
                // Also while the first pages are all hidden by the rules and more are on their way: not "no videos" yet.
                videos.loadingFirst || (videos.items.isEmpty() && shorts.isEmpty() && videos.loadingMore) -> fullWidthItem("loading") {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        ShashaLoaderWithText(stringResource(R.string.channel_loading))
                    }
                }
                videos.items.isEmpty() && shorts.isEmpty() -> fullWidthItem("empty") {
                    Text(
                        stringResource(R.string.channel_no_videos),
                        style = MaterialTheme.typography.titleMedium,
                        color = ShashaColors.Muted,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
                else -> {
                    // Every video here is the channel's own: the cards show only when each was published.
                    val firstLine = videos.items.take(columns)
                    videoCards(firstLine, watched, formats, nowMs, firstCard, showChannel = false) { onPlay(it.video) }
                    if (shorts.isNotEmpty()) {
                        fullWidthItem("shorts") {
                            ShortsShelf(
                                title = stringResource(R.string.channel_shorts_title),
                                subtitle = null,
                                items = shorts,
                                watched = watched,
                                cardWidth = shortCardWidth(maxWidth, padding),
                                onPlay = { onPlay(it.video) },
                                first = firstCard.takeIf { firstLine.isEmpty() },
                                showChannel = false,
                                modifier = Modifier.testTag("channel_shorts"),
                            )
                        }
                        if (videos.items.size > columns) fullWidthItem("videos_title") { ShelfTitle(stringResource(R.string.channel_videos_title)) }
                    }
                    videoCards(videos.items.drop(columns), watched, formats, nowMs, firstItem = null, showChannel = false) { onPlay(it.video) }
                }
            }
            if (videos.loadingMore || videos.moreError != null) {
                fullWidthItem("footer") { PagingFooter(videos.loadingMore, videos.moreError, onRetryMore, extractorVersion, onCheckUpdates) }
            }
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
    adult: Boolean,
    onParental: () -> Unit,
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
                    style = MaterialTheme.typography.headlineMedium.copy(textDirection = TextDirection.Content),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                details?.channel?.subscriberCount?.let {
                    Text(formats.subscribers(it), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
                }
            }
            if (details != null && adult) {
                Row(Modifier.padding(top = AvatarSize / 2 + 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SubscribeButton(subscribed, onToggleSubscription)
                    ShashaButton(
                        stringResource(R.string.channel_parental),
                        onParental,
                        icon = ShashaIcons.Shield,
                        modifier = Modifier.testTag("channel_parental"),
                    )
                }
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
            .background(Brush.linearGradient(listOf(if (ShashaColors.isLight) Color(0xFFCDEEEA) else Color(0xFF0E3B37), ShashaColors.SurfaceVariant, ShashaColors.Surface))),
    ) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, ShashaColors.Background.copy(alpha = 0.55f)))))
    }
}
