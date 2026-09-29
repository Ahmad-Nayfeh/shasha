package com.ahmadnayfeh.shasha.policy

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Owns every viewing rule. The app asks it before showing or playing anything (SPEC section 3).
 * Time only comes from [clock] (and its zone), which keeps the engine deterministic in tests.
 *
 * Schedules are read on the local wall clock: at any instant, watching is allowed when the wall
 * time of that instant is inside the window. Every prediction the engine makes (seconds left, the
 * next opening) is the exact instant at which that answer changes, daylight-saving jumps included.
 */
class PolicyEngine(private val clock: Clock) {

    /** Local epoch day in the clock's zone; quota counters and [QuotaOverride]s are keyed by it. */
    fun today(): Long = LocalDate.now(clock).toEpochDay()

    fun nowEpochMs(): Long = clock.millis()

    // ---- Content ---------------------------------------------------------------------------

    /** For lists (home, search, channel, playlist). */
    fun canShow(profile: Profile, item: Candidate): Decision {
        if (profile.isChild &&
            !profile.allowlist.hasChannel(item.channelId) &&
            !profile.allowlist.hasPlaylist(item.viaPlaylistId)
        ) {
            return Decision.Deny(DenyReason.NOT_IN_ALLOWLIST)
        }
        if (profile.blocklist.hasChannel(item.channelId)) return Decision.Deny(DenyReason.BLOCKED_CHANNEL)
        if (hasBlockedKeyword(profile, item.title)) return Decision.Deny(DenyReason.BLOCKED_KEYWORD)
        if (profile.hideShorts && isShort(item)) return Decision.Deny(DenyReason.SHORTS_HIDDEN)
        return Decision.Allow
    }

    /**
     * Content rules first (a forbidden item must not even trigger the "time up" screen),
     * then schedule, then quota. Use this inside the locked player (playlist auto-advance, replay).
     */
    fun canPlay(profile: Profile, item: Candidate, session: SessionState): Decision {
        val content = canShow(profile, item)
        if (content is Decision.Deny) return content
        return when (session) {
            is SessionState.OutsideSchedule -> Decision.Deny(DenyReason.OUTSIDE_SCHEDULE)
            SessionState.TimeUp -> Decision.Deny(DenyReason.TIME_UP)
            is SessionState.Ok, is SessionState.Warning -> Decision.Allow
        }
    }

    /**
     * For starting a new playback from outside the current player (navigation, phone link):
     * while a video is locked ([PlaybackLock]), nothing else may replace it until the lock ends.
     */
    fun canPlay(profile: Profile, item: Candidate, lock: LockState): Decision {
        val decision = canPlay(profile, item, lock.session)
        if (decision is Decision.Deny) return decision
        return if (lock.playbackLocked) Decision.Deny(DenyReason.LOCKED) else Decision.Allow
    }

    /**
     * For a video that arrives as a link (the phone's "share → screen", a pasted URL). A link's
     * `list=` parameter proves nothing about the video, so [Candidate.viaPlaylistId] is ignored:
     * in a child profile the video's own channel must be allowlisted (SPEC acceptance: a channel
     * outside the allowlist never shows, even through a link sent from the phone). To play it as
     * part of an allowlisted playlist, open the playlist itself.
     */
    fun canPlayLink(profile: Profile, item: Candidate, lock: LockState): Decision =
        canPlay(profile, item.copy(viaPlaylistId = null), lock)

    fun canShowChannel(profile: Profile, channelId: String): Decision {
        if (profile.isChild && !profile.allowlist.hasChannel(channelId)) return Decision.Deny(DenyReason.NOT_IN_ALLOWLIST)
        if (profile.blocklist.hasChannel(channelId)) return Decision.Deny(DenyReason.BLOCKED_CHANNEL)
        return Decision.Allow
    }

