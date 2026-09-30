package com.ahmadnayfeh.shasha.backup

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ahmadnayfeh.shasha.data.db.HistoryEntity
import com.ahmadnayfeh.shasha.data.db.ProfileEntity
import com.ahmadnayfeh.shasha.data.db.SubscriptionEntity
import com.ahmadnayfeh.shasha.data.db.SurrenderEventEntity
import com.ahmadnayfeh.shasha.data.db.UsageRecordEntity
import com.ahmadnayfeh.shasha.data.db.WatchLaterEntity
import com.ahmadnayfeh.shasha.data.db.WatchProgressEntity

/**
 * Whole tables for the manual backup ([BackupManager]): read all of them, and replace all of them inside
 * one transaction. Only queries: no schema of its own. The feed cache is not backed up (the sync fetches
 * it again); [deleteFeedOfOtherChannels] drops what belonged to channels a restore took away.
 */
@Dao
interface BackupDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun profiles(): List<ProfileEntity>

    @Query("SELECT * FROM subscriptions ORDER BY channelId")
    suspend fun subscriptions(): List<SubscriptionEntity>

    @Query("SELECT * FROM watch_later ORDER BY profileId, addedAtMs DESC, videoId")
    suspend fun watchLater(): List<WatchLaterEntity>

    @Query("SELECT * FROM history ORDER BY profileId, watchedAtMs DESC, videoId")
    suspend fun history(): List<HistoryEntity>

    @Query("SELECT * FROM watch_progress ORDER BY profileId, videoId")
    suspend fun progress(): List<WatchProgressEntity>

    @Query("SELECT * FROM usage_records ORDER BY profileId, epochDay, kind, channelId")
    suspend fun usage(): List<UsageRecordEntity>

    @Query("SELECT * FROM surrender_events ORDER BY profileId, epochMs, videoId")
    suspend fun surrenders(): List<SurrenderEventEntity>

    @Query("DELETE FROM profiles")
    suspend fun deleteProfiles()

    @Query("DELETE FROM subscriptions")
    suspend fun deleteSubscriptions()

    @Query("DELETE FROM watch_later")
    suspend fun deleteWatchLater()

    @Query("DELETE FROM history")
    suspend fun deleteHistory()

    @Query("DELETE FROM watch_progress")
    suspend fun deleteProgress()

    @Query("DELETE FROM usage_records")
    suspend fun deleteUsage()

    @Query("DELETE FROM surrender_events")
    suspend fun deleteSurrenders()

    @Query("DELETE FROM feed_items WHERE channelId NOT IN (SELECT channelId FROM subscriptions)")
    suspend fun deleteFeedOfOtherChannels()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfiles(rows: List<ProfileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(rows: List<SubscriptionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWatchLater(rows: List<WatchLaterEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(rows: List<HistoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgress(rows: List<WatchProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsage(rows: List<UsageRecordEntity>)

    @Insert
    suspend fun insertSurrenders(rows: List<SurrenderEventEntity>)
}
