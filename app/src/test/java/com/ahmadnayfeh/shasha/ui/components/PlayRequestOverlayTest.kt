package com.ahmadnayfeh.shasha.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.TV_1080P
import com.ahmadnayfeh.shasha.remote.PhonePlayRequests
import com.ahmadnayfeh.shasha.remote.RemotePlayState
import com.ahmadnayfeh.shasha.remote.RemoteResult
import com.ahmadnayfeh.shasha.remote.RemoteTexts
import com.ahmadnayfeh.shasha.ui.theme.ShashaTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * v1.2: «طلب تشغيل من الجوال» on the TV, over what is on screen (which stays: nothing is left or dimmed). Focus starts on
 * «قبول»; «قبول» opens the link, «رفض» or BACK refuses it; only the screens of the profile the rules allowed it for ask.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = TV_1080P)
class PlayRequestOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val requests = PhonePlayRequests(scope, RemoteTexts(context.resources))
    private val opened = AtomicInteger()

    @After
    fun tearDown() = scope.cancel()

    private fun send(profileId: String = "adult") =
        requests.submit(profileId, "10 Facts About Black Holes!", "w") {
            opened.incrementAndGet()
            RemoteResult.ok("يُعرض الآن")
        }

    private fun show(profileId: String = "adult") {
        compose.setContent {
            ShashaTheme {
                // What plays on the TV, under the question.
                Box(Modifier.fillMaxSize()) { Box(Modifier.size(100.dp).testTag("playing")) }
                PhonePlayRequestOverlay(requests, profileId)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theQuestion_namesTheVideo_withAcceptFocused_andAcceptOpensIt() {
        val sent = send()
        show()
        compose.onNodeWithTag("play_request").assertExists()
        compose.onNodeWithTag("play_request_title").assertTextEquals(context.getString(R.string.quoted_name, "10 Facts About Black Holes!"))
        compose.onNodeWithTag("play_request_accept").assertIsFocused()
        // What plays is still there.
        compose.onNodeWithTag("playing").assertExists()

        compose.onNodeWithTag("play_request_accept").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("play_request").assertDoesNotExist()
        assertThat(opened.get()).isEqualTo(1)
        assertThat(requests.latest()!!.id).isEqualTo(sent.id)
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.ACCEPTED)
    }

    @Test
    fun reject_opensNothing() {
        send()
        show()
        compose.onNodeWithTag("play_request_reject").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithTag("play_request").assertDoesNotExist()
        assertThat(opened.get()).isEqualTo(0)
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.REJECTED)
    }

    @Test
    fun back_isReject() {
        send()
        show()
        Espresso.pressBack()
        compose.waitForIdle()
        compose.onNodeWithTag("play_request").assertDoesNotExist()
        assertThat(opened.get()).isEqualTo(0)
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.REJECTED)
    }

    @Test
    fun anotherProfilesScreens_doNotAsk() {
        send(profileId = "kid")
        show(profileId = "adult")
        compose.onNodeWithTag("play_request").assertDoesNotExist()
        assertThat(requests.pending.value).isNotNull()
    }

    @Test
    fun aLinkSentWhileTheScreensAreUp_isAskedAtOnce() {
        show()
        compose.onNodeWithTag("play_request").assertDoesNotExist()
        send()
        compose.waitForIdle()
        compose.onNodeWithTag("play_request").assertExists()
    }
}
