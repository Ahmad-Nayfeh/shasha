package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Duration

class OverridesTest {

    private val today = epochDay("2026-09-28")

    /** 60 min quota, no schedule. */
    private val quotaKid = child().copy(schedule = null)

    /** 07:00–22:00, no quota. */
    private val scheduleKid = child().copy(dailyQuotaMinutes = null)

    // ---- Quota overrides ---------------------------------------------------------------------

    @Test
    fun extraTimeToday_extendsTheQuota() {
        val o = Overrides(quota = listOf(QuotaOverride(quotaKid.id, today, extraSeconds = 600)))
        val engine = engineAt("2026-09-28T12:00")
        assertThat(engine.session(quotaKid, 3600, o)).isEqualTo(SessionState.Ok(600))
        assertThat(engine.session(quotaKid, 3900, o)).isEqualTo(SessionState.Warning(300))
        assertThat(engine.session(quotaKid, 4200, o)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun severalGrantsTheSameDay_addUp() {
        val o = Overrides(
            quota = listOf(
                QuotaOverride(quotaKid.id, today, extraSeconds = 600),
                QuotaOverride(quotaKid.id, today, extraSeconds = 900),
            ),
        )
        assertThat(engineAt("2026-09-28T12:00").session(quotaKid, 3600, o)).isEqualTo(SessionState.Ok(1500))
    }

    @Test
    fun overridesOfOtherDaysOrProfiles_ignored() {
        val o = Overrides(
            quota = listOf(
                QuotaOverride(quotaKid.id, today - 1, extraSeconds = 600),
                QuotaOverride(quotaKid.id, today + 1, extraSeconds = 600),
                QuotaOverride("other-kid", today, extraSeconds = 600),
                QuotaOverride(quotaKid.id, today - 1, unlimited = true),
                QuotaOverride("other-kid", today, unlimited = true),
            ),
        )
        assertThat(engineAt("2026-09-28T12:00").session(quotaKid, 3600, o)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun unlimitedToday_liftsTheQuota() {
        val o = Overrides(quota = listOf(QuotaOverride(quotaKid.id, today, unlimited = true)))
        assertThat(engineAt("2026-09-28T12:00").session(quotaKid, 50_000, o)).isEqualTo(SessionState.Ok(null))
    }

    @Test
    fun unlimitedToday_stillRespectsTheSchedule() {
        val kid = child()
        val o = Overrides(quota = listOf(QuotaOverride(kid.id, today, unlimited = true)))
        assertThat(engineAt("2026-09-28T21:00").session(kid, 50_000, o)).isEqualTo(SessionState.Ok(3600))
        assertThat(engineAt("2026-09-28T22:30").session(kid, 50_000, o))
            .isInstanceOf(SessionState.OutsideSchedule::class.java)
    }

    @Test
    fun quotaOverride_expiresAtLocalMidnight() {
        val clock = clockAt("2026-09-28T23:59")
        val engine = PolicyEngine(clock)
        val grant = QuotaOverride(quotaKid.id, engine.today(), extraSeconds = 3600)
        val o = Overrides(quota = listOf(grant))
        assertThat(engine.session(quotaKid, 3600, o)).isEqualTo(SessionState.Ok(3600))
        clock.advance(Duration.ofMinutes(2)) // 00:01 next day
        // Even if the app still passed yesterday's usage, yesterday's grant no longer counts.
        assertThat(engine.session(quotaKid, 3600, o)).isEqualTo(SessionState.TimeUp)
        assertThat(engine.session(quotaKid, 0, o)).isEqualTo(SessionState.Ok(3600))
    }

    @Test
    fun quotaOverride_rejectsNegativeExtra() {
        assertThrows(IllegalArgumentException::class.java) { QuotaOverride("k", today, extraSeconds = -1) }
    }

    // ---- Schedule overrides ------------------------------------------------------------------

    @Test
    fun scheduleOverride_letsTheChildWatchOutsideTheWindow() {
        val o = Overrides(schedule = listOf(ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T23:30"))))
        assertThat(engineAt("2026-09-28T23:00").session(scheduleKid, 0, o)).isEqualTo(SessionState.Ok(1800))
        assertThat(engineAt("2026-09-28T23:27").session(scheduleKid, 0, o)).isEqualTo(SessionState.Warning(180))
    }

    @Test
    fun scheduleOverride_expiresAtItsTime() {
        val until = epochMsAt("2026-09-28T23:30")
        val o = Overrides(schedule = listOf(ScheduleOverride(scheduleKid.id, until)))
        val next = SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00"))
        assertThat(engineAt("2026-09-28T23:30").session(scheduleKid, 0, o)).isEqualTo(next)
        assertThat(engineAt("2026-09-28T23:45").session(scheduleKid, 0, o)).isEqualTo(next)
    }

    @Test
    fun scheduleOverride_ofOtherProfile_ignored() {
        val o = Overrides(schedule = listOf(ScheduleOverride("other-kid", epochMsAt("2026-09-28T23:30"))))
        assertThat(engineAt("2026-09-28T23:00").session(scheduleKid, 0, o))
            .isInstanceOf(SessionState.OutsideSchedule::class.java)
    }

    @Test
    fun severalScheduleOverrides_theLatestWins() {
        val o = Overrides(
            schedule = listOf(
                ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T23:10")),
                ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T23:40")),
                ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T23:20")),
                ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T22:50")), // already expired
            ),
        )
        assertThat(engineAt("2026-09-28T23:00").session(scheduleKid, 0, o)).isEqualTo(SessionState.Ok(2400))
    }

    @Test
    fun scheduleOverride_endingInsideTheWindow_runsToTheWindowEnd() {
        // 06:30 is closed; the override reaches 07:30, when the normal window is open until 22:00.
        val o = Overrides(schedule = listOf(ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T07:30"))))
        assertThat(engineAt("2026-09-28T06:30").session(scheduleKid, 0, o)).isEqualTo(SessionState.Ok(15 * 3600L + 1800))
    }

    @Test
    fun scheduleOverride_pastClosingTime_extendsTheEvening() {
        val o = Overrides(schedule = listOf(ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T22:20"))))
        assertThat(engineAt("2026-09-28T21:50").session(scheduleKid, 0, o)).isEqualTo(SessionState.Ok(1800))
    }

    @Test
    fun scheduleOverride_doesNotLiftTimeUp_andQuotaOverrideDoesNotLiftSchedule() {
        val kid = child()
        val scheduleOnly = Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-28T23:30"))))
        assertThat(engineAt("2026-09-28T23:00").session(kid, 3600, scheduleOnly)).isEqualTo(SessionState.TimeUp)
        val quotaOnly = Overrides(quota = listOf(QuotaOverride(kid.id, today, extraSeconds = 3600)))
        assertThat(engineAt("2026-09-28T23:00").session(kid, 3600, quotaOnly))
            .isInstanceOf(SessionState.OutsideSchedule::class.java)
    }

    @Test
    fun bothOverrides_theSoonerEndWins() {
        val kid = child()
        val o = Overrides(
            quota = listOf(QuotaOverride(kid.id, today, extraSeconds = 600)),
            schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-28T23:30"))),
        )
        assertThat(engineAt("2026-09-28T23:00").session(kid, 3600, o)).isEqualTo(SessionState.Ok(600))
    }

    // ---- Grants ------------------------------------------------------------------------------

    @Test
    fun grantExtraTime_beforeTheLimit_addsOnTop() {
        val engine = engineAt("2026-09-28T12:00")
        val grant = engine.grantExtraTime(quotaKid, usedTodaySeconds = 3000, overrides = Overrides.NONE, extraMinutes = 10)
        assertThat(grant).isEqualTo(QuotaOverride(quotaKid.id, today, extraSeconds = 600))
        assertThat(engine.session(quotaKid, 3000, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(1200))
    }

    @Test
    fun grantExtraTime_pastTheLimit_givesExactlyTheExtraFromNow() {
        val engine = engineAt("2026-09-28T12:00")
        val grant = engine.grantExtraTime(quotaKid, usedTodaySeconds = 3900, overrides = Overrides.NONE, extraMinutes = 10)
        assertThat(grant.extraSeconds).isEqualTo(900)
        assertThat(engine.session(quotaKid, 3900, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(600))
    }

    @Test
    fun grantExtraTime_countsEarlierGrantsOfToday_only() {
        val engine = engineAt("2026-09-28T12:00")
        val existing = Overrides(
            quota = listOf(
                QuotaOverride(quotaKid.id, today, extraSeconds = 600),
                QuotaOverride(quotaKid.id, today - 1, extraSeconds = 99_999),
                QuotaOverride("other", today, extraSeconds = 99_999),
            ),
        )
        val grant = engine.grantExtraTime(quotaKid, usedTodaySeconds = 4300, overrides = existing, extraMinutes = 10)
        assertThat(grant.extraSeconds).isEqualTo(700)
        assertThat(engine.session(quotaKid, 4300, existing + grant)).isEqualTo(SessionState.Ok(600))
    }

    @Test
    fun grantExtraTime_withoutQuota_isJustTheMinutes() {
        val engine = engineAt("2026-09-28T12:00")
        val grant = engine.grantExtraTime(adult(), usedTodaySeconds = 50_000, overrides = Overrides.NONE, extraMinutes = 5)
        assertThat(grant).isEqualTo(QuotaOverride("adult", today, extraSeconds = 300))
    }

    @Test
    fun grantExtraTime_needsPositiveMinutes() {
        val engine = engineAt("2026-09-28T12:00")
        assertThrows(IllegalArgumentException::class.java) { engine.grantExtraTime(quotaKid, 0, Overrides.NONE, 0) }
        assertThrows(IllegalArgumentException::class.java) { engine.grantExtraTime(quotaKid, 0, Overrides.NONE, -5) }
    }

    @Test
    fun grantUnlimitedToday() {
        val engine = engineAt("2026-09-28T12:00")
        assertThat(engine.grantUnlimitedToday(quotaKid)).isEqualTo(QuotaOverride(quotaKid.id, today, unlimited = true))
    }

    @Test
    fun grantScheduleOverride_forMinutes() {
        val engine = engineAt("2026-09-28T22:30")
        val grant = engine.grantScheduleOverride(scheduleKid, 30)
        assertThat(grant).isEqualTo(ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-28T23:00")))
        assertThat(engine.session(scheduleKid, 0, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(1800))
        assertThrows(IllegalArgumentException::class.java) { engine.grantScheduleOverride(scheduleKid, 0) }
    }

    @Test
    fun grantScheduleOverrideUntilEndOfDay() {
        val engine = engineAt("2026-09-28T22:30")
        val grant = engine.grantScheduleOverrideUntilEndOfDay(scheduleKid)
        assertThat(grant).isEqualTo(ScheduleOverride(scheduleKid.id, epochMsAt("2026-09-29T00:00")))
        assertThat(engine.session(scheduleKid, 0, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(5400))
    }

    // ---- Storage helpers ---------------------------------------------------------------------

    @Test
    fun pruned_dropsOnlyWhatCanNoLongerApply() {
        val now = epochMsAt("2026-09-28T12:00")
        val keepQuota = QuotaOverride("k", today, extraSeconds = 60)
        val keepSchedule = ScheduleOverride("k", now + 1)
        val o = Overrides(
            quota = listOf(QuotaOverride("k", today - 1, extraSeconds = 60), keepQuota),
            schedule = listOf(ScheduleOverride("k", now), ScheduleOverride("k", now - 1), keepSchedule),
        )
        assertThat(o.pruned(today, now)).isEqualTo(Overrides(listOf(keepQuota), listOf(keepSchedule)))
    }

    @Test
    fun plusOperators_append() {
        val q = QuotaOverride("k", today, extraSeconds = 60)
        val s = ScheduleOverride("k", 1)
        assertThat(Overrides.NONE + q + s).isEqualTo(Overrides(listOf(q), listOf(s)))
    }
}
