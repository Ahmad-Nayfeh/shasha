package com.ahmadnayfeh.shasha.ui.components

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import com.ahmadnayfeh.shasha.ui.theme.StartTextAlign

/**
 * Opens a video card's context menu (OK held, or the MENU key). [CardMenuHost] provides it; outside a
 * host (a child profile, screenshots, tests) cards simply have no menu.
 */
val LocalVideoMenu = staticCompositionLocalOf<(VideoItem) -> Unit> { {} }

/** What a channel or playlist card's context menu is about. */
@Immutable
sealed interface MenuTarget {
    data class Channel(val channel: ChannelItem) : MenuTarget

    data class Playlist(val playlist: PlaylistItem) : MenuTarget
}

/** Opens a channel or playlist card's context menu; nothing outside a host with parental actions. */
val LocalCollectionMenu = staticCompositionLocalOf<(MenuTarget) -> Unit> { {} }

/**
 * The parent's actions in the menus (adult profile only): the children's allowlists and the block list,
 * behind the profile's [unlock] (the same parent PIN, remembered for a few minutes, as the settings).
 */
class ParentalActions(val profiles: Flow<List<Profile>>, val lists: ParentalLists, val unlock: ParentUnlock)

/**
 * Hosts the context menus of the cards inside [content]. Videos: «شاهد لاحقاً» (add or remove) and «اذهب
 * إلى القناة». With [parental] (the adult profile), also, for the video's channel or a channel or playlist
 * card: «أضف إلى قائمة سماح <الطفل>» for each child profile, and «حظر القناة»; these ask for the parent PIN
 * first unless it was given in the last few minutes. No recommendations, nothing else.
 */
