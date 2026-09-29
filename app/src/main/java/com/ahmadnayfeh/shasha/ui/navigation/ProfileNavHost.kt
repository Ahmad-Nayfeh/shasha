package com.ahmadnayfeh.shasha.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.tv.material3.Icon
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.lock.LockController
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.components.CardMenuHost
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.ParentalActions
import com.ahmadnayfeh.shasha.ui.components.ProfileAvatar
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.containerViewModel
import com.ahmadnayfeh.shasha.ui.lock.KeepLockedVideoOnScreen
import com.ahmadnayfeh.shasha.ui.main.MainScreen
import com.ahmadnayfeh.shasha.ui.main.RailAction
import com.ahmadnayfeh.shasha.ui.main.SELECT_TAB
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.parental.LocalParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentUnlock
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelRoute
import com.ahmadnayfeh.shasha.ui.screens.diagnostics.DiagnosticsRoute
import com.ahmadnayfeh.shasha.ui.screens.home.HomeRoute
import com.ahmadnayfeh.shasha.ui.screens.kids.AllowedChannelsRoute
import com.ahmadnayfeh.shasha.ui.screens.kids.KidsHomeRoute
import com.ahmadnayfeh.shasha.ui.screens.library.HistoryRoute
import com.ahmadnayfeh.shasha.ui.screens.library.SubscriptionsRoute
import com.ahmadnayfeh.shasha.ui.screens.library.WatchLaterRoute
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerScreen
import com.ahmadnayfeh.shasha.ui.screens.playlist.PlaylistRoute
import com.ahmadnayfeh.shasha.ui.screens.search.SearchRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.AllowlistRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.BlocklistRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ChangePinRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ImportRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ProfileEditRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ProfilesRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ProtectionRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.StatsRoute
import com.ahmadnayfeh.shasha.ui.screens.updates.UpdatesPlaceholderScreen
import com.ahmadnayfeh.shasha.ui.settings.SettingsRoute
import com.ahmadnayfeh.shasha.ui.settings.backup.BackupRoute
import com.ahmadnayfeh.shasha.ui.settings.remote.RemoteRoute

/** What a tab of [MainScreen] shows; tests pass fakes. */
typealias TabContent = @Composable (tab: Tab, focus: ContentFocus, select: (Tab) -> Unit) -> Unit

// Subtle, short transitions: a fade with a hint of depth. Cheap enough for a weak TV GPU.
private const val ENTER_MS = 320
private const val EXIT_MS = 200

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    fadeIn(tween(ENTER_MS, delayMillis = 60)) + scaleIn(tween(ENTER_MS, easing = FastOutSlowInEasing), initialScale = 0.97f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS), targetScale = 1.02f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    fadeIn(tween(ENTER_MS, delayMillis = 60)) + scaleIn(tween(ENTER_MS, easing = FastOutSlowInEasing), initialScale = 1.02f)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    fadeOut(tween(EXIT_MS)) + scaleOut(tween(EXIT_MS), targetScale = 0.97f)

/**
 * The screens of one profile. Its navigation graph holds only what the profile may reach (SPEC §3): a
 * child's has the rail's two tabs, channel and playlist pages and the player, nothing else (no search,
 * no settings, no diagnostics, no import). The adult's context menus carry the parental actions.
 *
 * [onSwitchProfile] is the adult's «تبديل الملف»; [onExitProfile] a child's «خروج» (the caller asks for
 * the parent PIN). [tabsNav] and [tabContent] are for tests.
 *
 * [lock] (SPEC §4): every video the player starts is locked, and while this profile has a locked video
 * nothing but its player is shown ([KeepLockedVideoOnScreen]; it also brings it back after a restart).
 */
