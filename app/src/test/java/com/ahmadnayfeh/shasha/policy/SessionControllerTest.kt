package com.ahmadnayfeh.shasha.policy

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SessionControllerTest {
    private val child = kid() // 60 min, 07:00–22:00

    private class Harness(scope: TestScope, start: String, profile: Profile?) {
        val clock = SchedulerClock(scope.testScheduler, instantAt(start))
        val engine = PolicyEngine(clock)
        val profile = MutableStateFlow(profile)
        val usage = FakeUsageLedger()
        val overrides = FakeOverridesStore()
        val newDays = mutableListOf<Long>()
        val controller = SessionController(engine, this.profile, usage, overrides, scope.backgroundScope, onNewDay = { newDays += it })
        val warnings = mutableListOf<SessionState.Warning>()

        init {
            scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { controller.warningEvents.collect { warnings += it } }
            controller.start()
            scope.runCurrent()
        }

        val today: Long get() = engine.today()
        val state: SessionState get() = controller.state.value
    }

    @Test
    fun okThenWarningOnceThenTimeUp_andTheParentsUnlock() = runTest {
        val h = Harness(this, "2026-09-28T12:00", child)
        assertThat(h.controller.isReady.value).isTrue()
        assertThat(h.state).isEqualTo(SessionState.Ok(3600))

        h.usage.set(child.id, h.today, 3600 - 400)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Ok(400))
        assertThat(h.warnings).isEmpty()

        h.usage.set(child.id, h.today, 3600 - 300)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Warning(300))
        assertThat(h.warnings).containsExactly(SessionState.Warning(300))

        // Still in the warning: recomputed (usage, ticks) but not announced again.
        h.usage.set(child.id, h.today, 3600 - 120)
        advanceTimeBy(SessionController.TICK_MS)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Warning(120))
        assertThat(h.warnings).hasSize(1)

        h.usage.set(child.id, h.today, 3600)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.TimeUp)
        assertThat(h.state.countsTowardQuota).isFalse()

        assertThat(h.controller.grantUnlock(10)).isEqualTo(SessionState.Ok(600))
        assertThat(h.state).isEqualTo(SessionState.Ok(600))

        // Those ten minutes run out too: a new warning, a new "time up".
        h.usage.set(child.id, h.today, 3600 + 400)
        runCurrent()
        assertThat(h.warnings).containsExactly(SessionState.Warning(300), SessionState.Warning(200)).inOrder()
        h.usage.set(child.id, h.today, 3600 + 600)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.TimeUp)

        // "The rest of today": no quota, the bedtime at 22:00 stays.
        val now = h.clock.instant()
        val untilBedtime = (instantAt("2026-09-28T22:00").toEpochMilli() - now.toEpochMilli() + 999) / 1000
        assertThat(h.controller.unlimitedToday()).isEqualTo(SessionState.Ok(untilBedtime))
    }

    @Test
    fun aGrantFromThePhone_isSeenAtOnce() = runTest {
        val h = Harness(this, "2026-09-28T12:00", child)
        h.usage.set(child.id, h.today, 3600)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.TimeUp)

        // Stored by another path (the phone's parent section, another controller): the store's flow triggers it.
        h.overrides.add(Overrides(quota = listOf(QuotaOverride(child.id, h.today, extraSeconds = 900))), h.today, h.engine.nowEpochMs())
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Ok(900))
    }

    @Test
    fun switchingProfile_computesWithTheNewProfilesOwnUsage() = runTest {
        val adult = owner()
        val h = Harness(this, "2026-09-28T12:00", adult)
        h.usage.set(adult.id, h.today, 5 * 3600)
        h.usage.set(child.id, h.today, 3600 - 60)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Ok(null))

        h.profile.value = child
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Warning(60))
        assertThat(h.warnings).containsExactly(SessionState.Warning(60))

        h.profile.value = adult
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Ok(null))
        // Back into the child's last minute: announced again.
        h.profile.value = child
        runCurrent()
        assertThat(h.warnings).hasSize(2)
    }

    @Test
    fun anEditOfTheCurrentProfile_appliesAtOnce() = runTest {
        val h = Harness(this, "2026-09-28T12:00", child)
        h.usage.set(child.id, h.today, 1800)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.Ok(1800))

        h.profile.value = child.copy(dailyQuotaMinutes = 30) // the parent lowered the quota from the phone
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun bedtime_arrivesOnTime_notAtTheNextTick() = runTest {
        val h = Harness(this, "2026-09-28T21:59:50", child)
        assertThat(h.state).isEqualTo(SessionState.Warning(10))

        advanceTimeBy(10_000)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
        assertThat(h.state.countsTowardQuota).isFalse()
    }

    @Test
    fun midnight_startsANewDayWithTheFullQuota() = runTest {
        val noSchedule = child.copy(schedule = null)
        val h = Harness(this, "2026-09-28T23:59:50", noSchedule)
        h.usage.set(child.id, h.today, 3600)
        runCurrent()
        assertThat(h.state).isEqualTo(SessionState.TimeUp)
        val firstDay = h.today

        advanceTimeBy(SessionController.TICK_MS)
        runCurrent()
        assertThat(h.today).isEqualTo(firstDay + 1)
        assertThat(h.state).isEqualTo(SessionState.Ok(3600))
        assertThat(h.newDays).containsExactly(firstDay, firstDay + 1).inOrder()
    }

    @Test
    fun beforeTheProfileIsLoaded_theStateIsAStandIn_andCurrentWaits() = runTest {
        val h = Harness(this, "2026-09-28T12:00", profile = null)
        assertThat(h.controller.isReady.value).isFalse()
        assertThat(h.controller.refresh()).isNull()

        val decided = backgroundScope.async { h.controller.current() }
        runCurrent()
        assertThat(decided.isCompleted).isFalse()
        h.usage.set(child.id, h.today, 3600)
        h.profile.value = child
        runCurrent()
        assertThat(decided.await()).isEqualTo(SessionState.TimeUp)
        assertThat(h.controller.isReady.value).isTrue()
    }
}