    /**
     * A CHILD may open a playlist that is allowlisted itself or belongs to an allowlisted channel.
     * Pass [title] whenever the playlist is shown in a list: blocked title keywords apply to it too.
     */
    fun canShowPlaylist(profile: Profile, playlistId: String, ownerChannelId: String?, title: String? = null): Decision {
        if (profile.isChild &&
            !profile.allowlist.hasPlaylist(playlistId) &&
            !profile.allowlist.hasChannel(ownerChannelId)
        ) {
            return Decision.Deny(DenyReason.NOT_IN_ALLOWLIST)
        }
        if (profile.blocklist.hasChannel(ownerChannelId)) return Decision.Deny(DenyReason.BLOCKED_CHANNEL)
        if (title != null && hasBlockedKeyword(profile, title)) return Decision.Deny(DenyReason.BLOCKED_KEYWORD)
        return Decision.Allow
    }

    /** Short: flagged short-form, a "/shorts/" url, or a known non-live duration of at most 3 minutes. */
    fun isShort(item: Candidate): Boolean {
        if (item.isShortFormFlag) return true
        if (item.url.contains("/shorts/", ignoreCase = true)) return true
        val duration = item.durationSeconds ?: return false
        return !item.isLive && duration in 1..SHORTS_MAX_SECONDS
    }

    /**
     * Whether [profile]'s shorts rule cannot judge [item] until its duration is known: the profile hides
     * shorts, and the item is neither flagged, a "/shorts/" link nor live, and has no duration (RSS items
     * carry none). [canShow] lets such an item through, but playing it may still be refused once the
     * duration is known (a regular 2-minute video is a short here). A list that can learn the durations
     * (a channel's Videos tab) should leave these items out until it has; the player looks the video up first.
     */
    fun needsDuration(profile: Profile, item: Candidate): Boolean =
        profile.hideShorts && !item.isLive && (item.durationSeconds ?: 0) <= 0 && !isShort(item)

    fun features(profile: Profile): Features {
        val adult = profile.isAdult
        return Features(
            search = adult,
            textInput = adult,
            comments = adult && !profile.hideComments,
            undoSponsorSkip = adult,
            enterNeedsPin = adult,
            exitNeedsPin = !adult,
            adultExitChallenge = adult,
            channelBrowsing = true,
        )
    }

    // ---- Time ------------------------------------------------------------------------------

    /** Kept for the original contract: a single quota override, no schedule override. */
    fun session(profile: Profile, usedTodaySeconds: Long, override: QuotaOverride?): SessionState =
        session(profile, usedTodaySeconds, Overrides(quota = listOfNotNull(override)))

    /**
     * @param usedTodaySeconds FOREGROUND seconds of today for this profile (see [UsageStatsCalculator]),
     *   recorded only while [SessionState.countsTowardQuota] was true: the lock screens never use it up.
     * @param overrides all stored overrides; those of other profiles, older days or past expiry are ignored.
     */
    fun session(profile: Profile, usedTodaySeconds: Long, overrides: Overrides = Overrides.NONE): SessionState {
        val now = clock.instant()
        val used = usedTodaySeconds.coerceAtLeast(0)
        val scheduleOverrideUntil = activeScheduleOverride(profile, overrides, now.toEpochMilli())

        val schedule = profile.schedule?.takeUnless { it.isAlwaysAllowed }
        if (schedule != null && scheduleOverrideUntil == null && !isAllowedAt(schedule, now)) {
            return outsideSchedule(profile, schedule, now, used, overrides)
        }

        val limit = quotaLimitSeconds(profile, localDate(now).toEpochDay(), overrides)
        if (limit != null && used >= limit) return SessionState.TimeUp
        val quotaRemaining = limit?.let { it - used }

        val scheduleRemaining = schedule?.let { secondsUntilScheduleEnd(it, now, scheduleOverrideUntil) }

        val remaining = listOfNotNull(quotaRemaining, scheduleRemaining).minOrNull()
        return if (remaining != null && remaining <= WARNING_SECONDS) {
            SessionState.Warning(remaining)
        } else {
            SessionState.Ok(remaining)
        }
    }

    /** Playback lock, time up and outside schedule all keep the guard covering other apps (SPEC 4). */
    fun guardShouldEnforce(state: LockState): Boolean =
        state.playbackLocked ||
            state.session is SessionState.TimeUp ||
            state.session is SessionState.OutsideSchedule

    // ---- Playback lock (SPEC section 4) ------------------------------------------------------