@Composable
fun CardMenuHost(
    watch: WatchRepository,
    onOpenChannel: (channelId: String, name: String?) -> Unit,
    canOpenChannel: (String) -> Boolean,
    parental: ParentalActions?,
    content: @Composable () -> Unit,
) {
    var target by remember { mutableStateOf<Any?>(null) }
    // A parental action waiting for the parent PIN.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val messages = LocalAppMessages.current
    // Remembered: a new lambda in these static locals would recompose every screen when a menu opens.
    val openVideo = remember { { video: VideoItem -> target = video } }
    val openCollection = remember { { item: MenuTarget -> target = item } }
    CompositionLocalProvider(
        LocalVideoMenu provides openVideo,
        LocalCollectionMenu provides (if (parental != null) openCollection else NoCollectionMenu),
        content = content,
    )

    val profilesFlow = remember(parental) { parental?.profiles ?: flowOf(emptyList()) }
    val profiles by profilesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val children = profiles.filter { it.isChild }
    val blocked = remember(profiles) { ParentalLists.householdBlocklist(profiles).channels.mapTo(HashSet()) { it.channelId } }
    // Read when a menu opens: a lock on the parental rows says the PIN comes first.
    val locked = parental?.unlock?.isUnlocked != true

    fun act(message: String, action: suspend ParentalLists.() -> Unit) {
        val actions = parental ?: return
        target = null
        val run = {
            scope.launch {
                actions.lists.action()
                messages?.show(message)
            }
            Unit
        }
        if (actions.unlock.isUnlocked) run() else pending = run
    }

    fun parentalRow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) = MenuItem(icon, label, tag, locked, onClick)

    fun channelRows(channelId: String, name: String): List<MenuItem> = buildList {
        val quoted = resources.getString(R.string.quoted_name, name)
        children.forEach { child ->
            if (child.allowlist.hasChannel(channelId)) {
                add(
                    parentalRow(ShashaIcons.CheckCircle, resources.getString(R.string.menu_allowlist_remove, child.name), "menu_allow_${child.id}") {
                        act(resources.getString(R.string.message_allowlist_removed, quoted, child.name)) { disallow(child.id, channelId) }
                    },
                )
            } else {
                add(
                    parentalRow(ShashaIcons.Shield, resources.getString(R.string.menu_allowlist_add, child.name), "menu_allow_${child.id}") {
                        act(resources.getString(R.string.message_allowlist_added, quoted, child.name)) { allowChannel(child.id, ListedChannel(channelId, name)) }
                    },
                )
            }
        }
        if (channelId in blocked) {
            add(
                parentalRow(ShashaIcons.Block, resources.getString(R.string.menu_unblock_channel), "menu_block") {
                    act(resources.getString(R.string.message_unblocked, quoted)) { unblockChannel(channelId) }
                },
            )
        } else {
            add(
                parentalRow(ShashaIcons.Block, resources.getString(R.string.menu_block_channel), "menu_block") {
                    act(resources.getString(R.string.message_blocked, quoted)) { blockChannel(ListedChannel(channelId, name)) }
                },
            )
        }
    }

    when (val shown = target) {
        is VideoItem -> {
            val saved by remember(shown.videoId) { watch.isSaved(shown.videoId) }.collectAsStateWithLifecycle(initialValue = null)
            val channelId = shown.channelId
            VideoMenuDialog(
                video = shown,
                savedForLater = saved,
                onDismiss = { target = null },
                onToggleWatchLater = {
                    val wasSaved = saved == true
                    target = null
                    scope.launch { if (wasSaved) watch.removeFromWatchLater(shown.videoId) else watch.addToWatchLater(shown) }
                },
                onOpenChannel = if (channelId != null && canOpenChannel(channelId)) {
                    {
                        target = null
                        onOpenChannel(channelId, shown.channelName)
                    }
                } else {
                    null
                },
                extra = if (parental != null && channelId != null) channelRows(channelId, shown.channelName ?: channelId) else emptyList(),
            )
        }
        is MenuTarget.Channel -> CardMenuDialog(
            title = shown.channel.name,
            subtitle = stringResource(R.string.menu_channel_subtitle),
            items = channelRows(shown.channel.channelId, shown.channel.name),
            onDismiss = { target = null },
        )
        is MenuTarget.Playlist -> {
            val playlist = shown.playlist
            val quoted = resources.getString(R.string.quoted_name, playlist.title)
            val rows = children.map { child ->
                if (child.allowlist.hasPlaylist(playlist.playlistId)) {
                    parentalRow(ShashaIcons.CheckCircle, resources.getString(R.string.menu_allowlist_remove, child.name), "menu_allow_${child.id}") {
                        act(resources.getString(R.string.message_allowlist_removed, quoted, child.name)) { disallow(child.id, playlist.playlistId) }
                    }
                } else {
                    parentalRow(ShashaIcons.Shield, resources.getString(R.string.menu_allowlist_add, child.name), "menu_allow_${child.id}") {
                        act(resources.getString(R.string.message_allowlist_added, quoted, child.name)) {
                            allowPlaylist(child.id, ListedPlaylist(playlist.playlistId, playlist.title))
                        }
                    }
                }
            }
            CardMenuDialog(
                title = playlist.title,
                subtitle = playlist.channelName ?: stringResource(R.string.playlist_label),
                items = rows,
                onDismiss = { target = null },
                emptyHint = stringResource(R.string.menu_no_children),
            )
        }
        else -> Unit
    }

    val unlock = parental?.unlock
    val run = pending
    if (unlock != null && run != null) {
        PinDialog(onDismiss = { pending = null }) {
            ParentPinPad(
                title = stringResource(R.string.pin_parent_title),
                subtitle = stringResource(R.string.menu_parental_pin_body),
                check = unlock::check,
                onAccepted = {
                    pending = null
                    run()
                },
                initialLockMillis = unlock::lockMillis,
            )
        }
    }
}

private val NoCollectionMenu: (MenuTarget) -> Unit = {}

/** «…» around a name, isolated so an English title keeps its place in the Arabic sentence. */

/** One row of a context menu. [locked]: it asks for the parent PIN first (a small lock says so). */
@Immutable
class MenuItem(val icon: ImageVector, val label: String, val tag: String, val locked: Boolean = false, val onClick: () -> Unit)

/** A video card's menu, in a dialog (it keeps focus inside, and BACK closes it). */
@Composable
fun VideoMenuDialog(
    video: VideoItem,
    savedForLater: Boolean?,
    onDismiss: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onOpenChannel: (() -> Unit)?,
    extra: List<MenuItem> = emptyList(),
) {
    Dialog(onDismissRequest = onDismiss) {
        VideoMenuPanel(video, savedForLater, onToggleWatchLater, onOpenChannel, extra = extra)
    }
}

