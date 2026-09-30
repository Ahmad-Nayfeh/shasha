package com.ahmadnayfeh.shasha.lock

import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_BACK
import android.view.KeyEvent.KEYCODE_DPAD_CENTER
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import com.ahmadnayfeh.shasha.lock.ExitHold.Change
import com.ahmadnayfeh.shasha.lock.ExitHold.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The adult's 10-second hold (SPEC §4) over raw key events: remotes that repeat a held key, remotes that
 * only send the press and the release, and what must never leak to the screen underneath.
 */
class ExitHoldTest {
    private val hold = ExitHold()
    private val t0 = 1_000_000L

    private fun down(code: Int, at: Long, repeat: Int = 0, downTime: Long = t0) = hold.onKey(code, ACTION_DOWN, repeat, downTime, at)

    private fun up(code: Int, at: Long, downTime: Long = t0) = hold.onKey(code, ACTION_UP, 0, downTime, at)

    /** The system's repeats of a key held since [t0]: from 400 ms, every 50 ms, up to [until]. */
    private fun repeats(code: Int, until: Long): List<Outcome> =
        (400L..until step 50).mapIndexed { i, at -> down(code, t0 + at, repeat = i + 1) }

    @Test
    fun aShortPress_isAnOrdinaryPress_nothingIsTakenFromTheScreen() {
        assertThat(down(KEYCODE_BACK, t0)).isEqualTo(Outcome(consume = false))
        assertThat(up(KEYCODE_BACK, t0 + 120)).isEqualTo(Outcome(consume = false))
        assertThat(hold.isTracking).isFalse()
        assertThat(down(KEYCODE_DPAD_CENTER, t0 + 500, downTime = t0 + 500)).isEqualTo(Outcome(consume = false))
        assertThat(up(KEYCODE_DPAD_CENTER, t0 + 700, downTime = t0 + 500)).isEqualTo(Outcome(consume = false))
    }

    @Test
    fun holdingWithRepeats_showsTheRing_countsDown_andCompletesAtTenSeconds() {
        assertThat(down(KEYCODE_DPAD_CENTER, t0).consume).isFalse()
        val outcomes = repeats(KEYCODE_DPAD_CENTER, until = 9_950)
        // Every repeat is the hold's: nothing underneath sees it (a tv Surface would take one for a long click).
        assertThat(outcomes.all { it.consume }).isTrue()
        assertThat(outcomes.first().change).isEqualTo(Change.SHOWN)
        assertThat(outcomes.drop(1).map { it.change }.toSet()).containsExactly(Change.PROGRESS)
        assertThat(hold.isShown).isTrue()
        assertThat(hold.heldMillis).isEqualTo(9_950)
        assertThat(hold.secondsLeft).isEqualTo(1)

        val last = down(KEYCODE_DPAD_CENTER, t0 + 10_000, repeat = outcomes.size + 1)
        assertThat(last).isEqualTo(Outcome(consume = true, change = Change.COMPLETED))
        assertThat(hold.completedMillis).isEqualTo(10_000)
        assertThat(hold.isShown).isFalse()

        // Still held: its repeats and its release are swallowed (the phrase keyboard that opened must not get them).
        assertThat(hold.isSwallowing).isTrue()
        assertThat(down(KEYCODE_DPAD_CENTER, t0 + 10_050, repeat = outcomes.size + 2)).isEqualTo(Outcome(consume = true))
        assertThat(up(KEYCODE_DPAD_CENTER, t0 + 10_400)).isEqualTo(Outcome(consume = true))
        assertThat(hold.isTracking).isFalse()
    }

    @Test
    fun lettingGoBeforeTenSeconds_cancels_andTheReleaseDoesNotActUnderneath() {
        down(KEYCODE_BACK, t0)
        repeats(KEYCODE_BACK, until = 4_000)
        assertThat(hold.secondsLeft).isEqualTo(6)
        assertThat(up(KEYCODE_BACK, t0 + 4_020)).isEqualTo(Outcome(consume = true, change = Change.CANCELLED))
        assertThat(hold.isTracking).isFalse()
        assertThat(hold.isShown).isFalse()
    }