    /** Starting a video or a playlist locks it, in both kinds of profile (see [PlaybackLock]). */
    fun lockPlayback(profile: Profile, videoId: String, playlistId: String? = null): PlaybackLock =
        PlaybackLock(
            profileId = profile.id,
            unlock = if (profile.isChild) UnlockMethod.PARENT_PIN else UnlockMethod.EXIT_CHALLENGE,
            videoId = videoId,
            playlistId = playlistId,
            sinceEpochMs = nowEpochMs(),
        )

    /**
     * The current video reached its end (owner's decision: a natural end releases the lock).
     *
     * - A child's playlist that continues with [nextVideoId] keeps the child locked: the lock moves to
     *   that video, so the countdown to it cannot be used to leave. After the last video of the
     *   playlist, or at the end of a single video, the lock ends and the child goes back to the child
     *   home (still inside the child profile, its allowlist and its quota).
     * - The adult's lock is per video (SPEC: leaving *before the video ends* needs the challenge): it
     *   ends with every video, [nextVideoId] or not. The next playlist video locks itself again when
     *   it starts ([lockPlayback]).
     * - Only an end the video reached by itself counts ([played], see [PlayedPart.endedByItself]): a
     *   video skipped to its end stays locked, so its end card still asks for the PIN or the challenge
     *   (a child's playlist still moves on: the child stays locked on the next video anyway). Without a
     *   measurement ([played] null) the end counts.
     */
    fun onVideoEnded(lock: PlaybackLock, nextVideoId: String? = null, played: PlayedPart? = null): PlaybackLock? = when {
        nextVideoId != null && lock.unlock == UnlockMethod.PARENT_PIN -> lock.copy(videoId = nextVideoId)
        played != null && !played.endedByItself -> lock
        else -> null
    }

    /**
     * The locked video can no longer play here: YouTube no longer serves it (removed, private, age or
     * region restricted, 360p only, the extractor broken), or the rules changed meanwhile (a parent
     * removed its channel from the allowlist). Nothing is left to watch, so the lock ends as at a
     * natural end, instead of keeping the viewer on an error screen that only the PIN or the exit
     * challenge would open. It is not a surrender: the viewer did not choose it.
     *
     * Not for failures the viewer can cause or that pass (no network, which a child can cause by pulling
     * a cable or by switching the TV on before its Wi-Fi is up; YouTube's rate limits and blocks): the
     * lock stays and the video is tried again. Not for "time up" and "outside schedule" either: those
     * screens only pause the video, which continues, still locked, once the parent adds time.
     */
    fun onVideoUnavailable(lock: PlaybackLock): PlaybackLock? = null

    /**
     * Call only after the parent PIN was verified (TV or phone). It ends a child's lock. The adult's
     * lock is returned unchanged: knowing the PIN must not skip the exit challenge.
     */
    fun unlockWithPin(lock: PlaybackLock): PlaybackLock? =
        if (lock.unlock == UnlockMethod.PARENT_PIN) null else lock

    /**
     * The phone's «فك قفل فيديو» in the parent section (reached only with the parent PIN): ends any
     * lock, the adult's included (the owner is the parent, and this is his way out when the remote
     * cannot do the challenge). For the adult it is still leaving before the end: record
     * [surrenderOf] first (owner's decision to confirm: the alternative is refusing the adult's lock).
     */
    fun unlockByParent(lock: PlaybackLock): PlaybackLock? = null

    /**
     * What an early end of [lock] other than the exit challenge leaves in the stats: the adult's lock
     * ended from the phone ([unlockByParent]), or left behind when another profile takes the TV at the
     * start, is a surrender (SPEC §4: leaving before the end "يُسجَّل استسلاماً"); a child's lock is not
     * (only the parent can end it, with the PIN). Store it before the lock is released.
     */
    fun surrenderOf(lock: PlaybackLock): SurrenderEvent? =
        if (lock.unlock == UnlockMethod.EXIT_CHALLENGE) SurrenderEvent(lock.profileId, nowEpochMs(), lock.videoId) else null

