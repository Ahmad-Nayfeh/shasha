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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ahmadnayfeh.shasha.BuildConfig
import com.ahmadnayfeh.shasha.appContainer
import com.ahmadnayfeh.shasha.ui.components.VideoMenuHost
import com.ahmadnayfeh.shasha.ui.main.MainScreen
import com.ahmadnayfeh.shasha.ui.main.SELECT_TAB
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelRoute
import com.ahmadnayfeh.shasha.ui.screens.diagnostics.DiagnosticsRoute
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerScreen
import com.ahmadnayfeh.shasha.ui.screens.playlist.PlaylistRoute
import com.ahmadnayfeh.shasha.ui.screens.settings.ImportRoute
import com.ahmadnayfeh.shasha.ui.screens.updates.UpdatesPlaceholderScreen

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

@Composable
fun ShashaNavHost() {
    val nav = rememberNavController()
    val container = LocalContext.current.appContainer
    val actions = remember(nav) { browseActions(nav) }
    VideoMenuHost(container.watch, onOpenChannel = actions.openChannel) {
        NavHost(
            navController = nav,
            startDestination = Screen.Main,
            enterTransition = { enter() },
            exitTransition = { exit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
        ) {
            composable<Screen.Main> { entry -> MainScreen(actions, entry.savedStateHandle) }
            composable<Screen.Channel> { entry ->
                val route = entry.toRoute<Screen.Channel>()
                ChannelRoute(
                    channelId = route.channelId,
                    nameHint = route.name,
                    onPlay = actions.play,
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onCheckUpdates = actions.checkUpdates,
                )
            }
            composable<Screen.Playlist> { entry ->
                val route = entry.toRoute<Screen.Playlist>()
                PlaylistRoute(
                    playlistId = route.playlistId,
                    onPlay = { videoId, playlistId, index -> nav.navigate(Screen.Player(videoId, playlistId, index)) { launchSingleTop = true } },
                    onBack = dropUnlessResumed { nav.popBackStack() },
                    onCheckUpdates = actions.checkUpdates,
                )
            }
            composable<Screen.Diagnostics> {
                DiagnosticsRoute(onOpen = { videoId -> nav.navigate(Screen.Player(videoId)) { launchSingleTop = true } })
            }
            composable<Screen.ImportSubscriptions> {
                // «إلى الرئيسية»: back to the rail's home tab, where the imported channels' videos arrive.
                ImportRoute(
                    onDone = dropUnlessResumed {
                        nav.getBackStackEntry<Screen.Main>().savedStateHandle[SELECT_TAB] = Tab.HOME.name
                        nav.popBackStack(Screen.Main, inclusive = false)
                    },
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
                    onPlayNext = { videoId, index ->
                        // The next playlist video replaces this one: BACK still returns to the playlist.
                        nav.navigate(Screen.Player(videoId, route.playlistId, index)) {
                            popUpTo<Screen.Player> { inclusive = true }
                        }
                    },
                )
            }
            composable<Screen.Updates> {
                UpdatesPlaceholderScreen(
                    appVersion = BuildConfig.VERSION_NAME,
                    extractorVersion = container.videoSource.extractorVersion,
                    onBack = dropUnlessResumed { nav.popBackStack() },
                )
            }
        }
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
