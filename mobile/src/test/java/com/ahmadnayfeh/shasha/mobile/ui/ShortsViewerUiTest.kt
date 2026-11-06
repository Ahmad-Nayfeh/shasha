package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.TestStreams
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.data.toHistoryEntity
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.player.PlaybackMode
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ItemsPage
import com.ahmadnayfeh.shasha.youtube.PageKey
import com.ahmadnayfeh.shasha.youtube.ShortsPage
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/** A source whose streams the player refuses (Robolectric plays no media), recording which streams were asked for. */
class ShortsSource : FakeVideoSource() {
    val streams = ConcurrentLinkedQueue<String>()

    override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
        streams += videoId
        return TestStreams.details(video(videoId))
    }
}

class ShortsApp : MobileApp() {
    override fun createContainer(): AppContainer = object : AppContainer(this) {
        override val videoSource: VideoSource = ShortsSource()
    }
}

/** The whole app composed over [container], without pictures from the network. */
@OptIn(ExperimentalCoilApi::class)
internal fun ComposeContentTestRule.startAppOffline(container: AppContainer) {
    val pictures = AsyncImagePreviewHandler { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImage() }
    setContent {
        CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) { ShashaRoot(container) }
    }
}

/**
 * The Shorts viewer in the real app on Robolectric: opened from the Home's row, paged with swipes, one Short playing at a time with
 * the next one's streams looked up ahead, and left with back. The streams are ones the player refuses, so every Short shows its
 * error with «Try again»; what is checked is which Short the player holds, what was asked of YouTube, and what the screen shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ShortsApp::class, qualifiers = "en-$PHONE")
class ShortsViewerUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = context.container
    private val source get() = container.videoSource as ShortsSource

    private val channelId = "UCshortschannel0000000A"

    private fun short(id: String, title: String) =
        video(id, title = title, channelId = channelId, channelName = "Shorts channel").copy(isShortForm = true)

    /** A followed channel whose newest uploads are a video and three Shorts, stored, with the feed refreshed a minute ago. */
    private fun seedFeed() = runBlocking {
        container.settings.setFeedRefreshedAt(System.currentTimeMillis() - 60_000)
        container.database.subscriptions().upsert(SubscriptionEntity(channelId, "Shorts channel", avatarUrl = null, subscribedAt = NOW_MS))
        val uploads = listOf(
            video("vid00000001", title = "A long video", channelId = channelId, publishedAt = 4_000),
            short("sho00000001", "Short one").copy(publishedAtEpochMs = 3_000),
            short("sho00000002", "Short two").copy(publishedAtEpochMs = 2_000),
            short("sho00000003", "Short three").copy(publishedAtEpochMs = 1_000),
        )
        container.database.feed().upsertAll(uploads.map { it.toFeedEntity(channelId) })
    }

    private fun waitForPlayerOn(id: String) = compose.awaitCondition { container.player.state.value.video?.videoId == id }

    @Test
    fun theHomeShowsTheShortsAsARowOfTheirOwn_theLongVideosStayInTheList() {
        seedFeed()
        compose.startAppOffline(container)

        compose.awaitText("A long video")
        compose.onAllNodesWithText("Shorts").onFirst().assertIsDisplayed()
        for (title in listOf("Short one", "Short two", "Short three")) compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test
    fun aTappedShortOpensInTheViewer_theNextOnesStreamsAreLookedUpAhead_andSwipesPageThroughTheRow() {
        seedFeed()
        compose.startAppOffline(container)
        compose.awaitText("Short two")

        compose.onNodeWithText("Short two").performClick()

        // The viewer opens on the tapped Short: its title and channel over it, the player holds it in Short mode,
        // and the Short after it (not the one before) has its streams looked up while it plays.
        waitForPlayerOn("sho00000002")
        compose.awaitText("Try again") // the refused streams, shown as every failure is
        compose.onNodeWithText("Short two").assertIsDisplayed()
        compose.onNodeWithText("Shorts channel").assertIsDisplayed()
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
        assertThat(source.streams.toList()).containsExactly("sho00000002", "sho00000003").inOrder()
        // The bottom bar is gone: the viewer is the whole screen.
        assertThat(compose.onAllNodesWithText("Library").fetchSemanticsNodes()).isEmpty()

        // Swipe up: the next Short plays, from the lookup already made (asked for once), and nothing is left to look up after the last.
        compose.onRoot().performTouchInput { swipeUp() }
        waitForPlayerOn("sho00000003")
        compose.waitForIdle()
        assertThat(source.streams.toList()).containsExactly("sho00000002", "sho00000003").inOrder()
        compose.onNodeWithText("Short three").assertIsDisplayed()

        // Swipe up again at the last one: it stays. Swipe down twice: the first one is reached and the player follows.
        compose.onRoot().performTouchInput { swipeUp() }
        compose.waitForIdle()
        assertThat(container.player.state.value.video?.videoId).isEqualTo("sho00000003")
        compose.onRoot().performTouchInput { swipeDown() }
        waitForPlayerOn("sho00000002")
        compose.onRoot().performTouchInput { swipeDown() }
        waitForPlayerOn("sho00000001")
        compose.onRoot().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertThat(container.player.state.value.video?.videoId).isEqualTo("sho00000001") // the first one: it stays
        compose.onNodeWithText("Short one").assertIsDisplayed()
    }

    @Test
    fun backLeavesTheViewerAndLetsThePlayerGo() {
        seedFeed()
        compose.startAppOffline(container)
        compose.awaitText("Short one")
        compose.onNodeWithText("Short one").performClick()
        waitForPlayerOn("sho00000001")

        compose.onNodeWithContentDescription("Back").performClick()

        compose.awaitText("A long video") // the Home again, with its bar
        compose.awaitCondition { container.player.state.value.phase == PlayerPhase.IDLE }
        assertThat(container.player.mode).isEqualTo(PlaybackMode.VIDEO)
    }

    @Test
    fun theChannelOfTheShortOnScreenOpensItsPage_andBackComesBackToTheViewer() {
        seedFeed()
        compose.startAppOffline(container)
        compose.awaitText("Short one")
        compose.onNodeWithText("Short one").performClick()
        waitForPlayerOn("sho00000001")

        compose.onNodeWithText("Shorts channel").performClick()
        // The channel's page: its Videos / Shorts / Playlists tabs.
        compose.awaitText("Playlists")
        compose.tab("Shorts").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.awaitText("Short one")
        waitForPlayerOn("sho00000001")
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
    }

    @Test
    fun tryAgainAsksForTheShortsStreamsOnceMore() {
        seedFeed()
        compose.startAppOffline(container)
        compose.awaitText("Short one")
        compose.onNodeWithText("Short one").performClick()
        compose.awaitText("Try again")
        val before = source.streams.count { it == "sho00000001" }

        compose.onNodeWithText("Try again").performClick()

        compose.awaitCondition { source.streams.count { it == "sho00000001" } == before + 1 }
    }

    @Test
    fun withShortsOffTheHomeHasNoRowAndTheChannelNoTab() {
        runBlocking { container.settings.setShowShorts(false) }
        seedFeed()
        compose.startAppOffline(container)

        compose.awaitText("A long video")
        for (title in listOf("Shorts", "Short one", "Short two", "Short three")) {
            assertThat(compose.onAllNodesWithText(title).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun withShortsOffAChannelPageHasNoShortsTab_andOnItHasOne() {
        runBlocking { container.settings.setShowShorts(false) }
        container.launch.value = LaunchRequest.Channel(channelId)
        compose.startAppOffline(container)
        compose.awaitText("Playlists")
        compose.tab("Videos").assertIsDisplayed()
        compose.tab("Playlists").assertIsDisplayed()
        compose.tab("Shorts").assertDoesNotExist()
    }

    @Test
    fun withShortsOnAChannelPageHasTheShortsTabAndItsShortsOpenTheViewerOnThatList() {
        val shorts = listOf(short("sho00000001", "Short one"), short("sho00000002", "Short two"))
        source.onChannelShorts = { shorts }
        container.launch.value = LaunchRequest.Channel(channelId)
        compose.startAppOffline(container)
        compose.awaitText("Playlists")

        compose.tab("Shorts").performClick()
        compose.awaitText("Short two")
        compose.onNodeWithText("Short two").performClick()

        // The viewer opens on the channel's Shorts (not the normal player), at the tapped one, with the other as the next.
        waitForPlayerOn("sho00000002")
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
        assertThat(source.streams.toList()).containsExactly("sho00000002") // the last one: nothing ahead
    }

    @Test
    fun aShortInTheSuggestionsOpensInTheViewerToo() {
        runBlocking {
            container.database.history().upsert(video("watched01").toHistoryEntity(NOW_MS))
            container.settings.setFeedRefreshedAt(System.currentTimeMillis() - 60_000)
        }
        val shorts = listOf(short("sho00000001", "Suggested short"))
        source.onRelated = { shorts + video("rel00000001", title = "Suggested video") }
        compose.startAppOffline(container)

        compose.awaitText("Suggested for you")
        compose.awaitText("Suggested video")
        compose.onAllNodesWithText("Shorts").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Suggested short").performClick()

        waitForPlayerOn("sho00000001")
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
    }

    @Test
    fun theShortsOfASearchAreARowAndTheViewerOpensOnThatRow() {
        val found = listOf(
            video("res00000001", title = "A search result"),
            short("sho00000001", "Found short one"),
            short("sho00000002", "Found short two"),
            short("sho00000003", "Found short three"),
        )
        source.onSearch = { _, _, _ -> ItemsPage(found, null) }
        container.launch.value = LaunchRequest.Search("cats")
        compose.startAppOffline(container)

        compose.awaitText("A search result")
        compose.awaitText("Found short two")
        // The row has a title of its own, and the Shorts are not in the list above it.
        compose.onAllNodesWithText("Shorts").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Found short two").performClick()

        // The viewer opens on the row (the first Short before it is not looked up: the row's order is the viewer's).
        waitForPlayerOn("sho00000002")
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
        assertThat(source.streams.toList()).containsExactly("sho00000002", "sho00000003").inOrder()
    }

    @Test
    fun theShortsYouTubeFindsForTheQueryAreTheRowToo_andTheViewerOpensOnThem() {
        // The results hold no Short (YouTube keeps them in shelves of their own): the Shorts are asked for beside the search.
        source.onSearch = { _, _, _ -> ItemsPage(listOf(video("res00000001", title = "A search result")), null) }
        source.onSearchShorts = { _, _ ->
            ShortsPage(listOf(short("sho00000001", "Found short one"), short("sho00000002", "Found short two")), PageKey("2"))
        }
        container.launch.value = LaunchRequest.Search("cats")
        compose.startAppOffline(container)

        compose.awaitText("A search result")
        compose.awaitText("Found short two")
        compose.onAllNodesWithText("Shorts").onFirst().assertIsDisplayed()
        compose.onNodeWithText("Found short two").performClick()

        waitForPlayerOn("sho00000002")
        assertThat(container.player.mode).isEqualTo(PlaybackMode.SHORT)
    }

    @Test
    fun withShortsOffASearchShowsNoRow() {
        runBlocking { container.settings.setShowShorts(false) }
        source.onSearch = { _, _, _ -> ItemsPage(listOf(video("res00000001", title = "A search result"), short("sho00000001", "Found short one")), null) }
        container.launch.value = LaunchRequest.Search("cats")
        compose.startAppOffline(container)

        compose.awaitText("A search result")
        assertThat(compose.onAllNodesWithText("Found short one").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Shorts").fetchSemanticsNodes()).isEmpty()
        // And YouTube is not even asked for them.
        assertThat(source.shortsSearches).isEmpty()
    }

    @Test
    fun aHomeWithHistoryButNoNetworkAndNoChannelsStillSaysWhatToDo() {
        runBlocking { container.database.history().upsert(video("watched01").toHistoryEntity(NOW_MS)) }
        source.onRelated = { throw failure() }
        compose.startAppOffline(container)

        // Nothing could be suggested and nobody is followed: the friendly hint with its search button, not a blank page.
        compose.awaitText("No subscriptions yet")
        compose.onNodeWithText("Search for a channel and subscribe to see its newest videos here.").assertIsDisplayed()
    }

    @Test
    fun suggestionsWithoutChannelsKeepTheWayToFollowOneInView() {
        runBlocking { container.database.history().upsert(video("watched01").toHistoryEntity(NOW_MS)) }
        source.onRelated = { listOf(video("rel00000001", title = "Suggested video")) }
        compose.startAppOffline(container)

        compose.awaitText("Suggested video")
        compose.onNodeWithText("Suggested for you").assertIsDisplayed()
        // Below them, the way to a home of the viewer's own.
        compose.onNodeWithText("Search for a channel and subscribe to see its newest videos here.").assertIsDisplayed()
    }
}

/** «Show Shorts» on the Settings page of the real app: on when the app is new, and a tap turns it off and on, stored at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ShortsApp::class, qualifiers = "en-$PHONE")
class ShortsSettingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<Context>().container

    private fun stored() = runBlocking { container.settings.settings.first().showShorts }

    @Test
    fun theSettingsPageHasTheSwitchAndATapTurnsShortsOffAndOn() {
        assertThat(stored()).isTrue()
        compose.startAppOffline(container)
        compose.tab("Settings").performClick()

        compose.onNodeWithText("Show Shorts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Short vertical videos", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Show Shorts").performClick()
        compose.awaitCondition { !stored() }

        compose.onNodeWithText("Show Shorts").performClick()
        compose.awaitCondition { stored() }
    }

    @Test
    fun theGroupsOwnResetBringsThemBack() {
        runBlocking { container.settings.setShowShorts(false) }
        compose.startAppOffline(container)
        compose.tab("Settings").performClick()
        compose.onNodeWithText("Show Shorts").performScrollTo()

        // The group's «Reset» sits in the row of its title: the Shorts group is the one whose title is «Shorts».
        compose.onAllNodesWithText("Reset").filter(isEnabled()).let { enabled ->
            // Only a group that differs from its default has an enabled «Reset»: here, this one.
            assertThat(enabled.fetchSemanticsNodes()).hasSize(1)
            enabled[0].performScrollTo().performClick()
        }
        compose.awaitCondition { stored() }
    }
}
