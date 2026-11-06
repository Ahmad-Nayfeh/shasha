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
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.ahmadnayfeh.shasha.mobile.data.shareUrl
import com.ahmadnayfeh.shasha.mobile.ui.about.AboutScreen
import com.ahmadnayfeh.shasha.mobile.ui.channel.ChannelScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalSavedPlaylistIds
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalTabReselections
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalVideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchLaterIds
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalWatchProgress
import com.ahmadnayfeh.shasha.mobile.ui.components.VideoActions
import com.ahmadnayfeh.shasha.mobile.ui.components.WatchProgress
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
import com.ahmadnayfeh.shasha.mobile.ui.nav.ShortsTabRoute
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsHubScreen
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalChannelAvatars
import com.ahmadnayfeh.shasha.mobile.ui.components.LocalStreamPrefetch
import com.ahmadnayfeh.shasha.mobile.ui.nav.SubscriptionsRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.UpdateRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.UserPlaylistRoute
import com.ahmadnayfeh.shasha.mobile.ui.nav.WatchLaterRoute
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistPickerSheet
import com.ahmadnayfeh.shasha.mobile.ui.playlist.PlaylistScreen
import com.ahmadnayfeh.shasha.mobile.ui.playlist.UserPlaylistScreen
import com.ahmadnayfeh.shasha.mobile.ui.search.SearchScreen
import com.ahmadnayfeh.shasha.mobile.ui.settings.SettingsScreen
import com.ahmadnayfeh.shasha.mobile.ui.shorts.ShortsScreen
import com.ahmadnayfeh.shasha.mobile.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.mobile.ui.theme.isDark
import com.ahmadnayfeh.shasha.mobile.ui.update.UpdateScreen
import com.ahmadnayfeh.shasha.mobile.player.PlayerUiState
import com.ahmadnayfeh.shasha.mobile.ui.comments.CommentsViewModel
import com.ahmadnayfeh.shasha.mobile.ui.watch.MiniPlayer
import com.ahmadnayfeh.shasha.mobile.ui.watch.NowPlaying
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchEvent
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchRequest
import com.ahmadnayfeh.shasha.mobile.ui.watch.WatchScreen
import com.ahmadnayfeh.shasha.mobile.ui.watch.pictureInPictureOnLeave
import com.ahmadnayfeh.shasha.mobile.ui.watch.rememberWatchViewModel
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

