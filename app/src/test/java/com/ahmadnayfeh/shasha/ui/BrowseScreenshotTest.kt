package com.ahmadnayfeh.shasha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ImportResult
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ConfirmPanel
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.components.ShashaIcons
import com.ahmadnayfeh.shasha.ui.components.VideoMenuPanel
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelHeader
import com.ahmadnayfeh.shasha.ui.screens.channel.ChannelScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeState
import com.ahmadnayfeh.shasha.ui.screens.home.HomeSync
import com.ahmadnayfeh.shasha.ui.screens.library.HistoryScreen
import com.ahmadnayfeh.shasha.ui.screens.library.SubscriptionsScreen
import com.ahmadnayfeh.shasha.ui.screens.library.WatchLaterScreen
import com.ahmadnayfeh.shasha.ui.screens.player.EndCard
import com.ahmadnayfeh.shasha.ui.screens.player.PlaybackEnd
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.screens.playlist.PlaylistScreen
import com.ahmadnayfeh.shasha.ui.screens.search.SearchCallbacks
import com.ahmadnayfeh.shasha.ui.screens.search.SearchScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.ImportScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.ImportStatus
import com.ahmadnayfeh.shasha.ui.screens.settings.SettingsScreen
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM screenshots of the browsing screens (phase 2), with fake data and a fixed clock, at 1920x1080.
 * Record: ./gradlew :app:recordRoborazziDebug; CI verifies them (:app:verifyRoborazziDebug).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class BrowseScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalCoilApi::class)
    private fun snap(name: String, keys: List<Key> = emptyList(), content: @Composable () -> Unit) {
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme { content() }
            }
        }
        compose.waitForIdle()
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Shell(tab: Tab, focusRail: Boolean = false, content: @Composable () -> Unit) {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        if (focusRail) {
            LaunchedEffect(Unit) {
                withFrameNanos { }
                itemFocus.getValue(tab).requestFocus()
            }
        }
        MainShell(tab, {}, itemFocus) { content() }
    }

    @Composable
    private fun <T> stateOf(value: T) = remember { mutableStateOf(value) }

    @Composable
    private fun Home(state: HomeState, sync: HomeSync, focus: ContentFocus = remember { ContentFocus() }, watched: Map<String, Float> = UiFixtures.watched) =
        HomeScreen(state, stateOf(watched), sync, NOW, focus, "v0.26.5", onRefresh = {}, onPlay = {}, onImport = {}, onSearch = {}, onCheckUpdates = {})

    private val feed = HomeState(loaded = true, videos = UiFixtures.videoUis, subscriptionCount = 20)
    private val synced = SyncHealth(channels = 20, attempted = 20, lastAttemptAtMs = NOW - 5 * 60_000)

    @Test
    fun home() = snap("home") {
        Shell(Tab.HOME) { Home(feed, HomeSync(health = synced)) }
    }

    @Test
    fun homeEmpty() = snap("home_empty") {
        Shell(Tab.HOME) { Home(HomeState(loaded = true), HomeSync(health = SyncHealth()), watched = emptyMap()) }
    }

    @Test
    fun homeSyncFailing() = snap("home_sync_failing") {
        // YouTube changed something: 18 of 20 channels failed their last sync. The feed stays usable.
        val failing = synced.copy(failed = 18, lastError = Fixtures.error(SourceErrorKind.EXTRACTION_BROKEN, "ParsingException"))
        Shell(Tab.HOME) { Home(feed, HomeSync(health = failing)) }
    }

    @Test
    fun homeSyncFailed() = snap("home_sync_failed") {
        // Every channel failed and nothing was ever fetched: the unified error instead of an endless spinner.
        val failed = synced.copy(failed = 20, lastError = Fixtures.error(SourceErrorKind.BOT_CHECK, "SignInConfirmNotBotException"))
        Shell(Tab.HOME) { Home(HomeState(loaded = true, subscriptionCount = 20), HomeSync(health = failed), watched = emptyMap()) }
    }

    @Test
    fun homeOffline() = snap("home_offline") {
        // Just imported on a TV without internet: the first sync cannot start.
        Shell(Tab.HOME) { Home(HomeState(loaded = true, subscriptionCount = 20), HomeSync(online = false, health = SyncHealth(channels = 20)), watched = emptyMap()) }
    }

    @Test
    fun railExpanded() = snap("rail_expanded") {
        Shell(Tab.HOME, focusRail = true) { Home(feed, HomeSync(running = true, health = synced), focus = remember { ContentFocus(pending = false) }) }
    }

    @Test
    fun searchResults() = snap("search_results", keys = listOf(Key.DirectionDown, Key.DirectionDown)) {
        Shell(Tab.SEARCH) {
            SearchScreen(
                query = "الخط العربي",
                filter = SearchFilter.ALL,
                results = PagedState(items = UiFixtures.searchResults, loadingFirst = false),
                subscribed = stateOf(UiFixtures.subscribedIds),
                watched = stateOf(mapOf(UiFixtures.videos[5].videoId to 0.6f)),
                nowMs = NOW,
                focus = remember { ContentFocus() },
                extractorVersion = "v0.26.5",
                callbacks = SearchCallbacks({}, {}, {}, {}, {}, {}, {}, {}),
            )
        }
    }

    @Test
    fun channel() = snap("channel") {
        ChannelScreen(
            channelId = UiFixtures.calligraphyChannel.channelId,
            nameHint = null,
            header = ChannelHeader.Ready(UiFixtures.channelDetails),
            videos = PagedState(items = UiFixtures.channelVideos, loadingFirst = false),
            subscribed = true,
            watched = stateOf(mapOf(UiFixtures.channelVideos[1].video.videoId to 0.5f)),
            nowMs = NOW,
            focus = remember { ContentFocus() },
            extractorVersion = "v0.26.5",
            onToggleSubscription = {},
            onUnsubscribe = {},
            onPlay = {},
            onLoadMore = {},
            onRetryMore = {},
            onBack = {},
            onCheckUpdates = {},
        )
    }

    @Test
    fun channelGone() = snap("channel_gone") {
        // A terminated channel from an old Takeout export: it can be unsubscribed right here.
        ChannelScreen(
            channelId = UiFixtures.calligraphyChannel.channelId,
            nameHint = null,
            header = ChannelHeader.Failed(Fixtures.error(SourceErrorKind.ACCOUNT_TERMINATED, "AccountTerminatedException")),
            videos = PagedState(items = emptyList(), loadingFirst = true),
            subscribed = true,
            watched = stateOf(emptyMap()),
            nowMs = NOW,
            focus = remember { ContentFocus() },
            extractorVersion = "v0.26.5",
            onToggleSubscription = {},
            onUnsubscribe = {},
            onPlay = {},
            onLoadMore = {},
            onRetryMore = {},
            onBack = {},
            onCheckUpdates = {},
        )
    }

    @Test
    fun playlist() = snap("playlist") {
        PlaylistScreen(
            details = UiFixtures.playlist,
            videos = PagedState(items = UiFixtures.playlistVideos, loadingFirst = false, loadingMore = true),
            watched = stateOf(UiFixtures.playlistWatched),
            focus = remember { ContentFocus() },
            extractorVersion = "v0.26.5",
            onPlayFrom = {},
            onLoadMore = {},
            onRetryMore = {},
            onBack = {},
            onCheckUpdates = {},
        )
    }

    @Test
    fun playlistMoreFailed() = snap("playlist_more_failed") {
        // A later page failed: the unified error's title, name and extractor version, in the narrow list.
        PlaylistScreen(
            details = UiFixtures.playlist,
            videos = PagedState(
                items = UiFixtures.playlistVideos.take(3),
                loadingFirst = false,
                moreError = Fixtures.error(SourceErrorKind.EXTRACTION_BROKEN, "ParsingException"),
            ),
            watched = stateOf(UiFixtures.playlistWatched),
            focus = remember { ContentFocus() },
            extractorVersion = "v0.26.5",
            onPlayFrom = {},
            onLoadMore = {},
            onRetryMore = {},
            onBack = {},
            onCheckUpdates = {},
        )
    }

    @Test
    fun watchLater() = snap("watch_later") {
        Shell(Tab.WATCH_LATER) {
            WatchLaterScreen(UiFixtures.watchLater, stateOf(mapOf(UiFixtures.videos[6].videoId to 0.72f)), NOW, remember { ContentFocus() }) {}
        }
    }

    @Test
    fun history() = snap("history") {
        Shell(Tab.HISTORY) {
            HistoryScreen(UiFixtures.history, stateOf(UiFixtures.watched), NOW, remember { ContentFocus() }, onPlay = {}, onClear = {})
        }
    }

    @Test
    fun subscriptions() = snap("subscriptions") {
        Shell(Tab.SUBSCRIPTIONS) {
            SubscriptionsScreen(UiFixtures.subscriptions, remember { ContentFocus() }, { _, _ -> }, {}, {})
        }
    }

    @Test
    fun settings() = snap("settings") {
        Shell(Tab.SETTINGS) {
            SettingsScreen(
                remember { ContentFocus() },
                "0.1.0",
                "v0.26.5",
                onProfiles = {},
                onBlocklist = {},
                onStats = {},
                onChangePin = {},
                onImport = {},
                onDiagnostics = {},
                protection = ProtectionStatus(overlay = true, usageAccess = true, installPackages = true, guardRunning = true, youtube = YouTubeAppState.DISABLED),
            )
        }
    }

    @Test
    fun importWaiting() = snap("settings_import") {
        ImportScreen(ImportStatus.Waiting, ADB_COMMAND, onPick = {}, onDone = {})
    }

    @Test
    fun importDone() = snap("settings_import_done") {
        ImportScreen(ImportStatus.Done(ImportResult(added = 18, alreadyPresent = 2, invalid = 1)), ADB_COMMAND, onPick = {}, onDone = {})
    }

    @Test
    fun playerOsdWatchLaterAndResume() = snap("player_osd_watch_later_resume") {
        OverFakeVideo {
            PlayerOsd(
                state = Fixtures.osd,
                progress = { PlaybackProgress(positionMs = 757_000, bufferedMs = 820_000, durationMs = 1_510_000) },
                onAction = {},
                onExit = {},
                autoHideMs = NEVER,
                savedForLater = true,
                onToggleWatchLater = {},
                resumedFromMs = 754_000,
                resumeHintMs = NEVER,
            )
        }
    }

    @Test
    fun endCard() = snap("player_end_card") {
        OverFakeVideo { EndCard(PlaybackEnd.Finished, Fixtures.osd.title, onBack = {}, onReplay = {}, onPlayNext = {}) }
    }

    @Test
    fun upNext() = snap("player_up_next") {
        OverFakeVideo {
            EndCard(PlaybackEnd.UpNext(UiFixtures.playlistVideos[3].video), "", onBack = {}, onReplay = {}, onPlayNext = {}, tickMs = NEVER)
        }
    }

    @Test
    fun videoMenu() = snap("video_menu") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Shell(Tab.HOME) {
                Home(feed, HomeSync(health = synced), focus = remember { ContentFocus(pending = false) }, watched = emptyMap())
            }
            // What the dialog window's dim looks like.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
            VideoMenuPanel(UiFixtures.videos[1], savedForLater = false, onToggleWatchLater = {}, onOpenChannel = {})
        }
    }

    @Test
    fun clearHistoryConfirm() = snap("history_clear_confirm") {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ConfirmPanel("مسح سجل المشاهدة؟", "تُحذف كل الفيديوهات من السجل ولا يمكن التراجع. مواضع الاستكمال تبقى كما هي.", "مسح", ShashaIcons.Delete, {}, {})
        }
    }

    @Composable
    private fun OverFakeVideo(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize()) {
            Image(Fixtures.fakePicture(1920, 1080).asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            content()
        }
    }

    private companion object {
        const val NEVER = 3_600_000L
        const val ADB_COMMAND = "adb push subscriptions.csv \\\n    /sdcard/Android/data/com.ahmadnayfeh.shasha/files/import/subscriptions.csv"
    }
}
