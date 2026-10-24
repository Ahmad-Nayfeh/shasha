package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.ScriptedChannelSource
import com.ahmadnayfeh.shasha.mobile.channelId
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.ChannelItem
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The app whose YouTube answers like the network this was found on: it refuses the pages of a channel unless a test scripts them. */
class RefusedChannelsApp : MobileApp() {
    override fun createContainer(): AppContainer = object : AppContainer(this) {
        override val videoSource: VideoSource = ScriptedChannelSource()
    }
}

/**
 * A channel's page on Robolectric, over a source that answers its page and its tabs with `ContentNotAvailableException` (what a
 * phone on mobile data in Saudi Arabia got): the page must show the channel and its videos, never the error page whose only
 * way out was «Try again». The real navigation, database and screens; nothing asks the network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = RefusedChannelsApp::class, qualifiers = "en-$PHONE")
class ChannelPageTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = context.container
    private val source get() = container.videoSource as ScriptedChannelSource

    private val channel = channelId(7)
    private val longForm = "UULF" + channel.drop(2)
    private val shortsList = "UUSH" + channel.drop(2)

    // Characters a picture's address and a channel's name really have, and a navigation route must carry unharmed.
    private val picture = "https://yt3.ggpht.com/ytc/AIdro_kXyz=s176-c-k-c0x00ffffff-no-rj"
    private val name = "عبدالله العجيري | قناة"

    private fun v(id: String, title: String) = video(id, title = title, channelId = channel, channelName = name)

    @OptIn(ExperimentalCoilApi::class)
    private fun start() {
        val pictures = AsyncImagePreviewHandler { Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).asImage() }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides pictures) { ShashaRoot(container) }
        }
    }

    private fun stored() = runBlocking { container.database.subscriptions().get(channel) }

    @Test
    fun aFollowedChannelOpensWithItsNameAndVideosWhereYouTubeRefusesItsPages() {
        runBlocking { container.database.subscriptions().upsert(SubscriptionEntity(channel, name, avatarUrl = picture, subscribedAt = NOW_MS)) }
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("vid00000001", "The first video"), v("vid00000002", "The second video")))
        source.shortsTabFails(channel)
        source.playlist(shortsList, listOf(v("sho00000001", "A short one")))
        container.launch.value = LaunchRequest.Channel(channel)
        start()

        // Its videos come from the long-form playlist; the header is the subscription's.
        compose.awaitText("The first video")
        compose.onNodeWithText("The second video").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(name).fetchSemanticsNodes()).isNotEmpty()
        // No error page, no error text: the viewer follows the channel and can stop following it, and ask the page again, quietly.
        assertThat(compose.onAllNodesWithText("This content is not available.").fetchSemanticsNodes()).isEmpty()
        compose.onNodeWithText("Unsubscribe").assertIsDisplayed()
        compose.onNodeWithContentDescription("Try again").assertIsDisplayed()
        assertThat(source.calls).containsAtLeast("channel:$channel", "tab:$channel", "playlist:$longForm")

        // The Shorts come from the Shorts playlist; the Playlists tab has no other way and says so quietly.
        compose.tab("Shorts").performClick()
        compose.awaitText("A short one")
        compose.tab("Playlists").performClick()
        compose.awaitText("Playlists can't be shown right now.")
        assertThat(compose.onAllNodesWithText("This content is not available.").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun eachTabKeepsItsOwnPlaceInTheList() {
        source.tabFails(channel)
        source.playlist(longForm, (1..40).map { v("vid%08d".format(it), "Video number $it") })
        container.launch.value = LaunchRequest.Channel(channel)
        start()
        compose.awaitText("Video number 1")

        // Down the Videos, to the Playlists (a short tab), and back: the Videos are where the viewer left them.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Video number 30"))
        compose.onNodeWithText("Video number 30").assertIsDisplayed()
        compose.tab("Playlists").performClick()
        compose.awaitText("Playlists can't be shown right now.")
        compose.tab("Videos").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Video number 30").assertIsDisplayed()
    }

    @Test
    fun aChannelOpenedFromASearchResultShowsWhatTheResultSaidAndCanBeSubscribedTo() {
        source.searchResults(listOf(ChannelItem(channel, name, picture, subscriberCount = 1_200_000)))
        source.tabFails(channel)
        source.playlist(longForm, listOf(v("vid00000001", "The first video")))
        container.launch.value = LaunchRequest.Search("العجيري")
        start()

        compose.awaitText(name)
        compose.onNodeWithText(name).performClick()

        // The page of the channel: its own page failed, the result's name and picture stand in for it.
        compose.awaitText("The first video")
        compose.awaitText("Subscribe")
        assertThat(compose.onAllNodesWithText(name).fetchSemanticsNodes()).isNotEmpty()
        compose.onNodeWithText("Subscribe").performClick()
        compose.awaitText("Unsubscribe")

        // What the page showed is what was stored, whole (the picture's address went through the navigation route).
        val row = stored()!!
        assertThat(row.name).isEqualTo(name)
        assertThat(row.avatarUrl).isEqualTo(picture)

        compose.onNodeWithText("Unsubscribe").performClick()
        compose.awaitText("Subscribe")
        assertThat(stored()).isNull()
    }

    @Test
    fun aChannelNothingIsKnownOfAndNothingAnswersForIsStillAPageWithATryAgainThatWorks() {
        container.launch.value = LaunchRequest.Channel(channel)
        start()

        // The failure is in the list, quietly: the page is still there, with its way back, its tabs and its subscribe button.
        compose.awaitText("This content is not available.")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.tab("Shorts").assertIsDisplayed()
        compose.onNodeWithText("Subscribe").assertIsDisplayed()

        // YouTube answers: «Try again» brings the videos.
        source.tab(channel, listOf(v("vid00000001", "Back again")))
        compose.onNodeWithText("Try again").performClick()
        compose.awaitText("Back again")
        assertThat(compose.onAllNodesWithText("This content is not available.").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aChannelTheContentFilterRefusesIsNotAvailableWithoutItsNameOrASubscribeButton() {
        runBlocking { container.database.subscriptions().upsert(SubscriptionEntity(channel, name, avatarUrl = picture, subscribedAt = NOW_MS)) }
        source.headerFails(channel, SourceException(SourceErrorKind.CONTENT_UNAVAILABLE, "ContentFiltered", "Held back by the built-in content filter", null))
        container.launch.value = LaunchRequest.Channel(channel)
        start()

        compose.awaitText("This content is not available.")
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText(name).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Subscribe").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Unsubscribe").fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodesWithText("Try again").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aSubscriptionWithoutAPictureShowsItsInitialOnTheHomeScreenAndThePictureOnceItIsKnown() {
        val now = System.currentTimeMillis()
        runBlocking {
            // The feed was refreshed a minute ago: nothing is asked of the network.
            container.settings.setFeedRefreshedAt(now - 60_000)
            container.database.subscriptions().upsert(SubscriptionEntity(channel, "أحمد السيد", avatarUrl = null, subscribedAt = now))
            container.database.feed().upsertAll(listOf(v("vid00000001", "A video").toFeedEntity(channel)))
        }
        start()

        compose.awaitText("A video")
        // The picture is missing: the first letter of the name stands in for it, not a person icon (it is decorative: the name is read).
        compose.onNodeWithText("أ", useUnmergedTree = true).assertIsDisplayed()

        // A page of the channel loaded somewhere (a video's page): the stored picture is filled in, and the row shows it.
        runBlocking { container.subscriptions.updateDetails(ChannelItem(channel, "أحمد السيد", picture, subscriberCount = null)) }

        compose.waitUntil(10_000) { compose.onAllNodesWithText("أ", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
}
