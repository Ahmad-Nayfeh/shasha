package com.ahmadnayfeh.shasha.mobile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A channel the user follows: the home feed is made of their latest uploads. */
@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

/** One upload of a subscribed channel, kept from the last refresh so the home screen opens at once (and offline). */
@Entity(tableName = "feed_items", indices = [Index("channelId"), Index("publishedAt")])
data class FeedItemEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val title: String,
    val channelName: String?,
    val durationSeconds: Long?,
    val publishedAt: Long?,
    val thumbnailUrl: String?,
    val viewCount: Long?,
    val isLive: Boolean,
    val isShort: Boolean,
)

/** Where the viewer stopped in a video (resume), and how far it went (the bar under its thumbnail). */
@Entity(tableName = "watch_progress", indices = [Index("updatedAt")])
data class WatchProgressEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val watchedAt: Long,
)

@Entity(tableName = "watch_later", indices = [Index("addedAt")])
data class WatchLaterEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelId: String?,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val addedAt: Long,
)

enum class DownloadKind { VIDEO, AUDIO }

enum class DownloadStatus {
    /** Waiting for its turn (one download runs at a time). */
    QUEUED,

    /** Fetching or merging. */
    RUNNING,

    /** The file is in the gallery / music library ([DownloadEntity.uri]). */
    DONE,

    /** Stopped by an error ([DownloadEntity.error]); the viewer can try again. */
    FAILED,
}

/** A video or audio download, from the moment it is asked for to the finished file. */
@Entity(tableName = "downloads", indices = [Index("status"), Index("createdAt")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val channelName: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val kind: DownloadKind,
    /** The quality the viewer asked for (0 for audio); the one that was fetched is [height]. */
    val requestedHeight: Int,
    val height: Int,
    val status: DownloadStatus,
    /** Size of the finished file; 0 until then. */
    val fileBytes: Long,
    /** MediaStore uri (content://...) of the finished file. */
    val uri: String?,
    val displayName: String?,
    /** Short reason for [DownloadStatus.FAILED]: the exception's class name and message. */
    val error: String?,
    val createdAt: Long,
    val completedAt: Long?,
)

/** SponsorBlock's segments of one video, as JSON, with the time they were asked for. */
@Entity(tableName = "sponsor_segments")
data class SponsorSegmentsEntity(
    @PrimaryKey val videoId: String,
    val fetchedAtMs: Long,
    val segments: String,
)

@Entity(tableName = "search_history", indices = [Index("searchedAt")])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)
