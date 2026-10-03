package com.ahmadnayfeh.shasha.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.Fixtures
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.player.PlaybackProgress
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.UiFixtures.NOW
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.main.MainShell
import com.ahmadnayfeh.shasha.ui.main.Tab
import com.ahmadnayfeh.shasha.ui.model.ItemUi
import com.ahmadnayfeh.shasha.ui.paging.PagedState
import com.ahmadnayfeh.shasha.ui.screens.home.HomeScreen
import com.ahmadnayfeh.shasha.ui.screens.home.HomeState
import com.ahmadnayfeh.shasha.ui.screens.home.HomeSync
import com.ahmadnayfeh.shasha.ui.screens.player.EndCard
import com.ahmadnayfeh.shasha.ui.screens.player.PlaybackEnd
import com.ahmadnayfeh.shasha.ui.screens.player.PlayerOsd
import com.ahmadnayfeh.shasha.ui.screens.search.SearchCallbacks
import com.ahmadnayfeh.shasha.ui.screens.search.SearchScreen
import com.ahmadnayfeh.shasha.ui.screens.settings.ProfileEditScreen
import com.ahmadnayfeh.shasha.ui.theme.KidsAccent
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.SearchFilter
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * v1.1.1's screens: «الفيديوهات المقترحة» (the home's row, the player's row under its controls, the end card), Shorts
 * (the home's row, the «Shorts» badge on a tile, a vertical video in the player), the red progress
 * bars of السجل, and the profile page's two switches. JVM screenshots at 1920x1080, like the others.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class, ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = TV_1080P)
class SuggestionsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

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
    private fun Shell(tab: Tab, content: @Composable () -> Unit) {
        val itemFocus = remember { Tab.entries.associateWith { FocusRequester() } }
        MainShell(tab, {}, itemFocus) { content() }
    }

    @Composable
    private fun <T> stateOf(value: T) = remember { mutableStateOf(value) }

    /** The feed without its Shorts (they have their own row), as HomeViewModel gives it. */
    private val feed = HomeState(
        loaded = true,
        videos = UiFixtures.videoUis,
        subscriptionCount = 20,
        shorts = UiFixtures.shorts,
        suggested = UiFixtures.suggested,
    )
    private val synced = HomeSync(health = SyncHealth(channels = 20, attempted = 20, lastAttemptAtMs = NOW - 5 * 60_000))
    private val watched = UiFixtures.watched + mapOf(UiFixtures.suggested[1].video.videoId to 0.45f, UiFixtures.shorts[2].video.videoId to 1f)

    @Composable
    private fun Home(focus: ContentFocus = remember { ContentFocus() }) =
        HomeScreen(feed, stateOf(watched), synced, NOW, focus, "v0.26.5", onRefresh = {}, onPlay = {}, onImport = {}, onSearch = {}, onCheckUpdates = {})

    @Test
    fun homeSuggestions() = snap("home_suggestions", keys = listOf(Key.DirectionDown)) {
        // DOWN from the newest video: «مقترحة لك», under the feed's first line.
        Shell(Tab.HOME) { Home() }
    }

    @Test
    fun homeShorts() = snap("home_shorts", keys = listOf(Key.DirectionDown, Key.DirectionDown)) {
        // Once more: the «Shorts» row, the subscribed channels' newest Shorts in portrait tiles.
        Shell(Tab.HOME) { Home() }
    }

    @Test
    fun shortsBadge() = snap("search_results_shorts", keys = listOf(Key.DirectionDown, Key.DirectionDown)) {
        // Where Shorts show, a Short is a tile like the others, with the «Shorts» badge.
        val results: List<ItemUi> = listOf(
            UiFixtures.searchResults[1],
            UiFixtures.shorts[0],
            UiFixtures.searchResults[3],
            UiFixtures.suggested[2],
            UiFixtures.searchResults[5],
            UiFixtures.shorts[3],
            UiFixtures.searchResults[6],
            UiFixtures.searchResults[7],
        )
        Shell(Tab.SEARCH) {
            SearchScreen(
                query = "الخط العربي",
                filter = SearchFilter.VIDEOS,
                results = PagedState(items = results, loadingFirst = false),
                subscribed = stateOf(UiFixtures.subscribedIds),
                watched = stateOf(mapOf(UiFixtures.shorts[0].video.videoId to 0.5f)),
                nowMs = NOW,
                focus = remember { ContentFocus() },
                extractorVersion = "v0.26.5",
                callbacks = SearchCallbacks({}, {}, {}, {}, {}, {}, {}, {}),
            )
        }
    }

    // A channel's Shorts are a section of its page now: ChannelSectionsScreenshotTest takes «channel_shorts».

    private val suggestions = UiFixtures.suggested.map { it.video }
    private val suggestionsWatched = mapOf(suggestions[1].videoId to 0.45f, suggestions[4].videoId to 1f)

    @Test
    fun playerSuggestionsFolded() = snap("player_suggestions_folded") {
        // The controls where they always are; «مقترحة» under them, DOWN opens it.
        OverFakeVideo {
            PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, autoHideMs = NEVER, suggestions = suggestions, suggestionsWatched = suggestionsWatched)
        }
    }

    @Test
    fun playerSuggestions() = snap("player_suggestions", keys = listOf(Key.DirectionDown, Key.DirectionDown)) {
        // From the seek bar: DOWN to the controls, DOWN again into the row, which rises whole.
        OverFakeVideo {
            PlayerOsd(Fixtures.osd, { Fixtures.progress }, {}, {}, autoHideMs = NEVER, suggestions = suggestions, suggestionsWatched = suggestionsWatched)
        }
    }

    @Test
    fun endCardSuggestions() = snap("player_end_card_suggestions") {
        OverFakeVideo {
            EndCard(
                PlaybackEnd.Finished,
                Fixtures.osd.title,
                onBack = {},
                onReplay = {},
                onPlayNext = {},
                suggestions = suggestions,
                suggestionsWatched = suggestionsWatched,
            )
        }
    }

    @Test
    fun kidsEndCardSuggestions() = snap("player_kids_end_card_suggestions") {
        // A child whose parent turned suggestions on (its allowed channels only, no Shorts): the card keeps its
        // countdown home above the row; moving into the row stops it.
        KidsAccent {
            OverFakeVideo {
                EndCard(
                    PlaybackEnd.Finished,
                    "حكاية الأرنب الشجاع",
                    onBack = {},
                    onReplay = {},
                    onPlayNext = {},
                    tickMs = NEVER,
                    kidsHome = {},
                    suggestions = suggestions.filterNot { it.isShortForm },
                    suggestionsWatched = suggestionsWatched,
                )
            }
        }
    }

    @Test
    fun stillLockedEndCardSuggestions() = snap("player_skipped_end_card_suggestions") {
        // Skipped to the end in a locked video: the note says the lock stays, and the row still fits under the card.
        OverFakeVideo {
            EndCard(
                PlaybackEnd.Finished,
                Fixtures.osd.title,
                onBack = {},
                onReplay = {},
                onPlayNext = {},
                stillLocked = true,
                suggestions = suggestions,
                suggestionsWatched = suggestionsWatched,
            )
        }
    }

    @Test
    fun verticalVideo() = snap("player_vertical") {
        // A Short plays as the player shows any video: scaled to fit (ContentFrame's ContentScale.Fit, the same
        // resizeWithContentScale), so a portrait picture stands centred and whole between black bars.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                Fixtures.fakePicture(1080, 1920, seed = 1).asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.resizeWithContentScale(ContentScale.Fit, Size(1080f / 2, 1920f / 2)),
            )
            PlayerOsd(
                Fixtures.osd.copy(title = "قنديل البحر الذي لا يموت", channelName = "عالم البحار", qualityOptions = listOf(1080, 720, 480), currentHeight = 1080),
                { PlaybackProgress(positionMs = 21_000, bufferedMs = 46_000, durationMs = 46_000) },
                {},
                {},
                autoHideMs = NEVER,
                suggestions = suggestions,
                suggestionsWatched = suggestionsWatched,
            )
        }
    }

    @Test
    fun profileSwitches() = snap("profile_edit_shorts", keys = List(5) { Key.DirectionDown }) {
        // The child's page, scrolled to «إظهار مقاطع Shorts» and «إظهار الفيديوهات المقترحة» (focused), each saying what
        // it does.
        val sara = Profile.newChild("سارة", id = "sara", avatar = "rocket").copy(
            allowlist = Allowlist(channels = listOf(ListedChannel("UCkids00000000000000000a1", "عالم الطبيعة"))),
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvScrolling) {
            KidsAccent { ProfileEditScreen(sara, canDelete = true, onUpdate = {}, onOpenAllowlist = {}, onDelete = {}) }
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

        /**
         * How Compose follows the focus on Android TV: the focused element settles at 30% of the list's height (the
         * test's default, a phone's, scrolls only as far as the element's edge, leaving its zoom and glow cut).
         */
        val TvScrolling = object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val pivot = 0.3f * containerSize
                val target = if (size <= containerSize && containerSize - pivot < size) containerSize - size else pivot
                return offset - target
            }
        }
    }
}
