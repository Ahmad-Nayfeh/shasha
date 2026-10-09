package com.ahmadnayfeh.shasha.mobile.player

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.TestStreams
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorBlockRepository
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A stream that stops working while it plays (the screen off for a long video: its addresses expire, the connection breaks) is
 * extracted again by the player itself instead of waiting on the error screen for «Try again». Robolectric cannot play a stream,
 * so the failure is handed to the controller as the player would report it, and what is checked is that it asks for the streams
 * again (and, once the tries are used up, shows the error).
 */
@OptIn(ExperimentalCoroutinesApi::class, UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerControllerRecoveryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private val asked = ConcurrentLinkedQueue<String>()

    private val source = object : FakeVideoSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            asked += videoId
            return TestStreams.details(video(videoId))
        }
    }

    private val controller = PlayerController(
        context = context,
        videoSource = source,
        mediaSources = YouTubeMediaSourceFactory(context, OkHttpClient()),
        settings = { AppSettings(sponsorBlockEnabled = false) },
        sponsorBlock = SponsorBlockRepository(fetcher = { emptyList() }, dao = db.sponsorSegments(), now = { NOW_MS }),
        library = LibraryRepository(db.history(), db.watchLater(), db.progress()) { NOW_MS },
        scope = scope,
        elapsedClock = { 0L },
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun sourceError(cause: IOException) = ExoPlaybackException.createForSource(cause, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)

    private fun http403() = sourceError(
        HttpDataSource.InvalidResponseCodeException(403, "Forbidden", null, emptyMap(), DataSpec(Uri.parse("https://rr1.googlevideo.com/videoplayback")), ByteArray(0)),
    )

    @Test
    fun expiredAddressesAreExtractedAgain_andTheVideoGoesOn() {
        controller.play("a")
        asked.clear()

        controller.onPlaybackError(http403())

        assertThat(asked.toList()).containsExactly("a")
        assertThat(controller.state.value.video?.videoId).isEqualTo("a")
    }

    @Test
    fun aBrokenConnectionIsTriedAgainAfterAPause_andTheScreenSaysItIsLoading() {
        controller.play("a")
        controller.onPlaybackError(sourceError(SocketTimeoutException("read timed out"))) // the first try: at once
        asked.clear()

        controller.onPlaybackError(sourceError(SocketTimeoutException("read timed out"))) // the second: after a pause

        assertThat(controller.state.value.phase).isEqualTo(PlayerPhase.LOADING)
        assertThat(controller.state.value.error).isNull()
    }

    @Test
    fun whenTheTriesAreUsedUpTheErrorIsShown() {
        controller.play("a")
        repeat(StreamRecovery.MAX_ATTEMPTS) { controller.onPlaybackError(http403()) }

        controller.onPlaybackError(http403())

        assertThat(controller.state.value.phase).isEqualTo(PlayerPhase.ERROR)
    }
}
