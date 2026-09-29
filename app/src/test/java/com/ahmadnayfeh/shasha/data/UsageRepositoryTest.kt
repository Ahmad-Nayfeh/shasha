package com.ahmadnayfeh.shasha.data

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ahmadnayfeh.shasha.data.db.ShashaDatabase
import com.ahmadnayfeh.shasha.policy.ChannelUsage
import com.ahmadnayfeh.shasha.policy.SurrenderEvent
import com.ahmadnayfeh.shasha.policy.UsageKind
import com.ahmadnayfeh.shasha.policy.UsageRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class UsageRepositoryTest {
    private lateinit var db: ShashaDatabase
    private lateinit var usage: UsageRepository

    private val zone = ZoneId.of("Asia/Riyadh")
    private val today = LocalDate.parse("2026-09-28").toEpochDay()

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        usage = UsageRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun foregroundTime_addsUpPerProfileAndDay_inOneRow() = runBlocking<Unit> {
        repeat(4) { usage.addForeground("kid", today, 15) }
        usage.addForeground("kid", today, 7)
        usage.addForeground("kid", today - 1, 100)
        usage.addForeground("adult", today, 30)
        usage.addForeground("kid", today, 0) // ignored
        usage.addForeground("kid", today, -20) // ignored

        assertThat(usage.foregroundSeconds("kid", today)).isEqualTo(67)
        assertThat(usage.foregroundSeconds("kid", today - 1)).isEqualTo(100)
        assertThat(usage.foregroundSeconds("adult", today)).isEqualTo(30)
        assertThat(usage.foregroundSeconds("nobody", today)).isEqualTo(0)
        assertThat(usage.records("kid", today)).containsExactly(UsageRecord("kid", today, UsageKind.FOREGROUND, 67))
    }

    @Test
    fun playbackTime_isKeptPerChannel_withTheNewestName_andNeverCountsForTheQuota() = runBlocking<Unit> {
        usage.addPlayback("kid", today, 60, "UCa", "Old name")
        usage.addPlayback("kid", today, 30, "UCa", "New name")
        usage.addPlayback("kid", today, 10, "UCa", null) // the name stays
        usage.addPlayback("kid", today, 45, "UCb", "B")
        usage.addPlayback("kid", today, 5, null, "ignored name")

        assertThat(usage.records("kid", today)).containsExactly(
            UsageRecord("kid", today, UsageKind.PLAYBACK, 100, "UCa", "New name"),
            UsageRecord("kid", today, UsageKind.PLAYBACK, 45, "UCb", "B"),
            UsageRecord("kid", today, UsageKind.PLAYBACK, 5, null, null),
        )
        assertThat(usage.foregroundSeconds("kid", today)).isEqualTo(0)
    }

    @Test
    fun summary_givesTheStatsScreenItsNumbers() = runBlocking<Unit> {
        usage.addForeground("kid", today, 3_600)
        usage.addForeground("kid", today - 3, 1_800)
        usage.addForeground("kid", today - 8, 2_700) // the week before
        usage.addPlayback("kid", today, 1_200, "UCa", "A")
        usage.addPlayback("kid", today - 2, 3_000, "UCb", "B")
        usage.recordSurrender(SurrenderEvent("kid", epochMs = startOfDay(today) + HOUR_MS, videoId = "v"))
        usage.recordSurrender(SurrenderEvent("kid", epochMs = startOfDay(today - 20), videoId = "old"))
        usage.addForeground("adult", today, 9_999)

        val summary = usage.summary("kid", today, zone)
        assertThat(summary.dailyMinutes).containsExactly(0L, 0L, 0L, 30L, 0L, 0L, 60L).inOrder()
        assertThat(summary.thisWeekMinutes).isEqualTo(90)
        assertThat(summary.previousWeekMinutes).isEqualTo(45)
        assertThat(summary.changePercent).isEqualTo(100)
        assertThat(summary.topChannels).containsExactly(ChannelUsage("UCb", "B", 3_000), ChannelUsage("UCa", "A", 1_200)).inOrder()
        assertThat(summary.surrendersLast7Days).isEqualTo(1)
    }

    @Test
    fun prune_forgetsWhatTheStatsNoLongerNeed() = runBlocking<Unit> {
        // Watched every day for the last 92 days.
        for (day in today - UsageRepository.KEEP_DAYS - 1..today) usage.addForeground("kid", day, 60)
        usage.recordSurrender(SurrenderEvent("kid", startOfDay(today - UsageRepository.KEEP_DAYS) - 1, "old"))
        usage.recordSurrender(SurrenderEvent("kid", startOfDay(today - UsageRepository.KEEP_DAYS), "kept"))

        usage.prune(today, zone)

        val days = usage.records("kid", 0).map { it.epochDay }
        assertThat(days.min()).isEqualTo(today - UsageRepository.KEEP_DAYS)
        assertThat(days).hasSize(UsageRepository.KEEP_DAYS.toInt() + 1)
        assertThat(usage.surrenders("kid", 0).map { it.videoId }).containsExactly("kept")
    }

    @Test
    fun prune_keepsTheMostRecentDaysWithUsage_evenWhenTheyAreOld() = runBlocking<Unit> {
        // A TV that was hardly used: a day 200 days ago and one today are both kept (fewer than 90 days of data).
        usage.addForeground("kid", today - 200, 60)
        usage.addForeground("kid", today, 60)
        usage.prune(today, zone)
        assertThat(usage.records("kid", 0).map { it.epochDay }).containsExactly(today - 200, today)
    }

    @Test
    fun theClockSetFarAhead_doesNotWipeTodaysQuotaTheStatsOrTheSurrenders() = runBlocking<Unit> {
        for (day in today - 20..today) usage.addForeground("kid", day, 600)
        usage.addPlayback("kid", today, 300, "UCa", "A")
        usage.recordSurrender(SurrenderEvent("adult", startOfDay(today) + HOUR_MS, "v"))

        // The child sets the TV 100 days ahead: the daily cleanup runs on that day, and a tick of usage lands there.
        usage.addForeground("kid", today + 100, 15)
        usage.prune(today + 100, zone)

        // Back on the real date: today's time still counts toward the quota, the history and the surrender stay.
        assertThat(usage.foregroundSeconds("kid", today)).isEqualTo(600)
        assertThat(usage.records("kid", today - 20).map { it.epochDay }.distinct()).hasSize(22)
        assertThat(usage.surrenders("adult", 0)).hasSize(1)
    }

    @Test
    fun changes_emitOnEveryWrite() = runBlocking<Unit> {
        val seen = mutableListOf<Unit>()
        val watching = launch(start = CoroutineStart.UNDISPATCHED) { usage.changes.take(2).toList(seen) }
        // The first emission is the start; the next one is the write.
        withTimeout(5_000) {
            while (seen.isEmpty()) kotlinx.coroutines.delay(10)
            usage.addForeground("kid", today, 15)
            watching.join()
        }
        assertThat(seen).hasSize(2)
    }

    private fun startOfDay(epochDay: Long) = LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toInstant().toEpochMilli()
}
