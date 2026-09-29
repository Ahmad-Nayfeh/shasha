package com.ahmadnayfeh.shasha.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.ChannelAvatar
import com.ahmadnayfeh.shasha.ui.components.FocusSurface
import com.ahmadnayfeh.shasha.ui.components.LocalAppMessages
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ScreenHeader
import com.ahmadnayfeh.shasha.ui.components.ShashaButton
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.ShashaTextField
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.theme.Accents
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.ui.theme.ShashaDimens
import kotlinx.coroutines.launch

@Composable
fun AllowlistRoute(profileId: String) {
    val vm = containerViewModel("allowlist:$profileId") { AllowlistViewModel(profileId, it.profiles, it.subscriptions) }
    val state by vm.state.collectAsStateWithLifecycle()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val profile = state.profile ?: return
    var picking by rememberSaveable { mutableStateOf(false) }
    AllowlistScreen(
        profile = profile,
        onRemove = { id, name ->
            vm.remove(id)
            messages?.show(resources.getString(R.string.message_allowlist_removed, "«⁨$name⁩»", profile.name))
        },
        onAddFromSubscriptions = { picking = true },
    )
    if (picking) {
        SubscriptionsPicker(profile, state.subscriptions, onToggle = vm::toggle, onDismiss = { picking = false })
    }
}

/**
 * A child's allowlist (SPEC §3: only these channels and playlists): OK on a row removes it. Adding is done
 * where the channels are (a card's menu, a channel's page, the phone) or here from the subscriptions.
 */
