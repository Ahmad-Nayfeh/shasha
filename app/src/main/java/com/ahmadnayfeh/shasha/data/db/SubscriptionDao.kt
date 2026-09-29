package com.ahmadnayfeh.shasha.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE, channelId")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE channelId = :channelId)")
    fun observeIsSubscribed(channelId: String): Flow<Boolean>

    /** Newest successful sync of any channel, for "updated N minutes ago". */
    @Query("SELECT MAX(lastSyncAtMs) FROM subscriptions WHERE lastSyncError IS NULL")
    fun observeLastSuccessfulSync(): Flow<Long?>

    @Query("SELECT * FROM subscriptions WHERE channelId = :channelId")
    suspend fun get(channelId: String): SubscriptionEntity?

    /** The sync order: never synced first, then the longest ago (SQLite sorts NULL first). */
    @Query("SELECT * FROM subscriptions ORDER BY lastSyncAtMs, channelId")
    suspend fun stalestFirst(): List<SubscriptionEntity>

    /** Row ids in input order; -1 for channels that were already there (they are left untouched). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(channels: List<SubscriptionEntity>): List<Long>

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun delete(channelId: String): Int

    @Query("UPDATE subscriptions SET lastSyncAtMs = :atMs, lastSyncError = :error WHERE channelId = :channelId")
    suspend fun setSyncResult(channelId: String, atMs: Long, error: String?)

    @Query("UPDATE subscriptions SET name = :name WHERE channelId = :channelId")
    suspend fun rename(channelId: String, name: String)

    /** A null [avatarUrl] keeps the known one. */
    @Query("UPDATE subscriptions SET name = :name, avatarUrl = COALESCE(:avatarUrl, avatarUrl) WHERE channelId = :channelId")
    suspend fun updateDetails(channelId: String, name: String, avatarUrl: String?)
}
