package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.AppContainer
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.LaunchRequest
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.PlaylistItem
import com.ahmadnayfeh.shasha.youtube.PlaylistPage
import com.ahmadnayfeh.shasha.youtube.VideoSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val roadTrip = PlaylistItem("PLroadtrip0000000000000", "Road trip songs", "Travel Channel", null, 2)

/** The app over a source whose one playlist is [roadTrip] with two videos. */
class SavedPlaylistsApp : MobileApp() {
    override fun createContainer(): AppContainer = object : AppContainer(this) {
        override val videoSource: VideoSource = FakeVideoSource().apply {
            onPlaylist = { id, _ -> PlaylistPage(roadTrip.copy(playlistId = id), listOf(video("aaaaaaaaaaa"), video("bbbbbbbbbbb")), null) }
        }
    }
}

/**
 * A YouTube playlist saved to the Library: «Save to library» on its page turns into «Saved», the Library lists it under «Saved
 * playlists» and opens its page again, and its button there takes it out. The real app on Robolectric, over a fake source.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = SavedPlaylistsApp::class, qualifiers = "en-$PHONE")
class SavedPlaylistsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val container get() = ApplicationProvider.getApplicationContext<Context>().container

    private fun savedIds() = runBlocking { container.savedPlaylists.savedIds.first() }

    @Test
    fun aSavedPlaylistIsInTheLibrary_opensItsPage_andCanBeTakenOutThere() {
        runBlocking { container.savedPlaylists.save(roadTrip) }
        compose.startAppOffline(container)
        compose.navTab("You").performClick()

        compose.awaitText("Saved playlists")
        compose.onNodeWithText("Road trip songs").performScrollTo().performClick()

        // Its page, read from YouTube, says it is saved.
        compose.awaitText("Video aaaaaaaaaaa")
        compose.onNodeWithText("Saved").assertIsDisplayed()

        compose.onNodeWithText("Saved").performClick()

        compose.awaitText("Save to library")
        compose.awaitCondition { savedIds().isEmpty() }
    }

    @Test
    fun savingFromThePlaylistsPageAddsItToTheLibrary() {
        container.launch.value = LaunchRequest.Playlist(roadTrip.playlistId)
        compose.startAppOffline(container)
        compose.awaitText("Save to library")

        compose.onNodeWithText("Save to library").performClick()

        compose.awaitCondition { savedIds() == setOf(roadTrip.playlistId) }
        compose.awaitText("Saved")
        val saved = runBlocking { container.savedPlaylists.playlists.first() }
        assertThat(saved.single().title).isEqualTo("Road trip songs")
    }

    @Test
    fun theLibraryHasNoSavedSectionUntilAPlaylistIsSaved_andItsRowButtonRemovesIt() {
        compose.startAppOffline(container)
        compose.navTab("You").performClick()
        compose.awaitText("My playlists")
        assertThat(compose.onAllNodesWithText("Saved playlists").fetchSemanticsNodes()).isEmpty()

        runBlocking { container.savedPlaylists.save(roadTrip) }
        compose.awaitText("Saved playlists")
        compose.onNodeWithContentDescription("Remove from library").performScrollTo().performClick()

        compose.awaitCondition { savedIds().isEmpty() }
        compose.awaitCondition { compose.onAllNodesWithText("Saved playlists").fetchSemanticsNodes().isEmpty() }
    }
}