    /**
     * The adult leaves early: when the button was held for [AdultExitChallenge.HOLD_MILLIS] and
     * [typed] matches [phrase] (one of [AdultExitChallenge.phrases]), the lock ends and the returned
     * [SurrenderEvent] must be stored for the stats. Otherwise null, and the lock stays.
     */
    fun exitWithChallenge(lock: PlaybackLock, heldMillis: Long, phrase: String, typed: String): SurrenderEvent? {
        if (lock.unlock != UnlockMethod.EXIT_CHALLENGE) return null
        if (phrase !in AdultExitChallenge.phrases) return null
        if (!AdultExitChallenge.isHoldComplete(heldMillis) || !AdultExitChallenge.matches(phrase, typed)) return null
        return SurrenderEvent(lock.profileId, nowEpochMs(), lock.videoId)
    }

    // ---- Parent grants ---------------------------------------------------------------------

    /**
     * Gives [extraMinutes] more minutes from now: if the profile already went past its limit
     * (e.g. counted while the app was closing), the overshoot is covered too, so the child really
     * gets [extraMinutes].
     */
    fun grantExtraTime(profile: Profile, usedTodaySeconds: Long, overrides: Overrides, extraMinutes: Int): QuotaOverride {
        require(extraMinutes > 0) { "extraMinutes must be > 0, was $extraMinutes" }
        val today = today()
        val extraSeconds = extraMinutes * 60L
        val limit = quotaLimitSeconds(profile, today, overrides)
            ?: return QuotaOverride(profile.id, today, extraSeconds = extraSeconds) // no limit: nothing to cover
        val overshoot = (usedTodaySeconds.coerceAtLeast(0) - limit).coerceAtLeast(0)
        return QuotaOverride(profile.id, today, extraSeconds = saturatedAdd(overshoot, extraSeconds))
    }

    fun grantUnlimitedToday(profile: Profile): QuotaOverride =
        QuotaOverride(profile.id, today(), unlimited = true)

    fun grantScheduleOverride(profile: Profile, minutes: Int): ScheduleOverride {
        require(minutes > 0) { "minutes must be > 0, was $minutes" }
        return ScheduleOverride(profile.id, nowEpochMs() + minutes * 60_000L)
    }

    /**
     * Lifts the schedule for the rest of the local day: until the next local midnight, but never
     * past the end of the current (or, inside the window, the coming) closed period. Granted at
     * 00:30 with a 07:00–22:00 window it ends at 07:00, so it cannot lift that evening's bedtime.
     */
    fun grantScheduleOverrideUntilEndOfDay(profile: Profile): ScheduleOverride {
        val now = clock.instant()
        val midnight = startOfNextDay(now)
        val schedule = profile.schedule?.takeUnless { it.isAlwaysAllowed }
        val reopens = schedule?.let {
            val closes = if (isAllowedAt(it, now)) nextScheduleChange(it, now) else now
            closes?.let { c -> nextScheduleChange(it, c) }
        }
        val until = if (reopens != null && reopens.isBefore(midnight)) reopens else midnight
        return ScheduleOverride(profile.id, until.toEpochMilli())
    }

    /**
     * One parent unlock (PIN on the TV, or "فتح انتهى الوقت" on the phone) that works whether the
     * profile is stopped by its quota, its schedule, or both: returns the grants (possibly none)
     * after which it can watch for at least [minutes] from now. Store them with `overrides + result`.
     */
    fun grantUnlock(profile: Profile, usedTodaySeconds: Long, overrides: Overrides, minutes: Int): Overrides {
        require(minutes > 0) { "minutes must be > 0, was $minutes" }
        val now = clock.instant()
        val nowMs = now.toEpochMilli()
        val seconds = minutes * 60L
        var grants = Overrides.NONE

        val today = localDate(now).toEpochDay()
        val limit = quotaLimitSeconds(profile, today, overrides)
        if (limit != null) {
            val missing = saturatedAdd(usedTodaySeconds.coerceAtLeast(0) - limit, seconds)
            if (missing > 0) grants += QuotaOverride(profile.id, today, extraSeconds = missing)
        }

        val schedule = profile.schedule?.takeUnless { it.isAlwaysAllowed }
        if (schedule != null) {
            val overrideUntil = activeScheduleOverride(profile, overrides, nowMs)
            val open = overrideUntil != null || isAllowedAt(schedule, now)
            val left = if (open) secondsUntilScheduleEnd(schedule, now, overrideUntil) else 0L
            if (left != null && left < seconds) grants += ScheduleOverride(profile.id, nowMs + seconds * 1000)
        }
        return grants
    }

