package com.ahmadnayfeh.shasha.remote

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.MainActivity
import com.ahmadnayfeh.shasha.lock.AppVisibility
import com.ahmadnayfeh.shasha.ui.navigation.NavigationRequests
import com.ahmadnayfeh.shasha.ui.navigation.OpenResult
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.util.concurrent.CopyOnWriteArrayList

/** How a phone link reaches the TV's screens ([TvDisplay], [NavigationRequests]). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TvDisplayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val visibility = AppVisibility()
    private val profileScreens = MutableStateFlow(true)
    private val requests = NavigationRequests()
    private val opened = CopyOnWriteArrayList<Screen>()
    private val display = TvDisplay(context, visibility, profileScreens, requests, main = Dispatchers.Unconfined, settleMs = 100)

    @After
    fun tearDown() = scope.cancel()

    /** The nav host of [profileId]'s screens: it opens whatever that profile was allowed. */
    private fun follow(profileId: String = ADULT): Job {
        val job = scope.launch {
            requests.requests.collect { request ->
                val result = if (request.profileId == profileId) OpenResult.OPENED else OpenResult.OTHER_PROFILE
                if (result == OpenResult.OPENED) opened += request.destination
                request.done.complete(result)
            }
        }
        runBlocking { withTimeout(5_000) { while (!requests.isFollowed) delay(5) } }
        return job
    }

    @Test
    fun inFront_onAProfilesScreens_theDestinationOpens() = runBlocking<Unit> {
        follow()
        visibility.onResumed()
        assertThat(display.show(Screen.Channel("UCx"), ADULT)).isEqualTo(ShowResult.SHOWN)
        assertThat(opened).containsExactly(Screen.Channel("UCx"))
    }

    @Test
    fun anotherProfilesScreens_neverOpenIt() = runBlocking<Unit> {
        // The link was checked for the adult; meanwhile someone chose the child's profile on the TV.
        follow(profileId = "kid")
        visibility.onResumed()
        assertThat(display.show(Screen.Player("abcdefghijk", fromLink = true), ADULT)).isEqualTo(ShowResult.PROFILE_CHANGED)
        assertThat(opened).isEmpty()
    }

    @Test
    fun withoutANavHost_orOnThePicker_nothingOpens() = runBlocking<Unit> {
        visibility.onResumed()
        // No profile's screens composed at all (the setup, the picker): no nav host follows.
        assertThat(requests.open(Screen.Channel("UCx"), ADULT)).isEqualTo(OpenResult.FAILED)
        follow()
        profileScreens.value = false
        assertThat(display.show(Screen.Channel("UCx"), ADULT)).isEqualTo(ShowResult.NO_PROFILE)
        assertThat(opened).isEmpty()
    }

    @Test
    fun theScreenInStandby_isSaid() = runBlocking<Unit> {
        follow()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        assertThat(display.show(Screen.Channel("UCx"), ADULT)).isEqualTo(ShowResult.SCREEN_OFF)
        assertThat(opened).isEmpty()
    }

    @Test
    fun inTheBackground_withoutDisplayOverOtherApps_itCannotComeForward() = runBlocking<Unit> {
        follow()
        ShadowSettings.setCanDrawOverlays(false)
        assertThat(display.show(Screen.Channel("UCx"), ADULT)).isEqualTo(ShowResult.NOT_IN_FRONT)
        assertThat(shadowOf(context as Application).nextStartedActivity).isNull()
    }

    @Test
    fun inTheBackground_itComesForward_thenOpens_onceTheProfilesScreensStayUp() = runBlocking<Unit> {
        follow()
        ShadowSettings.setCanDrawOverlays(true)
        val shown = scope.async { display.show(Screen.Player("abcdefghijk", fromLink = true), ADULT) }
        val started = withTimeout(5_000) {
            var intent = shadowOf(context as Application).nextStartedActivity
            while (intent == null) {
                delay(10)
                intent = shadowOf(context).nextStartedActivity
            }
            intent
        }
        assertThat(started.component?.className).isEqualTo(MainActivity::class.java.name)
        visibility.onResumed()
        assertThat(withTimeout(5_000) { shown.await() }).isEqualTo(ShowResult.SHOWN)
        assertThat(opened).containsExactly(Screen.Player("abcdefghijk", fromLink = true))
    }

    @Test
    fun backInFront_theTvAskingWhoWatches_meansNothingOpens_evenIfAProfileIsChosenAtOnce() = runBlocking<Unit> {
        follow()
        ShadowSettings.setCanDrawOverlays(true)
        val shown = scope.async { display.show(Screen.Channel("UCx"), ADULT) }
        withTimeout(5_000) { while (shadowOf(context as Application).nextStartedActivity == null) delay(10) }
        visibility.onResumed()
        // The old screens are still up for a moment, then the relock's «من يشاهد الآن؟» replaces them.
        delay(30)
        val asked = System.nanoTime()
        profileScreens.value = false
        // Whoever holds the remote chooses a profile quickly: the link, sent before, is not theirs.
        delay(150)
        profileScreens.value = true
        assertThat(withTimeout(10_000) { shown.await() }).isEqualTo(ShowResult.NO_PROFILE)
        // Said at once, not after waiting for the screens to come back.
        assertThat((System.nanoTime() - asked) / 1_000_000).isLessThan(2_000L)
        delay(300)
        assertThat(opened).isEmpty()
    }

    private companion object {
        const val ADULT = "adult"
    }
}
