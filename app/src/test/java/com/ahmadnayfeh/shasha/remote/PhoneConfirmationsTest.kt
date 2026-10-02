package com.ahmadnayfeh.shasha.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * v1.2 (owner decision): a video only the phone could end early waits for the parent's «تأكيد» on the phone, two
 * minutes at most; «رفض», the time or the TV's «إلغاء» mean it does not start. On the test's virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneConfirmationsTest {
    private fun TestScope.confirmations() = PhoneConfirmations(nowMs = { testScheduler.currentTime })

    @Test
    fun thePlayerAsks_thePhoneSeesIt_andTheParentsConfirmationStartsTheVideo() = runTest {
        val confirmations = confirmations()
        val answer = async { confirmations.ask("adult", "video000001", "Documentary") }
        runCurrent()
        val waiting = confirmations.pending.value!!
        assertThat(waiting.profileId).isEqualTo("adult")
        assertThat(waiting.videoId).isEqualTo("video000001")
        assertThat(waiting.title).isEqualTo("Documentary")
        assertThat(confirmations.secondsLeft(waiting)).isEqualTo(120)
        advanceTimeBy(30_000)
        assertThat(confirmations.secondsLeft(waiting)).isEqualTo(90)
        assertThat(answer.isActive).isTrue()

        assertThat(confirmations.answer("another-id", approve = true)).isNull()
        assertThat(confirmations.answer(waiting.id, approve = true)).isSameInstanceAs(waiting)
        assertThat(answer.await()).isEqualTo(ConfirmAnswer.CONFIRMED)
        assertThat(confirmations.pending.value).isNull()
        // Answered once: a second answer (another phone) finds nothing.
        assertThat(confirmations.answer(waiting.id, approve = false)).isNull()
    }

    @Test
    fun thePhoneRefuses_theVideoDoesNotStart() = runTest {
        val confirmations = confirmations()
        val answer = async { confirmations.ask("adult", "video000001", "Documentary") }
        runCurrent()
        confirmations.answer(confirmations.pending.value!!.id, approve = false)
        assertThat(answer.await()).isEqualTo(ConfirmAnswer.REFUSED)
        assertThat(confirmations.pending.value).isNull()
    }

    @Test
    fun noAnswerInTwoMinutes_timesOut_andIsWithdrawn() = runTest {
        val confirmations = confirmations()
        val answer = async { confirmations.ask("adult", "video000001", "Documentary") }
        runCurrent()
        val waiting = confirmations.pending.value!!
        advanceTimeBy(PhoneConfirmations.TIMEOUT_MS - 1)
        runCurrent()
        assertThat(answer.isActive).isTrue()
        advanceTimeBy(2)
        runCurrent()
        assertThat(answer.await()).isEqualTo(ConfirmAnswer.TIMED_OUT)
        assertThat(confirmations.pending.value).isNull()
        assertThat(confirmations.answer(waiting.id, approve = true)).isNull()
    }

    @Test
    fun theTvsCancel_withdrawsTheRequestAtOnce() = runTest {
        val confirmations = confirmations()
        val answer = async { confirmations.ask("adult", "video000001", "Documentary") }
        runCurrent()
        val waiting = confirmations.pending.value!!
        answer.cancel()
        runCurrent()
        assertThat(confirmations.pending.value).isNull()
        assertThat(confirmations.answer(waiting.id, approve = true)).isNull()
    }

    @Test
    fun aNewPlayerAsking_endsTheOldRequest_asNotStarted() = runTest {
        val confirmations = confirmations()
        val old = async { confirmations.ask("adult", "video000001", "First") }
        runCurrent()
        val first = confirmations.pending.value!!
        val new = async { confirmations.ask("adult", "video000002", "Second") }
        runCurrent()
        assertThat(old.await()).isEqualTo(ConfirmAnswer.REFUSED)
        val second = confirmations.pending.value!!
        assertThat(second.title).isEqualTo("Second")
        assertThat(confirmations.answer(first.id, approve = true)).isNull()
        assertThat(confirmations.answer(second.id, approve = true)).isSameInstanceAs(second)
        assertThat(new.await()).isEqualTo(ConfirmAnswer.CONFIRMED)
    }
}
