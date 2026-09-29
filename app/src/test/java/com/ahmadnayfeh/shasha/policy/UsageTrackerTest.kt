package com.ahmadnayfeh.shasha.policy

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.policy.FakeUsageLedger.PlaybackRow
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UsageTrackerTest {
    private val child = kid()
    private val adult = owner()
    private val day = 20_000L

    private class Harness(val scope: TestScope, profile: Profile?) {
        val usage = FakeUsageLedger()
        val profile = MutableStateFlow(profile)
        val counts = MutableStateFlow(true)
        val foreground = MutableStateFlow(false)
        var day = 20_000L

        /** The monotonic clock: virtual time, plus whatever a test adds (a device that slept). */
        var slept = 0L
        val tracker = UsageTracker(usage, { day }, this.profile, counts, scope.backgroundScope, elapsedMs = { scope.testScheduler.currentTime + slept })

        init {
            tracker.start(foreground)
            scope.runCurrent()
        }

        fun seconds(profileId: String, onDay: Long = day) = usage.foreground[profileId to onDay] ?: 0L

        fun pass(ms: Long) {
            scope.advanceTimeBy(ms)
            scope.runCurrent()
        }
    }

    @Test
    fun countsOnlyInTheForeground_everyTick_andTheRestWhenLeaving() = runTest {
        val h = Harness(this, child)
        h.pass(60_000)
        assertThat(h.seconds(child.id)).isEqualTo(0)

        h.foreground.value = true
        runCurrent()
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(15)
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(30)

        // What a crash would lose right now: the 14 s since the last write, never more than a tick.
        h.pass(14_000)
        assertThat(h.seconds(child.id)).isEqualTo(30)

        h.foreground.value = false // Home, standby, another app
        runCurrent()
        assertThat(h.seconds(child.id)).isEqualTo(44)
        h.pass(120_000)
        assertThat(h.seconds(child.id)).isEqualTo(44)
    }

    @Test
    fun timeOnTheTimeUpScreen_isNotCounted() = runTest {
        val h = Harness(this, child)
        h.foreground.value = true
        runCurrent()
        h.pass(20_000)
        assertThat(h.seconds(child.id)).isEqualTo(15)

        h.counts.value = false // TimeUp or OutsideSchedule: the lock screen is on
        runCurrent()
        assertThat(h.seconds(child.id)).isEqualTo(20)
        h.pass(300_000)
        assertThat(h.seconds(child.id)).isEqualTo(20)

        h.counts.value = true // the parent unlocked
        runCurrent()
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(35)
    }

    @Test
    fun switchingProfile_givesEachProfileItsOwnTime() = runTest {
        val h = Harness(this, adult)
        h.foreground.value = true
        runCurrent()
        h.pass(10_000)
        h.profile.value = child
        runCurrent()
        assertThat(h.seconds(adult.id)).isEqualTo(10)

        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(15)
        // An edit of the current profile is not a switch: the tick goes on.
        h.profile.value = child.copy(dailyQuotaMinutes = 90)
        runCurrent()
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(30)
        assertThat(h.seconds(adult.id)).isEqualTo(10)
    }

    @Test
    fun noProfileYet_countsNothing() = runTest {
        val h = Harness(this, profile = null)
        h.foreground.value = true
        runCurrent()
        h.pass(60_000)
        assertThat(h.usage.foreground).isEmpty()
    }

    @Test
    fun theTickLandsOnTheDayItIsWritten_andSubSecondsCarryOver() = runTest {
        val h = Harness(this, child)
        h.foreground.value = true
        runCurrent()
        h.pass(15_000)
        h.day = day + 1 // midnight passed
        h.pass(15_000)
        assertThat(h.seconds(child.id, day)).isEqualTo(15)
        assertThat(h.seconds(child.id, day + 1)).isEqualTo(15)

        h.pass(700)
        h.foreground.value = false
        runCurrent()
        h.foreground.value = true
        runCurrent()
        h.pass(300)
        h.foreground.value = false
        runCurrent()
        assertThat(h.seconds(child.id, day + 1)).isEqualTo(16) // 0.7 s + 0.3 s
    }

    @Test
    fun aDeviceThatSlept_doesNotCountItsSleep() = runTest {
        val h = Harness(this, child)
        h.foreground.value = true
        runCurrent()
        h.slept = 3 * 3_600_000L // elapsedRealtime jumped: the coroutine did not run meanwhile
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isAtMost(2 * UsageTracker.TICK_MS / 1000)
    }

    @Test
    fun aFailingWrite_doesNotStopTheTracking() = runTest {
        val h = Harness(this, child)
        h.foreground.value = true
        runCurrent()
        h.usage.failWrites = true
        h.pass(15_000)
        h.usage.failWrites = false
        h.pass(15_000)
        assertThat(h.seconds(child.id)).isEqualTo(15)
    }

    @Test
    fun playback_isRecordedForTheCurrentProfile_withItsChannel() = runTest {
        val h = Harness(this, child)
        h.tracker.recordPlayback(ALLOWED_CHANNEL, "قناة", 12)
        h.tracker.recordPlayback(null, null, 3)
        h.tracker.recordPlayback(ALLOWED_CHANNEL, "قناة", 0) // nothing
        runCurrent()
        assertThat(h.usage.playback).containsExactly(
            PlaybackRow(child.id, day, 12, ALLOWED_CHANNEL, "قناة"),
            PlaybackRow(child.id, day, 3, null, null),
        ).inOrder()

        h.profile.value = null
        h.tracker.recordPlayback(ALLOWED_CHANNEL, "قناة", 5)
        runCurrent()
        assertThat(h.usage.playback).hasSize(2)
    }

    /** Stands in for the process lifecycle, driven by hand. */
    private class TestLifecycle : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun aPanelOverTheApp_onlyPausesIt_andTheTimeStillCounts() = runTest {
        val app = TestLifecycle()
        val usage = FakeUsageLedger()
        val tracker = UsageTracker(usage, { day }, MutableStateFlow(child), MutableStateFlow(true), backgroundScope, elapsedMs = { testScheduler.currentTime })
        tracker.start(spendingTime(app.lifecycle, MutableStateFlow(true)))
        app.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertThat(usage.foreground[child.id to day]).isEqualTo(15)

        // The TV's settings panel (the remote's settings key) or the notifications over the app: it is paused,
        // not stopped, and the video plays on behind the panel. The quota keeps counting.
        app.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(usage.foreground[child.id to day]).isEqualTo(45)

        // HOME, another app, standby: stopped, nothing counts.
        app.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()
        assertThat(usage.foreground[child.id to day]).isEqualTo(45)
    }

    @Test
    fun thePickerAndTheSetup_spendNobodysTime() = runTest {
        val app = TestLifecycle()
        val usage = FakeUsageLedger()
        val shown = MutableStateFlow(false) // the picker is on screen; the TV was last left in this child's profile
        val tracker = UsageTracker(usage, { day }, MutableStateFlow(child), MutableStateFlow(true), backgroundScope, elapsedMs = { testScheduler.currentTime })
        tracker.start(spendingTime(app.lifecycle, shown))
        app.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertThat(usage.foreground).isEmpty()

        shown.value = true // the child chose their profile
        runCurrent()
        advanceTimeBy(15_000)
        runCurrent()
        assertThat(usage.foreground[child.id to day]).isEqualTo(15)
        shown.value = false // «خروج»: the picker again
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(usage.foreground[child.id to day]).isEqualTo(15)
    }

    @Test
    fun playbackMeter_measuresOnlyPlayingTime() {
        var now = 0L
        val reported = mutableListOf<Long>()
        val meter = PlaybackMeter({ now }) { reported += it }

        meter.flush()
        meter.setPlaying(true)
        now = 4_600
        meter.flush() // 4.6 s: 4 reported, 0.6 carried
        now = 6_000
        meter.setPlaying(false) // paused: +1.4 s pending
        now = 100_000
        meter.flush() // 2.0 s
        meter.setPlaying(true)
        meter.setPlaying(true) // no double start
        now = 115_500
        meter.flush()
        assertThat(reported).containsExactly(4L, 2L, 15L).inOrder()
    }
}