@Composable
fun VideoMenuPanel(
    video: VideoItem,
    savedForLater: Boolean?,
    onToggleWatchLater: () -> Unit,
    onOpenChannel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    extra: List<MenuItem> = emptyList(),
) {
    val items = buildList {
        add(
            MenuItem(
                icon = if (savedForLater == true) ShashaIcons.CheckCircle else ShashaIcons.WatchLater,
                label = stringResource(if (savedForLater == true) R.string.menu_watch_later_remove else R.string.menu_watch_later_add),
                tag = "menu_watch_later",
                onClick = onToggleWatchLater,
            ),
        )
        if (video.channelId != null && onOpenChannel != null) {
            add(MenuItem(ShashaIcons.Person, stringResource(R.string.menu_go_to_channel), "menu_channel", onClick = onOpenChannel))
        }
        addAll(extra)
    }
    CardMenuPanel(video.title, video.channelName, items, modifier.testTag("video_menu"))
}

@Composable
fun CardMenuDialog(title: String, subtitle: String?, items: List<MenuItem>, onDismiss: () -> Unit, emptyHint: String? = null) {
    Dialog(onDismissRequest = onDismiss) {
        CardMenuPanel(title, subtitle, items, Modifier.testTag("card_menu"), emptyHint)
    }
}

/**
 * The press that opened a menu by being held (OK's long press) is still down when the menu appears: its repeats and its
 * release reach the menu's focused first row, and a release there is a click (tv-material's surfaces click on the key's
 * release), so the menu acted on its first row and closed as soon as OK was let go. This swallows that press's leftovers
 * (OK's repeats and its release) until a fresh press starts (a key down that is no repeat: the menu's own click); any
 * other key passes. A menu opened by MENU never sees such leftovers: OK's next press is a fresh one.
 */
internal class HeldSelectGuard {
    private var armed = true

    /** True when the key event belongs to the press that opened the menu: it must not reach the menu. */
    fun swallow(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        if (!armed || keyCode !in SELECT_KEYS) return false
        return when (action) {
            ACTION_DOWN -> if (repeatCount == 0) {
                armed = false // a fresh press: the menu's own
                false
            } else {
                true // the opening press, still held
            }
            ACTION_UP -> {
                armed = false // its release: the menu stays
                true
            }
            else -> false
        }
    }

    private companion object {
        /** What tv-material's surfaces click with. */
        val SELECT_KEYS = setOf(KEYCODE_DPAD_CENTER, KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER)
    }
}

/**
 * The menus' card: the item's title, a line under it, then the actions. Focus starts on the first. The
 * card stays inside the screen's safe margins: when the actions do not fit (a household with many
 * children), they scroll, following the focus. The long press that opened it ends without a click
 * ([HeldSelectGuard]): the menu stays until a row is chosen or BACK closes it.
 */
@Composable
fun CardMenuPanel(title: String, subtitle: String?, items: List<MenuItem>, modifier: Modifier = Modifier, emptyHint: String? = null) {
    val first = remember { FocusRequester() }
    val guard = remember { HeldSelectGuard() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                guard.swallow(native.keyCode, native.action, native.repeatCount)
            }
            .width(440.dp)
            .heightIn(max = MENU_MAX_HEIGHT)
            .background(ShashaColors.Surface, shape)
            .border(1.dp, ShashaColors.Outline, shape)
            .padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = if (items.isEmpty()) 20.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content),
            textAlign = StartTextAlign,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        )
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = ShashaColors.Muted, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp))
        }
        if (items.isEmpty() && emptyHint != null) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = ShashaColors.Muted,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        // The rows take what the title leaves and scroll inside it, with room around them for the focused row's glow.
        if (items.isNotEmpty()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items.forEachIndexed { index, item ->
                    MenuRow(
                        icon = item.icon,
                        label = item.label,
                        locked = item.locked,
                        onClick = item.onClick,
                        modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).testTag(item.tag),
                    )
                }
            }
        }
    }
}

/** The screen's height inside its overscan-safe margins (540dp less 50dp above and below). */
private val MENU_MAX_HEIGHT = 440.dp

@Composable
private fun MenuRow(icon: ImageVector, label: String, locked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    FocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        containerColor = ShashaColors.SurfaceVariant,
        focusedContainerColor = accent.main,
        focusedContentColor = accent.onMain,
        scale = 1.03f,
    ) {
        Row(Modifier.align(Alignment.CenterStart).fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (locked) {
                Spacer(Modifier.width(10.dp))
                Icon(
                    ShashaIcons.Lock,
                    contentDescription = null,
                    tint = LocalContentColor.current.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp).testTag("menu_row_locked"),
                )
            }
        }
    }
}
