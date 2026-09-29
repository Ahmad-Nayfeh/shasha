package com.ahmadnayfeh.shasha.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.navigation.BrowseActions
import com.ahmadnayfeh.shasha.ui.screens.home.HomeRoute
import com.ahmadnayfeh.shasha.ui.screens.library.HistoryRoute
import com.ahmadnayfeh.shasha.ui.screens.library.SubscriptionsRoute
import com.ahmadnayfeh.shasha.ui.screens.library.WatchLaterRoute
import com.ahmadnayfeh.shasha.ui.screens.search.SearchRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The browsing shell: the rail on the right and the selected tab beside it. Each tab keeps its scroll
 * position and last focused card while another tab is shown, and after the player or a channel page.
 *
 * BACK: from the content to the rail; from the rail to «الرئيسية»; from «الرئيسية»'s rail item it leaves
 * the app (the system handles it).
 */
@Composable
fun MainScreen(actions: BrowseActions, requests: SavedStateHandle? = null) {
    // Another screen may ask for a tab on its way back (the import's «إلى الرئيسية»): once, then forgotten.
    val requested by (requests?.getStateFlow<String?>(SELECT_TAB, null) ?: remember { MutableStateFlow(null) }).collectAsState()
    MainScreen(
        requestedTab = requested?.let { name -> Tab.entries.firstOrNull { it.name == name } },
        onRequestHandled = { requests?.set(SELECT_TAB, null) },
    ) { tab, focus, select -> TabContent(tab, focus, actions, select) }
}

/** SavedStateHandle key of the Main entry: the name of a [Tab] to show when coming back. */
const val SELECT_TAB = "select_tab"

/** The shell's state and behaviour, with the tabs' contents supplied by [tabContent] (tests use fakes). */
@Composable
internal fun MainScreen(
    requestedTab: Tab? = null,
    onRequestHandled: () -> Unit = {},
    tabContent: @Composable (tab: Tab, focus: ContentFocus, select: (Tab) -> Unit) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // Survives the trip to the player and back: focus returns where it was, rail or content.
    var railFocused by rememberSaveable { mutableStateOf(false) }
    val tabs = rememberSaveableStateHolder()
    val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
    // One per tab: while tabs cross-fade, the one leaving must not take the focus meant for the new one.
    val contentFocus = remember { Tab.entries.associateWith { ContentFocus(pending = it == tab && !railFocused) } }
    val current = contentFocus.getValue(tab)

    fun select(target: Tab) {
        tab = target
        contentFocus.getValue(target).request()
    }

    LaunchedEffect(Unit) {
        if (railFocused) {
            withFrameNanos { }
            itemFocus.getValue(tab).requestFocus()
        }
    }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            select(requestedTab)
            onRequestHandled()
        }
    }
    // A tab with nothing to focus (an empty list): the rail keeps the focus rather than nothing having it.
    LaunchedEffect(current, current.pending) {
        if (current.pending) {
            delay(EMPTY_TAB_FALLBACK_MS)
            if (current.pending && !railFocused) {
                current.cancel()
                itemFocus.getValue(tab).requestFocus()
            }
        }
    }
    BackHandler(enabled = !(railFocused && tab == Tab.HOME)) {
        if (!railFocused) {
            itemFocus.getValue(tab).requestFocus()
        } else {
            tab = Tab.HOME
            itemFocus.getValue(Tab.HOME).requestFocus()
        }
    }

    MainShell(tab, ::select, itemFocus, onRailFocusChanged = { railFocused = it }) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(220, delayMillis = 40)) togetherWith fadeOut(tween(90)) },
            modifier = Modifier.fillMaxSize(),
            label = "tab",
        ) { shown ->
            tabs.SaveableStateProvider(shown.name) { tabContent(shown, contentFocus.getValue(shown), ::select) }
        }
    }
}

/** The layout: [content] beside the rail, the rail drawn over it when it widens. */
@Composable
fun MainShell(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    itemFocus: Map<Tab, FocusRequester>,
    modifier: Modifier = Modifier,
    onRailFocusChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = RailCollapsedWidth)) { content() }
        NavRail(
            selected = selected,
            onSelect = onSelect,
            itemFocus = itemFocus,
            modifier = Modifier.align(Alignment.CenterStart).onFocusChanged { onRailFocusChanged(it.hasFocus) },
        )
    }
}

@Composable
private fun TabContent(tab: Tab, focus: ContentFocus, actions: BrowseActions, select: (Tab) -> Unit) {
    when (tab) {
        Tab.HOME -> HomeRoute(focus, actions.play, actions.openImport, onSearch = { select(Tab.SEARCH) }, onCheckUpdates = actions.checkUpdates)
        Tab.SEARCH -> SearchRoute(focus, actions)
        Tab.SUBSCRIPTIONS -> SubscriptionsRoute(focus, actions.openChannel, actions.openImport, onSearch = { select(Tab.SEARCH) })
        Tab.WATCH_LATER -> WatchLaterRoute(focus, actions.play)
        Tab.HISTORY -> HistoryRoute(focus, actions.play)
        Tab.SETTINGS -> SettingsScreen(
            focus = focus,
            appVersion = BuildConfig.VERSION_NAME,
            extractorVersion = LocalContext.current.appContainer.videoSource.extractorVersion,
            onDiagnostics = actions.openDiagnostics,
            onImport = actions.openImport,
        )
    }
}

/** Longer than the local database needs to answer, even on a cold start of a weak TV. */
private const val EMPTY_TAB_FALLBACK_MS = 1_500L
