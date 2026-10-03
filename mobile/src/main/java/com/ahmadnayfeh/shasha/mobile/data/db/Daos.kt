package com.ahmadnayfeh.shasha.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    fun observeIsSubscribed(channelId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(subscription: SubscriptionEntity)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String)
}

@Dao
interface FeedDao {
    /** Newest first; items without a date (a live stream) go last. */
    @Query("SELECT * FROM feed_items ORDER BY publishedAt IS NULL, publishedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<FeedItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FeedItemEntity>)

    @Query("DELETE FROM feed_items WHERE channelId = :channelId")
    suspend fun deleteForChannel(channelId: String)

    @Query("DELETE FROM feed_items WHERE channelId NOT IN (SELECT channelId FROM subscriptions)")
    suspend fun deleteOfUnsubscribed()

    @Query("DELETE FROM feed_items")
    suspend fun clear()

    /** One channel's refreshed uploads replace its old ones in one step: the list never shows half of each. */
    @Transaction
    suspend fun replaceChannel(channelId: String, items: List<FeedItemEntity>) {
        deleteForChannel(channelId)
        upsertAll(items)
    }
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId")
    suspend fun get(videoId: String): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC LIMIT 500")
    fun observeRecent(): Flow<List<WatchProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM history")
    suspend fun clear()

    /** Keeps the newest [keep] entries. */
    @Query("DELETE FROM history WHERE videoId NOT IN (SELECT videoId FROM history ORDER BY watchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchLaterEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE videoId = :videoId)")
    fun observeContains(videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM watch_later WHERE videoId = :videoId)")
    suspend fun contains(videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM watch_later")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC, id DESC")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String?)

    @Query(
        "UPDATE downloads SET status = 'DONE', height = :height, fileBytes = :bytes, uri = :uri, displayName = :name, " +
            "error = NULL, completedAt = :completedAt WHERE id = :id",
    )
    suspend fun markDone(id: Long, height: Int, bytes: Long, uri: String, name: String, completedAt: Long)

    /** After a restart nothing is running: what was running is queued again. */
    @Query("UPDATE downloads SET status = 'QUEUED' WHERE status = 'RUNNING'")
    suspend fun requeueRunning()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM downloads WHERE status IN ('QUEUED', 'RUNNING')")
    suspend fun pendingCount(): Int
}

@Dao
interface SponsorSegmentDao {
    @Query("SELECT * FROM sponsor_segments WHERE videoId = :videoId")
    suspend fun get(videoId: String): SponsorSegmentsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SponsorSegmentsEntity)

    @Query("SELECT COUNT(*) FROM sponsor_segments")
    suspend fun count(): Int

    /** Rows older than [oldestMs], or "from the future" (the clock went back): gone. */
    @Query("DELETE FROM sponsor_segments WHERE fetchedAtMs < :oldestMs OR fetchedAtMs > :newestMs")
    suspend fun prune(oldestMs: Long, newestMs: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun observe(limit: Int): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("DELETE FROM search_history WHERE `query` NOT IN (SELECT `query` FROM search_history ORDER BY searchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
