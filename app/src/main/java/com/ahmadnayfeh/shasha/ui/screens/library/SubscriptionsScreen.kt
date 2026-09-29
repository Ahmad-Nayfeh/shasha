package com.ahmadnayfeh.shasha.ui.screens.library

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ChannelCard
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.EmptyState
import com.ahmadnayfeh.shasha.ui.components.FocusArea
import com.ahmadnayfeh.shasha.ui.components.GridDefaults
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaGrid
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.TakeFocusWhenReady
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.components.fullWidthItem
import com.ahmadnayfeh.shasha.ui.format.rememberFormats
import com.ahmadnayfeh.shasha.ui.model.ChannelUi

@Composable
fun SubscriptionsRoute(focus: ContentFocus, onOpenChannel: (channelId: String, name: String) -> Unit, onImport: () -> Unit, onSearch: () -> Unit) {
    val vm = containerViewModel { SubscriptionsViewModel(it.subscriptions, it.policy.filter) }
    val channels by vm.channels.collectAsStateWithLifecycle()
    SubscriptionsScreen(channels, focus, onOpenChannel, onImport, onSearch)
}

/** الاشتراكات: every subscribed channel; OK opens its page (where it can be unsubscribed). */
@Composable
fun SubscriptionsScreen(
    channels: List<ChannelUi>?,
    focus: ContentFocus,
    onOpenChannel: (channelId: String, name: String) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formats = rememberFormats()
    TakeFocusWhenReady(focus, ready = channels != null)
    when {
        channels == null -> Unit
        channels.isEmpty() -> {
            val importButton = remember { FocusRequester() }
            FocusArea(focus, importButton, modifier.fillMaxSize().padding(GridDefaults.ContentPadding), Alignment.Center) {
                EmptyState(
                    ShashaIcons.Subscriptions,
                    stringResource(R.string.subscriptions_empty_title),
                    stringResource(R.string.subscriptions_empty_body),
                ) {
                    ShashaButton(stringResource(R.string.home_empty_import), onImport, icon = ShashaIcons.Download, primary = true, modifier = Modifier.focusRequester(importButton))
                    ShashaButton(stringResource(R.string.home_empty_search), onSearch, icon = ShashaIcons.Search)
                }
            }
        }
        else -> {
            val gridState = rememberLazyGridState()
            val firstCard = remember { FocusRequester() }
            ShashaGrid(gridState, focus, firstCard, modifier.fillMaxSize().testTag("subscriptions")) {
                fullWidthItem("header") {
                    ScreenHeader(stringResource(R.string.subscriptions_title), formats.count(R.array.count_channels, channels.size.toLong())) {
                        ShashaButton(stringResource(R.string.subscriptions_import), onImport, icon = ShashaIcons.Download)
                    }
                }
                itemsIndexed(channels, key = { _, it -> it.key }, contentType = { _, _ -> "channel" }) { index, item ->
                    ChannelCard(
                        item.channel,
                        subtitle = null,
                        onClick = { onOpenChannel(item.channel.channelId, item.channel.name) },
                        tileModifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                    )
                }
            }
        }
    }
}