@Composable
fun ProfileNavHost(
    profile: Profile,
    nav: NavHostController,
    onSwitchProfile: () -> Unit,
    onExitProfile: () -> Unit,
    modifier: Modifier = Modifier,
    tabsNav: NavHostController? = null,
    tabContent: TabContent? = null,
    lock: LockController? = null,
) {
    val kids = profile.isChild
    val actions = remember(nav) { browseActions(nav) }
    val settings = remember(nav) { settingsActions(nav) }
    val railActions = remember(kids, profile.avatar, profile.name) {
        if (kids) {
            listOf(RailAction(R.string.rail_exit, "rail_exit", onExitProfile) { Icon(ShashaIcons.Exit, contentDescription = null, modifier = it) })
        } else {
            listOf(RailAction(R.string.rail_switch_profile, "rail_switch_profile", onSwitchProfile) { ProfileAvatar(profile.avatar, profile.name, 28.dp) })
        }
    }
    val content: TabContent = tabContent ?: if (kids) {
        { tab, focus, _ -> KidsTabContent(tab, focus, actions) }
    } else {
        { tab, focus, select -> AdultTabContent(tab, focus, actions, settings, select) }
    }
    val graph: @Composable () -> Unit = {
        NavHost(
            navController = nav,
            startDestination = Screen.Main,
            modifier = modifier,
            enterTransition = { enter() },
            exitTransition = { exit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            composable<Screen.Main> { entry ->
                // The tab graph's controller belongs to this destination (saved with it), unless a test gives one.
                val tabs = tabsNav ?: rememberNavController()
                MainScreen(if (kids) Tab.Kids else Tab.Adult, entry.savedStateHandle, railActions, tabs, content)
            }
            browsingDestinations(nav, actions, profile, lock)
            if (!kids) adultDestinations(nav, actions, settings)
        }
        if (lock != null) KeepLockedVideoOnScreen(lock.lock, profile.id, nav)
        // SPEC §5: links from the phone, once the policy allowed them for this profile, open here.
        FollowNavigationRequests(nav, profile.id)
    }
    if (kids) {
        graph()
    } else {
        val container = LocalContext.current.appContainer
        // One parent unlock for this adult session (its ViewModel store): the settings and the card menus share it.
        val unlock = containerViewModel { ParentUnlock(it.parentPin) }
        val parental = remember(unlock) { ParentalActions(container.profiles.observeAll(), ParentalLists(container.profiles), unlock) }
        CompositionLocalProvider(LocalParentUnlock provides unlock) {
            CardMenuHost(
                watch = container.watch,
                onOpenChannel = actions.openChannel,
                canOpenChannel = { container.policy.canOpenChannel(it).isAllowed },
                parental = parental,
                content = graph,
            )
        }
    }
}

/** Channel and playlist pages, the player and the updater: every profile has them. */
private fun NavGraphBuilder.browsingDestinations(nav: NavHostController, actions: BrowseActions, profile: Profile, lock: LockController?) {
    val adult = profile.isAdult
    composable<Screen.Channel> { entry ->
        val route = entry.toRoute<Screen.Channel>()
        ChannelRoute(
            channelId = route.channelId,
            nameHint = route.name,
            adult = adult,
            onPlay = actions.play,
            onBack = dropUnlessResumed { nav.popBackStack() },
            onCheckUpdates = actions.checkUpdates,
        )
    }
    composable<Screen.Playlist> { entry ->
        val route = entry.toRoute<Screen.Playlist>()
        PlaylistRoute(
            playlistId = route.playlistId,
            adult = adult,
            onPlay = { videoId, playlistId, index -> nav.navigate(Screen.Player(videoId, playlistId, index)) { launchSingleTop = true } },
            onBack = dropUnlessResumed { nav.popBackStack() },
            onCheckUpdates = actions.checkUpdates,
        )
    }
    composable<Screen.Player>(
        // The video is on a SurfaceView, which cannot fade or scale: leave the player with a quick cut.
        exitTransition = { fadeOut(tween(120)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) { entry ->
        val route = entry.toRoute<Screen.Player>()
        PlayerScreen(
            videoId = route.videoId,
            onBack = dropUnlessResumed { nav.popBackStack() },
            onCheckUpdates = dropUnlessResumed { nav.navigate(Screen.Updates) { launchSingleTop = true } },
            playlistId = route.playlistId,
            playlistIndex = route.playlistIndex,
            fromLink = route.fromLink,
            startSeconds = route.startSeconds,
            onPlayNext = { videoId, index ->
                // The next playlist video replaces this one: BACK still returns to the playlist. What came as a
                // link stays one: each next video is judged by its own channel too.
                nav.navigate(Screen.Player(videoId, route.playlistId, index, fromLink = route.fromLink)) {
                    popUpTo<Screen.Player> { inclusive = true }
                }
            },
            profile = profile,
            // تشخيص's test videos are not watched: nothing locks them.
            lock = lock.takeUnless { route.diagnostic },
            // A child's video or playlist that ended frees the child: back to the child home (SPEC §4).
            onKidsHome = if (adult) null else dropUnlessResumed { nav.showHome() },
        )
    }
    composable<Screen.Updates> {
        val context = LocalContext.current
        UpdatesPlaceholderScreen(
            appVersion = BuildConfig.VERSION_NAME,
            extractorVersion = context.appContainer.videoSource.extractorVersion,
            onBack = dropUnlessResumed { nav.popBackStack() },
        )
    }
}

/** Back to the rail's home tab, whatever was opened on the way (a channel, a playlist). */
private fun NavHostController.showHome() {
    getBackStackEntry<Screen.Main>().savedStateHandle[SELECT_TAB] = Tab.HOME.name
    popBackStack(Screen.Main, inclusive = false)
}

/** Diagnostics, the import and the parent's settings: the adult profile only. */
private fun NavGraphBuilder.adultDestinations(nav: NavHostController, actions: BrowseActions, settings: SettingsActions) {
    composable<Screen.Diagnostics> {
        DiagnosticsRoute(onOpen = { videoId -> nav.navigate(Screen.Player(videoId, diagnostic = true)) { launchSingleTop = true } })
    }
    composable<Screen.ImportSubscriptions> {
        // «إلى الرئيسية»: back to the rail's home tab, where the imported channels' videos arrive.
        ImportRoute(onDone = dropUnlessResumed { nav.showHome() })
    }
    composable<Screen.Profiles> { ProfilesRoute(onOpenProfile = settings.openProfile) }
    composable<Screen.ProfileEdit> { entry ->
        ProfileEditRoute(
            entry.toRoute<Screen.ProfileEdit>().profileId,
            onOpenAllowlist = settings.openAllowlist,
            onBack = dropUnlessResumed { nav.popBackStack() },
        )
    }
    composable<Screen.Allowlist> { entry ->
        AllowlistRoute(entry.toRoute<Screen.Allowlist>().profileId)
    }
    composable<Screen.Blocklist> { BlocklistRoute() }
    composable<Screen.ChangePin> { ChangePinRoute(onDone = dropUnlessResumed { nav.popBackStack() }) }
    composable<Screen.Stats> { StatsRoute() }
    composable<Screen.Protection> { ProtectionRoute() }
    composable<Screen.RemoteControl> { RemoteRoute() }
    composable<Screen.Backup> { BackupRoute() }
}

@Composable
private fun AdultTabContent(tab: Tab, focus: ContentFocus, actions: BrowseActions, settings: SettingsActions, select: (Tab) -> Unit) {
    when (tab) {
        Tab.HOME -> HomeRoute(focus, actions.play, actions.openImport, onSearch = { select(Tab.SEARCH) }, onCheckUpdates = actions.checkUpdates)
        Tab.SEARCH -> SearchRoute(focus, actions)
        Tab.SUBSCRIPTIONS -> SubscriptionsRoute(focus, actions.openChannel, actions.openImport, onSearch = { select(Tab.SEARCH) })
        Tab.WATCH_LATER -> WatchLaterRoute(focus, actions.play)
        Tab.HISTORY -> HistoryRoute(focus, actions.play)
        Tab.SETTINGS -> SettingsRoute(focus, settings)
        Tab.ALLOWED_CHANNELS -> Unit // a child's tab
    }
}

@Composable
private fun KidsTabContent(tab: Tab, focus: ContentFocus, actions: BrowseActions) {
    when (tab) {
        Tab.HOME -> KidsHomeRoute(focus, actions)
        Tab.ALLOWED_CHANNELS -> AllowedChannelsRoute(focus, actions)
        else -> Unit // not in a child's rail
    }
}

private fun browseActions(nav: NavHostController) = BrowseActions(
    play = { video -> nav.navigate(Screen.Player(video.videoId)) { launchSingleTop = true } },
    openChannel = { channelId, name -> nav.navigate(Screen.Channel(channelId, name)) { launchSingleTop = true } },
    openPlaylist = { playlistId -> nav.navigate(Screen.Playlist(playlistId)) { launchSingleTop = true } },
    openImport = { nav.navigate(Screen.ImportSubscriptions) { launchSingleTop = true } },
    openDiagnostics = { nav.navigate(Screen.Diagnostics) { launchSingleTop = true } },
    checkUpdates = { nav.navigate(Screen.Updates) { launchSingleTop = true } },
)

private fun settingsActions(nav: NavHostController) = SettingsActions(
    openProfiles = { nav.navigate(Screen.Profiles) { launchSingleTop = true } },
    openProfile = { id -> nav.navigate(Screen.ProfileEdit(id)) { launchSingleTop = true } },
    openAllowlist = { id -> nav.navigate(Screen.Allowlist(id)) { launchSingleTop = true } },
    openBlocklist = { nav.navigate(Screen.Blocklist) { launchSingleTop = true } },
    openChangePin = { nav.navigate(Screen.ChangePin) { launchSingleTop = true } },
    openStats = { nav.navigate(Screen.Stats) { launchSingleTop = true } },
    openImport = { nav.navigate(Screen.ImportSubscriptions) { launchSingleTop = true } },
    openDiagnostics = { nav.navigate(Screen.Diagnostics) { launchSingleTop = true } },
    openProtection = { nav.navigate(Screen.Protection) { launchSingleTop = true } },
    openRemote = { nav.navigate(Screen.RemoteControl) { launchSingleTop = true } },
    openBackup = { nav.navigate(Screen.Backup) { launchSingleTop = true } },
    openUpdates = { nav.navigate(Screen.Updates) { launchSingleTop = true } },
)
