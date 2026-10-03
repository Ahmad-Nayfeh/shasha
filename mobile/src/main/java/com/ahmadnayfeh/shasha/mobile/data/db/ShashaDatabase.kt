package com.ahmadnayfeh.shasha.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The phone's data: subscriptions and the feed cache, history, watch later, resume positions, downloads, SponsorBlock's
 * cache and recent searches. File `shasha-mobile.db`; schemas are exported to `mobile/schemas/`.
 *
 * Every schema change bumps [version] and ships a migration: a phone with an older build installed would otherwise fail
 * Room's identity check when it opens the file.
 */
@Database(
    entities = [
        SubscriptionEntity::class,
        FeedItemEntity::class,
        WatchProgressEntity::class,
        HistoryEntity::class,
        WatchLaterEntity::class,
        DownloadEntity::class,
        SponsorSegmentsEntity::class,
        SearchHistoryEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ShashaDatabase : RoomDatabase() {
    abstract fun subscriptions(): SubscriptionDao
    abstract fun feed(): FeedDao
    abstract fun progress(): ProgressDao
    abstract fun history(): HistoryDao
    abstract fun watchLater(): WatchLaterDao
    abstract fun downloads(): DownloadDao
    abstract fun sponsorSegments(): SponsorSegmentDao
    abstract fun searchHistory(): SearchHistoryDao

    companion object {
        const val FILE_NAME = "shasha-mobile.db"

        fun create(context: Context): ShashaDatabase =
            Room.databaseBuilder(context.applicationContext, ShashaDatabase::class.java, FILE_NAME).build()
    }
}