@Composable
fun AllowlistScreen(
    profile: Profile,
    onRemove: (id: String, name: String) -> Unit,
    onAddFromSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val add = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { add.requestFocus() }
    }
    val allowlist = profile.allowlist
    CompositionLocalProvider(LocalAccent provides Accents.Kids) {
        Column(modifier.fillMaxSize().padding(ShashaDimens.ScreenPadding).testTag("allowlist")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(profile.avatar, profile.name, 56.dp)
                Spacer(Modifier.width(18.dp))
                ScreenHeader(
                    stringResource(R.string.allowlist_title, profile.name),
                    allowlistText(profile),
                    modifier = Modifier.weight(1f),
                ) {
                    ShashaButton(
                        stringResource(R.string.allowlist_add_from_subscriptions),
                        onAddFromSubscriptions,
                        icon = ShashaIcons.Add,
                        primary = true,
                        modifier = Modifier.focusRequester(add).testTag("allowlist_add"),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (allowlist.channels.isEmpty() && allowlist.playlists.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.allowlist_empty_body, profile.name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = ShashaColors.Muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(600.dp),
                    )
                }
            } else {
                Row(Modifier.fillMaxWidth().weight(1f).focusGroup(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    ListColumn(stringResource(R.string.allowlist_channels), Modifier.weight(1f)) {
                        items(allowlist.channels, key = { it.channelId }) { channel ->
                            RemovableRow(
                                title = channel.name,
                                action = stringResource(R.string.action_remove),
                                onClick = { onRemove(channel.channelId, channel.name) },
                                modifier = Modifier.testTag("allowed_${channel.channelId}"),
                            ) { ChannelAvatar(null, channel.name, channel.channelId, 36.dp) }
                        }
                    }
                    ListColumn(stringResource(R.string.allowlist_playlists), Modifier.weight(1f)) {
                        items(allowlist.playlists, key = { it.playlistId }) { playlist ->
                            RemovableRow(
                                title = playlist.title,
                                action = stringResource(R.string.action_remove),
                                onClick = { onRemove(playlist.playlistId, playlist.title) },
                                modifier = Modifier.testTag("allowed_${playlist.playlistId}"),
                            ) { Icon(ShashaIcons.PlaylistPlay, contentDescription = null, tint = ShashaColors.Muted, modifier = Modifier.size(28.dp)) }
                        }
                        if (allowlist.playlists.isEmpty()) {
                            item { Text(stringResource(R.string.allowlist_no_playlists), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.allowlist_how_to_add), style = MaterialTheme.typography.bodySmall, color = ShashaColors.Muted)
        }
    }
}

@Composable
private fun ListColumn(title: String, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Column(modifier.fillMaxHeight()) {
        SectionLabel(title)
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** A list row whose OK is its [action] («إزالة», «إلغاء الحظر»), spelled out on the row; [subtitle] under the title. */
@Composable
private fun RemovableRow(
    title: String,
    action: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable () -> Unit,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.Surface,
        focusedContainerColor = ShashaColors.SurfaceHigh,
        scale = 1.02f,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            leading()
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl),
                    // A Latin name lines up with the Arabic ones, on the right.
                    textAlign = TextAlign.Right,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall, color = ShashaColors.Muted, maxLines = 1)
                }
            }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier
                    .background(ShashaColors.Error.copy(alpha = 0.14f), RoundedCornerShape(percent = 50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(ShashaIcons.Close, contentDescription = null, tint = ShashaColors.Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(action, style = MaterialTheme.typography.labelMedium, color = ShashaColors.Error)
            }
        }
    }
}

/** The adult's subscriptions, each one OK away from the child's allowlist (or off it again). */
@Composable
private fun SubscriptionsPicker(profile: Profile, subscriptions: List<ListedChannel>, onToggle: (ListedChannel) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(LocalAccent provides Accents.Kids) {
            SubscriptionsPickerPanel(profile, subscriptions, onToggle)
        }
    }
}

@Composable
fun SubscriptionsPickerPanel(profile: Profile, subscriptions: List<ListedChannel>, onToggle: (ListedChannel) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val accent = LocalAccent.current
    DialogCard(stringResource(R.string.allowlist_pick_title, profile.name), Modifier.testTag("subscriptions_picker"), width = 520.dp) {
        if (subscriptions.isEmpty()) {
            Text(stringResource(R.string.allowlist_pick_empty), style = MaterialTheme.typography.bodyLarge, color = ShashaColors.Muted, textAlign = TextAlign.Center)
            return@DialogCard
        }
        LazyColumn(
            Modifier.heightIn(max = 380.dp).focusRestorer(first).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            contentPadding = PaddingValues(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(subscriptions, key = { _, it -> it.channelId }) { index, channel ->
                val allowed = profile.allowlist.hasChannel(channel.channelId)
                FocusSurface(
                    onClick = { onToggle(channel) },
                    modifier = Modifier.fillMaxWidth().height(56.dp).then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = if (allowed) accent.tint(0.12f) else ShashaColors.SurfaceVariant,
                    focusedContainerColor = ShashaColors.SurfaceHigh,
                    scale = 1.03f,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelAvatar(null, channel.name, channel.channelId, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            channel.name,
                            style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.ContentOrRtl),
                            textAlign = TextAlign.Right,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(allowed)
                    }
                }
            }
        }
    }
}

@Composable
fun BlocklistRoute() {
    val vm = containerViewModel { BlocklistViewModel(it.profiles, it.subscriptions, it.appScope) }
    val blocklist by vm.blocklist.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val messages = LocalAppMessages.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    BlocklistScreen(
        blocklist = blocklist ?: return,
        subscribed = subscribed,
        onUnblock = { channel ->
            vm.unblock(channel.channelId)
            messages?.show(resources.getString(R.string.message_unblocked, "«⁨${channel.name}⁩»"))
        },
        onUnsubscribe = { channel ->
            vm.unsubscribe(channel.channelId)
            messages?.show(resources.getString(R.string.message_unsubscribed, "«⁨${channel.name}⁩»"))
        },
        onAddKeyword = { word ->
            scope.launch {
                val added = vm.addKeyword(word)
                messages?.show(resources.getString(if (added) R.string.blocklist_keyword_added else R.string.blocklist_keyword_exists, word))
            }
        },
        onRemoveKeyword = vm::removeKeyword,
    )
}

/**
 * قائمة الحظر: blocked channels (blocked from a card's menu or a channel's page) and title keywords, for
 * every profile of the house. The keywords are typed on the TV's keyboard: this is the adult's settings.
 * A blocked channel the adult still subscribes to ([subscribed]) offers «إلغاء الاشتراك» beside it: الاشتراكات
 * no longer lists it and its page no longer opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlocklistScreen(
    blocklist: Blocklist,
    onUnblock: (ListedChannel) -> Unit,
    onAddKeyword: (String) -> Unit,
    onRemoveKeyword: (String) -> Unit,
    modifier: Modifier = Modifier,
    subscribed: Set<String> = emptySet(),
    onUnsubscribe: (ListedChannel) -> Unit = {},
) {
    var word by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val addButton = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { field.requestFocus() }
    }
    fun submit() {
        if (word.isNotBlank()) {
            onAddKeyword(word.trim())
            word = ""
        }
    }
    Column(modifier.fillMaxSize().padding(ShashaDimens.ScreenPadding).testTag("blocklist")) {
        ScreenHeader(stringResource(R.string.blocklist_title), stringResource(R.string.blocklist_subtitle))
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SectionLabel(stringResource(R.string.blocklist_keywords), icon = ShashaIcons.Edit)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShashaTextField(
                        value = word,
                        onValueChange = { word = it },
                        hint = stringResource(R.string.blocklist_keyword_hint),
                        icon = ShashaIcons.Block,
                        maxLength = 40,
                        onDone = { submit() },
                        modifier = Modifier.weight(1f).focusRequester(field).testTag("blocklist_field"),
                    )
                    Spacer(Modifier.width(12.dp))
                    ShashaButton(stringResource(R.string.action_add), ::submit, icon = ShashaIcons.Add, modifier = Modifier.focusRequester(addButton).testTag("blocklist_add"))
                }
                Spacer(Modifier.height(16.dp))
                if (blocklist.titleKeywords.isEmpty()) {
                    Text(stringResource(R.string.blocklist_no_keywords), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        blocklist.titleKeywords.forEach { keyword -> KeywordChip(keyword) { onRemoveKeyword(keyword) } }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                SectionLabel(stringResource(R.string.blocklist_channels), icon = ShashaIcons.Block)
                Spacer(Modifier.height(10.dp))
                if (blocklist.channels.isEmpty()) {
                    Text(stringResource(R.string.blocklist_no_channels), style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted)
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(blocklist.channels, key = { it.channelId }) { channel ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RemovableRow(
                                    title = channel.name,
                                    action = stringResource(R.string.blocklist_unblock),
                                    onClick = { onUnblock(channel) },
                                    modifier = Modifier.weight(1f).testTag("blocked_${channel.channelId}"),
                                    subtitle = if (channel.channelId in subscribed) stringResource(R.string.blocklist_subscribed) else null,
                                ) { ChannelAvatar(null, channel.name, channel.channelId, 36.dp) }
                                if (channel.channelId in subscribed) {
                                    Spacer(Modifier.width(10.dp))
                                    ShashaButton(
                                        stringResource(R.string.channel_unsubscribe),
                                        { onUnsubscribe(channel) },
                                        icon = ShashaIcons.Delete,
                                        compact = true,
                                        modifier = Modifier.testTag("unsubscribe_${channel.channelId}"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeywordChip(keyword: String, onRemove: () -> Unit) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onRemove,
        shape = RoundedCornerShape(percent = 50),
        containerColor = ShashaColors.SurfaceVariant,
        focusedContainerColor = accent.main,
        focusedContentColor = accent.onMain,
        scale = 1.06f,
        modifier = Modifier.testTag("keyword_$keyword"),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(keyword, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.ContentOrRtl))
            Spacer(Modifier.width(8.dp))
            Icon(ShashaIcons.Close, contentDescription = stringResource(R.string.action_remove), modifier = Modifier.size(16.dp))
        }
    }
}
