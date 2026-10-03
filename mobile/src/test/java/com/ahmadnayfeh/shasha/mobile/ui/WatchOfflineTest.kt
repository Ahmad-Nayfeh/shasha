package com.ahmadnayfeh.shasha.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.MainActivity
import com.ahmadnayfeh.shasha.mobile.MobileApp
import com.ahmadnayfeh.shasha.mobile.container
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadEntity
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadKind
import com.ahmadnayfeh.shasha.mobile.data.db.DownloadStatus
import com.ahmadnayfeh.shasha.mobile.player.PlayerPhase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The player screen on Robolectric without a network: a finished download is opened from the Downloads list, which
 * brings up the real ExoPlayer, the video surface, the controls and the details under the player. The file is not a
 * video, so the player ends in its error state: that it does (and does not crash) is part of the check.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MobileApp::class, qualifiers = "en-$PHONE")
class WatchOfflineTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun saveDownload(title: String): Long {
        val file = File(context.cacheDir, "saved.mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        return runBlocking {
            context.container.database.downloads().insert(
                DownloadEntity(
                    videoId = "abcdefghijk",
                    title = title,
                    channelName = "A channel",
                    thumbnailUrl = null,
                    durationSeconds = 120,
                    kind = DownloadKind.VIDEO,
                    requestedHeight = 720,
                    height = 720,
                    status = DownloadStatus.DONE,
                    fileBytes = file.length(),
                    uri = Uri.fromFile(file).toString(),
                    displayName = file.name,
                    error = null,
                    createdAt = 1,
                    completedAt = 2,
                ),
            )
        }
    }

    @Test
    fun aDownloadedVideoOpensInThePlayerScreenAndBackReturnsToTheList() {
        saveDownload("A saved video")
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_DOWNLOADS)

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            compose.awaitText("A saved video")
            compose.onNodeWithText("A saved video").performClick()

            // The player screen: its actions under the video (no «Download» for a file that is already saved).
            compose.awaitText("Share")
            compose.onNodeWithText("A saved video").assertIsDisplayed()
            compose.onNodeWithText("Watch later").assertIsDisplayed()
            assertThat(context.container.player.state.value.isOffline).isTrue()

            // The file is not a video: the player says so and offers to try again, instead of staying on a spinner.
            compose.awaitText("Try again", timeoutMillis = 20_000)
            assertThat(context.container.player.state.value.phase).isEqualTo(PlayerPhase.ERROR)

            // Back: the list again, and the player is let go.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.awaitText("Downloads")
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            compose.waitUntil(10_000) { context.container.player.state.value.phase == PlayerPhase.IDLE }
        }
    }
}
