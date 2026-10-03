package com.ahmadnayfeh.shasha.ui.settings

import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_ESCAPE
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.contentFocus
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.ui.theme.LocalAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaColors

/**
 * الإعدادات (SPEC §7), two panes for the remote: the list of settings on the right (the start in Arabic, beside
 * the rail) and, on the left, the pane of the one that has the focus. UP and DOWN go through the list and the
 * pane follows; LEFT or OK goes into the pane; RIGHT or BACK comes back to the list; BACK again goes to the rail.
 * Coming back from a screen a pane opened, the focus is where it was.
 *
 * The parental panes ([SettingsCategory.parental]) show their content only with [SettingsHubState.unlocked]; a
 * row that needs the PIN elsewhere shows a small lock. The child profile never gets here: its rail has no
 * settings and its navigation graph no settings destinations.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsHubScreen(
    state: SettingsHubState,
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    actions: SettingsHubActions,
    focus: ContentFocus,
    modifier: Modifier = Modifier,
) {
    val entries = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val paneFirst = remember { FocusRequester() }
    // The panes' focusable elements by name (paneItem): where the focus goes back to.
    val items = remember { PaneItems() }
    // The element each pane had last in this visit: LEFT or OK goes back to it (else to the pane's first).
    val memory = remember { mutableMapOf<SettingsCategory, String>() }
    // The pane element that had the focus last, null when the list had it. Back from a screen it opened (or when
    // the tab comes back) the focus returns to it, else to the entry shown in the list.
    var lastItem by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the focus last was: in the pane (true) or the list. When the pane's content changes under the focus
    // (the PIN opened it, or its few minutes ran out) the focus goes to the pane's new first element.
    var inPane by remember { mutableStateOf(false) }
    items.onFocused = { key ->
        lastItem = key
        memory[selected] = key
    }
    val toPane: () -> Unit = {
        val back = memory[selected]?.let(items::focus) ?: false
        if (!back) runCatching { paneFirst.requestFocus() }
    }
    val toList: () -> Unit = { runCatching { entries.getValue(selected).requestFocus() } }

    // Read while composing: the window may hand the focus to the list's first entry meanwhile, which forgets it.
    val restoreTo = lastItem
    LaunchedEffect(focus.pending) {
        if (focus.pending) {
            // The list and the pane compose their elements first: wait one frame so there is something to focus.
            withFrameNanos { }
            val restored = restoreTo?.let(items::focus) ?: false
            if (!restored) toList()
        }
    }
    // Read while composing, before the new content replaces the focused element: once it is gone, the focus
    // falls back to the entry in the list for a moment.
    val focusWasInPane = inPane
    LaunchedEffect(state.unlocked) {
        if (focusWasInPane && selected.parental) {
            withFrameNanos { }
            runCatching { paneFirst.requestFocus() }
        }
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides PivotScrolling, LocalPaneItems provides items) {
        // One focus group for the settings (MainScreen's content focus); no restorer of its own: it would turn a
        // request for a given element into its fallback.
        Box(modifier.fillMaxSize().contentFocus(focus).focusGroup().testTag("settings")) {
            Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 48.dp)) {
                CategoryList(
                    state = state,
                    selected = selected,
                    entries = entries,
                    onFocused = { category ->
                        inPane = false
                        lastItem = null
                        onSelect(category)
                    },
                    onOpen = toPane,
                    modifier = Modifier.width(ListWidth).fillMaxHeight().padding(vertical = 30.dp),
                )
                Spacer(Modifier.width(PaneGap))
                var backDown by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 36.dp)
                        .onFocusChanged { if (it.hasFocus) inPane = true }
                        .onKeyEvent { event ->
                            // BACK steps out of the pane to its entry in the list (the rail is one more BACK away).
                            val code = event.nativeKeyEvent.keyCode
                            if (code != KEYCODE_BACK && code != KEYCODE_ESCAPE) return@onKeyEvent false
                            when (event.type) {
                                KeyEventType.KeyDown -> backDown = true
                                KeyEventType.KeyUp -> if (backDown) {
                                    backDown = false
                                    toList()
                                }
                            }
                            true
                        }
                        .focusProperties {
                            // LEFT from the list comes in where this pane was left, or on its first element. A
                            // request for a given element (Enter: the PIN, the way back from a screen) goes through.
                            onEnter = {
                                if (requestedFocusDirection != FocusDirection.Enter) toPane()
                            }
                        }
                        .focusGroup()
                        .testTag("settings_pane_${selected.tag}"),
                ) {
                    key(selected) {
                        // The pane that replaces another fades in (only one is composed at a time: cheap on a weak TV).
                        val alpha = remember { Animatable(0f) }
                        LaunchedEffect(Unit) { alpha.animateTo(1f, tween(PANE_FADE_MS)) }
                        Column(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }) {
                            PaneHeader(selected, Modifier.padding(horizontal = 8.dp)) { HeaderStatus(selected, state) }
                            Spacer(Modifier.height(18.dp))
                            PaneBody(selected, state, actions, paneFirst)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolling that follows the focus the way Compose does on Android TV (a pivot): the focused element's top
 * settles at 30% of the list's height, so what comes after it shows, and the last elements bring the end of
 * the pane (its notes) into view. Set here so the screenshots and the tests scroll like the TV.
 */
