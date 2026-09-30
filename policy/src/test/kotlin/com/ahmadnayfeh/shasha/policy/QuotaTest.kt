package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QuotaTest {

    private val engine = engineAt("2026-09-28T12:00")

    /** Child with a 60 minute quota and no schedule, so only the quota matters. */
    private val kid = child().copy(schedule = null)

    @Test
    fun adultByDefault_unlimited() {
        assertThat(engine.session(adult(), usedTodaySeconds = 100_000)).isEqualTo(SessionState.Ok(null))
    }

    @Test
    fun remainingSecondsCountDown() {
        assertThat(engine.session(kid, 0)).isEqualTo(SessionState.Ok(3600))
        assertThat(engine.session(kid, 1800)).isEqualTo(SessionState.Ok(1800))
    }

    @Test
    fun warningInTheLastFiveMinutes() {
        assertThat(engine.session(kid, 3299)).isEqualTo(SessionState.Ok(301))
        assertThat(engine.session(kid, 3300)).isEqualTo(SessionState.Warning(300))
        assertThat(engine.session(kid, 3599)).isEqualTo(SessionState.Warning(1))
        assertThat(PolicyEngine.WARNING_SECONDS).isEqualTo(300)
    }

    @Test
    fun timeUpWhenQuotaReachedOrPassed() {
        assertThat(engine.session(kid, 3600)).isEqualTo(SessionState.TimeUp)
        assertThat(engine.session(kid, 9000)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun zeroQuota_isImmediatelyTimeUp() {
        assertThat(engine.session(kid.copy(dailyQuotaMinutes = 0), 0)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun negativeUsage_countsAsZero() {
        assertThat(engine.session(kid, -500)).isEqualTo(SessionState.Ok(3600))
    }

    @Test
    fun quotaAppliesToAdultsToo() {
        val me = adult().copy(dailyQuotaMinutes = 120)
        assertThat(engine.session(me, 3600)).isEqualTo(SessionState.Ok(3600))
        assertThat(engine.session(me, 7200)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun singleOverrideOverload_keepsOriginalContract() {
        val today = engine.today()
        assertThat(engine.session(kid, 3600, override = null)).isEqualTo(SessionState.TimeUp)
        assertThat(engine.session(kid, 3600, QuotaOverride(kid.id, today, extraSeconds = 900)))
            .isEqualTo(SessionState.Ok(900))
        assertThat(engine.session(kid, 3600, QuotaOverride(kid.id, today, unlimited = true)))
            .isEqualTo(SessionState.Ok(null))
    }

    @Test
    fun todayFollowsClockZone() {
        // 2026-09-28 22:00 UTC is already 2026-09-29 in Riyadh.
        val instantClock = clockAt("2026-09-28T22:00", UTC)
        assertThat(PolicyEngine(instantClock).today()).isEqualTo(epochDay("2026-09-28"))
        assertThat(PolicyEngine(instantClock.withZone(RIYADH)).today()).isEqualTo(epochDay("2026-09-29"))
        assertThat(engine.nowEpochMs()).isEqualTo(epochMsAt("2026-09-28T12:00"))
    }

    @Test
    fun usageCalculatorFeedsTheQuota() {
        val today = engine.today()
        val records = listOf(
            UsageRecord(kid.id, today, UsageKind.FOREGROUND, 1200),
            UsageRecord(kid.id, today, UsageKind.FOREGROUND, 1500),
            UsageRecord(kid.id, today, UsageKind.PLAYBACK, 2500, "UC1", "قناة"),
            UsageRecord(kid.id, today - 1, UsageKind.FOREGROUND, 3000),
            UsageRecord("someone-else", today, UsageKind.FOREGROUND, 3000),
        )
        val used = UsageStatsCalculator(RIYADH).foregroundSeconds(kid.id, today, records)
        assertThat(used).isEqualTo(2700)
        assertThat(engine.session(kid, used)).isEqualTo(SessionState.Ok(900))
    }
}
