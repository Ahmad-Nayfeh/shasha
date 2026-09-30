package com.ahmadnayfeh.shasha.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.format.Formats
import com.ahmadnayfeh.shasha.ui.model.ChannelUi
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.model.PlaylistUi
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.youtube.ChannelItem

/**
 * Video cards with stable keys. [watched] maps video ids to watched fractions; each card reads only its
 * own entry (derivedStateOf), so saving one video's progress does not recompose the whole grid.
 * [firstItem] is attached to the first card: where focus goes when the grid is entered the first time.
 */
fun LazyGridScope.videoCards(
    items: List<VideoUi>,
    watched: State<Map<String, Float>>,
    formats: Formats,
    nowMs: Long,
    firstItem: FocusRequester?,
    showChannel: Boolean = true,
    onPlay: (VideoUi) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, _ -> "video" }) { index, item ->
        val id = item.video.videoId
        val fraction by remember(id, watched) { derivedStateOf { watched.value[id] } }
        VideoCard(
            item = item,
            watched = fraction,
            formats = formats,
            nowMs = nowMs,
            onClick = { onPlay(item) },
            thumbnailModifier = if (index == 0 && firstItem != null) Modifier.focusRequester(firstItem) else Modifier,
            showChannel = showChannel,
        )
    }
}

/**
 * Mixed results (search): video cards, channel cards with a subscribe button under them, playlist
 * cards. [subscribed] holds the subscribed channel ids; each channel card reads only its own state.
 */
fun LazyGridScope.resultCards(
    items: List<ItemUi>,
    watched: State<Map<String, Float>>,
    subscribed: State<Set<String>>,
    formats: Formats,
    nowMs: Long,
    firstItem: FocusRequester?,
    onOpen: (ItemUi) -> Unit,
    onToggleSubscription: (ChannelItem) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> item.key }, contentType = { _, item -> item::class }) { index, item ->
        val first = if (index == 0 && firstItem != null) Modifier.focusRequester(firstItem) else Modifier
        when (item) {
            is VideoUi -> {
                val id = item.video.videoId
                val fraction by remember(id, watched) { derivedStateOf { watched.value[id] } }
                VideoCard(item, fraction, formats, nowMs, onClick = { onOpen(item) }, thumbnailModifier = first)
            }
            is ChannelUi -> {
                val id = item.channel.channelId
                val isSubscribed by remember(id, subscribed) { derivedStateOf { id in subscribed.value } }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelCard(
                        item.channel,
                        subtitle = item.channel.subscriberCount?.let(formats::subscribers),
                        onClick = { onOpen(item) },
                        tileModifier = first,
                    )
                    Spacer(Modifier.height(10.dp))
                    SubscribeButton(isSubscribed, onClick = { onToggleSubscription(item.channel) }, compact = true)
                }
            }
            is PlaylistUi -> PlaylistCard(item, formats, onClick = { onOpen(item) }, tileModifier = first)
        }
    }
}

/** «اشتراك» (teal) or «مشترك» with a check; pressing it again unsubscribes. */
@Composable
fun SubscribeButton(subscribed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    ShashaButton(
        text = stringResource(if (subscribed) R.string.action_subscribed else R.string.action_subscribe),
        onClick = onClick,
        icon = if (subscribed) ShashaIcons.Check else ShashaIcons.Add,
        primary = !subscribed,
        compact = compact,
        modifier = modifier.testTag("subscribe_button"),
    )
}
