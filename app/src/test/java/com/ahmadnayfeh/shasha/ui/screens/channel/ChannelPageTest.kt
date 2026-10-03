package com.ahmadnayfeh.shasha.ui.screens.channel

import android.app.Application
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.MutableProfile
import com.ahmadnayfeh.shasha.data.SubscriptionRepository
import com.ahmadnayfeh.shasha.data.WatchRepository
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.ContentFilter
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.PolicyEngine
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.ui.UiFixtures
import com.ahmadnayfeh.shasha.ui.components.ContentFocus
import com.ahmadnayfeh.shasha.ui.model.VideoUi
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.CHANNEL
import com.ahmadnayfeh.shasha.ui.paging.ScriptedSource.Companion.videos
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.ahmadnayfeh.shasha.youtube.ChannelSort
import com.ahmadnayfeh.shasha.youtube.ChannelTab
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Clock

/**
 * A channel's page as a viewer drives it: the real view model and the real rules under the real screen, a fake source whose
 * sections are scripted, and the remote's keys. UP climbs to the chips, LEFT and RIGHT move along them, OK picks one (the
 * list under it changes, the focus stays), DOWN comes back to the cards, OK on a card plays the video or opens the playlist.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = TV_1080P)
class ChannelPageTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: ShashaDatabase
    private val source = FakeChannelSource()
    private val engine = PolicyEngine(Clock.systemUTC())
    private val rules = MutableStateFlow(ContentFilter.of(engine, Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)))
    private val played = mutableListOf<String>()
    private val opened = mutableListOf<String>()

    @Before
    fun setUp() {
        // Every database call runs right where it is made, so the page is settled when a call returns.
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ShashaDatabase::class.java)
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .allowMainThreadQueries()
            .build()
        source.sections[ChannelTab.VIDEOS to ChannelSort.LATEST] = listOf(videos(1..30))
        source.sections[ChannelTab.VIDEOS to ChannelSort.POPULAR] = listOf(videos(201..230))
        source.sections[ChannelTab.SHORTS to ChannelSort.LATEST] = listOf(videos(101..112, short = { true }))
        source.sections[ChannelTab.PLAYLISTS to ChannelSort.LATEST] = listOf(listOf(FakeChannelSource.playlist(1), FakeChannelSource.playlist(2)))
    }

    @After
    fun tearDown() = db.close()

    private fun show(): ChannelViewModel {
        val vm = ChannelViewModel(
            CHANNEL,
            source,
            SubscriptionRepository(db, source),
            WatchRepository(db, MutableProfile()),
            CoroutineScope(Dispatchers.Unconfined),
            rules,
        )
        val pictures = AsyncImagePreviewHandler { request -> UiFixtures.fakeThumbnail(request.data.toString()).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) {
                ShashaTheme {
                    val header by vm.header.collectAsState()
                    val sections by vm.sections.collectAsState()
                    val items by vm.items.collectAsState()
                    ChannelScreen(
                        channelId = CHANNEL,
                        nameHint = "Channel",
                        header = header,
                        sections = sections,
                        items = items,
                        subscribed = false,
                        watched = vm.watched.collectAsState(),
                        nowMs = UiFixtures.NOW,
                        focus = remember { ContentFocus() },
                        extractorVersion = "test",
                        onToggleSubscription = vm::toggleSubscription,
                        onUnsubscribe = {},
                        onPlay = { played += it.videoId },
                        onOpenPlaylist = { opened += it },
                        onSelectTab = vm::selectTab,
                        onSelectSort = vm::selectSort,
                        onLoadMore = vm::loadMore,
                        onRetryMore = vm::retryMore,
                        onRetry = vm::retry,
                        onBack = {},
                        onCheckUpdates = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        return vm
    }

    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    private fun press(key: Key) = press(listOf(key))

    private fun id(n: Int) = "vid" + n.toString().padStart(8, '0')

    private val alongToThePlaylists = listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft)

    @Test
    fun thePageOpensOnTheVideos_withItsSectionsAndOrders_andOkPlaysTheFocusedCard() {
        show()

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0")
        for (tag in listOf("channel_tab_VIDEOS", "channel_tab_SHORTS", "channel_tab_LIVE", "channel_tab_PLAYLISTS")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        for (tag in listOf("channel_sort_LATEST", "channel_sort_POPULAR", "channel_sort_OLDEST")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        // The focus starts on the newest video: OK plays it.
        press(Key.DirectionCenter)
        assertThat(played).containsExactly(id(1))
    }

    @Test
    fun pickingAnotherSection_listsIt_andTheFocusStaysOnItsChip() {
        val vm = show()

        press(listOf(Key.DirectionUp, Key.DirectionLeft))
        compose.onNodeWithTag("channel_tab_SHORTS").assertIsFocused()
        press(Key.DirectionCenter)

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "SHORTS:LATEST:0").inOrder()
        compose.onNodeWithTag("channel_tab_SHORTS").assertIsFocused()
        assertThat(vm.sections.value.selected).isEqualTo(ChannelTab.SHORTS)
        // Portrait tiles now.
        assertThat(compose.onAllNodesWithTag("short_card").fetchSemanticsNodes()).isNotEmpty()
        // DOWN to one of them, OK plays it: one of the channel's Shorts.
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(played.single()).isIn((101..112).map(::id))
    }

    @Test
    fun pickingAnOrder_listsThatListFirst() {
        val vm = show()

        // To the last card of the first line, UP to the orders, one along: «الأكثر مشاهدة».
        press(listOf(Key.DirectionLeft, Key.DirectionLeft, Key.DirectionLeft, Key.DirectionUp))
        compose.onNodeWithTag("channel_sort_LATEST").assertIsFocused()
        press(listOf(Key.DirectionLeft, Key.DirectionCenter))

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "VIDEOS:POPULAR:0").inOrder()
        compose.onNodeWithTag("channel_sort_POPULAR").assertIsFocused()
        assertThat(vm.sections.value.sort).isEqualTo(ChannelSort.POPULAR)
        assertThat((vm.items.value.items.first() as VideoUi).video.videoId).isEqualTo(id(201))
        // DOWN, OK: one of the most viewed.
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertThat(played.single()).isIn((201..230).map(::id))
    }

    @Test
    fun aPlaylistOfTheChannelOpensFromItsPlaylistsSection() {
        show()

        press(alongToThePlaylists)
        compose.onNodeWithTag("channel_tab_PLAYLISTS").assertIsFocused()
        press(Key.DirectionCenter)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)

        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "PLAYLISTS:LATEST:0").inOrder()
        assertThat(opened.single()).isIn(listOf(FakeChannelSource.playlist(1).playlistId, FakeChannelSource.playlist(2).playlistId))
        assertThat(played).isEmpty()
    }

    @Test
    fun aChild_hasNoShortsSection_andNoParentalMenu() {
        rules.value = ContentFilter.of(
            engine,
            Profile.newChild("سارة", id = "kid").copy(allowlist = Allowlist(channels = listOf(ListedChannel(CHANNEL, "Channel")))),
        )
        show()

        compose.onNodeWithTag("channel_tab_VIDEOS").assertExists()
        compose.onNodeWithTag("channel_tab_PLAYLISTS").assertExists()
        compose.onNodeWithTag("channel_tab_SHORTS").assertDoesNotExist()
    }

    @Test
    fun aSectionThatFails_isSaidUnderItsChips_andTheOtherSectionsStayOneKeyAway() {
        source.failing = setOf("SHORTS:LATEST:0")
        show()

        press(listOf(Key.DirectionUp, Key.DirectionLeft, Key.DirectionCenter))

        compose.onNodeWithTag("paging_error").assertExists()
        compose.onNodeWithTag("channel_tab_VIDEOS").assertExists()
        compose.onNodeWithTag("channel_tab_SHORTS").assertIsFocused()
        // Back to the videos: as they were.
        press(listOf(Key.DirectionRight, Key.DirectionCenter))
        compose.onNodeWithTag("paging_error").assertDoesNotExist()
        assertThat(source.requests).containsExactly("VIDEOS:LATEST:0", "SHORTS:LATEST:0").inOrder()
    }
}
