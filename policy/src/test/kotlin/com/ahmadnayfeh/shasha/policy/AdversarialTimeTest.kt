package com.ahmadnayfeh.shasha.policy

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/**
 * Adversarial tests for the time rules: quota, schedule (midnight wrap, equal bounds, exact
 * boundaries, day change while watching, zones, DST), override expiry, the 5 minute warning,
 * the guard and the PIN lockout.
 *
 * Most checks hold the engine to its own word: whatever [PolicyEngine.session] promises
 * (seconds remaining, next opening) must be what it decides when that moment comes.
 */
class AdversarialTimeTest {

    // ---- Helpers -----------------------------------------------------------------------------

    private fun sessionAt(
        zone: ZoneId,
        at: Instant,
        profile: Profile,
        used: Long = 0,
        overrides: Overrides = Overrides.NONE,
    ): SessionState = PolicyEngine(MutableClock(at, zone)).session(profile, used, overrides)

    private fun remainingOf(s: SessionState): Long? = when (s) {
        is SessionState.Ok -> s.remainingSeconds
        is SessionState.Warning -> s.remainingSeconds
        else -> null
    }

    private fun local(dateTime: String, zone: ZoneId): Instant = LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    /** Second occurrence of a wall time that happens twice (DST fall-back). */
    private fun localLater(dateTime: String, zone: ZoneId): Instant =
        LocalDateTime.parse(dateTime).atZone(zone).withLaterOffsetAtOverlap().toInstant()

    /** A child limited only by [schedule] (no quota). */
    private fun scheduleOnly(schedule: Schedule?) = child().copy(dailyQuotaMinutes = null, schedule = schedule)

    /**
     * For a profile without quota, compares what the engine says at [t] with its later answers:
     * - Ok/Warning(r): open during [t, t + r), closed at t + r, Warning exactly when r ≤ 300;
     * - Ok(null): open for the next day;
     * - OutsideSchedule(n): closed during [t, n), open at n.
     */
    private fun scheduleViolations(
        zone: ZoneId,
        t: Instant,
        profile: Profile,
        overrides: Overrides = Overrides.NONE,
    ): List<String> {
        val s = sessionAt(zone, t, profile, 0, overrides)
        fun open(i: Instant) = sessionAt(zone, i, profile, 0, overrides).canWatch
        fun at(i: Instant) = i.atZone(zone).toString()
        val where = "${profile.schedule} ${overrides.schedule} at ${at(t)} said $s"
        val v = mutableListOf<String>()
        when (s) {
            is SessionState.Ok, is SessionState.Warning -> {
                val r = remainingOf(s)
                if (s is SessionState.Warning && s.remainingSeconds !in 1L..PolicyEngine.WARNING_SECONDS) {
                    v += "$where: warning out of range"
                }
                if (s is SessionState.Ok && r != null && r <= PolicyEngine.WARNING_SECONDS) v += "$where: should warn"
                if (r == null) {
                    for (h in listOf(1L, 6, 13, 25)) {
                        val p = t.plusSeconds(h * 3600)
                        if (!open(p)) v += "$where: but closed at ${at(p)}"
                    }
                } else if (r < 1) {
                    v += "$where: remaining must be positive"
                } else {
                    // (r - 1) s is surely before the real end, which is rounded up to r.
                    val probes = (1..7).map { t.plusMillis((r - 1) * 1000 * it / 8) } + t.plusSeconds(r - 1)
                    probes.firstOrNull { !open(it) }?.let { v += "$where: but already closed at ${at(it)}" }
                    val end = t.plusSeconds(r)
                    if (open(end)) v += "$where: but still open at ${at(end)}"
                }
            }
            is SessionState.OutsideSchedule -> {
                val n = Instant.ofEpochMilli(s.nextAllowedAtEpochMs)
                if (!n.isAfter(t)) {
                    v += "$where: next opening is not in the future"
                } else {
                    val span = Duration.between(t, n).toMillis()
                    val probes = (1..7).map { t.plusMillis(span * it / 8) } + n.minusMillis(1)
                    probes.firstOrNull { open(it) }?.let { v += "$where: but already open at ${at(it)}" }
                    if (!open(n)) v += "$where: but still closed at ${at(n)}"
                }
            }
            SessionState.TimeUp -> v += "$where: TimeUp without a quota"
        }
        return v
    }

    /**
     * Quota and schedule together. The child keeps watching from [t] (usage restarts at local
     * midnight). Only valid for [used] below the limit.
     */
    private fun quotaViolations(
        zone: ZoneId,
        t: Instant,
        profile: Profile,
        used: Long,
        overrides: Overrides = Overrides.NONE,
        checkAfterMidnight: Boolean = true,
    ): List<String> {
        val day = t.atZone(zone).toLocalDate()
        fun watchedUntil(i: Instant): Long {
            val d = i.atZone(zone).toLocalDate()
            return if (d == day) used + Duration.between(t, i).seconds
            else Duration.between(d.atStartOfDay(zone).toInstant(), i).seconds
        }
        fun at(i: Instant) = i.atZone(zone).toString()
        val s = sessionAt(zone, t, profile, used, overrides)
        val where = "quota=${profile.dailyQuotaMinutes} ${profile.schedule} used=$used at ${at(t)} said $s"
        val v = mutableListOf<String>()
        when (s) {
            is SessionState.Ok, is SessionState.Warning -> {
                val r = remainingOf(s)
                if (s is SessionState.Ok && r != null && r <= PolicyEngine.WARNING_SECONDS) v += "$where: should warn"
                if (r != null) {
                    // (r - 1) s is surely before the real end, which is rounded up to r.
                    val probes = ((1..7).map { t.plusMillis((r - 1) * 1000 * it / 8) } + t.plusSeconds(r - 1))
                        .filter { checkAfterMidnight || it.atZone(zone).toLocalDate() == day }
                    probes.firstOrNull { !sessionAt(zone, it, profile, watchedUntil(it), overrides).canWatch }
                        ?.let { v += "$where: but already stopped at ${at(it)}" }
                    val end = t.plusSeconds(r)
                    if (end.atZone(zone).toLocalDate() == day &&
                        sessionAt(zone, end, profile, watchedUntil(end), overrides).canWatch
                    ) {
                        v += "$where: but still open at ${at(end)}"
                    }
                }
            }
            is SessionState.OutsideSchedule -> {
                val n = Instant.ofEpochMilli(s.nextAllowedAtEpochMs)
                val usedThen = if (n.atZone(zone).toLocalDate() == day) used else 0
                if (!sessionAt(zone, n, profile, usedThen, overrides).canWatch) v += "$where: but closed at ${at(n)}"
            }
            SessionState.TimeUp -> v += "$where: TimeUp below the limit"
        }
        return v
    }

