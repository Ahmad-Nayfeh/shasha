package com.ahmadnayfeh.shasha.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The browsing shell of a profile: the rail on the right with the profile's [tabs], and the selected tab
 * beside it. The tabs are the destinations of the shell's own navigation graph, so a tab a profile does
 * not have is not even registered (a child's graph has no search). Each tab keeps its scroll position and
 * last focused card while another tab is shown, and after the player or a channel page.
 *
 * BACK: from the content to the rail; from the rail to the first tab («الرئيسية»); from there it leaves
 * the app (the system handles it).
 */
@Composable
fun MainScreen(
    tabs: List<Tab>,
    requests: SavedStateHandle?,
    railActions: List<RailAction> = emptyList(),
    tabsNav: NavHostController = rememberNavController(),
    tabContent: @Composable (tab: Tab, focus: ContentFocus, select: (Tab) -> Unit) -> Unit,
) {
    // Another screen may ask for a tab on its way back (the import's «إلى الرئيسية»): once, then forgotten.
    val requested by (requests?.getStateFlow<String?>(SELECT_TAB, null) ?: remember { MutableStateFlow(null) }).collectAsState()
    MainScreen(
        requestedTab = requested?.let { name -> tabs.firstOrNull { it.name == name } },
        onRequestHandled = { requests?.set(SELECT_TAB, null) },
        tabs = tabs,
        railActions = railActions,
        tabsNav = tabsNav,
        tabContent = tabContent,
    )
}

/** SavedStateHandle key of the Main entry: the name of a [Tab] to show when coming back. */
const val SELECT_TAB = "select_tab"

/**
 * The shell's state and behaviour, with the tabs' contents supplied by [tabContent] (tests use fakes).
 * [tabsNav] runs the tab graph: exactly one tab is on its back stack at any time (switching saves the
 * one left and restores the one shown), so BACK is always the shell's.
 */
@Composable
internal fun MainScreen(
    requestedTab: Tab? = null,
    onRequestHandled: () -> Unit = {},
    tabs: List<Tab> = Tab.Adult,
    railActions: List<RailAction> = emptyList(),
    tabsNav: NavHostController = rememberNavController(),
    tabContent: @Composable (tab: Tab, focus: ContentFocus, select: (Tab) -> Unit) -> Unit,
) {
    val first = tabs.first()
    var tab by rememberSaveable { mutableStateOf(first) }
    // Survives the trip to the player and back: focus returns where it was, rail or content.
    var railFocused by rememberSaveable { mutableStateOf(false) }
    val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
    // One per tab: while tabs cross-fade, the one leaving must not take the focus meant for the new one.
    val contentFocus = remember { Tab.entries.associateWith { ContentFocus(pending = it == tab && !railFocused) } }
    val current = contentFocus.getValue(tab)

    fun show(target: Tab) {
        val from = tab
        if (target == from || target !in tabs) return
        tab = target
        tabsNav.navigate(target.route) {
            popUpTo(from.route) {
                inclusive = true
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun select(target: Tab) {
        if (target !in tabs) return
        show(target)
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

    MainShell(tab, ::select, itemFocus, tabs = tabs, actions = railActions, onRailFocusChanged = { railFocused = it }) {
        NavHost(
            navController = tabsNav,
            startDestination = first.route,
            modifier = Modifier.fillMaxSize(),
            enterTransition = { fadeIn(tween(220, delayMillis = 40)) },
            exitTransition = { fadeOut(tween(90)) },
            popEnterTransition = { fadeIn(tween(220, delayMillis = 40)) },
            popExitTransition = { fadeOut(tween(90)) },
        ) {
            tabs.forEach { shown ->
                composable(shown.route) { tabContent(shown, contentFocus.getValue(shown), ::select) }
            }
        }
    }

    // After the tab graph: this handler is the one BACK reaches first.
    BackHandler(enabled = !(railFocused && tab == first)) {
        if (!railFocused) {
            itemFocus.getValue(tab).requestFocus()
        } else {
            show(first)
            itemFocus.getValue(first).requestFocus()
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
    tabs: List<Tab> = Tab.Adult,
    actions: List<RailAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = RailCollapsedWidth)) { content() }
        NavRail(
            selected = selected,
            onSelect = onSelect,
            itemFocus = itemFocus,
            modifier = Modifier.align(Alignment.CenterStart).onFocusChanged { onRailFocusChanged(it.hasFocus) },
            tabs = tabs,
            actions = actions,
        )
    }
}

/** Longer than the local database needs to answer, even on a cold start of a weak TV. */
private const val EMPTY_TAB_FALLBACK_MS = 1_500L
