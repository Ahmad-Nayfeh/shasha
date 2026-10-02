package com.ahmadnayfeh.shasha.ui.settings

import com.ahmadnayfeh.shasha.data.Behavior
import com.ahmadnayfeh.shasha.data.SyncHealth
import com.ahmadnayfeh.shasha.lock.ProtectionStatus
import com.ahmadnayfeh.shasha.lock.ReturnTime
import com.ahmadnayfeh.shasha.lock.YouTubeAppState
import com.ahmadnayfeh.shasha.policy.Allowlist
import com.ahmadnayfeh.shasha.policy.Blocklist
import com.ahmadnayfeh.shasha.policy.DayUsage
import com.ahmadnayfeh.shasha.policy.ListedChannel
import com.ahmadnayfeh.shasha.policy.ListedPlaylist
import com.ahmadnayfeh.shasha.policy.Profile
import com.ahmadnayfeh.shasha.policy.Schedule
import com.ahmadnayfeh.shasha.policy.UsageSummary
import com.ahmadnayfeh.shasha.search.VoiceAvailability
import com.ahmadnayfeh.shasha.sponsorblock.SponsorCategory
import com.ahmadnayfeh.shasha.ui.settings.remote.PairingStatus
import com.ahmadnayfeh.shasha.youtube.SourceErrorKind
import com.ahmadnayfeh.shasha.youtube.SourceException

/** A household of three for the settings' tests: the owner, Sara (4 channels, 2 playlists allowed) and Omar. */
internal object SettingsHubFixtures {
    const val NOW = 1_790_700_000_000L // 2026-09-29, early evening
    private const val MINUTE = 60_000L
    private const val DAY_SECONDS = 86_400L

    private val household = Blocklist(
        channels = listOf(ListedChannel("UCblock000000000000000001", "Prank Masters"), ListedChannel("UCblock000000000000000002", "قناة المقالب")),
        titleKeywords = listOf("مقلب", "تحدي", "rage"),
    )

    val adult = Profile.newAdult("أحمد", id = "owner").copy(blocklist = household)
    val sara = Profile.newChild("سارة", id = "sara", avatar = "rocket").copy(
        dailyQuotaMinutes = 60,
        schedule = Schedule(7 * 60, 20 * 60),
        blocklist = household,
        allowlist = Allowlist(
            channels = listOf(
                ListedChannel("UCkids00000000000000000a1", "عالم الطبيعة"),
                ListedChannel("UCkids00000000000000000a2", "Space Lab"),
                ListedChannel("UCkids00000000000000000a3", "حكايات قبل النوم"),
                ListedChannel("UCkids00000000000000000a4", "Math Corner"),
            ),
            playlists = listOf(
                ListedPlaylist("PLkids000000000000000001", "تعلم الحروف مع أرنوب"),
                ListedPlaylist("PLkids000000000000000002", "تجارب علمية للصغار"),
            ),
        ),
    )
    val omar = Profile.newChild("عمر", id = "omar", avatar = "bear").copy(dailyQuotaMinutes = 45, blocklist = household)
    val profiles = listOf(adult, sara, omar)

    private fun week(profileId: String, todayMinutes: Long, weekMinutes: Long, previousMinutes: Long, change: Int?): UsageSummary {
        val today = NOW / 1000 / DAY_SECONDS
        val days = (6 downTo 0).map { back -> DayUsage(today - back, if (back == 0) todayMinutes * 60 else weekMinutes * 60 / 7) }
        return UsageSummary(profileId, days, weekMinutes * 60, previousMinutes * 60, change, emptyList(), 0)
    }

    val usage = mapOf(
        adult.id to week(adult.id, todayMinutes = 55, weekMinutes = 9 * 60 + 15, previousMinutes = 11 * 60 + 50, change = -22),
        sara.id to week(sara.id, todayMinutes = 40, weekMinutes = 5 * 60 + 10, previousMinutes = 4 * 60 + 37, change = 12),
        omar.id to week(omar.id, todayMinutes = 0, weekMinutes = 2 * 60 + 5, previousMinutes = 0, change = null),
    )

    val protectionOk = ProtectionStatus(overlay = true, usageAccess = true, installPackages = true, guardRunning = true, youtube = YouTubeAppState.DISABLED)

    /** adb-setup was never run on a new TV: the guard's permissions are missing, YouTube and the manual clock are on. */
    val protectionMissing = ProtectionStatus(
        overlay = true,
        usageAccess = false,
        installPackages = false,
        guardRunning = false,
        youtube = YouTubeAppState.ENABLED,
        autoTime = false,
    )

    val lastReturn = ReturnTime(atEpochMs = NOW - 3 * MINUTE, fromPackage = "com.google.android.youtube.tv", detectMs = 420, coverMs = 460, backMs = 1_380)

    val remote = RemoteSummary(enabled = true, status = PairingStatus.RUNNING, address = "192.168.1.23:8765")

    val syncHealth = SyncHealth(
        channels = 20,
        attempted = 20,
        failed = 2,
        lastAttemptAtMs = NOW - 12 * MINUTE,
        lastError = SourceException(SourceErrorKind.NETWORK, "SocketTimeoutException", null, null),
    )

    fun state(unlocked: Boolean = true, behavior: Behavior = Behavior.DEFAULT) = SettingsHubState(
        versions = AppVersions("0.1.0", 1, "v0.26.5"),
        unlocked = unlocked,
        profiles = profiles,
        usage = usage,
        subscriptionCount = 20,
        sponsorCategories = SponsorCategory.DEFAULTS - SponsorCategory.OUTRO + SponsorCategory.FILLER,
        maxQuality = 1080,
        protection = protectionOk,
        lastReturn = lastReturn,
        remote = remote,
        syncHealth = syncHealth,
        device = DeviceFacts("TCL", "Smart TV Pro", "12", 31),
        voice = VoiceAvailability(systemScreen = "com.google.android.katniss", service = "com.google.android.googlequicksearchbox"),
        nowMs = NOW,
        behavior = behavior,
        remotePort = 8765,
    )
}