    /** Wall times to probe on [date]: every 30 minutes, plus each bound ±1 ms and -1 s. */
    private fun probes(date: LocalDate, zone: ZoneId, schedule: Schedule?): List<Instant> {
        val grid = (0 until MINUTES_PER_DAY step 30).flatMap { m ->
            val i = date.atTime(m / 60, m % 60).atZone(zone).toInstant()
            listOf(i, i.plusSeconds(17))
        }
        val bounds = listOfNotNull(schedule?.allowedFromMinute, schedule?.allowedUntilMinute).flatMap { m ->
            val i = if (m >= MINUTES_PER_DAY) {
                date.plusDays(1).atStartOfDay(zone).toInstant()
            } else {
                date.atTime(m / 60, m % 60).atZone(zone).toInstant()
            }
            listOf(i, i.minusMillis(1), i.plusMillis(1), i.minusSeconds(1))
        }
        return grid + bounds
    }

    private val dstFreeZones = listOf(
        "Asia/Riyadh", "Asia/Amman", "UTC", "Asia/Kolkata", "Asia/Kathmandu", "Pacific/Kiritimati", "Pacific/Pago_Pago",
    ).map { ZoneId.of(it) }

    private val sweepDates = listOf("2026-09-28", "2026-12-31", "2028-02-29").map { LocalDate.parse(it) }

    private val sweepSchedules: List<Schedule?> = run {
        val fixed = listOf(
            null,
            Schedule(0, 0), Schedule(420, 420), Schedule(1439, 1439), Schedule(0, 1440),
            Schedule.of(7, 0, 22, 0), Schedule.of(22, 0, 7, 0), Schedule.of(20, 0, 6, 0),
            Schedule(0, 480), Schedule(1080, 1440), Schedule(1439, 1440), Schedule(0, 1),
            Schedule(1, 0), Schedule(1439, 0), Schedule(1200, 0), Schedule(720, 721), Schedule(721, 720),
            Schedule(1, 1440), Schedule(0, 1439),
        )
        val random = Random(20260928)
        fixed + List(25) { Schedule(random.nextInt(0, MINUTES_PER_DAY), random.nextInt(0, MINUTES_PER_DAY + 1)) }
    }

    // ---- Schedule sweeps (regression coverage) -----------------------------------------------

