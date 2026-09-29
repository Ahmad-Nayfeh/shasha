package com.ahmadnayfeh.shasha.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ahmadnayfeh.shasha.youtube.VideoItem

/** A channel the owner follows (SPEC §2: local subscriptions, no account). */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    /** Unknown for channels imported from Takeout until their page is opened. */
    val avatarUrl: String?,
    val addedAtMs: Long,
    /** Last feed sync attempt of this channel; null = never synced. */
    val lastSyncAtMs: Long? = null,
    /** Why that attempt failed (e.g. "NETWORK: SocketTimeoutException"); null when it succeeded. */
    val lastSyncError: String? = null,
)

/** One upload of a subscribed channel, as the home feed shows it. */
@Entity(tableName = "feed_items", indices = [Index("channelId"), Index("publishedAtMs")])
data class FeedItemEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedAtMs: Long,
    val thumbnailUrl: String?,
    /** Null when unknown: RSS has no durations. */
    val durationSeconds: Long?,
    /** The source's short-form flag ("/shorts/" link or extractor flag); PolicyEngine.isShort adds the duration rule. */
    val isShort: Boolean,
    val isLive: Boolean,
    val url: String,
    val fetchedAtMs: Long,
    /** [publishedAtMs] came from a rounded "3 days ago" (the Videos-tab fallback): the upload may be older. */
    val publishedAtApproximate: Boolean = false,
) {
    /** The same card model search and channel pages use. */
    fun toVideoItem() = VideoItem(
        videoId = videoId,
        title = title,
        channelId = channelId,
        channelName = channelName,
        durationSeconds = durationSeconds,
        publishedAtEpochMs = publishedAtMs,
        thumbnailUrl = thumbnailUrl,
        viewCount = null,
        isLive = isLive,
        isShortForm = isShort,
        url = url,
        publishedAtIsApproximate = publishedAtApproximate,
    )
}

/** Where a profile stopped in a video (SPEC §1: continue where you left off). */
@Entity(tableName = "watch_progress", primaryKeys = ["profileId", "videoId"])
data class WatchProgressEntity(
    val profileId: String,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAtMs: Long,
) {
    /** Watched to the end (or marked watched): the next start is from the beginning. */
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs >= minOf(durationMs - FINISHED_MARGIN_MS, durationMs * 95 / 100)

    /** The position to continue from; 0 when finished or barely started. */
    val resumePositionMs: Long
        get() = if (isFinished || positionMs < MIN_RESUME_MS) 0 else positionMs

    private companion object {
        const val FINISHED_MARGIN_MS = 10_000L
        const val MIN_RESUME_MS = 5_000L
    }
}

/** One row per video and profile; watching again moves it to the top. */
@Entity(tableName = "history", primaryKeys = ["profileId", "videoId"], indices = [Index("profileId", "watchedAtMs")])
data class HistoryEntity(
    val profileId: String,
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val watchedAtMs: Long,
)

@Entity(tableName = "watch_later", primaryKeys = ["profileId", "videoId"], indices = [Index("profileId", "addedAtMs")])
data class WatchLaterEntity(
    val profileId: String,
    val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedAtMs: Long,
)