@OptIn(ExperimentalFoundationApi::class)
private val PivotScrolling = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val pivot = PIVOT_FRACTION * containerSize
        val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
        return offset - target
    }
}

private const val PIVOT_FRACTION = 0.3f

@Composable
private fun PaneBody(category: SettingsCategory, state: SettingsHubState, actions: SettingsHubActions, first: FocusRequester) {
    when (category) {
        SettingsCategory.PROFILES -> ProfilesPane(state, actions, first)
        SettingsCategory.LISTS -> ListsPane(state, actions, first)
        SettingsCategory.TIME -> TimePane(state, actions, first)
        SettingsCategory.SERIOUS -> SeriousPane(state, actions, first)
        SettingsCategory.STATS -> StatsPane(state, actions, first)
        SettingsCategory.SPONSORBLOCK -> SponsorBlockPane(state, actions, first)
        SettingsCategory.QUALITY -> QualityPane(state, actions, first)
        SettingsCategory.PLAYBACK -> PlaybackTimingPane(state, actions, first)
        SettingsCategory.PROTECTION -> ProtectionPane(state, actions, first)
        SettingsCategory.REMOTE -> RemotePane(state, actions, first)
        SettingsCategory.DATA -> DataPane(state, actions, first)
        SettingsCategory.UPDATES -> UpdatesPane(state, actions, first)
        SettingsCategory.RESET -> ResetPane(state, actions, first)
        SettingsCategory.APPEARANCE -> AppearancePane(state, actions, first)
        SettingsCategory.GUIDE -> GuidePane(state, actions, first)
        SettingsCategory.ABOUT -> AboutPane(first)
    }
}

/** What a pane's title shows on its far side: the protection's verdict, the server's state. */
@Composable
private fun HeaderStatus(category: SettingsCategory, state: SettingsHubState) {
    when (category) {
        SettingsCategory.PROTECTION -> ProtectionTag(state.protection)
        SettingsCategory.REMOTE -> RemoteTag(state.remote)
        else -> Unit
    }
}

/**
 * The settings, grouped under small headings, in a panel that scrolls with the focus. The focus enters on the
 * entry shown in the pane ([selected]); moving it shows another ([onFocused]); OK goes into the pane ([onOpen]).
 */
