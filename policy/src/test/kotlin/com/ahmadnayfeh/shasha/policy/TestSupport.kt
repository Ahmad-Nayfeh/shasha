package com.ahmadnayfeh.shasha.policy

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock tests can move. */
class MutableClock(private var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun set(instant: Instant) {
        now = instant
    }
}

/** Saudi Arabia: UTC+3 all year, no DST. */
val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")
val UTC: ZoneId = ZoneOffset.UTC

/** [localDateTime] like "2026-09-28T12:00" read in [zone]. */
fun clockAt(localDateTime: String, zone: ZoneId = RIYADH) =
    MutableClock(LocalDateTime.parse(localDateTime).atZone(zone).toInstant(), zone)

fun epochMsAt(localDateTime: String, zone: ZoneId = RIYADH): Long =
    LocalDateTime.parse(localDateTime).atZone(zone).toInstant().toEpochMilli()

fun epochDay(date: String): Long = LocalDate.parse(date).toEpochDay()

fun engineAt(localDateTime: String, zone: ZoneId = RIYADH) = PolicyEngine(clockAt(localDateTime, zone))

const val ALLOWED_CHANNEL = "UC_allowed"
const val OTHER_CHANNEL = "UC_other"
const val BLOCKED_CHANNEL = "UC_blocked"
const val ALLOWED_PLAYLIST = "PL_allowed"
const val OTHER_PLAYLIST = "PL_other"

fun adult(id: String = "adult") = Profile.newAdult("أحمد", id = id)

/** Child with the default rules and an allowlist of [ALLOWED_CHANNEL] and [ALLOWED_PLAYLIST]. */
fun child(id: String = "kid") = Profile.newChild("سارة", id = id).copy(
    allowlist = Allowlist(
        channels = listOf(ListedChannel(ALLOWED_CHANNEL, "قناة مسموحة")),
        playlists = listOf(ListedPlaylist(ALLOWED_PLAYLIST, "قائمة مسموحة")),
    ),
)

fun video(
    title: String = "فيديو عادي",
    channelId: String? = ALLOWED_CHANNEL,
    durationSeconds: Long? = 600,
    url: String = "https://www.youtube.com/watch?v=abc",
    isShortFormFlag: Boolean = false,
    viaPlaylistId: String? = null,
    isLive: Boolean = false,
    videoId: String = "abc",
) = Candidate(
    videoId = videoId,
    title = title,
    channelId = channelId,
    durationSeconds = durationSeconds,
    url = url,
    isShortFormFlag = isShortFormFlag,
    viaPlaylistId = viaPlaylistId,
    isLive = isLive,
)

fun Profile.withBlockedChannel(channelId: String = BLOCKED_CHANNEL) =
    copy(blocklist = blocklist.withChannel(ListedChannel(channelId, "محظورة")))

fun Profile.withBlockedKeywords(vararg keywords: String) =
    copy(blocklist = blocklist.copy(titleKeywords = keywords.toList()))

fun Profile.withoutLimits() = copy(dailyQuotaMinutes = null, schedule = null)

fun deny(reason: DenyReason): Decision = Decision.Deny(reason)
