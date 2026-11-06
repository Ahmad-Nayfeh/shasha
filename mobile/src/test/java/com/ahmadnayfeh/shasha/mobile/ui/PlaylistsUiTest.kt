package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.mobile.data.toFeedEntity
import com.ahmadnayfeh.shasha.mobile.video
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The viewer's own playlists in the real app on Robolectric: made, renamed and deleted from the Library, videos added from a video's
 * menu and from the player screen, and a list played in order. «Watch later» stays the built-in list beside them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ShortsApp::class, qualifiers = "en-$PHONE")
class PlaylistsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val container get() = context.container

    private val channelId = "UCplaylistchannel000000A"

    private fun lists() = runBlocking { container.userPlaylists.playlists.first() }

    private fun makeList(name: String, vararg videoIds: String): Long = runBlocking {
        val id = checkNotNull(container.userPlaylists.create(name))
        for (videoId in videoIds) container.userPlaylists.add(id, video(videoId, title = "Title of $videoId"))
        id
    }

    @Test
    fun theLibraryOffersToMakeAListAndShowsTheListsWithTheirSize() {
        compose.startAppOffline(container)
        compose.navTab("You").performClick()
        compose.awaitText("My playlists")
        // The hint follows Room's first answer, and the tab fades in: wait until it is on screen.
        compose.awaitCondition {
            runCatching { compose.onNodeWithText("Make a playlist to keep videos together, then add videos from their menu.").assertIsDisplayed() }.isSuccess
        }
        compose.onNodeWithContentDescription("New playlist").assertIsDisplayed()
        // The built-in lists are still there above the viewer's own.
        compose.onNodeWithText("Watch later").assertIsDisplayed()

        makeList("Cartoons")
        makeList("Cooking", "aaaaaaaaaaa")

        // Room tells the screen on its own thread, one write after the other: wait for the last thing that is to appear (the
        // second list with its video), not for the first.
        compose.awaitCondition { compose.onAllNodesWithText("1 videos").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Cartoons").assertIsDisplayed()
        compose.onNodeWithText("0 videos").assertIsDisplayed()
        compose.onNodeWithText("Cooking").assertIsDisplayed()
        // The empty hint is gone now that there are lists.
        assertThat(compose.onAllNodesWithText("Make a playlist to keep videos together, then add videos from their menu.").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aListOpensWithItsVideos_aVideoCanBeTakenOut_andTheListDeletedAfterAConfirmation() {
        val id = makeList("Cartoons", "aaaaaaaaaaa", "bbbbbbbbbbb")
        compose.startAppOffline(container)
        compose.navTab("You").performClick()
        compose.awaitText("Cartoons")
        compose.onNodeWithText("2 videos").assertIsDisplayed()

        compose.onNodeWithText("Cartoons").performClick()
        compose.awaitText("Title of aaaaaaaaaaa")
        compose.onNodeWithText("Title of bbbbbbbbbbb").assertIsDisplayed()
        compose.onNodeWithText("Play all").assertIsDisplayed()

        // The page's actions: rename and delete (the name dialog has a text field, which Robolectric cannot idle with).
        compose.onNodeWithContentDescription("Rename").assertIsDisplayed()

        // Take a video out.
        compose.onAllNodesWithContentDescription("Remove")[0].performClick()
        compose.awaitCondition { runBlocking { container.userPlaylists.videosNow(id).size } == 1 }
        compose.awaitCondition { compose.onAllNodesWithText("Title of aaaaaaaaaaa").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Title of bbbbbbbbbbb").assertIsDisplayed()

        // Delete, after a confirmation that names the list; back in the Library the list is gone.
        compose.onNodeWithContentDescription("Delete playlist").performClick()
        compose.awaitText("Delete this playlist?")
        compose.onNodeWithText("Delete").performClick()
        compose.awaitText("My playlists")
        compose.awaitCondition { lists().isEmpty() }
        compose.awaitCondition { compose.onAllNodesWithText("Cartoons").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun playAllOpensThePlayerScreenWithTheListAsItsQueue() {
        val id = makeList("Cartoons", "aaaaaaaaaaa", "bbbbbbbbbbb")
        compose.startAppOffline(container)
        compose.navTab("You").performClick()
        compose.awaitText("Cartoons")
        compose.onNodeWithText("Cartoons").performClick()
        compose.awaitText("Play all")

        compose.onNodeWithText("Play all").performClick()

        // The player screen plays the first video, and what follows is the list, in order.
        compose.awaitText("Playlist")
        compose.awaitText("Title of aaaaaaaaaaa")
        compose.onNodeWithText("Title of bbbbbbbbbbb").assertIsDisplayed()
        compose.awaitCondition { container.player.state.value.video?.videoId == "aaaaaaaaaaa" }
        assertThat(id).isGreaterThan(0)

        // Taking the second video from the queue plays it, and the list stays the queue (the next one would still follow).
        compose.onNodeWithText("Title of bbbbbbbbbbb").performClick()
        compose.awaitCondition { container.player.state.value.video?.videoId == "bbbbbbbbbbb" }
        compose.awaitText("Playlist")
        compose.onNodeWithText("Title of aaaaaaaaaaa").assertIsDisplayed()
    }

    @Test
    fun aVideosMenuSavesItToWatchLaterOrToTheListsOfTheViewer() {
        runBlocking {
            container.settings.setFeedRefreshedAt(System.currentTimeMillis() - 60_000)
            container.database.subscriptions().upsert(SubscriptionEntity(channelId, "A channel", avatarUrl = null, subscribedAt = NOW_MS))
            val listed = video("vid00000001", title = "A listed video", channelId = channelId, publishedAt = 1_000)
            container.database.feed().upsertAll(listOf(listed.toFeedEntity(channelId)))
        }
        val cartoons = makeList("Cartoons")
        compose.startAppOffline(container)
        compose.awaitText("A listed video")

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Save to playlist").performClick()

        // «Watch later» first, then the viewer's lists, then «New playlist».
        compose.awaitText("Save to…")
        compose.onNodeWithText("Watch later").assertIsDisplayed()
        compose.onNodeWithText("Cartoons").assertIsDisplayed()
        compose.onNodeWithText("Cartoons").performClick()
        compose.awaitCondition { runBlocking { container.userPlaylists.videosNow(cartoons).map { it.videoId } } == listOf("vid00000001") }

        // The same video in a second list, and in Watch later too (a list made meanwhile joins the sheet).
        compose.onNodeWithText("New playlist").assertIsDisplayed()
        makeList("Mix")
        compose.awaitText("Mix")
        compose.onNodeWithText("Mix").performClick()
        compose.awaitCondition { runBlocking { container.userPlaylists.containing("vid00000001").first() }.size == 2 }
        compose.onNodeWithText("Watch later").performClick()
        compose.waitForIdle()
        compose.awaitCondition { runBlocking { container.library.watchLaterItems.first().map { it.videoId } } == listOf("vid00000001") }
        assertThat(lists().map { it.name to it.itemCount }).containsExactly("Cartoons" to 1, "Mix" to 1).inOrder()

        // Tapping a list that holds the video takes it out again.
        compose.onNodeWithText("Cartoons").performClick()
        compose.waitForIdle()
        compose.awaitCondition { runBlocking { container.userPlaylists.videosNow(cartoons) }.isEmpty() }
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertThat(compose.onAllNodesWithText("Save to…").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun thePlayerScreenHasASaveButtonThatOpensTheSameSheet() {
        container.launch.value = LaunchRequest.Video("abcdefghijk", null, null)
        compose.startAppOffline(container)

        compose.awaitText("Save")
        compose.onNodeWithText("Watch later").assertIsDisplayed() // the built-in button stays beside it
        compose.onNodeWithText("Save").performClick()

        compose.awaitText("Save to…")
        compose.onNodeWithText("New playlist").assertIsDisplayed()
    }
}