    @Test
    fun sweep_scheduleOnly_predictionsMatchLaterDecisions_inDstFreeZones() {
        val violations = mutableListOf<String>()
        for (zone in dstFreeZones) for (schedule in sweepSchedules) for (date in sweepDates) {
            val profile = scheduleOnly(schedule)
            for (t in probes(date, zone, schedule)) violations += scheduleViolations(zone, t, profile)
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    @Test
    fun sweep_withScheduleOverrides_predictionsMatchLaterDecisions() {
        val random = Random(7)
        val violations = mutableListOf<String>()
        for (zone in listOf(RIYADH, ZoneId.of("Pacific/Pago_Pago"))) for (schedule in sweepSchedules) for (date in sweepDates) {
            val profile = scheduleOnly(schedule)
            for (t in probes(date, zone, schedule)) {
                val until = t.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60L * random.nextInt(1, 900))
                val overrides = Overrides(
                    schedule = listOf(
                        ScheduleOverride(profile.id, until.toEpochMilli()),
                        ScheduleOverride(profile.id, t.toEpochMilli()), // expired exactly now
                        ScheduleOverride("someone-else", until.toEpochMilli() + 3_600_000),
                    ),
                )
                violations += scheduleViolations(zone, t, profile, overrides)
            }
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    @Test
    fun sweep_quotaAndSchedule_neverStopEarlierThanAnnounced() {
        val random = Random(99)
        val violations = mutableListOf<String>()
        for (zone in listOf(RIYADH, UTC)) for (schedule in sweepSchedules) for (date in sweepDates) {
            val quota = listOf(1, 5, 6, 30, 60, 1440)[random.nextInt(6)]
            val profile = child().copy(dailyQuotaMinutes = quota, schedule = schedule)
            for (t in probes(date, zone, schedule)) {
                val used = random.nextLong(0, quota * 60L)
                violations += quotaViolations(zone, t, profile, used)
            }
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    @Test
    fun sweep_quotaWithQuotaAndScheduleOverrides_neverStopEarlierThanAnnounced() {
        // Today's extra time ends at midnight while the new day brings a fresh quota, so the
        // prediction is only held until midnight here (the day change has its own tests).
        val random = Random(2026)
        val violations = mutableListOf<String>()
        for (zone in listOf(RIYADH, ZoneId.of("Pacific/Kiritimati"))) for (schedule in sweepSchedules) for (date in sweepDates) {
            val quota = listOf(1, 5, 6, 30, 60, 1440)[random.nextInt(6)]
            val profile = child().copy(dailyQuotaMinutes = quota, schedule = schedule)
            for (t in probes(date, zone, schedule)) {
                val today = t.atZone(zone).toLocalDate().toEpochDay()
                val extra = listOf(0L, 1L, 299L, 600L, 3600L)[random.nextInt(5)]
                val unlimited = random.nextInt(6) == 0
                val scheduleOverrides = if (random.nextBoolean()) {
                    val until = t.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60L * random.nextInt(1, 600))
                    listOf(ScheduleOverride(profile.id, until.toEpochMilli()))
                } else {
                    emptyList()
                }
                val overrides = Overrides(
                    quota = listOf(
                        QuotaOverride(profile.id, today, extraSeconds = extra, unlimited = unlimited),
                        QuotaOverride(profile.id, today - 1, extraSeconds = 99_999, unlimited = true),
                        QuotaOverride("someone-else", today, unlimited = true),
                    ),
                    schedule = scheduleOverrides + ScheduleOverride("someone-else", Long.MAX_VALUE / 2),
                )
                val limit = quota * 60L + extra
                val used = if (unlimited) random.nextLong(0, 200_000) else random.nextLong(0, limit)
                violations += quotaViolations(zone, t, profile, used, overrides, checkAfterMidnight = false)
            }
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    // ---- from == until -----------------------------------------------------------------------

    @Test
    fun equalBounds_everyMinute_isAlwaysOpen_neverWarns_neverGuards() {
        val engine = engineAt("2026-09-28T12:00")
        for (x in 0 until MINUTES_PER_DAY step 37) {
            val profile = scheduleOnly(Schedule(x, x))
            for (probe in listOf("00:00", "00:00:00.001", "06:59:59", "12:00", "21:59:59.999", "23:59:59.999")) {
                val t = local("2026-09-28T$probe", RIYADH)
                val s = sessionAt(RIYADH, t, profile)
                assertWithMessage("Schedule($x,$x) at $probe").that(s).isEqualTo(SessionState.Ok(null))
                assertThat(engine.guardShouldEnforce(LockState(false, s))).isFalse()
            }
        }
    }

    @Test
    fun equalBounds_withQuota_onlyTheQuotaCounts() {
        val profile = child().copy(schedule = Schedule(1320, 1320)) // 22:00–22:00 = always
        assertThat(engineAt("2026-09-28T23:30").session(profile, 0)).isEqualTo(SessionState.Ok(3600))
        assertThat(engineAt("2026-09-28T03:00").session(profile, 3300)).isEqualTo(SessionState.Warning(300))
    }

    // ---- Exact boundaries --------------------------------------------------------------------

    @Test
    fun defaultChild_exactMillisecondBoundaries() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        fun at(time: String) = sessionAt(RIYADH, local("2026-09-28T$time", RIYADH), kid)
        assertThat(at("06:59:59.999")).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-28T07:00")))
        assertThat(at("07:00")).isEqualTo(SessionState.Ok(15 * 3600L))
        assertThat(at("07:00:00.001")).isEqualTo(SessionState.Ok(15 * 3600L)) // rounded up
        assertThat(at("21:54:59.999")).isEqualTo(SessionState.Ok(301))
        assertThat(at("21:55")).isEqualTo(SessionState.Warning(300))
        assertThat(at("21:59:59.999")).isEqualTo(SessionState.Warning(1))
        assertThat(at("22:00")).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
        assertThat(at("23:59:59.999")).isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    @Test
    fun wrapWindow_exactlyAtMidnight_isStillInside() {
        val night = scheduleOnly(Schedule.of(20, 0, 6, 0))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:59:59.999", RIYADH), night)).isEqualTo(SessionState.Ok(6 * 3600L + 1))
        assertThat(sessionAt(RIYADH, local("2026-09-29T00:00", RIYADH), night)).isEqualTo(SessionState.Ok(6 * 3600L))
        assertThat(sessionAt(RIYADH, local("2026-09-29T05:59:59.999", RIYADH), night)).isEqualTo(SessionState.Warning(1))
        assertThat(sessionAt(RIYADH, local("2026-09-29T06:00", RIYADH), night))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T20:00")))
    }

    @Test
    fun oneMinuteWindows() {
        val lastMinute = scheduleOnly(Schedule(1439, 1440))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:59", RIYADH), lastMinute)).isEqualTo(SessionState.Warning(60))
        assertThat(sessionAt(RIYADH, local("2026-09-29T00:00", RIYADH), lastMinute))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T23:59")))
        val allButMidnightMinute = scheduleOnly(Schedule(1, 0))
        assertThat(sessionAt(RIYADH, local("2026-09-28T00:00:30", RIYADH), allButMidnightMinute))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-28T00:01")))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:58", RIYADH), allButMidnightMinute)).isEqualTo(SessionState.Warning(120))
    }

    // ---- Warning threshold -------------------------------------------------------------------

    @Test
    fun warning_quotaBoundaries() {
        val engine = engineAt("2026-09-28T12:00")
        val five = child().copy(schedule = null, dailyQuotaMinutes = 5)
        assertThat(engine.session(five, 0)).isEqualTo(SessionState.Warning(300)) // warns from the start
        val six = five.copy(dailyQuotaMinutes = 6)
        assertThat(engine.session(six, 59)).isEqualTo(SessionState.Ok(301))
        assertThat(engine.session(six, 60)).isEqualTo(SessionState.Warning(300))
        assertThat(engine.session(six, 359)).isEqualTo(SessionState.Warning(1))
        assertThat(engine.session(six, 360)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun warning_tieBetweenQuotaAndSchedule() {
        // 21:55: 300 s to 22:00, and 300 s of quota left.
        assertThat(engineAt("2026-09-28T21:55").session(child(), 3300)).isEqualTo(SessionState.Warning(300))
        // 21:54: 360 s to 22:00, 301 s of quota left.
        assertThat(engineAt("2026-09-28T21:54").session(child(), 3299)).isEqualTo(SessionState.Ok(301))
    }

    @Test
    fun warning_scheduleOverrideBoundaries() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val t = local("2026-09-28T23:00", RIYADH)
        fun overrideFor(ms: Long) = Overrides(schedule = listOf(ScheduleOverride(kid.id, t.toEpochMilli() + ms)))
        assertThat(sessionAt(RIYADH, t, kid, 0, overrideFor(301_000))).isEqualTo(SessionState.Ok(301))
        assertThat(sessionAt(RIYADH, t, kid, 0, overrideFor(300_000))).isEqualTo(SessionState.Warning(300))
        assertThat(sessionAt(RIYADH, t, kid, 0, overrideFor(1))).isEqualTo(SessionState.Warning(1))
        assertThat(sessionAt(RIYADH, t, kid, 0, overrideFor(0)))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    // ---- Override expiry ---------------------------------------------------------------------

    @Test
    fun scheduleOverride_expiresAtTheExactMillisecond() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val until = epochMsAt("2026-09-28T23:30")
        val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, until)))
        assertThat(sessionAt(RIYADH, Instant.ofEpochMilli(until - 1), kid, 0, o)).isEqualTo(SessionState.Warning(1))
        assertThat(sessionAt(RIYADH, Instant.ofEpochMilli(until), kid, 0, o))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    @Test
    fun quotaOverride_countsUntilTheLastMillisecondOfItsDay() {
        val kid = child().copy(schedule = null)
        val o = Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = 600)))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:59:59.999", RIYADH), kid, 3600, o)).isEqualTo(SessionState.Ok(600))
        assertThat(sessionAt(RIYADH, local("2026-09-29T00:00", RIYADH), kid, 3600, o)).isEqualTo(SessionState.TimeUp)
        val unlimited = Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), unlimited = true)))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:59:59.999", RIYADH), kid, 99_999, unlimited)).isEqualTo(SessionState.Ok(null))
        assertThat(sessionAt(RIYADH, local("2026-09-29T00:00", RIYADH), kid, 99_999, unlimited)).isEqualTo(SessionState.TimeUp)
    }

    @Test
    fun untilEndOfDay_grantedAtTheLastMillisecond_lastsOneMillisecond() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val engine = PolicyEngine(MutableClock(local("2026-09-28T23:59:59.999", RIYADH), RIYADH))
        val grant = engine.grantScheduleOverrideUntilEndOfDay(kid)
        assertThat(grant.untilEpochMs).isEqualTo(epochMsAt("2026-09-29T00:00"))
        assertThat(engine.session(kid, 0, Overrides.NONE + grant)).isEqualTo(SessionState.Warning(1))
    }

    @Test
    fun overridesArePrunedExactlyWhenTheEngineStopsUsingThem() {
        val kid = child()
        val until = epochMsAt("2026-09-28T23:30")
        val o = Overrides(
            quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-28"), extraSeconds = 600)),
            schedule = listOf(ScheduleOverride(kid.id, until)),
        )
        for (t in listOf(until - 1, until, epochMsAt("2026-09-28T23:59:59.999"), epochMsAt("2026-09-29T00:00"))) {
            val engine = PolicyEngine(MutableClock(Instant.ofEpochMilli(t), RIYADH))
            val pruned = o.pruned(engine.today(), engine.nowEpochMs())
            assertWithMessage("at $t").that(engine.session(kid, 3600, pruned)).isEqualTo(engine.session(kid, 3600, o))
        }
    }

    // ---- Extreme override values (bug) ------------------------------------------------------

    /**
     * Long.MAX_VALUE is the natural "until further notice" sentinel, and stored/imported JSON can
     * hold any Long. session() is called every second by the guard; it must never throw.
     */
    @Test
    fun farFutureScheduleOverride_doesNotCrashTheEngine() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val forever = Overrides(schedule = listOf(ScheduleOverride(kid.id, Long.MAX_VALUE)))
        val s = runCatching { engineAt("2026-09-28T23:00").session(kid, 0, forever) }
        assertWithMessage("session() threw ${s.exceptionOrNull()}").that(s.isSuccess).isTrue()
        assertThat(s.getOrThrow().canWatch).isTrue()
    }

    @Test
    fun hugeExtraTime_doesNotWrapAroundIntoTimeUp() {
        val kid = child().copy(schedule = null)
        val today = epochDay("2026-09-28")
        val engine = engineAt("2026-09-28T12:00")
        val huge = Overrides(quota = listOf(QuotaOverride(kid.id, today, extraSeconds = Long.MAX_VALUE)))
        assertWithMessage("quota + Long.MAX_VALUE extra").that(engine.session(kid, 3600, huge).canWatch).isTrue()
        val twoBig = Overrides(
            quota = List(2) { QuotaOverride(kid.id, today, extraSeconds = Long.MAX_VALUE / 2 + 1) },
        )
        assertWithMessage("two grants of Long.MAX_VALUE / 2 + 1").that(engine.session(kid, 3600, twoBig).canWatch).isTrue()
    }

    @Test
    fun extremeUsage_neverThrows() {
        val engine = engineAt("2026-09-28T12:00")
        for (used in listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)) {
            assertThat(engine.session(child().copy(schedule = null), used)).isEqualTo(
                if (used == Long.MAX_VALUE) SessionState.TimeUp else SessionState.Ok(3600),
            )
            assertThat(engine.session(adult(), used)).isEqualTo(SessionState.Ok(null))
        }
    }

    // ---- Day change while watching -----------------------------------------------------------

    @Test
    fun dayChangeWhileWatching_everyStopIsAnnounced_andTheQuotaRestartsAtMidnight() {
        // Night window 20:00–06:00, 60 minutes a day, 10 minutes left at 23:00. The app ticks every second.
        val kid = child().copy(schedule = Schedule.of(20, 0, 6, 0))
        val clock = clockAt("2026-09-28T23:00")
        val engine = PolicyEngine(clock)
        val usedByDay = mutableMapOf(engine.today() to 3000L)
        var previous: SessionState? = null
        val stops = mutableListOf<String>()
        repeat(8 * 3600) {
            val today = engine.today()
            val used = usedByDay.getOrDefault(today, 0L)
            val s = engine.session(kid, used)
            if (previous?.canWatch == true && !s.canWatch) {
                assertWithMessage("stop at ${clock.instant().atZone(RIYADH)}").that(previous).isEqualTo(SessionState.Warning(1))
                stops += clock.instant().atZone(RIYADH).toLocalTime().toString()
            }
            if (s.canWatch) usedByDay[today] = used + 1
            previous = s
            clock.advance(Duration.ofSeconds(1))
        }
        assertThat(stops).containsExactly("23:10", "01:00").inOrder()
        assertThat(sessionAt(RIYADH, local("2026-09-29T00:00", RIYADH), kid, 0)).isEqualTo(SessionState.Ok(3600))
    }

    @Test
    fun dayChangeWhileWatching_scheduleOverridePastMidnight_countsToTheNewDay() {
        val kid = child() // 07:00–22:00, 60 min
        val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-29T00:30"))))
        // 23:50 on the 28th, 20 min of quota left: the override (40 min) is not the limit, the quota is.
        assertThat(engineAt("2026-09-28T23:50").session(kid, 2400, o)).isEqualTo(SessionState.Ok(1200))
        // After midnight the quota starts again; the override is the limit now.
        assertThat(engineAt("2026-09-29T00:00").session(kid, 0, o)).isEqualTo(SessionState.Ok(1800))
        assertThat(engineAt("2026-09-29T00:30").session(kid, 1800, o))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    // ---- Next opening vs a used-up quota (bug) ------------------------------------------------

    /**
     * SessionState.OutsideSchedule says "it opens again at nextAllowedAt". When the window reopens
     * on a local day whose quota is already used up, the screen does not open then: the child is
     * told "come back at 07:00" and gets "time up" (PIN only) at 07:00.
     */
    @Test
    fun outsideSchedule_promisedOpening_isTimeUp_whenAnOverrideAlreadyUsedTheNewDaysQuota() {
        val kid = child() // 07:00–22:00, 60 min
        // The parent let the child watch until 01:00 (schedule override granted at 23:00).
        // 00:00–01:00 used the whole quota of the 29th. The override has just ended.
        val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-29T01:00"))))
        val t = local("2026-09-29T01:00", RIYADH)
        val usedOn29th = 3600L
        val s = sessionAt(RIYADH, t, kid, usedOn29th, o)
        if (s is SessionState.OutsideSchedule) {
            val n = Instant.ofEpochMilli(s.nextAllowedAtEpochMs)
            val usedThen = if (n.atZone(RIYADH).toLocalDate() == t.atZone(RIYADH).toLocalDate()) usedOn29th else 0
            val then = sessionAt(RIYADH, n, kid, usedThen, o)
            assertWithMessage("at 01:00 the engine said $s, but at ${n.atZone(RIYADH)} it says $then")
                .that(then.canWatch).isTrue()
        }
    }

    @Test
    fun outsideSchedule_promisedOpening_isTimeUp_wrapWindowQuotaUsedAfterMidnight() {
        // Night window 20:00–06:00, 60 min. The child watched 00:00–01:00 on the 28th.
        val kid = child().copy(schedule = Schedule.of(20, 0, 6, 0))
        val t = local("2026-09-28T12:00", RIYADH)
        val s = sessionAt(RIYADH, t, kid, 3600)
        if (s is SessionState.OutsideSchedule) {
            val n = Instant.ofEpochMilli(s.nextAllowedAtEpochMs)
            val usedThen = if (n.atZone(RIYADH).toLocalDate() == t.atZone(RIYADH).toLocalDate()) 3600L else 0
            val then = sessionAt(RIYADH, n, kid, usedThen)
            assertWithMessage("at 12:00 the engine said $s, but at ${n.atZone(RIYADH)} it says $then")
                .that(then.canWatch).isTrue()
        }
    }

    // ---- DST (bugs; the owner's zones Riyadh/Amman have no DST, Cairo and Beirut do) ----------

    @Test
    fun dstGap_windowEndInsideTheGap_warnsBeforeTheRealEnd_berlin() {
        // Berlin jumps from 02:00 to 03:00 on 2026-03-29. A 20:00–02:30 window really closes at
        // 02:00 (the next instant reads 03:00, outside), but the engine counts down to 03:30.
        val berlin = ZoneId.of("Europe/Berlin")
        val night = scheduleOnly(Schedule.of(20, 0, 2, 30))
        val t = local("2026-03-29T01:55", berlin)
        assertThat(sessionAt(berlin, t.plusSeconds(300), night).canWatch).isFalse() // the engine itself closes at 02:00
        assertThat(sessionAt(berlin, t, night)).isEqualTo(SessionState.Warning(300))
    }

    @Test
    fun dstGap_windowEndInsideTheGap_warnsBeforeTheRealEnd_cairo() {
        // Cairo jumps from 00:00 to 01:00 on 2026-04-24. A 20:00–00:30 window closes at midnight.
        val cairo = ZoneId.of("Africa/Cairo")
        val evening = scheduleOnly(Schedule.of(20, 0, 0, 30))
        val t = local("2026-04-23T23:55", cairo)
        assertThat(sessionAt(cairo, t.plusSeconds(300), evening).canWatch).isFalse()
        assertThat(sessionAt(cairo, t, evening)).isEqualTo(SessionState.Warning(300))
    }

    @Test
    fun dstGap_windowStartInsideTheGap_opensWhenTheEngineLetsTheChildIn() {
        // Window 02:30–10:00 on Berlin's spring-forward day (02:00 jumps to 03:00). At 01:00 the
        // engine announces 03:30, yet at 03:10 it already lets the child in (wall time ≥ 02:30).
        // Either answer can be fixed; they must agree (expected: opens at 03:00, the gap's end).
        val berlin = ZoneId.of("Europe/Berlin")
        val early = scheduleOnly(Schedule(150, 600))
        val s = sessionAt(berlin, local("2026-03-29T01:00", berlin), early)
        assertThat(s).isInstanceOf(SessionState.OutsideSchedule::class.java)
        val threeTen = local("2026-03-29T03:10", berlin)
        if (sessionAt(berlin, threeTen, early).canWatch) {
            assertWithMessage("announced $s at 01:00, but open at 03:10")
                .that((s as SessionState.OutsideSchedule).nextAllowedAtEpochMs).isAtMost(threeTen.toEpochMilli())
        }
    }

    @Test
    fun dstOverlap_windowEndInsideTheRepeatedHour_neverStopsWithoutWarning_berlin() {
        // Berlin goes from 03:00 back to 02:00 on 2026-10-25; 02:00–02:59 happens twice.
        val berlin = ZoneId.of("Europe/Berlin")
        val night = scheduleOnly(Schedule.of(20, 0, 2, 30))
        // Second 02:26: the engine lets the child watch, so it must stop at 02:30 (4 min) — or not
        // let them in at all. It answers "Ok, 24 hours left", then stops 4 minutes later.
        val t = localLater("2026-10-25T02:26", berlin)
        assertThat(sessionAt(berlin, localLater("2026-10-25T02:31", berlin), night).canWatch).isFalse()
        assertThat(sessionAt(berlin, t, night)).isIn(
            listOf(SessionState.Warning(240), SessionState.OutsideSchedule(local("2026-10-25T20:00", berlin).toEpochMilli())),
        )
    }

    @Test
    fun dstOverlap_closedAtFirstPass_mustNotReopenBeforeTheAnnouncedTime_berlin() {
        val berlin = ZoneId.of("Europe/Berlin")
        val night = scheduleOnly(Schedule.of(20, 0, 2, 30))
        val firstPass = local("2026-10-25T02:40", berlin) // earlier offset (CEST): closed
        val s = sessionAt(berlin, firstPass, night)
        assertThat(s).isInstanceOf(SessionState.OutsideSchedule::class.java)
        val secondPass = localLater("2026-10-25T02:10", berlin) // 30 minutes later (CET)
        if (sessionAt(berlin, secondPass, night).canWatch) {
            assertWithMessage("closed at 02:40 CEST with $s, but open again at 02:10 CET")
                .that((s as SessionState.OutsideSchedule).nextAllowedAtEpochMs).isAtMost(secondPass.toEpochMilli())
        }
    }

    @Test
    fun dstOverlap_windowEndInsideTheRepeatedHour_neverStopsWithoutWarning_cairo() {
        // Cairo goes from 00:00 (+03) back to 23:00 (+02) on the night of 2026-10-29.
        val cairo = ZoneId.of("Africa/Cairo")
        val evening = scheduleOnly(Schedule.of(18, 0, 23, 30))
        val t = localLater("2026-10-29T23:26", cairo)
        assertThat(sessionAt(cairo, localLater("2026-10-29T23:31", cairo), evening).canWatch).isFalse()
        assertThat(sessionAt(cairo, t, evening)).isIn(
            listOf(SessionState.Warning(240), SessionState.OutsideSchedule(local("2026-10-30T18:00", cairo).toEpochMilli())),
        )
    }

    /**
     * Every instant of [date] in [zone] every [stepMinutes] (both passes of a repeated hour), each
     * zone transition ±1 ms, and every instant whose wall time is a schedule bound (±1 ms, -1 s).
     */
    private fun instantProbes(date: LocalDate, zone: ZoneId, schedule: Schedule?, stepMinutes: Long = 10): List<Instant> {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val grid = generateSequence(start) { it.plusSeconds(stepMinutes * 60) }.takeWhile { it.isBefore(end) }.toList()
        val transitions = listOfNotNull(zone.rules.nextTransition(start.minusMillis(1))?.instant?.takeIf { it.isBefore(end) })
        val bounds = listOfNotNull(schedule?.allowedFromMinute, schedule?.allowedUntilMinute).flatMap { m ->
            val wall = if (m >= MINUTES_PER_DAY) date.plusDays(1).atStartOfDay() else date.atTime(m / 60, m % 60)
            zone.rules.getValidOffsets(wall).map { wall.toInstant(it) }
        }
        return grid + (transitions + bounds).flatMap { listOf(it, it.minusMillis(1), it.plusMillis(1), it.minusSeconds(1)) }
    }

    /** Spring-forward and fall-back days, including jumps at midnight (Cairo) and of 30 minutes (Lord Howe). */
    private val dstDays = listOf(
        "Europe/Berlin" to listOf("2026-03-29", "2026-10-25"),
        "Africa/Cairo" to listOf("2026-04-23", "2026-04-24", "2026-10-29", "2026-10-30"),
        "Asia/Beirut" to listOf("2026-03-28", "2026-03-29", "2026-10-24", "2026-10-25"),
        "Australia/Lord_Howe" to listOf("2026-04-05", "2026-10-04"),
        "America/Havana" to listOf("2026-03-08", "2026-11-01"),
    ).flatMap { (zone, days) -> days.map { ZoneId.of(zone) to LocalDate.parse(it) } }

    @Test
    fun sweep_scheduleOnly_predictionsMatchLaterDecisions_onDstDays() {
        val violations = mutableListOf<String>()
        for ((zone, date) in dstDays) for (schedule in sweepSchedules) {
            val profile = scheduleOnly(schedule)
            for (t in instantProbes(date, zone, schedule)) violations += scheduleViolations(zone, t, profile)
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    @Test
    fun sweep_quotaAndSchedule_neverStopEarlierThanAnnounced_onDstDays() {
        val random = Random(1203)
        val violations = mutableListOf<String>()
        for ((zone, date) in dstDays) for (schedule in sweepSchedules) {
            val quota = listOf(1, 5, 6, 30, 60, 1440)[random.nextInt(6)]
            val profile = child().copy(dailyQuotaMinutes = quota, schedule = schedule)
            for (t in instantProbes(date, zone, schedule, stepMinutes = 30)) {
                violations += quotaViolations(zone, t, profile, random.nextLong(0, quota * 60L))
            }
        }
        assertWithMessage("${violations.size} violations").that(violations.take(20)).isEmpty()
    }

    @Test
    fun dstOverlap_windowEndAtTheRepeatedMidnight_cairo() {
        // 23:59–24:00 on Cairo's fall-back night: at the first 23:59 the window closes 60 s later,
        // when the clock goes back to 23:00 (it used to say 3660 s).
        val cairo = ZoneId.of("Africa/Cairo")
        val lastMinute = scheduleOnly(Schedule(1439, 1440))
        assertThat(sessionAt(cairo, local("2026-10-29T23:59", cairo), lastMinute)).isEqualTo(SessionState.Warning(60))
        // Midnight is skipped on 2026-04-24, so a 00:00–00:01 window does not open that day at all.
        val firstMinute = scheduleOnly(Schedule(0, 1))
        assertThat(sessionAt(cairo, local("2026-04-23T23:00", cairo), firstMinute))
            .isEqualTo(SessionState.OutsideSchedule(local("2026-04-25T00:00", cairo).toEpochMilli()))
    }

    @Test
    fun outsideSchedule_zeroQuota_isTimeUp_becauseItNeverOpens() {
        val kid = child().copy(dailyQuotaMinutes = 0)
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:00", RIYADH), kid)).isEqualTo(SessionState.TimeUp)
        // A grant for the day of the next opening makes the promise true again.
        val tomorrow = Overrides(quota = listOf(QuotaOverride(kid.id, epochDay("2026-09-29"), extraSeconds = 600)))
        assertThat(sessionAt(RIYADH, local("2026-09-28T23:00", RIYADH), kid, 0, tomorrow))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
    }

    @Test
    fun outsideSchedule_quotaUsedUp_opensAtTheNextDaysWindow_exactValues() {
        val kid = child() // 07:00–22:00, 60 min
        val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, epochMsAt("2026-09-29T01:00"))))
        assertThat(sessionAt(RIYADH, local("2026-09-29T01:00", RIYADH), kid, 3600, o))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-30T07:00")))
        // Not used up: the same day's opening stands.
        assertThat(sessionAt(RIYADH, local("2026-09-29T01:00", RIYADH), kid, 3599, o))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00")))
        val night = child().copy(schedule = Schedule.of(20, 0, 6, 0))
        assertThat(sessionAt(RIYADH, local("2026-09-28T12:00", RIYADH), night, 3600))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T00:00")))
    }

    @Test
    fun farFutureScheduleOverride_neverEnds_andNearOnesStillCount() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val t = local("2026-09-28T23:00", RIYADH)
        for (until in listOf(Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE / 2)) {
            val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, until)))
            assertThat(sessionAt(RIYADH, t, kid, 0, o)).isEqualTo(SessionState.Ok(null))
        }
        // A year away is still an ordinary override: 23:00 is outside the window, so it ends exactly then.
        val nextYear = local("2027-09-28T23:00", RIYADH)
        val o = Overrides(schedule = listOf(ScheduleOverride(kid.id, nextYear.toEpochMilli())))
        assertThat(sessionAt(RIYADH, t, kid, 0, o)).isEqualTo(SessionState.Ok(Duration.between(t, nextYear).seconds))
    }

    @Test
    fun dst_defaultChildWindow_isUnaffected_inCairoAndBeirut() {
        val kid = scheduleOnly(Profile.DEFAULT_CHILD_SCHEDULE)
        val violations = mutableListOf<String>()
        for ((zone, days) in listOf(
            ZoneId.of("Africa/Cairo") to listOf("2026-04-23", "2026-04-24", "2026-10-29", "2026-10-30"),
            ZoneId.of("Asia/Beirut") to listOf("2026-03-28", "2026-03-29", "2026-10-24", "2026-10-25"),
        )) {
            for (day in days) {
                val start = LocalDate.parse(day).atStartOfDay(zone).toInstant()
                for (step in 0 until 24 * 12) violations += scheduleViolations(zone, start.plusSeconds(step * 300L), kid)
            }
        }
        assertWithMessage("${violations.size} violations").that(violations.take(10)).isEmpty()
    }

    // ---- Zones -------------------------------------------------------------------------------

    @Test
    fun farApartZones_sameInstant_differentDays() {
        val instant = Instant.parse("2026-09-28T11:00:00Z")
        val kiritimati = PolicyEngine(MutableClock(instant, ZoneId.of("Pacific/Kiritimati"))) // 01:00 on the 29th
        val pagoPago = PolicyEngine(MutableClock(instant, ZoneId.of("Pacific/Pago_Pago"))) // 00:00 on the 28th
        assertThat(kiritimati.today()).isEqualTo(epochDay("2026-09-29"))
        assertThat(pagoPago.today()).isEqualTo(epochDay("2026-09-28"))
        val kid = child()
        assertThat(kiritimati.session(kid, 0))
            .isEqualTo(SessionState.OutsideSchedule(epochMsAt("2026-09-29T07:00", ZoneId.of("Pacific/Kiritimati"))))
        // A grant keyed by one zone's day does not leak into another zone's day.
        val grant = kiritimati.grantUnlimitedToday(kid)
        assertThat(pagoPago.session(kid.copy(schedule = null), 3600, Overrides.NONE + grant)).isEqualTo(SessionState.TimeUp)
    }

    // ---- Guard -------------------------------------------------------------------------------

    @Test
    fun guard_overAWholeDayOfTheDefaultChild_followsTheRulesExactly() {
        val kid = child() // 07:00–22:00, 60 min
        val clock = clockAt("2026-09-28T00:00")
        val engine = PolicyEngine(clock)
        var used = 0L
        repeat(24 * 60) { minute ->
            val s = engine.session(kid, used)
            val inWindow = minute in 7 * 60 until 22 * 60
            val expected = !(inWindow && used < 3600)
            assertWithMessage("minute $minute, used $used: $s").that(engine.guardShouldEnforce(LockState(false, s))).isEqualTo(expected)
            assertThat(engine.guardShouldEnforce(LockState(true, s))).isTrue()
            if (s.canWatch) used += 60
            clock.advance(Duration.ofMinutes(1))
        }
        assertThat(used).isEqualTo(3600)
    }

    @Test
    fun lockedPlayback_timeRulesComeFirst_contentRulesBeforeThem() {
        val kid = child()
        val engine = engineAt("2026-09-28T23:00")
        val outside = engine.session(kid, 0)
        assertThat(engine.canPlay(kid, video(), LockState(true, outside))).isEqualTo(deny(DenyReason.OUTSIDE_SCHEDULE))
        assertThat(engine.canPlay(kid, video(), LockState(true, SessionState.TimeUp))).isEqualTo(deny(DenyReason.TIME_UP))
        assertThat(engine.canPlay(kid, video(channelId = OTHER_CHANNEL), LockState(true, outside)))
            .isEqualTo(deny(DenyReason.NOT_IN_ALLOWLIST))
        assertThat(engine.canPlay(kid, video(), LockState(true, SessionState.Warning(1)))).isEqualTo(deny(DenyReason.LOCKED))
        assertThat(engine.canPlay(kid, video(), LockState(false, SessionState.Warning(1)))).isEqualTo(Decision.Allow)
    }

    @Test
    fun grantAtTheLastSecond_beforeMidnight_isNotNeededAfterMidnight() {
        val kid = child().copy(schedule = null)
        val clock = clockAt("2026-09-28T23:59:30")
        val engine = PolicyEngine(clock)
        assertThat(engine.session(kid, 3700)).isEqualTo(SessionState.TimeUp)
        val grant = engine.grantExtraTime(kid, 3700, Overrides.NONE, 10)
        assertThat(engine.session(kid, 3700, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(600))
        clock.advance(Duration.ofSeconds(30))
        assertThat(engine.session(kid, 0, Overrides.NONE + grant)).isEqualTo(SessionState.Ok(3600))
    }

    @Test
    fun adultExitHold_boundaries() {
        assertThat(AdultExitChallenge.isHoldComplete(AdultExitChallenge.HOLD_MILLIS - 1)).isFalse()
        assertThat(AdultExitChallenge.isHoldComplete(AdultExitChallenge.HOLD_MILLIS)).isTrue()
        assertThat(AdultExitChallenge.isHoldComplete(-1)).isFalse()
        assertThat(AdultExitChallenge.holdProgress(-5)).isEqualTo(0f)
        assertThat(AdultExitChallenge.holdProgress(5_000)).isEqualTo(0.5f)
        assertThat(AdultExitChallenge.holdProgress(Long.MAX_VALUE)).isEqualTo(1f)
        assertThat(AdultExitChallenge.isRequired(adult(), videoFinished = false)).isTrue()
        assertThat(AdultExitChallenge.isRequired(adult(), videoFinished = true)).isFalse()
        assertThat(AdultExitChallenge.isRequired(child(), videoFinished = false)).isFalse() // the child needs the PIN instead
        assertThat(engineAt("2026-09-28T12:00").features(child()).exitNeedsPin).isTrue()
    }

    // ---- PIN lockout -------------------------------------------------------------------------

    @Test
    fun pinLockout_endsAtTheExactMillisecond() {
        val clock = clockAt("2026-09-28T12:00")
        val limiter = PinAttemptLimiter(clock)
        repeat(5) { limiter.attempt { false } }
        clock.advance(Duration.ofMillis(PinAttemptLimiter.LOCKOUT_MILLIS - 1))
        var checked = false
        assertThat(limiter.attempt { checked = true; true }).isEqualTo(PinAttemptResult.LockedOut(1))
        assertThat(checked).isFalse()
        clock.advance(Duration.ofMillis(1))
        assertThat(limiter.attempt { checked = true; false }).isEqualTo(PinAttemptResult.Wrong(4))
        assertThat(checked).isTrue()
    }

    @Test
    fun pinLockout_restoredFromAFarFutureState_isCappedAtFiveMinutes() {
        val clock = clockAt("2026-09-28T12:00")
        val corrupted = PinAttemptState(consecutiveFailures = 0, lockedUntilEpochMs = clock.millis() + Duration.ofDays(365).toMillis())
        val limiter = PinAttemptLimiter(clock, corrupted)
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.LockedOut(PinAttemptLimiter.LOCKOUT_MILLIS))
        clock.advance(Duration.ofMinutes(5))
        assertThat(limiter.attempt { true }).isEqualTo(PinAttemptResult.Success)
    }

    @Test
    fun pinLockout_wrongAttemptsDuringLockout_doNotCountTowardTheNextOne() {
        val clock = clockAt("2026-09-28T12:00")
        val limiter = PinAttemptLimiter(clock)
        repeat(5) { limiter.attempt { false } }
        repeat(20) { assertThat(limiter.attempt { false }).isInstanceOf(PinAttemptResult.LockedOut::class.java) }
        clock.advance(Duration.ofMinutes(5))
        val afterLockout = (1..5).map { limiter.attempt { false } }
        // The next lockout in a row is longer (see PinAttemptLimiterTest).
        assertThat(afterLockout.last()).isEqualTo(PinAttemptResult.LockedOut(PinAttemptLimiter.lockoutMillis(2)))
        assertThat(afterLockout.dropLast(1)).containsExactly(
            PinAttemptResult.Wrong(4), PinAttemptResult.Wrong(3), PinAttemptResult.Wrong(2), PinAttemptResult.Wrong(1),
        ).inOrder()
    }
}
