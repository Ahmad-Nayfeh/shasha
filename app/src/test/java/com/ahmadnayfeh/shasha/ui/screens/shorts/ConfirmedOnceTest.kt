package com.ahmadnayfeh.shasha.ui.screens.shorts

import com.ahmadnayfeh.shasha.remote.ConfirmAnswer
import com.ahmadnayfeh.shasha.ui.screens.player.StartConfirmation
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Serious Mode with the way out turned off: a start that only the phone could end early waits for the parent's «تأكيد». In the Shorts
 * player that is asked once for the whole visit, not before every Short flipped to (a tap on a phone for each one would make the
 * Shorts unusable), and only a real confirmation counts as the one.
 */
class ConfirmedOnceTest {
    private class Phone(var needed: Boolean = true, var answer: ConfirmAnswer = ConfirmAnswer.CONFIRMED) : StartConfirmation {
        val asked = mutableListOf<String>()

        override fun needed(videoId: String) = needed

        override suspend fun ask(videoId: String, title: String): ConfirmAnswer {
            asked += videoId
            return answer
        }
    }

    @Test
    fun onceTheParentConfirmed_noOtherShortAsksAgain() = runBlocking<Unit> {
        val phone = Phone()
        val once = ConfirmedOnce(phone)
        assertThat(once.needed("aaaaaaaaaaa")).isTrue()
        assertThat(once.ask("aaaaaaaaaaa", "مقطع")).isEqualTo(ConfirmAnswer.CONFIRMED)
        assertThat(once.needed("bbbbbbbbbbb")).isFalse()
        assertThat(once.needed("aaaaaaaaaaa")).isFalse()
        assertThat(phone.asked).containsExactly("aaaaaaaaaaa")
    }

    @Test
    fun aRefusalOrATimeout_isNotAConfirmation_theNextShortAsksAgain() = runBlocking<Unit> {
        val phone = Phone(answer = ConfirmAnswer.REFUSED)
        val once = ConfirmedOnce(phone)
        assertThat(once.ask("aaaaaaaaaaa", "مقطع")).isEqualTo(ConfirmAnswer.REFUSED)
        assertThat(once.needed("bbbbbbbbbbb")).isTrue()
        phone.answer = ConfirmAnswer.TIMED_OUT
        assertThat(once.ask("bbbbbbbbbbb", "مقطع")).isEqualTo(ConfirmAnswer.TIMED_OUT)
        assertThat(once.needed("ccccccccccc")).isTrue()
    }

    @Test
    fun whatNeedsNoConfirmation_neverAsks() {
        val once = ConfirmedOnce(Phone(needed = false))
        assertThat(once.needed("aaaaaaaaaaa")).isFalse()
    }
}
