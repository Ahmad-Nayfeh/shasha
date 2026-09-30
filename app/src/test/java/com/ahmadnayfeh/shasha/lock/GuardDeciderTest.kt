package com.ahmadnayfeh.shasha.lock

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * SPEC §4's guard, its decisions alone: another app in front while something is enforced is covered and
 * Shasha brought back, again until it is back, and the return is measured; nothing happens otherwise (the
 * screen off or the screensaver shown included), and a return is never measured from before the TV's
 * standby or its screensaver.
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
        dreaming: Boolean = false,
        activeSinceMs: Long? = null,
    ) = GuardInput(
        nowMs,
        enforced,
        enforcedSinceMs = 0,
        appResumed = resumed,
        appLeftAtMs = leftAtMs,
        screenOn = screenOn,
        front = front,
        dreaming = dreaming,
        activeSinceMs = activeSinceMs,
    )

    @Test
    fun nothingEnforced_orShashaInFront_orTheScreenOff_orTheScreensaver_meansNothingToDo() {
        val netflix = FrontApp(NETFLIX, null, LEFT_AT + 300)
        val idles = listOf(
            input(LEFT_AT + 500, netflix, enforced = false),
            input(LEFT_AT + 500, netflix, resumed = true),
            input(LEFT_AT + 500, netflix, screenOn = false),
            input(LEFT_AT + 500, netflix, dreaming = true),
        )
        for (idle in idles) {
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
        val app = FrontApp(NETFLIX, null, LEFT_AT + 100)
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
        // Back: from an app nobody saw, not "from Shasha".
        val back = decider.step(input(LEFT_AT + 3_600, stale, resumed = true))
        assertThat(back.returned).isEqualTo(GuardReturn("", detectMs = timing.fastWindowMs, coverMs = null, backMs = 3_600))
    }

    @Test
    fun noUsageEventAtAll_isAnotherApp_afterAMoment() {
        // The screensaver's (or the launcher's) event may be on its way: a moment's wait, as for a stale own entry.
        val soon = decider.step(input(LEFT_AT + 100, front = null))
        assertThat(soon.launchApp).isFalse()
        assertThat(soon.nextCheckMs).isEqualTo(timing.fastPollMs)
        val later = decider.step(input(LEFT_AT + timing.fastWindowMs, front = null))
        assertThat(later.launchApp).isTrue()
        assertThat(decider.step(input(LEFT_AT + 3_500, resumed = true)).returned?.fromPackage).isEmpty()
    }

    @Test
    fun wakingTheTvIntoShashaItself_isNotAReturn() {
        // Shasha was in front when the TV went to standby at LEFT_AT; 8 hours later the screen comes on (the guard
        // hears it first) and Shasha's activity resumes a moment later. Its newest usage event is still its own,
        // from before the standby — or nothing at all.
        val wake = LEFT_AT + EIGHT_HOURS
        val stale = FrontApp(OWN, "com.ahmadnayfeh.shasha.MainActivity", LEFT_AT - 60_000)
        for (front in listOf(stale, null)) {
            val step = decider.step(input(wake + 150, front, activeSinceMs = wake))
            assertThat(step.launchApp).isFalse()
            assertThat(step.showOverlay).isFalse()
            assertThat(step.nextCheckMs).isEqualTo(timing.fastPollMs)
            assertThat(decider.step(input(wake + 900, front, resumed = true, activeSinceMs = wake)).returned).isNull()
        }
    }

    @Test
    fun wakingTheTvIntoTheLauncher_isMeasuredFromTheWake_notFromTheStandby() {
        val wake = LEFT_AT + EIGHT_HOURS
        val launcher = FrontApp(LAUNCHER, "MainActivity", wake + 400)
        assertThat(decider.step(input(wake + 600, launcher, activeSinceMs = wake)).launchApp).isTrue()
        val back = decider.step(input(wake + 1_900, launcher, resumed = true, activeSinceMs = wake))
        assertThat(back.returned).isEqualTo(GuardReturn(LAUNCHER, detectMs = 600, coverMs = null, backMs = 1_900))
    }

    @Test
    fun theScreensaver_endsAReturn_andWhatComesAfterItIsMeasuredFromItsEnd() {
        val netflix = FrontApp(NETFLIX, null, LEFT_AT + 300)
        assertThat(decider.step(input(LEFT_AT + 450, netflix)).launchApp).isTrue()
        // The screensaver starts (its broadcast): nothing can come in front of it, and the cover would hide it.
        val dream = decider.step(input(LEFT_AT + 1_000, netflix, dreaming = true))
        assertThat(dream.hideOverlay).isTrue()
        assertThat(dream.launchApp).isFalse()
        assertThat(dream.nextCheckMs).isNull()
        assertThat(decider.returning).isFalse()
        // An hour later a key ends it (its broadcast) and Netflix, below it, resumes.
        val end = LEFT_AT + ONE_HOUR
        val again = FrontApp(NETFLIX, null, end + 150)
        assertThat(decider.step(input(end + 350, again, activeSinceMs = end)).launchApp).isTrue()
        val back = decider.step(input(end + 1_600, again, resumed = true, activeSinceMs = end))
        assertThat(back.returned?.backMs).isEqualTo(1_600L)
    }

    @Test
    fun theScreensaverInFront_withoutItsBroadcast_endsAReturnToo() {
        val netflix = FrontApp(NETFLIX, null, LEFT_AT + 300)
        assertThat(decider.step(input(LEFT_AT + 450, netflix)).launchApp).isTrue()
        // UsageStats shows the screensaver's activity in front, newer than the return's start: drop the return.
        val dream = FrontApp(DREAM_PACKAGE, GuardDecider.DREAM_ACTIVITY, LEFT_AT + 2_000)
        val dropped = decider.step(input(LEFT_AT + 2_200, dream))
        assertThat(dropped.hideOverlay).isTrue()
        assertThat(dropped.launchApp).isFalse()
        assertThat(decider.returning).isFalse()
        // No Shasha launches while it shows, however long.
        for (now in LEFT_AT + 5_000..LEFT_AT + ONE_HOUR step 60_000) {
            assertThat(decider.step(input(now, dream)).launchApp).isFalse()
        }
    }

    @Test
    fun anAppAfterTheScreensaver_isMeasuredFromItsOwnStart() {
        // The screensaver started over Shasha (Shasha paused at LEFT_AT, the screensaver's activity came 200 ms
        // later; its broadcast was missed). An hour later Home ends it and the launcher comes.
        val dream = FrontApp(DREAM_PACKAGE, GuardDecider.DREAM_ACTIVITY, LEFT_AT + 200)
        assertThat(decider.step(input(LEFT_AT + 300, dream)).launchApp).isFalse()
        val home = LEFT_AT + ONE_HOUR
        val launcher = FrontApp(LAUNCHER, "MainActivity", home)
        assertThat(decider.step(input(home + 250, launcher)).launchApp).isTrue()
        val back = decider.step(input(home + 1_400, launcher, resumed = true))
        assertThat(back.returned).isEqualTo(GuardReturn(LAUNCHER, detectMs = 250, coverMs = null, backMs = 1_400))
    }

    private companion object {
        const val OWN = "com.ahmadnayfeh.shasha"
        const val NETFLIX = "com.netflix.ninja"
        const val LAUNCHER = "com.google.android.apps.tv.launcherx"
        const val DREAM_PACKAGE = "com.google.android.backdrop"
        const val LEFT_AT = 1_000_000L
        const val ONE_HOUR = 3_600_000L
        const val EIGHT_HOURS = 8 * ONE_HOUR
    }
}
