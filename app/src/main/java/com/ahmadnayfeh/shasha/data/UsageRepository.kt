package com.ahmadnayfeh.shasha.data

import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.data.db.SurrenderEventEntity
import com.ahmadnayfeh.shasha.data.db.UsageRecordEntity
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import com.ahmadnayfeh.shasha.policy.UsageKind
import com.ahmadnayfeh.shasha.policy.UsageRecord
import com.ahmadnayfeh.shasha.policy.UsageStatsCalculator
import com.ahmadnayfeh.shasha.policy.UsageSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId

/** What the session and the usage tracker need from the recorded usage ([UsageRepository]; tests use a fake). */
interface UsageLedger {
    /** Emits when recorded usage may have changed (and once at the start). */
    val changes: Flow<Unit>

    /** FOREGROUND seconds of [epochDay]: what the daily quota counts. */
    suspend fun foregroundSeconds(profileId: String, epochDay: Long): Long

    suspend fun addForeground(profileId: String, epochDay: Long, seconds: Long)

    suspend fun addPlayback(profileId: String, epochDay: Long, seconds: Long, channelId: String?, channelName: String?)
}

/**
 * Watching time and surrenders (SPEC §3 stats, §4 surrenders), per profile and local day. Records are
 * added up per (profile, day, kind, channel), so a day is a handful of rows however often it is written.
 * Main-safe: Room runs the queries on its own threads.
 */
class UsageRepository(private val db: ShashaDatabase) : UsageLedger {
    private val usage = db.usage()
    private val surrenders = db.surrenders()

    override val changes: Flow<Unit> = db.invalidationTracker.createFlow(TABLE).map { }

    override suspend fun foregroundSeconds(profileId: String, epochDay: Long): Long =
        usage.total(profileId, epochDay, UsageKind.FOREGROUND)

    override suspend fun addForeground(profileId: String, epochDay: Long, seconds: Long) {
        if (seconds <= 0) return
        usage.add(profileId, epochDay, UsageKind.FOREGROUND, NO_CHANNEL, channelName = null, seconds)
    }

    /** Time a video played, for "most watched channels". [channelId] null = unknown (counted without a channel). */
    override suspend fun addPlayback(profileId: String, epochDay: Long, seconds: Long, channelId: String?, channelName: String?) {
        if (seconds <= 0) return
        usage.add(profileId, epochDay, UsageKind.PLAYBACK, channelId ?: NO_CHANNEL, channelName?.takeIf { channelId != null }, seconds)
    }

    /** [profileId]'s records from [sinceEpochDay] on, as the stats calculator takes them. */
    suspend fun records(profileId: String, sinceEpochDay: Long): List<UsageRecord> =
        usage.since(profileId, sinceEpochDay).map { it.toRecord() }

    suspend fun recordSurrender(event: SurrenderEvent) {
        surrenders.insert(SurrenderEventEntity(profileId = event.profileId, epochMs = event.epochMs, videoId = event.videoId))
    }

    suspend fun surrenders(profileId: String, sinceEpochMs: Long): List<SurrenderEvent> =
        surrenders.since(profileId, sinceEpochMs).map { SurrenderEvent(it.profileId, it.epochMs, it.videoId) }

    /** The stats screen's numbers for [profileId]: this week and the one before, ending [today]. */
    suspend fun summary(profileId: String, today: Long, zone: ZoneId): UsageSummary {
        val since = today - 2 * UsageStatsCalculator.DAYS + 1
        val sinceMs = LocalDate.ofEpochDay(since).atStartOfDay(zone).toInstant().toEpochMilli()
        return UsageStatsCalculator(zone).summary(profileId, today, records(profileId, since), surrenders(profileId, sinceMs))
    }

    /**
     * Forgets usage and surrenders older than [keepDays] days before [today] (the stats look back two weeks),
     * but only beyond the [keepDays] most recent days that have any usage. [today] is the TV's clock: set a
     * hundred days ahead for a moment, it must not wipe today's quota, the stats and the surrenders once the
     * clock is back. Each such jump costs at most one old day.
     */
    suspend fun prune(today: Long, zone: ZoneId, keepDays: Long = KEEP_DAYS) {
        val oldestKept = usage.dayWithNewer(keepDays.toInt() - 1) ?: return
        val before = minOf(today - keepDays, oldestKept)
        usage.deleteBefore(before)
        surrenders.deleteBefore(LocalDate.ofEpochDay(before).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    private fun UsageRecordEntity.toRecord() = UsageRecord(
        profileId = profileId,
        epochDay = epochDay,
        kind = kind,
        seconds = seconds,
        channelId = channelId.ifEmpty { null },
        channelName = channelName,
    )

    companion object {
        const val KEEP_DAYS = 90L
        private const val TABLE = "usage_records"

        /** The key column cannot be NULL (see UsageRecordEntity). */
        private const val NO_CHANNEL = ""
    }
}
