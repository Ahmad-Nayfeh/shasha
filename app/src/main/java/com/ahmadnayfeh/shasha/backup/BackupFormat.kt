package com.ahmadnayfeh.shasha.backup

import com.ahmadnayfeh.shasha.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.data.db.SurrenderEventEntity
import com.ahmadnayfeh.shasha.data.db.UsageRecordEntity
import com.ahmadnayfeh.shasha.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.data.db.WatchProgressEntity
import com.ahmadnayfeh.shasha.policy.UsageKind
import kotlinx.serialization.Serializable

// The rows of the backup file, one class per table (field names are the file format: rename none of them).
// Each converts from and to its Room entity; `valid` rejects values no app state could hold.

@Serializable
internal data class SubscriptionRow(val channelId: String, val name: String, val avatarUrl: String? = null, val addedAtMs: Long = 0) {
    val valid: Boolean get() = channelId.isNotBlank() && addedAtMs >= 0

    // A restored channel counts as never synced: the next sync fetches it first.
    fun toEntity() = SubscriptionEntity(channelId, name.ifBlank { channelId }, avatarUrl, addedAtMs)

    companion object {
        fun of(e: SubscriptionEntity) = SubscriptionRow(e.channelId, e.name, e.avatarUrl, e.addedAtMs)
    }
}

/** A video in «شاهد لاحقاً» ([atMs]: when it was added) or in the history ([atMs]: when it was watched). */
@Serializable
internal data class SavedVideoRow(
    val profileId: String,
    val videoId: String,
    val title: String,
    val channelId: String? = null,
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val atMs: Long,
) {
    val valid: Boolean get() = profileId.isNotBlank() && videoId.isNotBlank() && atMs >= 0 && (durationSeconds ?: 0) >= 0

    fun toWatchLater() = WatchLaterEntity(profileId, videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, atMs)

    fun toHistory() = HistoryEntity(profileId, videoId, title, channelId, channelName, thumbnailUrl, durationSeconds, atMs)

    companion object {
        fun of(e: WatchLaterEntity) = SavedVideoRow(e.profileId, e.videoId, e.title, e.channelId, e.channelName, e.thumbnailUrl, e.durationSeconds, e.addedAtMs)

        fun of(e: HistoryEntity) = SavedVideoRow(e.profileId, e.videoId, e.title, e.channelId, e.channelName, e.thumbnailUrl, e.durationSeconds, e.watchedAtMs)
    }
}

@Serializable
internal data class ProgressRow(val profileId: String, val videoId: String, val positionMs: Long, val durationMs: Long, val updatedAtMs: Long) {
    val valid: Boolean get() = profileId.isNotBlank() && videoId.isNotBlank() && positionMs >= 0 && durationMs >= 0 && updatedAtMs >= 0

    fun toEntity() = WatchProgressEntity(profileId, videoId, positionMs, durationMs, updatedAtMs)

    companion object {
        fun of(e: WatchProgressEntity) = ProgressRow(e.profileId, e.videoId, e.positionMs, e.durationMs, e.updatedAtMs)
    }
}

/** [channelId] "" = time without a channel (see UsageRecordEntity). */
@Serializable
internal data class UsageRow(
    val profileId: String,
    val epochDay: Long,
    val kind: String,
    val channelId: String = "",
    val channelName: String? = null,
    val seconds: Long,
) {
    private val usageKind: UsageKind? get() = UsageKind.entries.firstOrNull { it.name == kind }

    val valid: Boolean get() = profileId.isNotBlank() && usageKind != null && seconds >= 0

    fun toEntity() = UsageRecordEntity(profileId, epochDay, usageKind!!, channelId, channelName, seconds)

    companion object {
        fun of(e: UsageRecordEntity) = UsageRow(e.profileId, e.epochDay, e.kind.name, e.channelId, e.channelName, e.seconds)
    }
}

@Serializable
internal data class SurrenderRow(val profileId: String, val epochMs: Long, val videoId: String) {
    val valid: Boolean get() = profileId.isNotBlank() && epochMs >= 0

    fun toEntity() = SurrenderEventEntity(profileId = profileId, epochMs = epochMs, videoId = videoId)

    companion object {
        fun of(e: SurrenderEventEntity) = SurrenderRow(e.profileId, e.epochMs, e.videoId)
    }
}