    /**
     * The parent's "for the rest of today" (the TV's PIN or the phone's "فتح انتهى الوقت" without
     * minutes), whatever stops the profile: today's quota is lifted ([grantUnlimitedToday]) and, when
     * the schedule keeps the profile from watching right now, the schedule too, until the end of the
     * local day ([grantScheduleOverrideUntilEndOfDay]). A bedtime that is still to come today stays.
     * Returns only the grants that change something; store them with `overrides + result`.
     */
    fun grantRestOfToday(profile: Profile, overrides: Overrides): Overrides {
        val now = clock.instant()
        val today = localDate(now).toEpochDay()
        var grants = Overrides.NONE
        if (quotaLimitSeconds(profile, today, overrides) != null) grants += QuotaOverride(profile.id, today, unlimited = true)
        val schedule = profile.schedule?.takeUnless { it.isAlwaysAllowed }
        if (schedule != null &&
            activeScheduleOverride(profile, overrides, now.toEpochMilli()) == null &&
            !isAllowedAt(schedule, now)
        ) {
            grants += grantScheduleOverrideUntilEndOfDay(profile)
        }
        return grants
    }

    // ---- Helpers ---------------------------------------------------------------------------

    @Volatile
    private var keywordMatcher: KeywordMatcher? = null

    /** Keywords are normalized once per blocklist, not once per item of a list. */
    private fun hasBlockedKeyword(profile: Profile, title: String): Boolean {
        val keywords = profile.blocklist.titleKeywords
        if (keywords.isEmpty()) return false
        val matcher = keywordMatcher?.takeIf { it.keywords == keywords }
            ?: KeywordMatcher(keywords).also { keywordMatcher = it }
        return matcher.firstMatch(title) != null
    }

    private fun activeScheduleOverride(profile: Profile, overrides: Overrides, nowMs: Long): Long? =
        overrides.schedule
            .filter { it.profileId == profile.id && it.untilEpochMs > nowMs }
            .maxOfOrNull { it.untilEpochMs }

    /** Seconds the profile may watch on [epochDay], or null when unlimited (no quota, or an unlimited grant). */
    private fun quotaLimitSeconds(profile: Profile, epochDay: Long, overrides: Overrides): Long? {
        val quota = profile.dailyQuotaMinutes ?: return null
        val grants = overrides.quota.filter { it.profileId == profile.id && it.epochDay == epochDay }
        if (grants.any { it.unlimited }) return null
        return grants.fold(quota * 60L) { sum, grant -> saturatedAdd(sum, grant.extraSeconds) }
    }

    /**
     * Outside the window. The announced opening is when watching really becomes possible: nothing
     * is watched while the window is closed, so if today's quota is already used up, a window that
     * reopens later today would only show "time up"; the quota restarts at local midnight.
     */
    private fun outsideSchedule(
        profile: Profile,
        schedule: Schedule,
        now: Instant,
        used: Long,
        overrides: Overrides,
    ): SessionState {
        val today = localDate(now)
        var opening = nextScheduleChange(schedule, now) ?: return SessionState.TimeUp
        if (localDate(opening) == today) {
            val limit = quotaLimitSeconds(profile, today.toEpochDay(), overrides)
            if (limit != null && used >= limit) {
                val midnight = startOfNextDay(now)
                opening = if (isAllowedAt(schedule, midnight)) midnight else nextScheduleChange(schedule, midnight) ?: return SessionState.TimeUp
            }
        }
        val openingDay = localDate(opening)
        if (openingDay != today) {
            // A fresh day starts at 0 seconds used; only a quota of 0 keeps it closed.
            val limit = quotaLimitSeconds(profile, openingDay.toEpochDay(), overrides)
            if (limit != null && limit <= 0) return SessionState.TimeUp
        }
        return SessionState.OutsideSchedule(opening.toEpochMilli())
    }