private class Tab(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/** The tab whose first page [destination] is; null for every other page. */
private fun tabOf(destination: NavDestination): Tab? = tabs.firstOrNull { destination.hasRoute(it.routeClass) }

/** The bottom bar, for the tests. */
internal const val NAV_BAR_TAG = "nav_bar"

private const val NAV_FADE_IN_MS = 180
private const val NAV_FADE_OUT_MS = 120

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    Tab(ShortsTabRoute, ShortsTabRoute::class, R.string.nav_shorts, Icons.Outlined.SmartDisplay, Icons.Filled.SmartDisplay),
    Tab(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
)

/** The app: the theme, the four tabs along the bottom (Home, Shorts, You; Subscriptions is a tab on the Home; Search and Settings are in the top bars), every screen, and the things all of them share (menus, sheets). */
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
        // Read through the bars under the thumbnails only (WatchedBar): its updates, every few seconds while a video plays, do not
        // recompose this screen.
        val progressState = container.library.watchedFractions.collectAsStateWithLifecycle(initialValue = emptyMap())
        val progress = remember(progressState) { WatchProgress { progressState.value[it] } }
        val savedIds by remember { container.library.watchLaterItems.map { list -> list.mapTo(HashSet<String>()) { it.videoId } as Set<String> } }
            .collectAsStateWithLifecycle(initialValue = emptySet())
        // The followed channels' pictures, for the circle on a video's card.
        val channelAvatars by remember {
            container.subscriptions.subscriptions.map { list -> list.mapNotNull { c -> c.avatarUrl?.let { c.channelId to it } }.toMap() }
        }.collectAsStateWithLifecycle(initialValue = emptyMap())
        // The Shorts the Home holds: what the Shorts tab opens the viewer on.
        val homeVm: com.ahmadnayfeh.shasha.mobile.ui.home.HomeViewModel = viewModel(factory = com.ahmadnayfeh.shasha.mobile.ui.home.HomeViewModel.factory(container))
        val homeStateHolder = homeVm.state.collectAsStateWithLifecycle()
        val homeShorts by remember { derivedStateOf { homeStateHolder.value.shorts } }
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
        // The app's one video, above the navigation: the watch page expanded, or the mini player above the bar.
        val nowPlaying: NowPlaying = viewModel(factory = NowPlaying.factory(container))
        val session = nowPlaying.session
        val bridge = LocalActivityBridge.current
        val inPip by bridge.inPictureInPicture.collectAsStateWithLifecycle()
        // The player's state changes often (captions, buffering): this screen reads only the few things it needs of it (derived
        // below, each recomposing it only when it changes); the mini player reads the rest in its own scope.
        val playerStateHolder = container.player.state.collectAsStateWithLifecycle()
        val playerPlaying by remember { derivedStateOf { playerStateHolder.value.isPlaying } }
        val playerAspect by remember { derivedStateOf { playerStateHolder.value.aspectRatio } }
        val pipWanted by remember { derivedStateOf { pictureInPictureOnLeave(playerStateHolder.value, setting = true) } }
        // Where the video is in the window (the watch page's picture, or the mini player's): picture-in-picture grows out of it.
        var videoBounds by remember { mutableStateOf<Rect?>(null) }
        // The floating window shows the watch page's picture, and the page is what the viewer comes back to from it.
        LaunchedEffect(inPip) { if (inPip) nowPlaying.expand() }
        val watchShown = session != null && (nowPlaying.expanded || inPip)
        val miniShown = session != null && !watchShown
        val onShorts = destination?.hasRoute(ShortsRoute::class) == true
        // The Shorts viewer takes the player for itself (also when the viewer comes back to it from a detour): the video goes.
        LaunchedEffect(onShorts) { if (onShorts) nowPlaying.dismiss() }
        // A video that really played lets the Home's suggestions change when the viewer comes back to it (and only then).
        val playingId by remember { derivedStateOf { playerStateHolder.value.let { s -> s.video?.videoId?.takeIf { s.isPlaying } } } }
        LaunchedEffect(playingId) { playingId?.let(container.stableSuggestions::markPlayed) }

        /**
         * The Shorts viewer, on [videos] (the list the viewer opens from: a channel's Shorts, the Home's row, the search results'),
         * at [index]. It takes the player over: the video that was open (expanded or in the mini player) goes.
         */
        fun openShorts(videos: List<VideoItem>, index: Int) {
            val short = videos.getOrNull(index) ?: return
            val queueId = container.shortsQueues.put(videos)
            nowPlaying.dismiss()
            navController.navigate(ShortsRoute(short.videoId, queueId)) { launchSingleTop = true }
        }

        /**
         * Opens [request] on the watch page, over whatever page the viewer is on, in place of the video that was open ([expand]
         * false: in the mini player, where the video before was). The Shorts viewer, when it is in front, makes way for it.
         */
        fun play(request: WatchRequest, expand: Boolean = true) {
            if (navController.currentDestination?.hasRoute(ShortsRoute::class) == true) navController.popBackStack()
            nowPlaying.open(request, expand)
        }

        fun openVideo(video: VideoItem, playlistId: String? = null, expand: Boolean = true) {
            // A Short has a player of its own, wherever it was tapped (a related video, a list that knows no better): its own queue of one.
            if (video.isShortForm && playlistId == null && settings.showShorts) {
                openShorts(listOf(video), 0)
                return
            }
            container.player.remember(video)
            container.player.prefetch(video.videoId) // the streams are looked up while the screen opens
            play(WatchRequest(video.videoId, playlistId), expand)
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
            // The Shorts tab opens the vertical player at once, on the Shorts the Home holds; with none yet, its page says so.
            if (tab.routeClass == ShortsTabRoute::class && homeShorts.isNotEmpty()) {
                openShorts(homeShorts, 0)
                return
            }
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

        /**
         * The channel's page with the name and picture the opener knows: it shows them when YouTube's own page for the channel cannot be
         * read. Opened from the watch page, the video goes on in the mini player under it.
         */
        fun openChannelPage(channel: ChannelItem) {
            nowPlaying.collapse()
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
                        .putExtra(Intent.EXTRA_TEXT, "${video.title}\n${shareUrl(video)}")
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
            // Another page asked for: the video that is open goes on in the mini player.
            if (r !is LaunchRequest.Video) nowPlaying.collapse()
            when (r) {
                is LaunchRequest.Video -> play(WatchRequest(r.id, r.playlistId, r.startSeconds ?: 0))
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
            LocalChannelAvatars provides channelAvatars,
        ) {
            val watchVm = session?.let { key(it) { rememberWatchViewModel(container, it) } }
            // The video's comments live as long as the video (a downloaded file has none); the setting can take them away.
            val commentsVm = session?.takeIf { settings.showComments && it.request.downloadId < 0 }?.let { s ->
                key(s) { viewModel<CommentsViewModel>(viewModelStoreOwner = s, key = "comments", factory = CommentsViewModel.factory(container, s.request.videoId)) }
            }
            if (session != null && watchVm != null) {
                key(session) {
                    // The app comes back to the front: if the player was let go meanwhile, the open video plays again.
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { watchVm.onShown() }
                    // Playlists go on by themselves, also in the mini player.
                    LaunchedEffect(watchVm) {
                        watchVm.eventFlow.collect { event ->
                            if (event is WatchEvent.PlayNext) openVideo(event.video, watchVm.playlistId, expand = nowPlaying.expanded)
                        }
                    }
                }
                // Leaving the app while the video plays (on the watch page or in the mini player) turns it into a floating window. The
                // window's parameters are kept current all along (not set when the viewer leaves, which is too late for Android 12's
                // automatic entry); the video's going takes them back.
                val autoEnterPip = pipWanted && settings.pictureInPicture
                LaunchedEffect(autoEnterPip, playerAspect, videoBounds) {
                    bridge.setPictureInPicture(autoEnter = autoEnterPip, aspect = playerAspect, sourceRect = videoBounds)
                }
                DisposableEffect(Unit) {
                    onDispose { bridge.setPictureInPicture(autoEnter = false, aspect = PlayerUiState.DEFAULT_ASPECT, sourceRect = null) }
                }
                // The screen stays on while the video plays.
                val view = LocalView.current
                DisposableEffect(playerPlaying) {
                    view.keepScreenOn = playerPlaying
                    onDispose { view.keepScreenOn = false }
                }
            }

            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    // Over the watch page while it is up (below), else above the bar.
                    snackbarHost = { if (!watchShown) SnackbarHost(snackbar) },
                    bottomBar = {
                        // The watch page and the Shorts viewer are the whole screen; the mini player sits on the bar.
                        if (!watchShown && !onShorts) Column {
                            if (miniShown && session != null) {
                                MiniPlayer(
                                    state = playerStateHolder.value,
                                    player = container.player.player,
                                    videoId = session.request.videoId,
                                    onExpand = nowPlaying::expand,
                                    onTogglePlay = container.player::togglePlay,
                                    onRetry = container.player::retry,
                                    onClose = nowPlaying::close,
                                    onVideoBounds = { videoBounds = it },
                                )
                            }
                            NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, modifier = Modifier.testTag(NAV_BAR_TAG)) {
                                for (tab in tabs) {
                                    NavigationBarItem(
                                        selected = currentTab === tab,
                                        onClick = { onTabTapped(tab) },
                                        icon = { Icon(if (currentTab === tab) tab.selectedIcon else tab.icon, contentDescription = null) },
                                        label = { Text(stringResource(tab.label), style = MaterialTheme.typography.labelSmall) },
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = Color.Transparent,
                                            selectedIconColor = MaterialTheme.colorScheme.onBackground,
                                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                                            unselectedIconColor = MaterialTheme.colorScheme.onBackground,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
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
                        // Under the watch page the pages stay as the viewer left them, out of reach (and out of the accessibility tree).
                        modifier = Modifier.padding(padding).then(if (watchShown) Modifier.clearAndSetSemantics {} else Modifier),
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
                                onManageSubscriptions = { navController.navigate(SubscriptionsRoute) },
                                covered = watchShown,
                            )
                        }
                        composable<ShortsTabRoute> {
                            ShortsHubScreen(container, onOpenShorts = { videos, index -> openShorts(videos, index) })
                        }
                        composable<SearchRoute> { entry ->
                            SearchScreen(
                                container,
                                initialQuery = entry.toRoute<SearchRoute>().query,
                                onOpenVideo = { openVideo(it) },
                                onOpenChannel = { openChannelPage(it) },
                                onOpenPlaylist = { navController.navigate(PlaylistRoute(it)) },
                                onOpenShorts = { videos, index -> openShorts(videos, index) },
                                onBack = { navController.popBackStack() },
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
                                onOpenSettings = { navController.navigate(SettingsRoute) },
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(container, onBack = { navController.popBackStack() }, onOpenAbout = { navController.navigate(AboutRoute) }, onOpenUpdate = { navController.navigate(UpdateRoute) })
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
                                onPlay = { download -> play(WatchRequest(videoId = download.videoId, downloadId = download.id)) },
                            )
                        }
                        composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
                        composable<UpdateRoute> { UpdateScreen(container, onBack = { navController.popBackStack() }) }
                    }
                }
                if (watchShown && watchVm != null) {
                    WatchScreen(
                        container,
                        vm = watchVm,
                        onCollapse = nowPlaying::collapse,
                        onOpenVideo = { video, playlistId -> openVideo(video, playlistId) },
                        onOpenChannel = { openChannelPage(it) },
                        onVideoBounds = { videoBounds = it },
                        comments = commentsVm,
                    )
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
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
