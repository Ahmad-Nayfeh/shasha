package com.ahmadnayfeh.shasha.remote

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.ahmadnayfeh.shasha.policy.DenyReason
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.ui.navigation.Screen
import com.ahmadnayfeh.shasha.ui.parental.ParentalLists
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.2's owner decisions on the controller's side, over the TV's real rules ([RemoteTestEnv]): a link from the phone
 * never plays by itself (it waits for «قبول» on the TV, what plays goes on meanwhile), a refused link never asks, and the
 * parent's «تأكيد» for a video only the phone could end early.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TvRemoteControllerV12Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var env: RemoteTestEnv
    private val controller get() = env.controller

    @Before
    fun setUp() {
        env = remoteEnv(tmp.root)
    }

    @After
    fun tearDown() = env.close()

    private fun text(id: Int, vararg args: Any) = env.texts.get(id, *args)

    @Test
    fun anAllowedLink_waitsForAcceptOnTheTv_andWhatPlaysGoesOnUntouched() = runBlocking<Unit> {
        val playing = env.playOnScreen("onscreen001")
        val sent = controller.playLink(watchLink(ADULT_VIDEO))
        assertThat(sent.ok).isTrue()
        assertThat(sent.message).isEqualTo(text(R.string.remote_play_waiting, "Documentary"))
        val request = sent.request!!
        assertThat(request.state).isEqualTo(RemotePlayState.PENDING)
        assertThat(request.title).isEqualTo("Documentary")
        assertThat(request.secondsLeft).isIn(1..30)

        // Asked on the TV, for the adult's screens; nothing opened, the player on screen not touched.
        val waiting = env.playRequests.pending.value!!
        assertThat(waiting.id).isEqualTo(request.id)
        assertThat(waiting.profileId).isEqualTo("adult")
        assertThat(env.display.shown).isEmpty()
        assertThat(env.display.prepared).isEqualTo(1)
        assertThat(playing.commands).isEmpty()
        assertThat(playing.left).isEqualTo(0)
        assertThat(env.hub.active.value).isSameInstanceAs(playing)
        assertThat(controller.status().playRequest!!.state).isEqualTo(RemotePlayState.PENDING)

        // «قبول»: opened now, as a link the phone sent (no «تأكيد» on top); the phone reads it.
        val ended = env.acceptOnTv()
        assertThat(ended.state).isEqualTo(RemotePlayState.ACCEPTED)
        assertThat(ended.message).isEqualTo(text(R.string.remote_play_sent, "Documentary"))
        assertThat(env.display.shown).containsExactly(Screen.Player(ADULT_VIDEO, fromLink = true, phoneApproved = true))
        assertThat(controller.status().playRequest).isEqualTo(ended)
    }

    @Test
    fun rejectedOnTheTv_opensNothing_andThePhoneReadsIt() = runBlocking<Unit> {
        val playing = env.playOnScreen("onscreen001")
        val sent = controller.playLink(watchLink(ADULT_VIDEO)).request!!
        assertThat(env.playRequests.reject(sent.id)).isTrue()
        val ended = env.endOf(sent.id)
        assertThat(ended.state).isEqualTo(RemotePlayState.REJECTED)
        assertThat(ended.message).isEqualTo(text(R.string.remote_play_rejected))
        assertThat(env.display.shown).isEmpty()
        assertThat(playing.left).isEqualTo(0)
        assertThat(controller.status().playRequest!!.state).isEqualTo(RemotePlayState.REJECTED)
    }

    @Test
    fun refusedLinks_neverAskTheTv() = runBlocking<Unit> {
        env.enterChild()
        val outside = controller.playLink(watchLink(OUTSIDE_VIDEO))
        assertThat(outside.ok).isFalse()
        assertThat(outside.message).isEqualTo(env.texts.deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(outside.request).isNull()
        val notYouTube = controller.playLink("https://example.com/watch?v=abcdefghijk")
        assertThat(notYouTube.ok).isFalse()
        assertThat(notYouTube.request).isNull()
        assertThat(env.playRequests.pending.value).isNull()
        assertThat(env.display.prepared).isEqualTo(0)
        assertThat(controller.status().playRequest).isNull()
    }

    @Test
    fun aLockedVideo_refusesTheLink_withTheLocksReason_beforeAnything() = runBlocking<Unit> {
        env.lock.onVideoStarted("adult", "onscreen001", null)
        assertThat(env.lock.current()).isNotNull()
        val sent = controller.playLink(watchLink(ADULT_VIDEO))
        assertThat(sent.ok).isFalse()
        assertThat(sent.message).isEqualTo(env.texts.deny(DenyReason.LOCKED))
        assertThat(sent.request).isNull()
        assertThat(env.playRequests.pending.value).isNull()
        assertThat(env.display.prepared).isEqualTo(0)
    }

    @Test
    fun aTvThatCannotAsk_refusesAtOnce() = runBlocking<Unit> {
        for (state in listOf(ShowResult.SCREEN_OFF, ShowResult.NOT_IN_FRONT, ShowResult.NO_PROFILE)) {
            env.display.result = state
            val sent = controller.playLink(watchLink(ADULT_VIDEO))
            assertThat(sent.ok).isFalse()
            assertThat(sent.message).isEqualTo(env.texts.show(state))
            assertThat(sent.request).isNull()
            assertThat(env.playRequests.pending.value).isNull()
        }
    }

    @Test
    fun aVideoLockedWhileTheLinkWaited_dropsTheQuestion_withTheLocksReason() = runBlocking<Unit> {
        val sent = controller.playLink(watchLink(ADULT_VIDEO)).request!!
        // The TV starts a video meanwhile: Serious Mode locks it.
        env.lock.onVideoStarted("adult", "onscreen001", null)
        val ended = env.endOf(sent.id)
        assertThat(ended.state).isEqualTo(RemotePlayState.FAILED)
        assertThat(ended.message).isEqualTo(env.texts.deny(DenyReason.LOCKED))
        assertThat(env.playRequests.pending.value).isNull()
        assertThat(env.display.shown).isEmpty()
        assertThat(env.lock.current()!!.videoId).isEqualTo("onscreen001")
    }

    @Test
    fun theTvSwitchesProfileWhileTheLinkWaits_theQuestionGoes() = runBlocking<Unit> {
        val sent = controller.playLink(watchLink(INSIDE_VIDEO)).request!!
        env.enterChild()
        val ended = env.endOf(sent.id)
        assertThat(ended.state).isEqualTo(RemotePlayState.FAILED)
        assertThat(ended.message).isEqualTo(text(R.string.remote_profile_changed))
        assertThat(env.display.shown).isEmpty()
    }

    @Test
    fun acceptedButTheRulesChangedMeanwhile_opensNothing() = runBlocking<Unit> {
        val sent = controller.playLink(watchLink(ADULT_VIDEO)).request!!
        // The household blocks the channel while the TV asks: «قبول» checks again.
        val channelId = env.source.videos.getValue(ADULT_VIDEO).channelId!!
        ParentalLists(env.profiles).blockChannel(ListedChannel(channelId, "قناة"))
        withTimeout(5_000) { env.gate.filter.first { it.profile?.blocklist?.hasChannel(channelId) == true } }
        assertThat(env.playRequests.accept(sent.id)).isTrue()
        val ended = env.endOf(sent.id)
        assertThat(ended.state).isEqualTo(RemotePlayState.FAILED)
        assertThat(ended.message).isEqualTo(env.texts.deny(DenyReason.BLOCKED_CHANNEL))
        assertThat(env.display.shown).isEmpty()
    }

    @Test
    fun theParentsConfirmation_fromThePhone_answersThePlayer() = runBlocking<Unit> {
        assertThat(controller.status().confirmation).isNull()
        val asked = env.scope.async { env.confirmations.ask("adult", ADULT_VIDEO, "Documentary") }
        val waiting = withTimeout(5_000) { env.confirmations.pending.first { it != null } }!!
        val shown = controller.status().confirmation!!
        assertThat(shown.id).isEqualTo(waiting.id)
        assertThat(shown.title).isEqualTo("Documentary")
        assertThat(shown.secondsLeft).isIn(1..120)

        assertThat(controller.answerConfirmation("made-up", approve = true))
            .isEqualTo(RemoteResult.error(text(R.string.remote_confirm_gone)))
        assertThat(controller.answerConfirmation(waiting.id, approve = true))
            .isEqualTo(RemoteResult.ok(text(R.string.remote_confirm_done, "Documentary")))
        assertThat(asked.await()).isEqualTo(ConfirmAnswer.CONFIRMED)
        assertThat(controller.status().confirmation).isNull()
        // Answered: another phone's answer finds nothing.
        assertThat(controller.answerConfirmation(waiting.id, approve = false).ok).isFalse()

        val refused = env.scope.async { env.confirmations.ask("adult", ADULT_VIDEO, "Documentary") }
        val second = withTimeout(5_000) { env.confirmations.pending.first { it != null } }!!
        assertThat(controller.answerConfirmation(second.id, approve = false))
            .isEqualTo(RemoteResult.ok(text(R.string.remote_confirm_refused, "Documentary")))
        assertThat(refused.await()).isEqualTo(ConfirmAnswer.REFUSED)
    }
}