    /**
     * Seconds (rounded up, so never 0 while allowed) until the schedule stops the session, or null
     * when it does not stop in the foreseeable future (e.g. an "until further notice" override).
     */
    private fun secondsUntilScheduleEnd(schedule: Schedule, now: Instant, overrideUntilMs: Long?): Long? {
        val end = if (overrideUntilMs == null) {
            nextScheduleChange(schedule, now)
        } else {
            val horizon = now.toEpochMilli().coerceIn(0, Long.MAX_VALUE - FAR_FUTURE_MS) + FAR_FUTURE_MS
            if (overrideUntilMs > horizon) return null
            val overrideEnd = Instant.ofEpochMilli(overrideUntilMs)
            if (isAllowedAt(schedule, overrideEnd)) nextScheduleChange(schedule, overrideEnd) else overrideEnd
        } ?: return null
        val millis = end.toEpochMilli() - now.toEpochMilli()
        return (millis + 999) / 1000
    }

    private fun isAllowedAt(schedule: Schedule, instant: Instant): Boolean {
        val wall = instant.atZone(clock.zone)
        return schedule.allows(wall.hour * 60 + wall.minute)
    }

    /**
     * The first instant strictly after [from] at which [isAllowedAt] gives a different answer, or
     * null if it never changes (an always-open schedule).
     *
     * Between two offset transitions of the zone the wall clock runs evenly, so the answer can only
     * change when the wall time reaches the window's opening or closing minute. At a transition the
     * wall clock jumps (forward over a gap, back over a repeated hour), so the answer is checked
     * right there too. LocalDateTime.atZone is not used on purpose: it moves a wall time that falls
     * in a gap forward by the gap's length, and always picks the first pass of a repeated hour.
     */
    private fun nextScheduleChange(schedule: Schedule, from: Instant): Instant? {
        if (schedule.isAlwaysAllowed) return null
        val rules = clock.zone.rules
        val current = isAllowedAt(schedule, from)
        val boundary = if (current) schedule.allowedUntilMinute else schedule.allowedFromMinute
        var t = from
        repeat(MAX_ZONE_TRANSITIONS) {
            val offset = rules.getOffset(t)
            val wall = LocalDateTime.ofInstant(t, offset)
            var next = wallTime(wall.toLocalDate(), boundary)
            if (!next.isAfter(wall)) next = wallTime(wall.toLocalDate().plusDays(1), boundary)
            val candidate = next.toInstant(offset)
            val transition = rules.nextTransition(t)?.instant
            if (transition == null || candidate.isBefore(transition)) return candidate
            if (isAllowedAt(schedule, transition) != current) return transition
            t = transition
        }
        return null
    }

    /** [minuteOfDay] on [date]; 1440 is the midnight that ends the day. */
    private fun wallTime(date: LocalDate, minuteOfDay: Int): LocalDateTime =
        if (minuteOfDay >= MINUTES_PER_DAY) {
            date.plusDays(1).atStartOfDay()
        } else {
            date.atTime(minuteOfDay / 60, minuteOfDay % 60)
        }

    private fun localDate(instant: Instant): LocalDate = instant.atZone(clock.zone).toLocalDate()

    /** When the quota restarts: the next local midnight (just after the gap if midnight is skipped). */
    private fun startOfNextDay(instant: Instant): Instant =
        localDate(instant).plusDays(1).atStartOfDay(clock.zone).toInstant()

    /** Non-negative [b] added to [a] without wrapping past Long.MAX_VALUE (stored grants can hold any value). */
    private fun saturatedAdd(a: Long, b: Long): Long = if (a > Long.MAX_VALUE - b) Long.MAX_VALUE else a + b

    companion object {
        const val SHORTS_MAX_SECONDS = 180L
        const val WARNING_SECONDS = 5 * 60L

        /** A schedule override ending further away than this is treated as "until further notice". */
        private const val FAR_FUTURE_MS = 100L * 366 * 24 * 3_600_000

        /** Far more than any zone has between two openings; only guards against broken zone data. */
        private const val MAX_ZONE_TRANSITIONS = 64
    }
}
