package com.ahmadnayfeh.shasha.policy

import com.ahmadnayfeh.shasha.data.ADULT_PROFILE_ID
import com.ahmadnayfeh.shasha.data.OverridesStore
import com.ahmadnayfeh.shasha.data.UsageLedger
import com.ahmadnayfeh.shasha.youtube.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Saudi Arabia: UTC+3 all year. */
val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")

fun instantAt(localDateTime: String, zone: ZoneId = RIYADH): Instant = LocalDateTime.parse(localDateTime).atZone(zone).toInstant()

fun epochMsAt(localDateTime: String): Long = instantAt(localDateTime).toEpochMilli()

fun epochDayOf(date: String): Long = LocalDate.parse(date).toEpochDay()

/** A clock tests set and move by hand. */
class MutableClock(@Volatile private var now: Instant, private val zone: ZoneId = RIYADH) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun set(localDateTime: String) {
        now = instantAt(localDateTime, zone)
    }

    fun advanceMillis(ms: Long) {
        now = now.plusMillis(ms)
    }
}

/** Wall time that runs with the coroutine test scheduler's virtual time, starting at [start]. */
class SchedulerClock(private val scheduler: TestCoroutineScheduler, private val start: Instant, private val zone: ZoneId = RIYADH) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = SchedulerClock(scheduler, start, zone)

    override fun instant(): Instant = start.plusMillis(scheduler.currentTime)
}

// Ids shaped like YouTube's ("UC" + 22 characters).
const val ALLOWED_CHANNEL = "UCallowedallowedallowed0"
const val OTHER_CHANNEL = "UCotherotherotherother00"
const val BLOCKED_CHANNEL = "UCblockedblockedblocked0"
const val ALLOWED_PLAYLIST = "PLallowedallowedallowed0"
const val OTHER_PLAYLIST = "PLotherotherotherother00"

fun owner() = Profile.newAdult("الكبار", id = ADULT_PROFILE_ID)

/** Default child rules (60 min, 07:00–22:00, shorts hidden) with [ALLOWED_CHANNEL] and [ALLOWED_PLAYLIST] allowlisted. */
fun kid(id: String = "kid", name: String = "سارة") = Profile.newChild(name, id = id).copy(
    allowlist = Allowlist(
        channels = listOf(ListedChannel(ALLOWED_CHANNEL, "قناة مسموحة")),
        playlists = listOf(ListedPlaylist(ALLOWED_PLAYLIST, "قائمة مسموحة")),
    ),
)

fun clip(
    id: String,
    channelId: String? = ALLOWED_CHANNEL,
    title: String = "فيديو $id",
    durationSeconds: Long? = 600,
    short: Boolean = false,
    live: Boolean = false,
    url: String = if (short) "https://www.youtube.com/shorts/$id" else "https://www.youtube.com/watch?v=$id",
) = VideoItem(
    videoId = id,
    title = title,
    channelId = channelId,
    channelName = channelId?.let { "اسم $it" },
    durationSeconds = durationSeconds,
    publishedAtEpochMs = null,
    thumbnailUrl = null,
    viewCount = null,
    isLive = live,
    isShortForm = short,
    url = url,
)

/** Usage kept in maps; [changes] emits on every write, like Room's invalidation. */
class FakeUsageLedger : UsageLedger {
    val foreground = mutableMapOf<Pair<String, Long>, Long>()
    val playback = mutableListOf<PlaybackRow>()
    private val version = MutableStateFlow(0)

    /** Throws from every write while set. */
    var failWrites = false

    data class PlaybackRow(val profileId: String, val epochDay: Long, val seconds: Long, val channelId: String?, val channelName: String?)

    override val changes: Flow<Unit> = version.map { }

    override suspend fun foregroundSeconds(profileId: String, epochDay: Long): Long = foreground[profileId to epochDay] ?: 0

    override suspend fun addForeground(profileId: String, epochDay: Long, seconds: Long) {
        check(!failWrites) { "disk full" }
        foreground[profileId to epochDay] = foregroundSeconds(profileId, epochDay) + seconds
        version.value++
    }

    override suspend fun addPlayback(profileId: String, epochDay: Long, seconds: Long, channelId: String?, channelName: String?) {
        check(!failWrites) { "disk full" }
        playback += PlaybackRow(profileId, epochDay, seconds, channelId, channelName)
        version.value++
    }

    /** Sets the recorded FOREGROUND time directly, as if the tracker had written it. */
    fun set(profileId: String, epochDay: Long, seconds: Long) {
        foreground[profileId to epochDay] = seconds
        version.value++
    }
}

class FakeOverridesStore : OverridesStore {
    val stored = MutableStateFlow(Overrides.NONE)

    override val overrides: Flow<Overrides> = stored

    override suspend fun current(): Overrides = stored.value

    override suspend fun add(grants: Overrides, today: Long, nowEpochMs: Long) {
        stored.value = (stored.value + grants).pruned(today, nowEpochMs)
    }
}
