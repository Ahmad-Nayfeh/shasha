package com.ahmadnayfeh.shasha.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    /** Newest first, subscribed channels only (items of a channel being removed never show). */
    @Query(
        """
        SELECT f.* FROM feed_items AS f
        INNER JOIN subscriptions AS s ON s.channelId = f.channelId
        ORDER BY f.publishedAtMs DESC, f.videoId
        LIMIT :limit
        """,
    )
    fun observeHome(limit: Int): Flow<List<FeedItemEntity>>

    @Query("SELECT * FROM feed_items WHERE channelId = :channelId ORDER BY publishedAtMs DESC, videoId")
    suspend fun forChannel(channelId: String): List<FeedItemEntity>

    @Upsert
    suspend fun upsert(items: List<FeedItemEntity>)

    @Query("DELETE FROM feed_items WHERE videoId IN (:videoIds)")
    suspend fun delete(videoIds: List<String>): Int

    @Query(
        """
        DELETE FROM feed_items WHERE channelId = :channelId AND videoId NOT IN (
            SELECT videoId FROM feed_items WHERE channelId = :channelId
            ORDER BY publishedAtMs DESC, videoId LIMIT :keep
        )
        """,
    )
    suspend fun keepNewest(channelId: String, keep: Int): Int

    @Query("DELETE FROM feed_items WHERE publishedAtMs < :cutoffMs")
    suspend fun deleteOlderThan(cutoffMs: Long): Int

    @Query("DELETE FROM feed_items WHERE channelId = :channelId")
    suspend fun deleteChannel(channelId: String): Int
}
