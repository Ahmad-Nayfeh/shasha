package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.R
import com.ahmadnayfeh.shasha.mobile.data.watchUrl
import com.ahmadnayfeh.shasha.mobile.ui.about.AboutScreen
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalSavedPlaylistIds
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalTabReselections
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchLaterIds
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchProgress
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoActions
import com.ahmadnayfeh.shasha.mobile.ui.downloads.DownloadSheet
import com.ahmadnayfeh.shasha.mobile.ui.downloads.DownloadsScreen
import com.ahmadnayfeh.shasha.mobile.ui.home.HomeScreen
import com.ahmadnayfeh.shasha.mobile.ui.library.HistoryScreen
import com.ahmadnayfeh.shasha.mobile.ui.library.LibraryScreen
import com.ahmadnayfeh.shasha.mobile.ui.library.SubscriptionsScreen
import com.ahmadnayfeh.shasha.mobile.ui.library.WatchLaterScreen
import com.ahmadnayfeh.shasha.mobile.ui.nav.AboutRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.ChannelRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.DownloadsRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.HistoryRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.HomeRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.LibraryRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.PlaylistRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.SearchRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.SettingsRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.ShortsRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.SubscriptionsRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.UpdateRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.UserPlaylistRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.WatchLaterRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.WatchRoute
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistPickerSheet
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistScreen
import com.ahmadnayfeh.shasha.mobile.ui.playlist.UserPlaylistScreen
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchScreen
import com.ahmadnayfeh.shasha.mobile.ui.settings.SettingsScreen
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsScreen
import com.ahmadnayfeh.shasha.mobile.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.mobile.ui.theme.isDark
import com.ahmadnayfeh.shasha.mobile.ui.update.UpdateScreen
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchScreen
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

private class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector)

/** The tab whose first page [destination] is; null for every other page. */
private fun tabOf(destination: NavDestination): Tab? = tabs.firstOrNull { destination.hasRoute(it.routeClass) }

private const val NAV_FADE_IN_MS = 180
private const val NAV_FADE_OUT_MS = 120

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Filled.Home),
    Tab(SearchRoute(), SearchRoute::class, R.string.nav_search, Icons.Filled.Search),
    Tab(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.Filled.VideoLibrary),
    Tab(SettingsRoute, SettingsRoute::class, R.string.nav_settings, Icons.Filled.Settings),
)

