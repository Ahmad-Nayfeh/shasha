package com.ahmadnayfeh.shasha.remote

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.R
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1.2 (owner decision): a link from the phone never plays by itself. It waits on the TV for «قبول» or «رفض», 30 s at
 * most, one at a time; the phone follows it through its status ([PhonePlayRequests.latest]). On the test's virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PhonePlayRequestsTest {
    private val texts = RemoteTexts(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun TestScope.requests() = PhonePlayRequests(backgroundScope, texts, nowMs = { testScheduler.currentTime })

    /** What opening it did, counted. */
    private var opened = 0

    private fun opens(result: RemoteResult = RemoteResult.ok("يُعرض الآن")): suspend () -> RemoteResult = {
        opened++
        result
    }

    @Test
    fun aLink_waitsUntilAccepted_thenOpens_andThePhoneReadsHowItEnded() = runTest {
        val requests = requests()
        val sent = requests.submit("adult", "Documentary", "بانتظار القبول", opens())
        assertThat(sent.state).isEqualTo(RemotePlayState.PENDING)
        assertThat(sent.message).isEqualTo("بانتظار القبول")
        assertThat(sent.secondsLeft).isEqualTo(30)
        val waiting = requests.pending.value!!
        assertThat(waiting.id).isEqualTo(sent.id)
        assertThat(waiting.profileId).isEqualTo("adult")
        assertThat(waiting.title).isEqualTo("Documentary")

        advanceTimeBy(10_000)
        runCurrent()
        assertThat(opened).isEqualTo(0)
        assertThat(requests.latest()!!.secondsLeft).isEqualTo(20)
        assertThat(requests.secondsLeft(waiting)).isEqualTo(20)

        assertThat(requests.accept(sent.id)).isTrue()
        assertThat(requests.pending.value).isNull()
        // Opening (the TV's rules asked again): the phone still reads it as waiting, with no time left.
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.PENDING)
        assertThat(requests.latest()!!.secondsLeft).isEqualTo(0)
        runCurrent()
        assertThat(opened).isEqualTo(1)
        assertThat(requests.latest()).isEqualTo(RemotePlayRequest(sent.id, "Documentary", RemotePlayState.ACCEPTED, "يُعرض الآن"))

        // Answered: a second «قبول» does nothing, and its 30 s change nothing.
        assertThat(requests.accept(sent.id)).isFalse()
        assertThat(requests.reject(sent.id)).isFalse()
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(opened).isEqualTo(1)
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.ACCEPTED)
    }

    @Test
    fun nobodyAnswers_rejectedByItselfAfter30Seconds_andNothingOpens() = runTest {
        val requests = requests()
        val sent = requests.submit("adult", "Documentary", "w", opens())
        advanceTimeBy(PhonePlayRequests.TIMEOUT_MS - 1)
        runCurrent()
        assertThat(requests.pending.value).isNotNull()
        assertThat(requests.latest()!!.secondsLeft).isEqualTo(1)

        advanceTimeBy(2)
        runCurrent()
        assertThat(requests.pending.value).isNull()
        assertThat(requests.latest())
            .isEqualTo(RemotePlayRequest(sent.id, "Documentary", RemotePlayState.TIMED_OUT, texts.get(R.string.remote_play_timed_out)))
        assertThat(requests.accept(sent.id)).isFalse()
        runCurrent()
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun rejectedOnTheTv_opensNothing() = runTest {
        val requests = requests()
        val sent = requests.submit("kid", "Cartoon", "w", opens())
        assertThat(requests.reject("another-id")).isFalse()
        assertThat(requests.reject(sent.id)).isTrue()
        runCurrent()
        assertThat(requests.pending.value).isNull()
        assertThat(requests.latest())
            .isEqualTo(RemotePlayRequest(sent.id, "Cartoon", RemotePlayState.REJECTED, texts.get(R.string.remote_play_rejected)))
        assertThat(requests.accept(sent.id)).isFalse()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(opened).isEqualTo(0)
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.REJECTED)
    }

    @Test
    fun aNewerLink_replacesTheOneWaiting_andKeepsItsOwn30Seconds() = runTest {
        val requests = requests()
        val first = requests.submit("adult", "First", "w", opens())
        advanceTimeBy(20_000)
        val second = requests.submit("adult", "Second", "w", opens())
        assertThat(requests.pending.value!!.id).isEqualTo(second.id)
        assertThat(requests.accept(first.id)).isFalse()
        // The phones follow the newer one.
        assertThat(requests.latest()!!.id).isEqualTo(second.id)
        assertThat(requests.latest()!!.secondsLeft).isEqualTo(30)

        // The first one's 30 s end: the second still waits (its own end is 30 s after it came).
        advanceTimeBy(15_000)
        runCurrent()
        assertThat(requests.pending.value!!.id).isEqualTo(second.id)
        advanceTimeBy(15_001)
        runCurrent()
        assertThat(requests.pending.value).isNull()
        assertThat(requests.latest()!!.state).isEqualTo(RemotePlayState.TIMED_OUT)
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun theTvCannotOpenItAnyMore_thePhoneReadsWhy() = runTest {
        val requests = requests()
        val refused = requests.submit("adult", "Documentary", "w", opens(RemoteResult.error("تغيّر الملف")))
        requests.accept(refused.id)
        runCurrent()
        assertThat(requests.latest()).isEqualTo(RemotePlayRequest(refused.id, "Documentary", RemotePlayState.FAILED, "تغيّر الملف"))

        // A failure while opening is an answer too, never a crash.
        val broken = requests.submit("adult", "Documentary", "w") { throw IllegalStateException("screens gone") }
        requests.accept(broken.id)
        runCurrent()
        assertThat(requests.latest())
            .isEqualTo(RemotePlayRequest(broken.id, "Documentary", RemotePlayState.FAILED, texts.get(R.string.remote_show_failed)))
    }

    @Test
    fun theTvLeavesTheProfile_theRequestForItIsDropped() = runTest {
        val requests = requests()
        val sent = requests.submit("adult", "Documentary", "w", opens())
        requests.onProfile("adult")
        assertThat(requests.pending.value).isNotNull()

        requests.onProfile("kid")
        assertThat(requests.pending.value).isNull()
        assertThat(requests.latest())
            .isEqualTo(RemotePlayRequest(sent.id, "Documentary", RemotePlayState.FAILED, texts.get(R.string.remote_profile_changed)))
        assertThat(requests.accept(sent.id)).isFalse()
        runCurrent()
        assertThat(opened).isEqualTo(0)
    }

    @Test
    fun aVideoGetsLocked_theRequestIsDroppedWithTheLocksReason() = runTest {
        val requests = requests()
        requests.onLocked("الوضع الجاد مفعّل") // nothing waits: nothing to drop
        assertThat(requests.latest()).isNull()

        val sent = requests.submit("adult", "Documentary", "w", opens())
        requests.onLocked("الوضع الجاد مفعّل")
        assertThat(requests.pending.value).isNull()
        assertThat(requests.latest()).isEqualTo(RemotePlayRequest(sent.id, "Documentary", RemotePlayState.FAILED, "الوضع الجاد مفعّل"))
        assertThat(requests.accept(sent.id)).isFalse()
    }

    @Test
    fun howItEnded_isKeptTwoMinutesForThePhones() = runTest {
        val requests = requests()
        val sent = requests.submit("adult", "Documentary", "w", opens())
        requests.reject(sent.id)
        advanceTimeBy(PhonePlayRequests.KEEP_MS)
        assertThat(requests.latest()!!.id).isEqualTo(sent.id)
        advanceTimeBy(1)
        assertThat(requests.latest()).isNull()
    }
}
