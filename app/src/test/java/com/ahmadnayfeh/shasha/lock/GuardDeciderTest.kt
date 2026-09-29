package com.ahmadnayfeh.shasha.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SPEC §4's guard, its decisions alone: another app in front while something is enforced is covered and
 * Shasha brought back, again until it is back, and the return is measured; nothing happens otherwise.
 */
class GuardDeciderTest {
    private val timing = GuardTiming()
    private val decider = GuardDecider(OWN, timing)

    private fun input(
        nowMs: Long,
        front: FrontApp? = null,
        enforced: Boolean = true,
        resumed: Boolean = false,
        leftAtMs: Long? = LEFT_AT,
        screenOn: Boolean = true,
    ) = GuardInput(nowMs, enforced, enforcedSinceMs = 0, appResumed = resumed, appLeftAtMs = leftAtMs, screenOn = screenOn, front = front)

    @Test
    fun nothingEnforced_orShashaInFront_orTheScreenOff_meansNothingToDo() {
        val netflix = FrontApp("com.netflix.ninja", null, LEFT_AT + 300)
        for (idle in listOf(input(LEFT_AT + 500, netflix, enforced = false), input(LEFT_AT + 500, netflix, resumed = true), input(LEFT_AT + 500, netflix, screenOn = false))) {
            val step = decider.step(idle)
            assertThat(step.showOverlay).isFalse()
            assertThat(step.launchApp).isFalse()
            assertThat(step.hideOverlay).isTrue()
            assertThat(step.nextCheckMs).isNull()
        }
    }

    @Test
    fun homeDuringALock_coversAndBringsShashaBack_untilItIsBack_andMeasuresIt() {
        // Home at LEFT_AT: the launcher's event arrives 300 ms later, the guard sees it at +450.
        val launcher = FrontApp("com.google.android.apps.tv.launcherx", "MainActivity", LEFT_AT + 300)
        val first = decider.step(input(LEFT_AT + 450, launcher))
        assertThat(first.showOverlay).isTrue()
        assertThat(first.launchApp).isTrue()
        decider.onCovered(LEFT_AT + 520)

        // Not back yet, before the re-arm: wait. After it: raise again.
        assertThat(decider.step(input(LEFT_AT + 1_450, launcher)).launchApp).isFalse()
        val again = decider.step(input(LEFT_AT + 450 + timing.rearmMs, launcher))
        assertThat(again.launchApp).isTrue()
        assertThat(again.showOverlay).isTrue()

        // Back: uncovered, and the numbers SPEC asks for.
        val back = decider.step(input(LEFT_AT + 3_100, launcher, resumed = true))
        assertThat(back.hideOverlay).isTrue()
        assertThat(back.returned).isEqualTo(GuardReturn("com.google.android.apps.tv.launcherx", detectMs = 450, coverMs = 520, backMs = 3_100))
        assertThat(decider.returning).isFalse()
    }

    @Test
    fun aReturnThatNeverComes_stopsCoveringAfterTenSeconds_butKeepsTrying() {
        val app = FrontApp("com.netflix.ninja", null, LEFT_AT + 100)
        var now = LEFT_AT + 200
        assertThat(decider.step(input(now, app)).showOverlay).isTrue()
        var covered = true
        while (now < LEFT_AT + 30_000) {
            now += timing.rearmMs
            val step = decider.step(input(now, app))
            assertThat(step.launchApp).isTrue()
            covered = step.showOverlay
        }
        // The TV stays usable when Shasha cannot come back: the cover is no longer renewed.
        assertThat(covered).isFalse()
    }

    @Test
    fun ownOrSystemScreensInFront_areNotAnotherApp() {
        // Shasha's own resume after its pause (it is on its way back), the chooser, the screensaver.
        val own = FrontApp(OWN, "com.ahmadnayfeh.shasha.MainActivity", LEFT_AT + 100)
        val dream = FrontApp("com.google.android.backdrop", GuardDecider.DREAM_ACTIVITY, LEFT_AT + 200)
        val chooser = FrontApp("android", "com.android.internal.app.ResolverActivity", LEFT_AT + 200)
        for (front in listOf(own, dream, chooser)) {
            val step = decider.step(input(LEFT_AT + 60_000, front))
            assertThat(step.launchApp).isFalse()
            assertThat(step.nextCheckMs).isEqualTo(timing.pollMs)
        }
    }

    @Test
    fun shashasOwnEntryFromBeforeItsPause_isLateOnlyForAMoment() {
        // Home was just pressed: the newest event is still Shasha's own resume, from before its pause.
        val stale = FrontApp(OWN, "com.ahmadnayfeh.shasha.MainActivity", LEFT_AT - 60_000)
        val soon = decider.step(input(LEFT_AT + 200, stale))
        assertThat(soon.launchApp).isFalse()
        assertThat(soon.nextCheckMs).isEqualTo(timing.fastPollMs)
        // No event came at all (an HDMI input, a panel): something else is in front all the same.
        val later = decider.step(input(LEFT_AT + timing.fastWindowMs, stale))
        assertThat(later.launchApp).isTrue()
        assertThat(later.showOverlay).isTrue()
    }

    @Test
    fun anUnknownFront_whileShashaIsNotInFront_isAnotherApp() {
        // After a reboot, before any usage event: the guard does not wait for one.
        val step = decider.step(input(LEFT_AT + 100, front = null))
        assertThat(step.launchApp).isTrue()
    }

    private companion object {
        const val OWN = "com.ahmadnayfeh.shasha"
        const val LEFT_AT = 1_000_000L
    }
}