/** The app: the theme, the four tabs along the bottom, every screen, and the things all of them share (menus, sheets). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShashaRoot(container: AppContainer) {
    val settings by container.settingsFlow.collectAsStateWithLifecycle()
    val dark = settings.theme.isDark()
    val activity = LocalActivity.current as? ComponentActivity
    // The system bars' icons follow the app's own theme, not only the phone's.
    DisposableEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
        )
        onDispose {}
    }

    ShashaTheme(settings.theme) {
        val navController = rememberNavController()
        val context = LocalContext.current
        // Read when a snackbar is shown, not when this screen was composed: the language may have changed since.
        val resources by rememberUpdatedState(LocalResources.current)
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val progress by container.library.watchedFractions.collectAsStateWithLifecycle(initialValue = emptyMap())
        val savedIds by remember { container.library.watchLaterItems.map { list -> list.mapTo(HashSet<String>()) { it.videoId } as Set<String> } }
            .collectAsStateWithLifecycle(initialValue = emptySet())
        val savedPlaylistIds by container.savedPlaylists.savedIds.collectAsStateWithLifecycle(initialValue = emptySet())
        var downloadTarget by remember { mutableStateOf<VideoItem?>(null) }
        var playlistTarget by remember { mutableStateOf<VideoItem?>(null) }
        val request by container.launch.collectAsStateWithLifecycle()
        val backStack by navController.currentBackStackEntryAsState()
        val destination = backStack?.destination
        // The tab whose pages are on top: the bar marks it also on a page opened from it (a channel, a playlist).
        val stack by navController.currentBackStack.collectAsStateWithLifecycle()
        val currentTab = stack.lastOrNull { tabOf(it.destination) != null }?.destination?.let(::tabOf)
        val reselections = remember { MutableSharedFlow<KClass<*>>(extraBufferCapacity = 1) }

        /**
         * The Shorts viewer, on [videos] (the list the viewer opens from: a channel's Shorts, the Home's row, the search results'),
         * at [index]. [replace]: it takes the place of the player screen it was opened from.
         */
        fun openShorts(videos: List<VideoItem>, index: Int, replace: Boolean = false) {
            val short = videos.getOrNull(index) ?: return
            val queueId = container.shortsQueues.put(videos)
            navController.navigate(ShortsRoute(short.videoId, queueId)) {
                if (replace) popUpTo<WatchRoute> { inclusive = true }
                launchSingleTop = true
            }
        }

        fun openVideo(video: VideoItem, playlistId: String? = null, replace: Boolean = false) {
            // A Short has a player of its own, wherever it was tapped (a related video, a list that knows no better): its own queue of one.
            if (video.isShortForm && playlistId == null && settings.showShorts) {
                openShorts(listOf(video), 0, replace)
                return
            }
            container.player.remember(video)
            navController.navigate(WatchRoute(video.videoId, playlistId)) {
                // The next video replaces this one: back goes to where the viewer came from, not through every video.
                if (replace) popUpTo<WatchRoute> { inclusive = true }
                launchSingleTop = true
            }
        }

        /**
         * A tab always opens at its own first page, never at the page inside it the viewer was on before: everything above the
         * home is dropped (not saved for later) and the tab's page starts fresh. Back from a tab goes to the home.
         */
        fun openTab(route: Any) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
                restoreState = false
            }
        }

        /**
         * A tap on a tab in the bar: another tab opens at its first page ([openTab]); the tab the viewer is in goes back to its
         * first page, and when it is there already its list scrolls back to the top ([LocalTabReselections]).
         */
        fun onTabTapped(tab: Tab) {
            val stack = navController.currentBackStack.value
            val owner = stack.lastOrNull { entry -> tabOf(entry.destination) != null }
            if (owner == null || !owner.destination.hasRoute(tab.routeClass)) {
                openTab(tab.route)
                return
            }
            if (stack.lastOrNull() === owner) {
                scope.launch { reselections.emit(tab.routeClass) }
            } else {
                navController.popBackStack(owner.destination.id, inclusive = false)
            }
        }

        /** The channel's page with the name and picture the opener knows: it shows them when YouTube's own page for the channel cannot be read. */
        fun openChannelPage(channel: ChannelItem) {
            navController.navigate(ChannelRoute.of(channel))
        }

        val actions = remember(container) {
            object : VideoActions {
                override fun toggleWatchLater(video: VideoItem) {
                    scope.launch {
                        val saved = container.library.toggleWatchLater(video)
                        snackbar.showSnackbar(resources.getString(if (saved) R.string.snack_watch_later_added else R.string.snack_watch_later_removed))
                    }
                }

                override fun addToPlaylist(video: VideoItem) {
                    playlistTarget = video
                }

                override fun download(video: VideoItem) {
                    downloadTarget = video
                }

                override fun share(video: VideoItem) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, video.title)
                        .putExtra(Intent.EXTRA_TEXT, "${video.title}\n${watchUrl(video.videoId)}")
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                }

                override fun openChannel(channel: ChannelItem) {
                    openChannelPage(channel)
                }

                override fun toggleSavedPlaylist(playlist: PlaylistItem) {
                    scope.launch {
                        val saved = container.savedPlaylists.toggle(playlist)
                        snackbar.showSnackbar(resources.getString(if (saved) R.string.snack_playlist_saved else R.string.snack_playlist_removed))
                    }
                }
            }
        }

        // A link, a share or a notification asks for a screen.
        LaunchedEffect(request) {
            val r = request ?: return@LaunchedEffect
            container.launch.value = null
            when (r) {
                is LaunchRequest.Video -> navController.navigate(WatchRoute(r.id, r.playlistId, r.startSeconds ?: 0))
                is LaunchRequest.Playlist -> navController.navigate(PlaylistRoute(r.id))
                is LaunchRequest.Channel -> navController.navigate(ChannelRoute(r.raw))
                is LaunchRequest.Search -> openTab(SearchRoute(r.query))
                LaunchRequest.Downloads -> navController.navigate(DownloadsRoute)
            }
        }

        CompositionLocalProvider(
            LocalVideoActions provides actions,
            LocalWatchProgress provides progress,
            LocalWatchLaterIds provides savedIds,
            LocalSavedPlaylistIds provides savedPlaylistIds,
            LocalTabReselections provides reselections,
        ) {
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    // The player screen and the Shorts viewer are the whole screen.
                    if (destination?.hasRoute(WatchRoute::class) != true && destination?.hasRoute(ShortsRoute::class) != true) {
                        NavigationBar {
                            for (tab in tabs) {
                                NavigationBarItem(
                                    selected = currentTab === tab,
                                    onClick = { onTabTapped(tab) },
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(stringResource(tab.label)) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                // Short fades: Navigation's own (0.7 s) made every tap on a tab or a video feel slow.
                NavHost(
                    navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.padding(padding),
                    enterTransition = { fadeIn(tween(NAV_FADE_IN_MS)) },
                    exitTransition = { fadeOut(tween(NAV_FADE_OUT_MS)) },
                    popEnterTransition = { fadeIn(tween(NAV_FADE_IN_MS)) },
                    popExitTransition = { fadeOut(tween(NAV_FADE_OUT_MS)) },
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            container,
                            onOpenVideo = { openVideo(it) },
                            onOpenChannel = { openChannelPage(it) },
                            onOpenShorts = { videos, index -> openShorts(videos, index) },
                            onSearch = { openTab(SearchRoute()) },
                        )
                    }
                    composable<SearchRoute> { entry ->
                        SearchScreen(
                            container,
                            initialQuery = entry.toRoute<SearchRoute>().query,
                            onOpenVideo = { openVideo(it) },
                            onOpenChannel = { openChannelPage(it) },
                            onOpenPlaylist = { navController.navigate(PlaylistRoute(it)) },
                            onOpenShorts = { videos, index -> openShorts(videos, index) },
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            container,
                            onOpenDownloads = { navController.navigate(DownloadsRoute) },
                            onOpenHistory = { navController.navigate(HistoryRoute) },
                            onOpenWatchLater = { navController.navigate(WatchLaterRoute) },
                            onOpenSubscriptions = { navController.navigate(SubscriptionsRoute) },
                            onOpenPlaylist = { navController.navigate(UserPlaylistRoute(it)) },
                            onOpenSavedPlaylist = { navController.navigate(PlaylistRoute(it)) },
                        )
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(container, onOpenAbout = { navController.navigate(AboutRoute) }, onOpenUpdate = { navController.navigate(UpdateRoute) })
                    }
                    composable<ChannelRoute> { entry ->
                        ChannelScreen(
                            container,
                            route = entry.toRoute<ChannelRoute>(),
                            onBack = { navController.popBackStack() },
                            onOpenVideo = { openVideo(it) },
                            onOpenPlaylist = { navController.navigate(PlaylistRoute(it)) },
                            onOpenShorts = { videos, index -> openShorts(videos, index) },
                        )
                    }
                    composable<PlaylistRoute> { entry ->
                        PlaylistScreen(
                            container,
                            playlistId = entry.toRoute<PlaylistRoute>().playlistId,
                            onBack = { navController.popBackStack() },
                            onOpenVideo = { video, playlistId -> openVideo(video, playlistId) },
                        )
                    }
                    composable<UserPlaylistRoute> { entry ->
                        UserPlaylistScreen(
                            container,
                            playlistId = entry.toRoute<UserPlaylistRoute>().playlistId,
                            onBack = { navController.popBackStack() },
                            onPlay = { video, playlistId -> openVideo(video, playlistId) },
                        )
                    }
                    composable<ShortsRoute> { entry ->
                        ShortsScreen(
                            container,
                            route = entry.toRoute<ShortsRoute>(),
                            onBack = { navController.popBackStack() },
                            onOpenChannel = { openChannelPage(it) },
                        )
                    }
                    composable<WatchRoute> { entry ->
                        WatchScreen(
                            container,
                            route = entry.toRoute<WatchRoute>(),
                            onBack = { navController.popBackStack() },
                            onOpenVideo = { video, playlistId -> openVideo(video, playlistId, replace = true) },
                            onOpenChannel = { openChannelPage(it) },
                        )
                    }
                    composable<HistoryRoute> { HistoryScreen(container, onBack = { navController.popBackStack() }, onOpenVideo = { openVideo(it) }) }
                    composable<WatchLaterRoute> { WatchLaterScreen(container, onBack = { navController.popBackStack() }, onOpenVideo = { openVideo(it) }) }
                    composable<SubscriptionsRoute> {
                        SubscriptionsScreen(
                            container,
                            onBack = { navController.popBackStack() },
                            onOpenChannel = { openChannelPage(it) },
                            onSearch = { openTab(SearchRoute()) },
                        )
                    }
                    composable<DownloadsRoute> {
                        DownloadsScreen(
                            container,
                            onBack = { navController.popBackStack() },
                            onPlay = { download ->
                                navController.navigate(WatchRoute(videoId = download.videoId, downloadId = download.id))
                            },
                        )
                    }
                    composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
                    composable<UpdateRoute> { UpdateScreen(container, onBack = { navController.popBackStack() }) }
                }
            }

            playlistTarget?.let { video ->
                PlaylistPickerSheet(container, video, onDismiss = { playlistTarget = null })
            }
            downloadTarget?.let { video ->
                DownloadSheet(
                    container,
                    video,
                    onDismiss = { downloadTarget = null },
                    onStarted = { scope.launch { snackbar.showSnackbar(resources.getString(R.string.snack_download_started)) } },
                )
            }
        }
    }
}
