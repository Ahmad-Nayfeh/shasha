package com.ahmadnayfeh.shasha.mobile.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.mobile.FakeVideoSource
import com.ahmadnayfeh.shasha.mobile.TestStreams
import com.ahmadnayfeh.shasha.mobile.data.AppSettings
import com.ahmadnayfeh.shasha.mobile.data.LibraryRepository
import com.ahmadnayfeh.shasha.mobile.data.NOW_MS
import com.ahmadnayfeh.shasha.mobile.data.inMemoryDatabase
import com.ahmadnayfeh.shasha.mobile.failure
import com.ahmadnayfeh.shasha.mobile.sponsorblock.SponsorBlockRepository
import com.ahmadnayfeh.shasha.mobile.video
import com.ahmadnayfeh.shasha.youtube.StreamDetails
import com.ahmadnayfeh.shasha.youtube.playback.YouTubeMediaSourceFactory
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * What the Shorts viewer asks of the app's one player: [PlaybackMode.SHORT] for a Short and the video's mode for everything else,
 * and the lookup of the next Short's streams made ahead and used once. Robolectric cannot play media (the streams here are the
 * kind the factory refuses, as in the other tests), so what is checked is what the controller asks of its source and the mode it
 * is in; what the player does with real streams is for a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerControllerShortsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase()
    // The controller takes its scope, so no Main dispatcher is replaced: a replaced one would stay replaced for the Robolectric tests that
    // run after this one in the same JVM (the app's own scope runs on the real Main).
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
    private var elapsed = 0L
    private val asked = ConcurrentLinkedQueue<String>()
    private val failures = HashSet<String>()

    /** While set, the source does not answer: the lookup stays in flight. */
    private var hold: CompletableDeferred<Unit>? = null

    private val source = object : FakeVideoSource() {
        override suspend fun stream(videoId: String, allowLowQuality: Boolean): StreamDetails {
            asked += videoId
            hold?.await()
            if (failures.remove(videoId)) throw failure()
            return TestStreams.details(video(videoId))
        }
    }

    private var appSettings = AppSettings(sponsorBlockEnabled = false)

    private val controller = PlayerController(
        context = context,
        videoSource = source,
        mediaSources = YouTubeMediaSourceFactory(context, OkHttpClient()),
        settings = { appSettings },
        sponsorBlock = SponsorBlockRepository(fetcher = { emptyList() }, dao = db.sponsorSegments(), now = { NOW_MS }),
        library = LibraryRepository(db.history(), db.watchLater(), db.progress()) { NOW_MS },
        scope = scope,
        elapsedClock = { elapsed },
    )

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    private fun short(id: String) = video(id).copy(isShortForm = true)

    @Test
    fun aShortIsPlayedInShortMode_aVideoInVideoMode_andLeavingForgetsTheMode() {
        assertThat(controller.mode).isEqualTo(PlaybackMode.VIDEO)

        controller.playShort(short("a"))
        assertThat(controller.mode).isEqualTo(PlaybackMode.SHORT)
        assertThat(controller.state.value.video?.videoId).isEqualTo("a")

        controller.play("b")
        assertThat(controller.mode).isEqualTo(PlaybackMode.VIDEO)

        controller.playShort(short("c"))
        controller.stop()
        assertThat(controller.mode).isEqualTo(PlaybackMode.VIDEO)
        assertThat(controller.state.value.phase).isEqualTo(PlayerPhase.IDLE)
    }

    @Test
    fun theShortOnScreenIsShownAtOnceFromWhatTheListKnew() {
        controller.playShort(short("a").copy(title = "عنوان", channelName = "قناة"))

        val state = controller.state.value
        assertThat(state.video?.title).isEqualTo("عنوان")
        assertThat(state.video?.channelName).isEqualTo("قناة")
    }

    @Test
    fun theSameShortAskedForAgainWhileItLoadsIsNotStartedAgain() {
        hold = CompletableDeferred()
        controller.playShort(short("a"))
        controller.playShort(short("a")) // the pager reporting the page it is on again

        assertThat(asked.toList()).containsExactly("a")
        assertThat(controller.state.value.phase).isEqualTo(PlayerPhase.LOADING)
    }

    @Test
    fun aShortThatFailedIsStartedAgainWhenItIsAskedForAgain() {
        failures += "a"
        controller.playShort(short("a"))
        assertThat(controller.state.value.phase).isEqualTo(PlayerPhase.ERROR)

        controller.playShort(short("a")) // «Try again» on the viewer, or swiping away and back

        assertThat(asked.toList()).containsExactly("a", "a").inOrder()
    }

    @Test
    fun theNextShortsStreamsAreLookedUpAheadAndUsedWhenItPlaysWithoutAskingAgain() {
        controller.playShort(short("a"))
        controller.prefetch("b")
        assertThat(asked.toList()).containsExactly("a", "b").inOrder()

        controller.playShort(short("b"))

        assertThat(asked.toList()).containsExactly("a", "b").inOrder() // b was asked for once, ahead
        assertThat(controller.state.value.video?.videoId).isEqualTo("b")
    }

    @Test
    fun theSameLookupIsNotMadeTwice() {
        controller.prefetch("b")
        controller.prefetch("b")

        assertThat(asked.toList()).containsExactly("b")
    }

    @Test
    fun aLookupIsUsedOnce_theSecondPlayOfTheSameShortAsksAgain() {
        controller.prefetch("b")
        controller.playShort(short("b"))
        controller.playShort(short("c"))

        controller.playShort(short("b"))

        assertThat(asked.toList()).containsExactly("b", "c", "b").inOrder()
    }

    @Test
    fun aLookupForAnotherShortIsNotUsedForThisOne() {
        controller.prefetch("b")

        controller.playShort(short("c"))

        assertThat(asked.toList()).containsExactly("b", "c").inOrder()
    }

    @Test
    fun aLookupThatFailedIsNotKept_thePlayAsksAgain() {
        failures += "b"
        controller.prefetch("b")

        controller.playShort(short("b"))

        assertThat(asked.toList()).containsExactly("b", "b").inOrder()
    }

    @Test
    fun aLookupThatIsTooOldIsNotUsed_theStreamsAddressesMayHaveExpired() {
        controller.prefetch("b")
        elapsed += PlayerController.PREFETCH_MAX_AGE_MS + 1

        controller.playShort(short("b"))

        assertThat(asked.toList()).containsExactly("b", "b").inOrder()
    }

    @Test
    fun aNewerLookupReplacesTheOlderOne() {
        controller.prefetch("b")
        controller.prefetch("c")

        controller.playShort(short("b")) // b's lookup was dropped when c's was made

        assertThat(asked.toList()).containsExactly("b", "c", "b").inOrder()
    }

    @Test
    fun leavingForgetsTheLookup() {
        controller.prefetch("b")
        controller.stop()

        controller.playShort(short("b"))

        assertThat(asked.toList()).containsExactly("b", "b").inOrder()
    }

    @Test
    fun aVideoOnThePlayerScreenIsPlayedInVideoModeWhateverThePlayerDidBefore() {
        controller.playShort(short("a"))

        controller.play("a") // the same id, but as a video now: the player screen starts it afresh

        assertThat(controller.mode).isEqualTo(PlaybackMode.VIDEO)
        assertThat(asked.toList()).containsExactly("a", "a").inOrder()
    }

    @Test
    fun aShortIsUprightFromTheStart_aVideoIsWide() {
        controller.playShort(short("a"))
        assertThat(controller.state.value.aspectRatio).isEqualTo(PlayerUiState.SHORT_ASPECT)

        controller.play("b")
        assertThat(controller.state.value.aspectRatio).isEqualTo(PlayerUiState.DEFAULT_ASPECT)
    }

    @Test
    fun aShortStartsTheMediaSessionLikeAVideo_forTheNotificationAndTheBackground() {
        val app = shadowOf(context as Application)
        while (app.nextStartedService != null) Unit // what came before

        controller.playShort(short("a"))

        assertThat(app.nextStartedService?.component?.className).isEqualTo(PlaybackService::class.java.name)
    }

    @Test
    fun aShortPlaysOnWhenTheAppIsLeft_likeAVideo() {
        controller.playShort(short("a"))
        controller.player.playWhenReady = true

        controller.onAppStopped()

        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun withoutBackgroundPlaybackAShortPausesAndPlaysOnWhenTheViewerComesBack() {
        appSettings = appSettings.copy(backgroundPlayback = false)
        controller.playShort(short("a"))
        controller.player.playWhenReady = true

        controller.onAppStopped()
        assertThat(controller.player.playWhenReady).isFalse()

        controller.onAppStarted()
        assertThat(controller.player.playWhenReady).isTrue()
    }

    @Test
    fun theShortsStartAtTheNormalSpeed_andKeepTheSpeedChosenInThemFromOneToTheNext() {
        controller.play("v")
        controller.setSpeed(2f)

        controller.playShort(short("a"))
        assertThat(controller.state.value.speed).isEqualTo(1f)

        controller.setSpeed(1.5f)
        controller.playShort(short("b"))
        assertThat(controller.state.value.speed).isEqualTo(1.5f)
    }
}