@Composable
private fun CategoryList(
    state: SettingsHubState,
    selected: SettingsCategory,
    entries: Map<SettingsCategory, FocusRequester>,
    onFocused: (SettingsCategory) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    val scroll = rememberScrollState()
    Column(
        modifier
            .clip(shape)
            .background(PanelColor)
            .border(1.dp, ShashaColors.Outline.copy(alpha = 0.6f), shape)
            .padding(top = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 22.dp)) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            Text(
                stringResource(R.string.settings_version, state.versions.name),
                style = MaterialTheme.typography.labelMedium,
                color = ShashaColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    // The scroll container is the list's focus group: entering it lands on the entry shown.
                    .focusRestorer(entries.getValue(selected))
                    .verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
            ) {
                SettingsGroup.entries.forEach { group ->
                    Text(
                        stringResource(group.label),
                        style = MaterialTheme.typography.labelMedium,
                        color = ShashaColors.Muted,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                    )
                    SettingsCategory.entries.filter { it.group == group }.forEach { category ->
                        CategoryEntry(
                            category = category,
                            selected = category == selected,
                            onFocused = { onFocused(category) },
                            onOpen = onOpen,
                            modifier = Modifier.focusRequester(entries.getValue(category)),
                        ) { EntryMark(category, state) }
                    }
                }
            }
            // More above or below: the list fades into the panel instead of being cut.
            if (scroll.canScrollBackward) Fade(Modifier.align(Alignment.TopCenter), toBottom = false)
            if (scroll.canScrollForward) Fade(Modifier.align(Alignment.BottomCenter), toBottom = true)
        }
    }
}

/**
 * A small sign at the end of an entry: on the parental ones a lock, open once the PIN was given (for its few
 * minutes); a status dot on the protection and the server.
 */
@Composable
private fun EntryMark(category: SettingsCategory, state: SettingsHubState) {
    when {
        category.parental -> Icon(
            if (state.unlocked) SettingsIcons.LockOpen else ShashaIcons.Lock,
            contentDescription = null,
            modifier = Modifier.size(16.dp).graphicsLayer { alpha = 0.75f }.testTag(if (state.unlocked) "entry_unlocked" else "entry_locked"),
        )
        category == SettingsCategory.PROTECTION -> Dot(protectionDot(state.protection))
        category == SettingsCategory.REMOTE -> state.remote?.let { Dot(if (it.status == PairingStatus.RUNNING) LocalAccent.current.bright else ShashaColors.Muted) }
    }
}

@Composable
private fun Fade(modifier: Modifier, toBottom: Boolean) {
    val colors = if (toBottom) listOf(Color.Transparent, PanelColor) else listOf(PanelColor, Color.Transparent)
    Box(modifier.fillMaxWidth().height(32.dp).drawWithContent { drawRect(Brush.verticalGradient(colors)) })
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(9.dp).background(color, CircleShape).border(1.dp, ShashaColors.Background.copy(alpha = 0.4f), CircleShape))
}

@Composable
private fun CategoryEntry(
    category: SettingsCategory,
    selected: Boolean,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    mark: @Composable () -> Unit,
) {
    val accent = LocalAccent.current
    Surface(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .height(EntryHeight)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .semantics { this.selected = selected }
            .testTag("settings_cat_${category.tag}"),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(percent = 50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) accent.tint(0.16f) else Color.Transparent,
            contentColor = if (selected) accent.bright else ShashaColors.Text,
            focusedContainerColor = accent.main,
            focusedContentColor = accent.onMain,
            pressedContainerColor = accent.bright,
            pressedContentColor = accent.onMain,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = accent.buttonGlow),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(category.icon, contentDescription = null, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(category.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            mark()
        }
    }
}

/** The list's panel: the surface colour a little into the background, opaque (its fades end in it exactly). */
private val PanelColor: Color get() = lerp(ShashaColors.Background, ShashaColors.Surface, 0.75f)

private val ListWidth = 264.dp
private val PaneGap = 30.dp
private val EntryHeight = 44.dp
private const val PANE_FADE_MS = 180