    @Test
    fun aRemoteWithoutRepeats_isFollowedByTheClock_andItsLateReleaseCompletes() {
        assertThat(down(KEYCODE_BACK, t0).consume).isFalse()
        assertThat(hold.onTick(t0 + 200)).isNull()
        assertThat(hold.onTick(t0 + 450)).isEqualTo(Change.SHOWN)
        assertThat(hold.onTick(t0 + 5_000)).isEqualTo(Change.PROGRESS)
        assertThat(hold.progress).isWithin(0.001f).of(0.5f)
        assertThat(hold.onTick(t0 + 10_016)).isEqualTo(Change.COMPLETED)
        assertThat(hold.onTick(t0 + 10_032)).isNull()
        assertThat(up(KEYCODE_BACK, t0 + 11_000)).isEqualTo(Outcome(consume = true))

        // Only the press and, ten seconds later, the release (no clock in between): the release completes it.
        assertThat(down(KEYCODE_DPAD_CENTER, t0 + 20_000, downTime = t0 + 20_000).consume).isFalse()
        assertThat(up(KEYCODE_DPAD_CENTER, t0 + 30_500, downTime = t0 + 20_000)).isEqualTo(Outcome(consume = true, change = Change.COMPLETED))
        assertThat(hold.completedMillis).isEqualTo(10_500)
    }

    @Test
    fun anotherKeyDuringAVisibleHold_isIgnored_duringAPress_itEndsThePress() {
        down(KEYCODE_DPAD_CENTER, t0)
        repeats(KEYCODE_DPAD_CENTER, until = 1_000)
        assertThat(down(KEYCODE_DPAD_LEFT, t0 + 1_020, downTime = t0 + 1_020)).isEqualTo(Outcome(consume = true))
        assertThat(hold.isShown).isTrue()

        hold.reset()
        down(KEYCODE_DPAD_CENTER, t0 + 5_000, downTime = t0 + 5_000)
        assertThat(down(KEYCODE_DPAD_LEFT, t0 + 5_100, downTime = t0 + 5_100)).isEqualTo(Outcome(consume = false))
        assertThat(hold.isTracking).isFalse()
    }

    @Test
    fun aLostRelease_neverBlocksWhatComesNext() {
        // The ring shows, the release gets lost, a new press comes: the ring goes and the press is a press.
        down(KEYCODE_BACK, t0)
        repeats(KEYCODE_BACK, until = 2_000)
        assertThat(down(KEYCODE_BACK, t0 + 5_000, downTime = t0 + 5_000)).isEqualTo(Outcome(consume = false, change = Change.CANCELLED))
        assertThat(up(KEYCODE_BACK, t0 + 5_100, downTime = t0 + 5_000)).isEqualTo(Outcome(consume = false))

        // A completed hold whose release got lost: the next press (on the phrase keyboard) goes through.
        down(KEYCODE_DPAD_CENTER, t0 + 10_000, downTime = t0 + 10_000)
        hold.onTick(t0 + 20_000)
        assertThat(hold.isSwallowing).isTrue()
        assertThat(down(KEYCODE_DPAD_CENTER, t0 + 30_000, downTime = t0 + 30_000)).isEqualTo(Outcome(consume = false))
        assertThat(hold.isSwallowing).isFalse()
        assertThat(down(KEYCODE_DPAD_LEFT, t0 + 30_100, downTime = t0 + 30_100)).isEqualTo(Outcome(consume = false))
    }

    @Test
    fun aRepeatWhosePressWentElsewhere_stillCountsFromThatPress() {
        // The press reached another screen (or was before the lock); its repeats carry its down time.
        assertThat(down(KEYCODE_BACK, t0 + 3_000, repeat = 52)).isEqualTo(Outcome(consume = true, change = Change.SHOWN))
        assertThat(hold.heldMillis).isEqualTo(3_000)
    }

    @Test
    fun secondsLeft_roundUp_andNeverShowZeroBeforeTheEnd() {
        down(KEYCODE_BACK, t0)
        hold.onTick(t0 + 400)
        assertThat(hold.secondsLeft).isEqualTo(10)
        hold.onTick(t0 + 9_001)
        assertThat(hold.secondsLeft).isEqualTo(1)
        hold.onTick(t0 + 9_999)
        assertThat(hold.secondsLeft).isEqualTo(1)
    }
}
